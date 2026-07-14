# Governance

`cloud-itonami-isic-6493` is an OSS open-business blueprint for factoring
activities -- purchasing accounts receivable (invoices) from third parties
at a discount, advancing cash to the client, then collecting from the
account debtor at maturity and releasing the reserve.
Governance covers both the capability layer and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- the Factoring Governor remains independent of the advisor.
- hard policy violations (fabricated spec-basis, sanctions hit, incomplete
  records, debtor/funder concentration ceiling breaches, a fee rate that
  does not match the published schedule, a stale or missing solvency
  attestation) cannot be overridden by human approval.
- advancing cash and releasing a reserve always escalate to a human --
  never automated.
- a solvency attestation is a ground-truth recompute from this actor's own
  ledger, never a self-reported or privately-held number (see
  `docs/adr/0001-architecture.md`'s 全東信 case study for why).
- every hold, approval, advance and settlement path is auditable.
- personal and customer data stay outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or license
should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit and data-flow review.

Certified operators can lose certification for:

- bypassing the Factoring Governor's policy checks
- mishandling customer or account-debtor data
- misrepresenting certification status
- failing to respond to security incidents
- hiding material changes to customer-facing operation
- publishing a solvency attestation that does not match an independent
  recompute of the operator's own real ledger
