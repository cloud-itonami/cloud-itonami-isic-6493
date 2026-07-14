# ADR-0003: Real `kotoba-lang/swift` / `kotoba-lang/banking` / `kotoba-lang/ekyc` integration

- Status: Accepted (2026-07-14)
- Related: ADR-0001 (this repo's own architecture record -- the
  `factoring.*` governed core this integration extends, not
  reimplements); ADR-0002 (the Cloudflare Worker deployment this
  integration is deployed into and re-smoke-tested against); `kotoba-
  lang/swift` `docs/adr/0001-real-wire-format.md`; `kotoba-lang/
  banking` `docs/adr/0001-open-banking-api-layer.md`; `kotoba-lang/
  ekyc` `docs/adr/0001-architecture.md` (the three sibling capability
  libraries' own design records -- read in full before writing any
  integration code)

## Context

Three sibling capability libraries landed in this fleet the same day as
this actor's Cloudflare Worker deployment (built by parallel agents):
`kotoba-lang/swift` (a real SWIFT MT103/MT202 wire-format encoder +
real pain.001/pacs.008 ISO 20022 XML, upgraded from a prior EDN
placeholder), `kotoba-lang/banking`'s new `kotoba.banking.api`
namespace (a real Berlin Group NextGenPSD2 XS2A payment-initiation/
account-information request/response layer), and `kotoba-lang/ekyc`'s
new `kotoba.ekyc` namespace (a real JPN 犯収法施行規則 Art.6(1) non-face-
to-face identity-verification method catalog with an honest NIST SP
800-63A-4 IAL cross-reference).

Every prior `cloud-itonami-isic-*` actor's `:banking`-tagged capability
reference has been "cited but not directly required" (this repo's own
ADR-0001, Decision 9: "the same self-contained-sibling posture holds:
no code dependency"). This ADR is the first to change that, on
explicit owner instruction: wire these three libraries into this
actor's ACTUAL governed flow as live code, not a docstring citation.

## Decision

### 1. `:verify` gains a real eKYC check -- `factoring.governor/ekyc-verification-invalid-violations`

A new HARD, un-overridable check (re-checked at `:advance/fund` too,
the SAME "ground truth independently re-derived at every relevant op"
discipline `sanctions-hit-violations` already established, for the
SAME reason -- a receivable must never reach an actuation by skipping
verify). It independently constructs real `kotoba.ekyc/verification`
records for the CLIENT and the ACCOUNT DEBTOR from the receivable's own
ground-truth `:client-*`/`:debtor-*` fields (`factoring.registry/
client-ekyc-verification`/`debtor-ekyc-verification`, new pure
functions) and runs `kotoba.ekyc/validate` against the real statutory
catalog. This is the FIRST HARD check in this fleet whose violation
carries the full structured result from a dependency library
(`:ekyc/disposition`/`:ekyc/reasons`), not only a human-readable
`:detail` string -- per the owner's explicit instruction: "a real,
specific HARD-check violation... not just an abstract boolean."

**Scope choice, documented not silently assumed**: 犯収法's identity-
verification duty is narrowly about the 特定事業者's own CUSTOMER (the
client). This governor extends the SAME method-validation discipline
to the ACCOUNT DEBTOR too -- broader than a narrow reading of the
statute, an enhanced-due-diligence posture this actor chooses, not one
the statute itself mandates for a third party. Both parties default to
`:corp-ro` (name+registered-office declaration + registry-information-
lookup) in this catalog's demo data, since factoring is predominantly
B2B; `:client-subject-type`/`:debtor-subject-type` are configurable per
receivable (default `:corporate`) for an operator whose client base
includes natural persons.

### 2. Advance/settle now construct a real settlement-instruction artifact -- `factoring.registry/build-settlement-instruction`

**Chosen: XS2A (Berlin Group NextGenPSD2 payment-initiation) as the
DEFAULT rail, with SWIFT MT103 as a real, genuinely-implemented
alternative, auto-selected by currency.**

The reasoning, not a coin flip: factoring's actual settlement pattern
is "the factor's own bank initiates a payment out of the factor's own
operating account into the client's account" -- structurally exactly
XS2A's payment-initiation shape (a TPP-initiated domestic/EU-
interoperable payment against the payer's own ASPSP), not a
correspondent-banking FI-to-FI wire (MT202, which this actor does not
use at all) or a customer credit transfer routed through SWIFT's
interbank messaging network for two banks with no direct relationship
(MT103's classic use case). A factor advancing cash to its own client
is "my bank, pay this beneficiary" -- XS2A's `payment-initiation-
request`, not an interbank wire, for the common case.

MT103 is not merely documented-but-unbuilt, though: `factoring.
registry/choose-settlement-rail` auto-selects it whenever the
receivable's currency's minor-unit convention is not 2 --
`kotoba.banking.api/payment-initiation-request`'s `amountValue`
conversion ASSUMES 2 minor units always (a limitation that library's
own ns docstring documents: 0-minor-unit currencies like JPY are "NOT
handled"). Silently routing a JPY-denominated advance through XS2A
would misconvert the amount by a factor of 100 -- a structural
correctness guard, not a stylistic preference.

This also happens to line up with jurisdictional reality, cited
honestly as a useful coincidence rather than claimed as the design
rationale: Japan does not participate in the IBAN scheme at all (a
JPY/JPN receivable naturally has no IBAN to route through XS2A's
`accountReference` anyway, only a BIC -- exactly what MT103's option-A/
K party fields need), while the EU/GBR jurisdictions this catalog seeds
are IBAN-native and PSD2/XS2A-native. This actor's own (fictional)
settlement account is domiciled with a fictional German bank, used for
BOTH rails: XS2A for EUR/USD/GBP-ish receivables (the bank initiating
its own payment), and MT103 for JPY receivables (the same bank sending
an international correspondent wire to the client's Japanese bank --
realistic; German banks routinely originate SWIFT wires to Japanese
beneficiaries).

An operator can also force MT103 per-receivable via `:settlement-rail`
for a genuinely cross-border correspondent-banking case even on a
2-decimal currency (e.g. a USD receivable settling somewhere with no
direct banking relationship to this factor's own bank) -- the auto-
selection is a floor (JPY always forces MT103), not a ceiling.

### 3. New `factoring.store` concept: `settlement-account` (admin, not governed)

A single administrative record ({:iban :bic :account-holder-name}) --
this factor's OWN bank account, the ordering/debtor side of every
settlement-instruction artifact. New `register-settlement-account!`/
`settlement-account` protocol methods (both `MemStore` and
`DatomicStore`), the SAME admin-not-governed posture ADR-0002's
`register-funder!` already established: recording this factor's own
banking details carries no proposal-fabrication or actuation risk, the
same class of operator-entered ground truth `demo-funders`/
`:debtor-credit-limit` already are.

### 4. Graceful degradation on incomplete banking details -- never a crash

`build-settlement-instruction` catches the underlying constructors'
`ex-info` (a missing/invalid `:client-iban` for an XS2A leg, a nil
`mt103` result for missing/invalid BICs) and surfaces `{:settlement/
rail rail :settlement/leg leg :settlement/error [...]}` instead of
propagating the exception. A receivable's settlement-detail
completeness is a DATA-completeness concern, not a governance one --
the ACTUATION decision itself (commit/hold, per `factoring.governor`'s
HARD checks) is unaffected either way; only the wire/API ARTIFACT
specifically shows a clear error instead of crashing the whole
StateGraph run. Returns nil entirely (not even an error marker) when no
`settlement-account` is configured yet.

### 5. A second real JSON-string-vs-Clojure-keyword coercion bug, found proactively this time

ADR-0002 documented finding (via live smoke-testing, not inspection)
that a JSON body's `:debtor-risk-tier` arrives as a Clojure STRING
(`js->clj :keywordize-keys true` only keywordizes map KEYS, not
values), silently breaking the keyword-keyed fee-schedule lookup.
`worker/routes.cljc`'s `coerce-receivable-patch` was extended
PROACTIVELY this time -- before shipping, not after rediscovering the
same bug class live -- to also coerce `:client-subject-type`/
`:debtor-subject-type`/`:client-ekyc-method`/`:debtor-ekyc-method`/
`:settlement-rail` and each eKYC evidence item's own `:kind`/
`:fields-confirmed` (a new `coerce-ekyc-evidence` helper, one level
deeper than the top-level patch). Regression-tested in `worker/test/
routes_test.clj` (`unrecognized-ekyc-method-hard-holds-through-the-
http-layer-even-as-a-json-string`) before this ADR was written.

### 6. Live re-smoke-test before landing

A JPY receivable was run through the FULL lifecycle against the live
`https://factoring.murakumo.cloud` deployment: intake -> `POST /verify`
(response confirmed to carry a real `kotoba.ekyc/validate` result,
`"disposition":"pass"`, real `corp-ro` method + honest `not-applicable`
IAL note) -> underwrite -> `POST /solvency/attest` -> `POST /advance`
(**actuation 1**, response confirmed to carry a genuine, well-formed
SWIFT MT103 wire string with the correct value-date/currency/amount) ->
collect -> `POST /settle` (**actuation 2**, same artifact class, correct
settlement math). A second case confirmed an unrecognized eKYC method
produces a real, specific 409
(`{"rule":"ekyc-verification-invalid","disposition":"fail",
"reasons":["unrecognized-method"]}`). Test data deleted from the live
KV afterward.

## Honesty boundary (verbatim, sharper now that real artifacts are in play)

This deployment makes the GOVERNED DECISION software live, callable and
durable at a real HTTPS URL. It does NOT wire any real bank transfer/
payment rail, real funder capital, or real KYC/AML provider, and does
not claim any real money moves through it. As of this integration, the
ARTIFACTS this actor produces are themselves REAL and spec-conformant
-- a genuine SWIFT MT103 wire string, a genuine Berlin Group XS2A
payment-initiation JSON payload, a genuine 犯収法施行規則-conformant
eKYC verification-method validation -- but EVERY one of them is
constructed, stored in the audit ledger, and returned in the API
response, and NEVER transmitted anywhere: no real SWIFTNet connection,
no real bank API call, no real eKYC vendor call. This actor produces
exactly what a licensed operator's real banking/eKYC integration would
need to receive and forward; the forwarding step itself remains
explicitly out of scope and unattached.

## Consequences

- (+) This actor is the first `cloud-itonami-isic-*` actor in this
  fleet to depend on a `:banking`-family capability library as live
  code, not a docstring citation -- a genuine precedent change, per
  explicit owner instruction.
- (+) The verify/advance/settle HTTP responses now carry real,
  independently-inspectable artifacts (an eKYC validation record, a
  SWIFT wire string, an XS2A JSON payload), confirmed live against the
  deployed URL, not merely unit-tested.
- (+) The rail-selection logic (Decision 2) is driven by a genuine
  technical correctness constraint (XS2A's documented 2-decimal
  assumption) rather than an arbitrary business rule, and the JPY-forces-
  MT103/no-IBAN correspondence is honestly cited as a coincidence, not
  oversold as the design rationale.
- (+) The JSON-coercion bug class (Decision 5) was fixed proactively
  this time, not rediscovered live a second time -- direct evidence the
  ADR-0002 lesson transferred forward.
- (-) Both party's eKYC scope choice (extending the method-validation
  discipline to the account debtor, Decision 1) is broader than 犯収法's
  own narrow customer-only reading -- documented, not silently assumed;
  a future revision could make this configurable if an operator's
  actual compliance posture differs.
- (-) `build-settlement-instruction`'s graceful degradation (Decision
  4) means an incompletely-configured receivable can still reach a
  committed actuation with no artifact (or an error-marker artifact) --
  correct per this actor's design (data completeness != governance),
  but an operator relying on the artifact downstream must check for
  `:settlement/error`/absence, not assume it always succeeds.
- 100 tests / 389 assertions across the whole repo now (82/336 core
  actor + 18/53 worker HTTP-API), lint-clean on both.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| MT103/MT202 (correspondent banking) as the default rail for everything | ❌ | Factoring's actual settlement pattern (the factor's own bank paying its own client) is structurally an XS2A payment-initiation, not an interbank wire -- see Decision 2 |
| A single settlement rail, no per-currency auto-selection | ❌ | Would either misconvert JPY amounts through XS2A's 2-decimal-only amountValue conversion, or force every EUR/USD/GBP receivable through the heavier MT103 path unnecessarily |
| Crash/reject the actuation when banking details are incomplete | ❌ | Conflates a data-completeness concern with a governance one; the governed DECISION (commit/hold) should not depend on whether a wire artifact happens to be fully constructible -- see Decision 4 |
| Narrow the eKYC check to the client only (statute's literal scope) | ❌ (documented alternative) | This actor's chosen enhanced-due-diligence posture extends it to the account debtor too -- a deliberate, honestly-documented scope choice, not the only defensible one |
