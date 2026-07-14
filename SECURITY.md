# Security Policy

This project handles factoring (receivables-purchase) workflows, including
client, account-debtor and funding-source data. Treat vulnerabilities as
potentially high impact even when the demo data is synthetic.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential exposure
- real client, account-debtor or funder data exposure
- authorization bypass
- Factoring Governor bypass
- audit-ledger tampering
- a solvency attestation path that could publish a number diverging from
  an independent ledger recompute
- over-disclosure in reports or exports

## Reporting

Use GitHub private vulnerability reporting when available for the
repository. If that is unavailable, contact the repository maintainers
through the cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on customer/debtor data, policy enforcement, solvency-attestation
  integrity or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets outside Git.
- Keep real client/account-debtor/funder data outside this repository.
- Run policy tests before deployment.
- Export and review audit logs regularly.
- Publish solvency attestations frequently, and treat any gap beyond
  `factoring.registry/stale-after-n-advances` as a deployment-blocking
  condition, not a warning to be dismissed.
- Use least privilege for operators and service accounts.
