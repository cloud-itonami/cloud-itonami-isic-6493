# Contributing

`cloud-itonami-isic-6493` accepts contributions to the OSS blueprint, capability
bindings, policy tests, documentation and operator model.

## Development

The capability layer lives in [`kotoba-lang/banking`](https://github.com/kotoba-lang/banking). This repo holds the business blueprint and operator
contracts.

```bash
clojure -M:dev:test
clojure -M:lint
```

Keep changes small and include tests for any capability-layer change.

## Rules

- Do not commit real customer records, credentials, or personal/financial data.
- Keep advancing cash and releasing a reserve behind the Factoring Governor.
- Treat this vertical as high-risk: add tests for spec-basis, sanctions
  screening, concentration limits, fee-schedule conformance, solvency
  attestation and audit logging.
- Never let a solvency attestation ship without the governor's independent
  recompute matching it (see `docs/adr/0001-architecture.md`).
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which policy invariant is affected
- how it was tested
- whether operator or certification docs need updates
