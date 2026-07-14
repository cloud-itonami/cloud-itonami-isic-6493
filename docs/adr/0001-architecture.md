# ADR-0001: cloud-itonami-isic-6493 -- Factoring-LLM as a contained intelligence node

- Status: Accepted (2026-07-14)
- Related: `cloud-itonami-isic-6492` (`credit.*`, Credit-LLM ⊣ Credit
  Governor -- the closest operational template: single-entity Store,
  lending-style intake→assess→screen→approve→disburse lifecycle,
  ground-truth-recompute affordability check); `cloud-itonami-isic-6499`
  (`vcfund.*`, DD-LLM ⊣ InvestmentCommitteeGovernor -- the template for
  this build's TWO-actuation shape, `:actuation/call`/`:actuation/
  deploy`/`:actuation/distribute`/`:actuation/clawback`); superproject
  ADR-2607080200 (`6491`, most recent prior superproject-level "new
  vertical" ADR, establishes the check-family-taxonomy vocabulary this
  ADR uses); superproject ADR-2607141700 (this build's own
  superproject-level record, filed alongside this repo-level ADR)

## Context

The owner asked whether `cloud-itonami` has a factoring business.
Investigation found `cloud-itonami-isic-6499` was originally scaffolded
around factoring/check-cashing/money-order framing but was fully
repurposed in 2026-07 into a venture-capital-fund actor -- factoring
content no longer exists there. The owner then asked whether ISIC has a
dedicated factoring code. Verified against the OFFICIAL UN ISIC Rev.5
structure CSV and explanatory-notes PDF: **class 6493, "Factoring
activities," is a genuine, distinct, standalone Rev.5 class** -- not a
subset of 6499 -- with zero existing footprint anywhere in this fleet.

While this build was still at the `facts.cljc` stage, the owner surfaced
a real 2026-07 news story that reframed the whole design: **全東信
(Zentoshin)**, a Japanese card-settlement/early-payment-advance company
(functionally factoring-adjacent -- it advanced merchants their card-sales
proceeds early, then collected from the card companies later), filed for
bankruptcy with **JPY 115.1 billion** in liabilities. At least **~20 years
of alleged accounting fraud (決算粉飾)** had hidden its true insolvency the
entire time. When it collapsed, **~200,000 merchant stores nationwide**
were affected and **~50,000+ merchants had JPY 5+ billion in payments
stuck/unpaid**, with zero warning -- merchants had no way to independently
verify the company's actual solvency; they could only trust its self-
reported books, falsified for two decades.

This is not a hypothetical risk to design against in the abstract -- it is
a documented, large-scale, real-world failure of exactly the business
model this repository publishes. The owner asked for the design to
structurally prevent this class of failure via three properties:
**分散型 (distributed)**, **コード公開 (open source)**, and **公平 (fair)**.
This ADR treats those as first-class design requirements, not README
prose bolted on afterward.

## Problem

Factoring bundles several distinct concerns under one governed workflow,
on top of the standard "seal the LLM, layer governance" problem every
sibling actor solves:

1. **Legal-basis correctness** -- is the receivables-assignment law cited
   for a verification proposal official, or invented? Unlike `credit.
   facts`'s (`6492`) national truth-in-lending regime, this catalog's US
   entry cites the UNIFORM Commercial Code text directly (materially
   uniform across all 50 states) rather than a state exemplar, and its
   UK entry honestly reflects that factoring itself is NOT a licensed
   activity there (industry self-regulation via UK Finance, not a
   statutory regulator) -- see `factoring.facts`'s own docstring.
2. **Underwriting inversion** -- unlike `credit.governor`'s (`6492`)
   BORROWER-side affordability check, the party whose repayment capacity
   actually matters in factoring is the ACCOUNT DEBTOR, who never submits
   a proposal to this actor at all. The client's own creditworthiness is
   comparatively unimportant (the factor buys the receivable, it does not
   lend against the client's balance sheet). The LEGAL spec-basis,
   however, still follows the CLIENT's jurisdiction (mirroring UCC
   Article 9's own choice-of-law rule) -- a deliberate, non-symmetric
   split between legal domicile and credit-risk domicile.
3. **Two real actuation events, not one** -- advancing cash to the client
   and later releasing the reserve are two independent real-money-
   movement directions, the same shape `6499` established for its own
   four capital-movement directions (this build reuses two of those
   four: an inbound-to-client advance and a later inbound-to-client
   settlement, with no LP-style capital-call-in or GP-clawback-repay
   direction -- factoring has no equivalent of either).
4. **A single company's solvency was invisible for 20 years** (Zentoshin).
   No prior actor in this fleet has had to structurally answer "how would
   an outside party know this business can actually pay what it owes,
   without trusting its private books?"
5. **A single funding source concentrated all counterparty risk**
   (Zentoshin). No prior single-actuation actor in this fleet has had to
   model MULTIPLE independent capital sources behind its own advances,
   each individually capacity-capped.
6. **Opaque, potentially discriminatory pricing** is a known factoring/
   invoice-finance industry concern (privately negotiated per-client
   discount rates with no public reference point).

## Decision

### 1. Factoring-LLM is sealed into the bottom node; it never advances or settles directly

`factoring.factoringllm` returns exactly seven kinds of proposal: intake
normalization, verification checklist, underwriting verdict, cash-advance
draft, collection-receipt draft, settlement draft, and solvency-
attestation draft. No proposal writes the SSoT or moves real cash
directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 factoring operation

`factoring.operation/build` is the SAME StateGraph shape as every sibling
actor's operation namespace, copied verbatim.

### 3. TWO entity kinds, unlike `credit.store`'s single application entity

`factoring.store` has the RECEIVABLE (the dynamic entity every op acts
on, mirroring `credit.store`'s application) and the FUNDER (a small,
mostly-static reference registry -- each funding source's own committed
capital line, seeded once, not filed per-transaction). This still
respects the "no dynamically-filed sub-record" shape `credit.store`/
`casualty.store`/`reinsurance.store` established -- funders are closer to
`credit.facts`'s static reference data than to `pension.store`'s
dynamically-filed sub-records.

### 4. The exposure-aggregation primitive: this build's principal structural contribution

`factoring.registry/aggregate-exposure` is a pure fn summing a predicate-
matched subset of receivables currently `#{:advanced :collected}`.
`debtor-exposure`, `funder-exposure` and `book-exposure` are the SAME
primitive applied at three scopes -- per-account-debtor, per-funding-
source, and whole-book. This is genuinely different from every check
`credit.governor`/`vcfund.governor` (the two templates read directly for
this build) implement: `credit.governor/affordability-exceeded-
violations` reads a SINGLE record's own fields; `vcfund.governor/
clawback-exceeds-entitlement-violations` independently recomputes from a
proposal's CLAIM. Neither aggregates across MULTIPLE stored records for
one counterparty axis. `debtor-concentration-ceiling-exceeded-violations`
and `funder-concentration-ceiling-exceeded-violations` both reuse the
EXISTING direct-comparison MAXIMUM-ceiling family (per superproject
ADR-2607080200's taxonomy) -- this build does NOT claim a new comparison
family, only a new INPUT shape underneath it. This scoping is deliberately
honest and NOT claimed as a whole-fleet first: it is verified distinct
against the two templates this build read directly (`6492`, `6499`), not
audited against the whole ~140-actor fleet.

### 5. Solvency attestation: the direct anti-Zentoshin structural fix

`factoring.registry/solvency-report` is a PURE ground-truth recompute
(outstanding cash-out obligations vs. total funding capacity) from the
Store's own receivable/funder history -- never a stored/self-reported
flag. Two governor checks close the loop:

- `solvency-attestation-mismatch-violations` (fires on `:solvency/
  attest`): the advisor's claimed attestation numbers must EXACTLY match
  an independent recompute, or HOLD. The advisor cannot publish a rosier
  number than the ledger actually supports -- the direct structural
  answer to ~20 years of self-reported, unverified books.
- `solvency-attestation-stale-or-missing-violations` (fires on
  `:advance/fund`): an advance may never proceed unless a fresh (within
  `stale-after-n-advances`, a small representative threshold of 2) clean
  attestation is on file AND a LIVE recompute -- including the pending
  advance's own face amount -- still shows solvent right now. Books must
  be re-verified frequently, never trusted for years.

The attestation history (`factoring.store/solvency-attestation-history`/
`latest-solvency-attestation`) is exposed on the SAME `Store` protocol
every read already goes through -- any client, account debtor, or outside
observer with read access to a live deployment can call it themselves.
This R0 does not additionally stand up a dedicated public HTTP endpoint
(see "Future scope" below); wiring one is additive and does not touch this
governed core.

### 6. Distributed funding: no single funder can reproduce a Zentoshin-style cascade

Every receivable carries a `:funder-id` (one funder per receivable in this
R0 -- see "Alternatives considered" for why pro-rata multi-funder
syndication is deferred, not built). `funder-concentration-exceeded?`
converts a funder's aggregate FACE exposure to actual cash-out terms
(`* advance-rate`) before comparing against that funder's own recorded
`:funding-capacity`. The demo/test seed data models THREE independent,
named funders (fictional institutional names, not real companies) with
capacities summing to JPY-scale 9,000,000 -- small enough that the test
suite can genuinely exercise a funder-concentration breach in isolation
from any debtor-concentration breach (`test/factoring/governor_contract_
test.cljc`'s `funder-concentration-exceeded-in-isolation-is-held`).

### 7. Fair pricing: a published, versioned schedule, not private negotiation

`factoring.registry/fee-schedule` (`{:version :v1 :tiers {:tier-a 0.02
:tier-b 0.03 :tier-c 0.05}}`) is the ONLY source of a legitimate fee rate.
`fee-rate-mismatch-violations` (an exact-match check, the `6629`/`6520`/
`6820`/`6612` family) blocks any advance whose applied rate does not
exactly match the schedule for the receivable's `:debtor-risk-tier`. A
future schedule revision is additive (`:v2`), never a silent retroactive
rewrite of what `:v1` meant historically.

### 8. Status-lifecycle precondition, generalized to THREE ops proactively

`credit.governor`'s (`6492`) ADR Decision 5 documented a real bug: a
status-precondition check written as a single terminal-value equality
(`(= :approved status)`) spuriously re-fired on a double-disbursement
attempt, because status legitimately advances PAST the checked value
after the next actuation. The fix (check a status VALUE SET covering
every state reachable only via the precondition) is applied here
PROACTIVELY, and generalized across THREE ops instead of one:
`receivable-status-precondition-violations` checks `:advance/fund`
(needs `#{:underwritten :advanced :collected :settled}`), `:receivable/
collect` (needs `#{:advanced :collected :settled}`), and `:reserve/
settle` (needs `#{:collected :settled}`) -- one reusable function instead
of three near-duplicates. `double-advance-violations`/`double-settle-
violations` remain SEPARATE dedicated guards (not folded into the
precondition check), the same layering `6492`'s `double-disbursement-
violations` established.

### 9. Sanctions screening re-checked at BOTH verify and advance, not just verify

`sanctions-hit-violations` independently re-reads the client's and
account debtor's sanctions-screening ground truth directly off the Store
at BOTH `:receivable/verify` and `:advance/fund` -- not gated behind a
single upstream step. This closes a real design gap found while writing
`test/factoring/governor_contract_test.cljc`'s `sanctions-hit-also-
blocks-advance-directly-skipping-verify`: since `:receivable/underwrite`
has no HARD precondition requiring `:receivable/verify` to have run first
(mirroring `6492`'s `:loan/approve`, which likewise has no precondition
on `:jurisdiction/assess`), a receivable could otherwise reach `:advance/
fund` by skipping verify entirely, bypassing sanctions screening. Re-
checking ground truth at every relevant op (not trusting that an earlier
step happened) is the SAME discipline `casualty.governor/sanctions-
violations` established (three reuses per that check's own history, per
superproject ADR-2607080200's citation of it).

### 10. Two actuations, `:solvency/attest` deliberately excluded from `:auto` despite moving no capital

`factoring.governor/high-stakes` has exactly two members (`:actuation/
advance-cash`, `:actuation/release-reserve`), matching `6499`'s multi-
actuation shape rather than every single-actuation sibling's one-member
set. `factoring.phase`'s phase 3 `:auto` set has only ONE member
(`:receivable/intake`), the same shape `6492`'s phase 3 has --
`:solvency/attest` is deliberately NOT auto-eligible even at phase 3,
even though it moves no capital: publishing a solvency attestation is a
genuine DECISION with real reputational/legal consequences if wrong (the
same posture `6492`'s `:loan/approve` has toward underwriting decisions),
so a human always reviews the PUBLISHED claim even though the governor's
own independent recompute already guarantees the NUMBERS are correct.

### 11. Deferred: the `.kotoba`/WASM-subset safety-kernel extraction (`credit.kernels.gate`/`vcfund.kernels.gate`)

Both templates read directly for this build (`6492`, `6499`) extract
their governor/phase decision core into a safe-kotoba-subset `kernels.
gate` namespace (integer-coded, fail-closed, per the cloud-itonami
kernels discipline, superproject ADR-2607101200). This build deliberately
does NOT port that layer -- `factoring.governor`/`factoring.phase` decide
directly in idiomatic `.cljc`, the shape most of this fleet's actors
(built before or without that convention) still use. Given this build's
already-larger-than-typical scope (three first-class anti-Zentoshin
mitigations layered onto the standard actor shape), the kernels
extraction is treated as an orthogonal, additive hardening step available
as a follow-up, not a blocking requirement for this R0's file layout. See
`test/factoring/portable_cljs_test_runner.cljc`'s own docstring for the
same note.

## Consequences

- (+) Factoring gets the same governed, auditable-actor treatment as
  every prior actor in this fleet, with the FIRST fleet-wide precedent
  for a publicly-queryable, ledger-recomputed solvency attestation
  gating a real-money actuation.
- (+) The two-actuation invariant (governor + phase, two layers) is
  regression-tested by `test/factoring/phase_test.cljc`'s never-auto
  tests for both `:advance/fund` and `:reserve/settle`.
  `:solvency/attest`'s own never-auto invariant is tested separately.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/factoring/
  store_contract_test.cljc`, the same `:db-api`-driven swap pattern
  every sibling actor uses -- including the new funder entity and the
  three history collections (advance/settlement/attestation).
- (+) The exposure-aggregation primitive (`debtor-exposure`/`funder-
  exposure`/`book-exposure`, one pure fn, three scopes) demonstrates a
  genuinely different check-input shape than either template read
  directly for this build.
- (+) The sanctions-recheck-at-advance gap (Decision 9) was found and
  fixed DURING this build via test-writing, not discovered later --
  the same "a real bug was caught during verification" discipline
  `6492`'s own ADR documents about itself.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `factoring.facts/coverage`
  reports this honestly.
- (-) `debtor-credit-limit` and `:funding-capacity` are ground-truth
  fields set at intake/registration, not computed by a real underwriting/
  credit-scoring model -- see individual namespace docstrings.
- (-) One funder per receivable (no pro-rata multi-funder syndication on
  a single advance) -- see Alternatives.
- (-) The `.kotoba`/WASM kernels-extraction layer `6492`/`6499` both have
  is deferred, not ported -- see Decision 11.
- 63 tests / 268 assertions, lint-clean (`clojure -M:lint`), demo
  (`clojure -M:dev:run`) verified to run end-to-end exactly as designed:
  one clean lifecycle through both actuations (gated by a solvency
  attestation) plus SEVEN HARD-hold cases (stale/missing attestation,
  debtor+funder concentration co-firing, an isolated funder-
  concentration breach, an isolated fee-schedule mismatch, spec-basis,
  sanctions-hit, evidence-incomplete) that never reach a human, plus
  double-advance/double-settle guards -- every scenario's disposition
  and violation `:basis` was inspected directly against this ADR's
  Decision sections before landing (not just "tests pass").

## Known issue, out of scope: `cloud-itonami-isic-6492`'s title/content mismatch

While researching the official ISIC Rev.5 structure for this build, a
pre-existing classification mislabeling was found in `cloud-itonami-isic-
6492`'s own README, which it does NOT fix (out of scope for this
factoring build; flagged here for a future ADR):

`cloud-itonami-isic-6492`'s README claims "ISIC Rev.5 6492: Other credit
granting." The OFFICIAL Rev.5 title for class 6492 is actually
**"International trade financing activities"** (verified against the
same `ISIC5_structure.csv`/`ISIC5_Exp_Notes_11Mar2024.pdf` this ADR cites
for 6493). `6492`'s actual implemented content (loan-application intake,
underwriting, disbursement -- general consumer/commercial lending)
structurally matches official code **6495 "Other credit granting
activities"** (whose explanatory note lists exactly this: granting of
consumer credit, provision of long-term finance to industry, money
lending outside the banking system, credit granting by specialized non-
depository institutions, pawnbrokers), NOT 6492 (whose note is narrowly
about supporting cross-border goods shipment financing). This looks like
a classification mislabeling from before Rev.5's official class-level
structure was cross-checked against the live UN source, predating this
build. This repository does not modify `cloud-itonami-isic-6492` (it is a
read-only template for this build) and does not claim 6495 -- a future
ADR should decide whether to relabel `6492`'s README/registry entry, or
register a genuinely separate 6495 repository for "Other credit
granting."

## Future scope (not built in this R0)

- **On-chain/stablecoin settlement rail.** `cloud-itonami-isic-6499`
  already has precedent (SAFT token deals, on-chain LP settlement via a
  `:wallet-address` field) for an optional crypto-native path alongside
  traditional banking rails. A pluggable stablecoin escrow path for
  `:advance/fund`/`:reserve/settle` would be the direct structural
  analog to "you cannot secretly falsify a public chain for 20 years" --
  genuinely complementary to the solvency-attestation mitigation already
  built. Deferred: this R0's ledger-recompute attestation already
  delivers the core transparency property without a blockchain
  dependency, and a real on-chain integration is a substantial
  additional scope this ADR does not want to rush.
- **Pro-rata multi-funder syndication.** One receivable, one funder, in
  this R0 (see Alternatives). A syndicated receivable (multiple funders
  sharing exposure pro-rata on a single advance) is additive: extend
  `:funder-id` to a vector of `{:funder-id :share}` maps, and generalize
  `aggregate-exposure`'s amount-fn to weight by share.
- **A dedicated public read API.** This R0 exposes solvency/concentration
  data via the `Store` protocol (any caller with a `Store` reference can
  query it); a real deployment's public-facing HTTP surface for outside
  observers is an operator/deployment concern, not this actor's core.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Keep `cloud-itonami-isic-6493` unclaimed | ❌ | The standing "pick a new ISIC blueprint vertical" direction continues; 6493 is a genuine, distinct, correctly-cited, zero-footprint Rev.5 class |
| Treat the anti-Zentoshin mitigations as README-only disclaimers | ❌ | Explicitly rejected by the owner -- "genuinely load-bearing in the implementation, not just prose." All three (solvency-attestation-mismatch, solvency-attestation-stale-or-missing, funder-concentration-exceeded, fee-rate-mismatch) are real governor HARD checks with dedicated tests |
| Build pro-rata multi-funder syndication in R0 | ❌ | Meaningful additional complexity (fractional-share exposure math); single-funder-per-receivable already delivers the CORE distributed-funding property (no one funder backs the whole book) across a multi-funder BOOK; syndication on a single receivable is an honestly-scoped, additive R2 |
| Build the on-chain/stablecoin settlement rail now | ❌ | The ledger-recompute solvency attestation already delivers the core anti-Zentoshin transparency property without a blockchain dependency; sketched as documented future scope per the owner's own explicit guidance not to let it block or bloat R0 |
| Port `credit.kernels.gate`'s WASM-subset safety-kernel extraction | ❌ (deferred) | Orthogonal, additive hardening; this R0's file layout matches the requested Store/Registry/Facts/Advisor/Governor/Phase/Operation/Sim shape directly, most of this fleet's actors already use plain idiomatic decision logic, and this build's scope is already larger than typical given the three first-class mitigations |
| Fold `debtor-concentration`/`funder-concentration` into one check | ❌ | Each guards a DIFFERENT counterparty axis with a different real-world consequence (account-debtor credit risk vs. funding-source cascade risk) -- collapsing them would blur two genuinely distinct HARD violations into one, weakening the audit trail's basis specificity |
