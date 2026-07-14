# Operator Guide

## First Deployment

1. Register the operator's license, jurisdiction and responsible principals.
2. Import historical receivables, clients, account debtors and funders.
3. Run read-only validation of existing records against this blueprint's
   contracts.
4. Configure the Factoring Governor's hold/escalation policy, each
   funder's recorded `:funding-capacity`, and each debtor's recorded
   `:debtor-credit-limit`.
5. Publish an initial solvency attestation and a dry-run operation +
   audit export.

## Minimum Production Controls

- spec-basis citation required before any customer-facing determination
- advancing cash and releasing a reserve always require a human sign-off
- a fresh, ledger-recomputed solvency attestation must be on file before
  any advance -- publish attestations frequently, at minimum whenever
  `factoring.registry/stale-after-n-advances` advances would otherwise
  elapse without one
- no single funder's recorded `:funding-capacity` may back more of the
  book than this actor's own concentration check allows
- every advance's applied fee rate must match the currently published
  `factoring.registry/fee-schedule` for its risk tier -- no private,
  discretionary terms
- audit export for every hold, approval, advance, collection and
  settlement
- backup manual process for governor/system outage

## Certification

Certified operators must prove receivable/client/debtor-record integrity,
governor independence, evidence-backed reporting, human review for every
high-stakes action, genuine funding-source diversification (not a single
funder dressed up as several), and that published solvency attestations
have never diverged from an independently reproducible recompute of the
operator's real ledger.
