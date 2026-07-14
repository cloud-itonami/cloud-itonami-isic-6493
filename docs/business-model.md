# Business Model: Factoring activities

## Classification

- Repository: `cloud-itonami-isic-6493`
- ISIC Rev.5: `6493`
- Activity: factoring -- purchasing accounts receivable (invoices) from
  third parties at a discount, advancing cash, collecting at maturity,
  releasing the reserve
- Social impact: financial inclusion, distributed/non-custodial funding,
  fair non-discretionary pricing, publicly-verifiable solvency

## Customer

- independent small/mid-size businesses needing working-capital advances
  against unpaid invoices
- factoring operators seeking a governed, auditable execution scaffold
- syndicate/institutional funders wanting attributed, capped exposure

## Offer

- receivable intake and per-jurisdiction legal-basis checklisting
- invoice-authenticity/duplicate-pledge/KYC verification
- account-debtor underwriting against a recorded concentration limit
- cash advance proposal
- collection recording
- reserve settlement proposal
- publicly-queryable, ledger-recomputed solvency attestation
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per receivable-in-force
- support: monthly retainer with SLA
- migration: import from an incumbent factoring/invoice-finance system
- factoring fee (the published, versioned per-risk-tier discount rate)

## Trust Controls

- no cash advance and no reserve settlement is ever released without
  human sign-off
- a fabricated jurisdiction spec-basis, a client/account-debtor sanctions
  hit, incomplete verification evidence, an advance attempted before
  underwriting, an account debtor or funding source whose aggregate
  exposure would exceed its own recorded limit, an applied fee rate that
  does not match the published schedule, or a stale/missing/mismatched
  solvency attestation -- each forces a hold, not an override
- a receivable cannot be advanced or settled twice: double-actuation
  guards fire off this actor's own receivable status alone
- every intake, verification, underwriting, advance, collection,
  settlement and solvency-attestation path is auditable
- solvency is a ground-truth recompute from this actor's own ledger,
  never a self-reported number -- the direct structural answer to the
  全東信 (Zentoshin) bankruptcy case (see `docs/adr/0001-architecture.md`)
- no single funding source can back enough of the book to reproduce a
  single-balance-sheet cascade failure
- emergency manual override paths remain outside LLM control
