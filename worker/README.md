# `cloud-itonami-isic-6493` Worker deployment -- `factoring.murakumo.cloud`

This directory deploys the `factoring.*` actor (see the [repo
README](../README.md) and [ADR-0001](../docs/adr/0001-architecture.md))
as a live Cloudflare Worker, mirroring
[`cloud-murakumo-fleet`](https://github.com/gftdcojp/cloud-murakumo-fleet)'s
`local-murakumo.routes`/`local-murakumo.worker` pattern: a pure,
JVM-testable `(store, request) -> response` handler (`routes.cljc`) is
the reference contract, and an async translation layer (`worker.cljs`)
runs it in the Worker runtime.

**Live URL**: `https://factoring.murakumo.cloud`

## Honesty boundary -- read this before treating this as "money moving"

This deployment makes the **governed decision software** live, callable
and durable (persistent audit ledger in Cloudflare KV) at a real HTTPS
URL. It does **not**, and must **not**, wire any real bank transfer /
payment rail, real funder capital, real KYC/AML provider, or claim any
real money moves through it. `POST /receivables/<id>/advance` and `POST
/receivables/<id>/settle` remain governed, audited **decision points**
in the software: an authorized caller hitting those endpoints gets a
real, audited, HARD-check-enforced commit/hold disposition and a ledger
entry -- but no real currency moves anywhere, because no real bank/payment
integration is attached, and this deployment does not fabricate one (no
fake payment-processor call, no pretend wire transfer, no invented
funder API). The value delivered is a live, production, governed
decision+transparency service that a real licensed factor could point
real capital and real banking rails at -- the governance/audit/
transparency machinery is fully live and real; the money-movement
backend is intentionally not attached and is out of scope for this
deployment.

## Architecture

```
HTTP request
     |
     v
worker.cljs   -- CORS, auth gate (public transparency vs. gated), JSON
     |            parse, deep /health check
     v
kv-store.cljs -- load-store!: hydrate a factoring.store/MemStore from
     |           ONE Cloudflare KV key (a pr-str'd EDN snapshot of the
     |           whole book -- receivables, funders, verifications,
     |           underwritings, ledger, advance/settlement/attestation
     |           histories)
     v
routes.cljc   -- the SAME pure handler the JVM test suite exercises,
     |           running the SAME factoring.operation langgraph-clj
     |           StateGraph (Factoring-LLM propose -> Factoring Governor
     |           censor -> phase gate) against the hydrated store
     v
kv-store.cljs -- persist-store!: write the mutated snapshot back to KV
     v
worker.cljs   -- JSON response
```

Why hydrate-compute-persist instead of `local-murakumo.kv-store`'s
per-key streaming op-map: see `kv_store.cljs`'s own docstring. Short
version -- several of this actor's HARD checks (debtor/funder
concentration, solvency staleness) independently aggregate over
*every other stored receivable*, running through the same synchronous,
already-tested `factoring.governor`/`factoring.operation` code the JVM
suite proves correct is safer than hand-duplicating that logic a second
time as async KV op-by-op code (the way `local-murakumo.worker/route`
duplicates `local-murakumo.routes/handle`'s much simpler CRUD ops).

**Human-in-the-loop over a single-round-trip API**: `factoring.
operation`'s StateGraph pauses (`interrupt-before`) before any
non-auto-eligible write, modeling "a human must sign off." Since this
Worker has no separate approval UI, an authenticated caller's own
request to a gated actuation route IS that sign-off -- `routes.cljc`'s
`run-op!` immediately resumes an `:escalate` disposition as an approval
by the authenticated caller. **HARD violations still hold,
un-overridably** -- this does not weaken the governor, it only
collapses the SOFT confidence/actuation-approval gate into the same
HTTP call. See `routes.cljc`'s `run-op!` docstring for the full
reasoning.

## Auth shape

| Route | Access |
|---|---|
| `GET /health` | public |
| `GET /fee-schedule` | public -- the published, versioned fee schedule |
| `GET /solvency/attestation` | public -- latest ledger-recomputed attestation |
| `GET /solvency/attestations` | public -- full attestation history |
| `GET /funders` | public -- funder roster + recorded capacities |
| everything else | `X-Api-Key: <token>` or `Authorization: Bearer <token>`, matching the `FACTORING_API_KEY` secret |

The public routes are unauthenticated **by design** -- the whole point
of this actor's anti-Zentoshin transparency machinery (see the repo
README's "全東信 (Zentoshin)" section) is that a client, an account
debtor, or any outside observer can independently verify solvency and
pricing without trusting the operator. Gating those behind a credential
would defeat the purpose.

Gated routes fail **closed**: if `FACTORING_API_KEY` is not configured,
gated routes return 503, not an open door -- a deliberate deviation from
`cloud-murakumo-fleet`'s own fail-open precedent, since this actor's
gated routes include actuation decision points (see the honesty
boundary above for what "actuation" does and does not mean here).

The API key is stored as a Cloudflare Worker secret
(`wrangler secret put FACTORING_API_KEY`, never committed) and mirrored
in 1Password (`gftdcojp` vault, item "cloud-itonami-isic-6493
FACTORING_API_KEY (factoring.murakumo.cloud)") for operator recovery.

## Routes

See `routes.cljc`'s own docstring for the full, authoritative surface
list (`GET/POST /receivables[...]`, `POST /solvency/attest`, `POST
/funders`, `GET /ledger`, etc.) -- kept in one place to avoid drift
between this README and the code.

## KV storage

One namespace, `FACTORING_KV` (binding), id `a333f4584ea14f0aa123358a292bf7ed`,
account `ai-gftd-cloud`. One key (`"book"`) holds the entire factoring
book as a `pr-str`'d EDN string (never JSON -- see `factoring.store/
snapshot`'s own docstring for why: JSON has no keyword type, and every
HARD check in `factoring.governor` compares keyword values).

**Known limitation, documented not silently ignored**: Cloudflare KV is
eventually consistent with no built-in optimistic-concurrency lock, so
two requests racing against the book within the propagation window
could interleave unsafely (see `kv_store.cljs`'s own docstring for the
full accounting, including why this is the same category of accepted,
documented limitation `local-murakumo.kv-store`'s own `/itonami/trial`
idempotency caveat already establishes as precedent in this fleet). A
real high-throughput deployment would need a Durable Object or D1
transaction as the book's single authoritative writer -- out of scope
for this R0.

## Build + deploy

```bash
npm install
npm run build     # shadow-cljs release worker -> dist/worker.js
npm run deploy    # build + wrangler deploy
wrangler secret put FACTORING_API_KEY   # once, or to rotate
```

Cloudflare auth is the local `wrangler login` OAuth session (account
`ai-gftd-cloud`), same as every other Worker in this workspace -- see
this repo's `secrets-location-map` skill.

## Smoke-testing a deployment

```bash
curl https://factoring.murakumo.cloud/health
curl https://factoring.murakumo.cloud/fee-schedule
curl https://factoring.murakumo.cloud/solvency/attestation
curl https://factoring.murakumo.cloud/funders
```

The first three deploys of this Worker (2026-07-14) were smoke-tested
with a full lifecycle (funder registration, intake, verify, underwrite,
a stale-attestation HOLD, a solvency attestation publish, actuation 1
[advance], collect, actuation 2 [settle], a double-advance HOLD) against
the live URL, catching and fixing two real bugs in the process (an
empty-POST-body crash, and a JSON string vs. Clojure keyword mismatch
on `:debtor-risk-tier` that silently broke the fee-schedule exact-match
check -- see `routes.cljc`'s `coerce-receivable-patch` and
`worker.cljs`'s `parse-body!` docstrings). The test data was deleted
from KV afterward (`wrangler kv key delete "book" --remote`), so this
deployment starts from a genuinely empty book.
