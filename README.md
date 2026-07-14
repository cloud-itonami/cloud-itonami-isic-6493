# cloud-itonami-isic-6493

Open Business Blueprint for **ISIC Rev.5 6493**: Factoring activities.
This repository publishes a factoring actor -- receivable intake,
invoice-authenticity/duplicate-pledge/KYC verification, account-debtor
underwriting, a governed cash advance, collection recording, and a
governed reserve settlement -- as an OSS business that any qualified,
licensed factor can fork, deploy, run, improve and sell.

UN ISIC Rev.5's own explanatory note for division 649, class 6493, reads
in full: *"This class covers the activity of purchasing accounts
receivable (i.e., invoices) from third parties at a discount. This class
excludes: debt financing undertaken with account receivables as
collateral, see 6495."* (`ISIC5_Exp_Notes_11Mar2024.pdf`, UN Statistics
Division Task Team on ISIC). 6493 is a genuine, distinct, standalone Rev.5
class -- NOT a subset of 6499 ("Other financial service activities n.e.c.")
-- sitting alongside its division-649 siblings `6491` (Financial leasing
activities), `6492` (International trade financing activities), `6494`
(Securitisation activities) and `6495` (Other credit granting activities)
in the official ISIC Rev.5 structure. See
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) §"Known
issue: 6492's title/content mismatch" for a related fleet finding this
repository does NOT fix.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as every
prior actor in this fleet, most directly
[`cloud-itonami-isic-6492`](https://github.com/cloud-itonami/cloud-itonami-isic-6492)
(Credit-LLM ⊣ Credit Governor, the closest operational template) and
[`cloud-itonami-isic-6499`](https://github.com/cloud-itonami/cloud-itonami-isic-6499)
(DD-LLM ⊣ InvestmentCommitteeGovernor, the template for this actor's
two-actuation shape). Here it is **Factoring-LLM ⊣ Factoring Governor**.

> **Why an actor layer at all?** An LLM is great at drafting a
> verification checklist, normalizing receivable intake, and computing
> concentration/solvency arithmetic -- but it has **no notion of which
> jurisdiction's receivables-assignment law is official, no license to
> advance real cash, and no way to know on its own whether an account
> debtor's aggregate exposure actually exceeds this factor's own recorded
> limit for them, or whether the whole book can actually still pay what it
> owes**. Letting it advance cash directly invites fabricated jurisdiction
> citations, a silently overconcentrated book, and a self-reported solvency
> picture nobody can verify -- exactly the failure mode below. This project
> seals the Factoring-LLM into a single node and wraps it with an
> independent **Factoring Governor**, a human **approval workflow**, and an
> immutable, publicly-queryable **audit ledger**.

## Why this design: 全東信 (Zentoshin)

In 2026-07, **全東信 (Zentoshin)**, a Japanese card-settlement/early-payment-
advance company -- functionally a factoring-adjacent business that advanced
merchants their card-sales proceeds early and collected from the card
companies later -- filed for bankruptcy with **JPY 115.1 billion** in
liabilities. At least **~20 years of alleged accounting fraud (決算粉飾)**
had hidden its true insolvency the entire time. When it collapsed,
**~200,000 merchant stores nationwide** were affected and **~50,000+
merchants had JPY 5+ billion in payments stuck/unpaid**, with zero warning
-- because merchants had no way to independently verify the company's
actual solvency; they could only trust its self-reported books, which were
reportedly falsified for two decades.

The owner asked for a factoring design that structurally prevents this
class of failure via three properties: **分散型 (distributed)**, **コード公開
(open source)**, and **公平 (fair)**. `code` is open by construction (this
whole repository is AGPL-3.0-or-later, published publicly). The other two
are load-bearing design decisions, not disclaimers:

1. **Public, cryptographically-independent solvency transparency.**
   `factoring.registry/solvency-report` is a PURE ground-truth recompute
   from this actor's own receivable/funder history -- never a stored or
   self-reported flag. Any client, account debtor, or outside observer
   with read access to a deployment's Store can call it (or read the
   append-only `:solvency/attest` history) and independently verify
   solvency themselves -- the exact capability Zentoshin's merchants
   never had. `factoring.governor/solvency-attestation-mismatch-
   violations` additionally refuses to let the advisor publish an
   attestation whose numbers do not match this SAME independent recompute
   -- the advisor cannot self-report a rosier picture than the ledger
   actually supports. `factoring.governor/solvency-attestation-stale-or-
   missing-violations` refuses EVERY cash advance unless a fresh (`<=
   factoring.registry/stale-after-n-advances` advances old), clean
   attestation is on file AND a live recompute (including the pending
   advance itself) still shows solvent -- this actor structurally cannot
   keep taking in new business while quietly insolvent.
2. **Distributed, non-custodial funding.** Zentoshin's failure surface was
   a SINGLE company's balance sheet backing every merchant's advance -- one
   entity's insolvency took everyone down at once. Every receivable in
   this actor is attributed to a named, independent funding source
   (`:funder-id`), and `factoring.governor/funder-concentration-ceiling-
   exceeded-violations` blocks the book from concentrating too much
   aggregate exposure on any single funder, so no one funder's collapse
   can cascade to the whole client base.
3. **Fair, non-discretionary pricing.** `factoring.registry/fee-schedule`
   is a PUBLISHED, VERSIONED fee/discount-rate schedule by account-debtor
   risk tier -- not privately negotiated per-client secret terms.
   `factoring.governor/fee-rate-mismatch-violations` blocks any advance
   whose applied rate does not exactly match the published schedule for
   its tier.

See [`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for
the full design record, including why 全東信 motivated these specific
checks rather than being generic hardening.

## Scope: what this actor does and does not do

This actor covers receivable intake through verification, underwriting,
cash advance, collection and reserve settlement, gated by concentration,
solvency and fair-pricing checks. It does **not**, by itself, hold a
factoring/lending license in any jurisdiction, and it does not claim to.
It also does **not** model a full underwriting decision -- no full credit-
bureau integration, no dilution/dispute-rate modeling, no real payment-
rail integration (see individual namespace docstrings for the honest
simplifications each makes). Whoever deploys and operates a live instance
(a licensed factor) supplies the jurisdiction-specific license, the real
underwriting expertise, the real funding-source relationships and the real
banking/payment integrations, and bears that jurisdiction's liability --
the software supplies the governed, spec-cited, audited execution
scaffold, including the distributed-funding and solvency-transparency
structure, so that operator does not have to build the compliance and
trust layer from scratch for every new market.

### Underwriting inversion vs. `cloud-itonami-isic-6492`

`credit.governor`'s (`6492`) affordability check is BORROWER-side: it
recomputes the loan APPLICANT's own debt-to-income ratio. Factoring
inverts this: the party whose repayment capacity actually matters is the
**ACCOUNT DEBTOR** (the third party who owes the underlying invoice, and
who never submits a proposal to this actor at all) -- the CLIENT's (the
seller of the receivable) own creditworthiness is comparatively
unimportant, because the factor is buying the receivable, not lending
against the client's balance sheet. `factoring.governor/debtor-
concentration-ceiling-exceeded-violations` reflects this: its ground truth
is the ACCOUNT DEBTOR's aggregate exposure, not the client's. The LEGAL
spec-basis, by contrast, still follows the CLIENT's jurisdiction -- this
matches UCC Article 9's own choice-of-law rule (perfection follows the
location of the party granting/selling the interest, not the account
debtor). See `factoring.facts`'s own docstring for more.

### Actuation

**Advancing real cash to a client, and releasing the held-back reserve to
a client, are never autonomous, at any phase, by construction.** Unlike
`6492` (which has exactly ONE real-world actuation event), factoring has
**two** independent real-money-movement directions -- the initial cash
advance and the later reserve settlement -- so `factoring.governor/high-
stakes` has two members: `:actuation/advance-cash` and `:actuation/
release-reserve` (the same two-actuation shape
[`cloud-itonami-isic-6499`](https://github.com/cloud-itonami/cloud-itonami-isic-6499)
established for its own four capital-movement directions). Two independent
layers enforce this for each (`factoring.governor`'s high-stakes gate and
`factoring.phase`'s phase table, which never puts either op in any phase's
`:auto` set) -- see `factoring.phase`'s docstring and `test/factoring/
phase_test.cljc`'s never-auto tests. Collection (`:receivable/collect`,
recording that the account debtor paid) is deliberately NOT an actuation:
no cash leaves the factor there -- the debtor's payment is an inbound
receipt into the factor's own account, not an act the factor originates.
Publishing a solvency attestation (`:solvency/attest`) is likewise not an
actuation (no cash moves), but it is STILL never auto-eligible -- see
`factoring.phase`'s own docstring for why.

## The core contract

```
receivable + jurisdiction facts (factoring.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────────┐
   │ Factoring-LLM│ ─────────────▶ │  Factoring Governor          │  (independent system)
   │  (sealed)    │  + citations    │ spec-basis · sanctions-hit ·   │
   └──────────────┘                 │ evidence-incomplete · status-   │
                             commit ◀────┼──────────▶ hold │ precondition ·
                                 │             │           │ debtor/funder-
                           record + ledger  escalate ─▶ 人間承認     concentration ·
                                             (ALWAYS for               fee-rate-mismatch ·
                                              :advance/fund,            solvency-attestation-
                                              :reserve/settle           mismatch/stale-or-missing ·
                                              AND :solvency/attest)     double-advance/settle
```

**The Factoring-LLM never advances cash or releases a reserve the
Factoring Governor would reject, and never does either without a human
factor sign-off.** Hard violations (fabricated jurisdiction citations, a
client/debtor sanctions hit, incomplete verification evidence, an advance
attempted before underwriting, an account debtor or funding source whose
aggregate exposure would exceed its own recorded limit, an applied fee
rate that does not match the published schedule, or a stale/missing/
mismatched solvency attestation) force **hold** and *cannot* be approved
past; a clean advance/settlement/attestation proposal still always routes
to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean intake-through-settlement lifecycle + a solvency-attestation-gated advance + seven HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Live deployment

This actor also runs live at **`https://factoring.murakumo.cloud`** --
a Cloudflare Worker (`worker/`, KV-backed, live-smoke-tested) exposing
the SAME governed operations over HTTP: the public transparency surface
(`GET /health`, `/fee-schedule`, `/solvency/attestation(s)`, `/funders`)
needs no credential by design (the whole anti-Zentoshin point); every
other route is gated behind an API key. See
[`worker/README.md`](worker/README.md) and
[`docs/adr/0002-cloudflare-worker-deployment.md`](docs/adr/0002-cloudflare-worker-deployment.md)
for the full deployment record, including the **honesty boundary**:
this deployment makes the governed decision+audit+transparency software
live and real; it attaches no real bank/payment rail, real funder
capital or real KYC/AML provider, and fabricates none of those.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot**
performs the physical domain work. Here a document-custody robot manages
physical invoices, assignment-notification paperwork and KYC records,
under the actor, gated by the independent **Factoring Governor**. The
governor never dispatches hardware itself; `:high`/`:safety-critical`
actions require human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Factoring Governor, advance/settlement/attestation draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, distributed-funding and solvency-transparency structure, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6493`). Related capability contracts (accounts/IBAN/double-entry-ledger/
clearing shapes) are published as
[`kotoba-lang/banking`](https://github.com/kotoba-lang/banking); this
actor's `factoring.*` namespaces are a self-contained governed
implementation -- it does not require the capability lib directly, the
same "self-contained sibling" relationship `credit.*` (`6492`) has toward
the same capability lib.

## Layout

| File | Role |
|---|---|
| `src/factoring/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + advance/settlement/solvency-attestation history. TWO entity kinds: the RECEIVABLE (dynamic) and the FUNDER (a small, mostly-static reference registry) |
| `src/factoring/registry.cljc` | Advance/settlement/attestation draft records, `compute-advance-amount`/`compute-reserve-amount`/`compute-settlement-amount`, the PUBLISHED VERSIONED `fee-schedule`, and the exposure-aggregation primitive (`aggregate-exposure` -> `debtor-exposure`/`funder-exposure`/`book-exposure`/`solvency-report`) -- see its own ns docstring for the full 全東信 rationale |
| `src/factoring/facts.cljc` | Per-jurisdiction receivables-assignment/factoring-law catalog (client's jurisdiction, mirroring UCC Article 9's choice-of-law rule) with an official spec-basis citation per entry, honest coverage reporting |
| `src/factoring/factoringllm.cljc` | **Factoring-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/verification/underwriting/advance/collection/settlement/solvency-attestation proposals |
| `src/factoring/governor.cljc` | **Factoring Governor** -- 9 named HARD checks + 2 double-actuation guards; see its own ns docstring for the full check-family-taxonomy honesty accounting |
| `src/factoring/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted verify → supervised (advance/settle/attest always human; receivable intake is the ONLY auto-eligible op, no capital risk) |
| `src/factoring/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/factoring/sim.cljc` | demo driver |
| `test/factoring/*_test.cljc` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Receivable intake + per-jurisdiction legal-basis checklisting, HARD-gated on an official spec-basis citation (`:receivable/intake`/`:receivable/verify`) | Full credit-bureau integration, dilution/dispute-rate modeling |
| Invoice-authenticity/duplicate-pledge/notification-of-assignment/KYC evidence, sanctions screening of BOTH client and account debtor (`:receivable/verify`) | Real e-signature/document-rendering integration |
| Account-debtor underwriting against a real, aggregated concentration limit (`:receivable/underwrite`) | Real loan-servicing/banking/payment-rail integration, tax/regulatory reporting |
| Cash advance, independently re-verified against debtor concentration, funder concentration, the published fee schedule and a live solvency recompute (`:advance/fund`) | Multi-funder pro-rata syndication on a single receivable (this R0 attributes one funder per receivable -- see ADR) |
| Collection recording (`:receivable/collect`) | Collections/dispute-resolution coordination workflows |
| Reserve settlement, independently re-verified against a double-settlement guard (`:reserve/settle`) | On-chain/stablecoin settlement rails (see ADR "Future scope") |
| Publicly-queryable, ledger-recomputed solvency attestation (`:solvency/attest`), the direct anti-Zentoshin mitigation | A dedicated public HTTP/API surface for outside observers (this R0 exposes the capability via the `Store` protocol; wiring a public read endpoint is an operator/deployment concern) |
| Immutable audit ledger for every intake/verification/underwriting/advance/collection/settlement/attestation decision | |

Extending coverage is additive: add the next gate as its own governed op
with its own HARD checks and tests, following the SAME "an independent
governor re-verifies against the actor's own records before any real-world
act" pattern this repo's flagship ops already establish.

## Jurisdiction coverage (honest)

`factoring.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `factoring.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `factoring.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `Factoring-LLM` + `Factoring Governor` run as real,
tested code (see `Run` above), modeled closely on the prior actors'
architecture, most directly `cloud-itonami-isic-6492`'s Store/Registry/
Governor/Phase/Advisor/Operation/Sim shape and `cloud-itonami-isic-6499`'s
two-(here two-of-four)-actuation shape. See
`docs/adr/0001-architecture.md` for the design.

## License

Code and implementation templates are AGPL-3.0-or-later.
