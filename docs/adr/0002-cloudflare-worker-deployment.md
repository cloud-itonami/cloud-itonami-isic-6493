# ADR-0002: Cloudflare Worker deployment -- `factoring.murakumo.cloud`

- Status: Accepted (2026-07-14)
- Related: ADR-0001 (this repo's architecture record -- the `factoring.*`
  actor this deployment wraps, unchanged); `gftdcojp/cloud-murakumo-fleet`
  (`local-murakumo.routes`/`local-murakumo.worker`/`local-murakumo.
  kv-store`, the deployment template this build mirrors structurally);
  ADR-2606272330 (`murakumo.cloud`'s own registration/zone history);
  superproject ADR-2607142000 (this deployment's superproject-level
  record)

## Context

The owner asked to deploy `cloud-itonami-isic-6493` to `murakumo.cloud`
"so it actually runs as a business." `murakumo.cloud` already has two
live pieces: the apex/`www` static SPA (the `cloud-murakumo` GPU-
scheduler product, untouched by this ADR) and `api.murakumo.cloud`, a
real production Cloudflare Worker (`gftdcojp/cloud-murakumo-fleet`) that
served as this deployment's structural template -- read in full before
writing anything: `src/local_murakumo/routes.cljc` (pure sync handler,
JVM-tested), `src/local_murakumo/worker.cljs` (async translation),
`src/local_murakumo/kv_store.cljs` (KV-backed op-map), `wrangler.toml`
(custom-domain route + KV binding pattern).

## Decision

### 1. `worker/` subdirectory in this repo, not a split repo

No existing convention forces a split (`cloud-murakumo-fleet` and its
apex-site counterpart are two SEPARATE repos, but that reflects two
genuinely separate live products under the same zone, not a template
for "always split deploy code out"). Keeping the actor and its
deployment together keeps the governed decision logic and its HTTP
exposure co-versioned.

### 2. `routes.cljc` wraps `factoring.operation` unchanged -- it does not reimplement it

Unlike `local-murakumo.routes` (simple, independent CRUD ops with no
cross-record business logic), this actor's writes run `factoring.
governor`'s HARD checks, several of which independently AGGREGATE over
every other stored receivable (`factoring.registry/aggregate-exposure`).
Re-deriving that logic a second time as hand-written async code (the
way `local-murakumo.worker/route` duplicates `local-murakumo.routes/
handle`'s much simpler CRUD ops) would risk behavioral drift between
the JVM-tested version and the deployed version. Instead: `routes.cljc`
is a thin, pure `(store, request) -> response` wrapper that runs the
EXACT SAME `factoring.operation` langgraph-clj StateGraph the 68-test
JVM suite already proves correct, tested again here (JVM, `worker/test/
routes_test.clj`, `store/seed-db`) against the identical business logic
the Worker runs.

### 3. Human-in-the-loop over a single-round-trip HTTP API

`factoring.operation`'s `interrupt-before` models human sign-off as a
separate resume call. This deployment has no separate approval UI, so
`routes.cljc/run-op!` treats an authenticated caller's own request to a
gated actuation route AS that sign-off, auto-resuming an `:escalate`
disposition within the same HTTP call. **HARD violations still hold,
un-overridably** -- this collapses only the SOFT confidence/actuation-
approval gate into one round trip; it does not weaken the governor. See
`routes.cljc`'s own docstring for the full reasoning and the honesty-
boundary text this decision does not change.

### 4. Hydrate-compute-persist KV pattern, not per-key streaming

`kv_store.cljs` diverges from `local-murakumo.kv-store`'s generic async
`{:put :get :list :append :read}` op-map: the whole book (every
receivable, funder, verification, underwriting, and the ledger/advance/
settlement/attestation histories) round-trips through ONE KV key as a
`pr-str`'d EDN snapshot (`factoring.store/snapshot`/`from-snapshot`,
new small additive functions on the existing `Store` protocol -- see
ADR-0001's `MemStore`, unchanged in shape). EDN, not JSON: this book's
`:status`/`:debtor-risk-tier` values are Clojure keywords, and every
HARD check in `factoring.governor` compares them as such.

**Known, documented limitation**: Cloudflare KV is eventually
consistent with no built-in optimistic-concurrency lock, so two
requests racing against the book within the propagation window could
interleave unsafely. Accepted for this R0 -- the same category of
documented limitation `local-murakumo.kv-store`'s own `/itonami/trial`
idempotency caveat already establishes as precedent in this fleet. A
real high-throughput deployment would need a Durable Object/D1
transaction as the book's single authoritative writer.

### 5. Administrative funder registration bypasses the LLM/governor pipeline -- a new `register-funder!` Store method

`POST /funders` writes directly via a new `factoring.store/register-
funder!` protocol method (implemented on both `MemStore` and
`DatomicStore`), NOT through `factoring.operation`'s StateGraph. This is
a deliberate, reasoned exception: recording who a funding partner is and
their own committed capacity carries no proposal-fabrication or
actuation risk analogous to what the governor exists to check (no HARD
check in this actor's design references funder-registration integrity)
-- it is the same kind of operator-entered ground truth
`:debtor-credit-limit`/`:funding-capacity` already were as static seed
data before this deployment existed.

### 6. Fail-closed auth, deliberately deviating from the template's fail-open precedent

`local-murakumo.worker/auth-ok?` fails OPEN (no configured secret means
auth passes). This Worker's gated routes fail CLOSED (503 if
`FACTORING_API_KEY` is unset) -- this actor's gated routes include
actuation decision points, a materially higher stakes class than the
template's inference-credits routes.

### 7. Public transparency surface, unauthenticated by design

`GET /health`, `/fee-schedule`, `/solvency/attestation(s)`, `/funders`
require no credential -- gating them would defeat the entire anti-
Zentoshin purpose of ADR-0001's solvency-attestation/fee-schedule/
funder-roster design (see that ADR's Context section for the 全東信
case). Every other route (receivable reads carry client/account-debtor
identifying data; every write) requires the `FACTORING_API_KEY` bearer
token.

### 8. Live smoke-testing caught two real bugs before this ADR was written

Both fixed and regression-tested (`worker/test/routes_test.clj`) before
landing, not left as known-broken:

1. **Empty POST bodies crashed `.json()`** on every action route that
   needs no caller-supplied body (verify/underwrite/advance/collect/
   settle/attest -- see `routes.cljc`'s `op!` docstring for why those
   need none). Fixed with a defensive text-then-`JSON.parse` pattern
   (`worker.cljs`'s `parse-body!`), the same discipline `local-murakumo.
   worker` already uses for UPSTREAM responses, applied here to the
   INCOMING request instead.
2. **JSON has no keyword type**: a JSON body's `"debtor-risk-tier":
   "tier-a"` arrives as a Clojure STRING after `js->clj :keywordize-keys
   true` (which only keywordizes map KEYS, not values), silently
   breaking `fee-rate-mismatch-violations`'s exact-match lookup against
   `factoring.registry/fee-schedule`'s keyword-keyed tiers forever.
   Fixed with `routes.cljc`'s `coerce-receivable-patch`, a boundary
   coercion at intake (the same kind of boundary-coercion `local-
   murakumo.routes`'s own `catalog-opts` does for query-string values).

Both were found by actually exercising the deployed URL end-to-end
(intake -> verify -> underwrite -> a stale-attestation HOLD -> a
solvency attestation publish -> **actuation 1 (advance)** -> collect ->
**actuation 2 (settle)** -> a double-advance HOLD), not by inspection --
the live audit ledger (`GET /ledger`) was read back afterward and
matched the expected sequence of commits and holds exactly. The test
data (one funder, two receivables) was deleted from KV afterward
(`wrangler kv key delete "book" --remote`), so this deployment starts
from a genuinely empty book, not fixture data.

## Honesty boundary (verbatim, see also `routes.cljc`'s own docstring)

This deployment makes the GOVERNED DECISION software live, callable and
durable at a real HTTPS URL. It does NOT wire any real bank transfer /
payment rail, real funder capital, or real KYC/AML provider, and does
not claim any real money moves through it. `:advance/fund` and `:reserve/
settle` remain governed, audited DECISION POINTS: an authorized caller
gets a real, audited, HARD-check-enforced commit/hold disposition and
ledger entry -- but no real currency moves anywhere, because no real
bank/payment integration is attached, and this deployment does not
fabricate one. The value delivered is a live, production, governed
decision+transparency service that a real licensed operator could point
real capital and real banking rails at -- the governance/audit/
transparency machinery is fully live and real; the money-movement
backend is intentionally not attached.

## Consequences

- (+) The `factoring.*` actor is now reachable at a real HTTPS URL with
  a persistent, KV-backed audit ledger, not just a JVM demo.
- (+) The public transparency surface is genuinely live and
  unauthenticated, provably (curled without any credential).
- (+) Two real bugs were caught and fixed by live smoke-testing before
  this ADR was written, not left latent.
- (+) `worker/test/routes_test.clj` (15 tests / 43 assertions) locks in
  both fixes and the full HTTP-API contract, run on the JVM against the
  same `factoring.store/seed-db` the core actor's own suite uses.
- (-) The KV eventual-consistency race window (Decision 4) is an
  accepted, documented limitation, not solved -- a real high-volume
  deployment needs a Durable Object/D1 writer.
- (-) `run-op!`'s auto-approval-by-authenticated-caller (Decision 3)
  means there is no SEPARATE two-person or delayed-approval workflow at
  the HTTP layer today -- the API key holder IS the sign-off. Fine for
  this R0 (a single-operator deployment); a multi-operator/segregation-
  of-duties workflow is additive future scope, not built here.
- (-) No real LLM advisor is wired -- the deployed advisor is the SAME
  deterministic `factoring.factoringllm/mock-advisor` the JVM test
  suite exercises. Correctness/safety is enforced by the GOVERNOR (which
  independently recomputes/verifies every proposal), not by advisor
  determinism, so this is a safe, honest choice for this deployment, not
  a shortcut around governance -- wiring `factoring.factoringllm/llm-
  advisor` (needs an inference API key) is a natural, separately-scoped
  follow-up.
- Live URL: `https://factoring.murakumo.cloud`. KV namespace:
  `FACTORING_KV`, id `a333f4584ea14f0aa123358a292bf7ed`, account
  `ai-gftd-cloud`. `FACTORING_API_KEY` secret set via `wrangler secret
  put`, mirrored in 1Password (`gftdcojp` vault) for operator recovery.
- 83 tests / 328 assertions across the whole repo now (68/285 core actor
  + 15/43 worker HTTP-API), lint-clean on both.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Split deployment code into a separate repo | ❌ | No existing convention forces it; keeps governed logic and its HTTP exposure co-versioned |
| Port `local-murakumo.kv-store`'s per-key streaming op-map verbatim | ❌ | Would require hand-duplicating `factoring.governor`'s aggregation-based HARD checks a second time in async JS-interop, risking drift from the tested version -- see Decision 2/4 |
| Fail-open auth, matching the template exactly | ❌ | This actor's gated routes include actuation decision points, materially higher stakes than the template's inference-credits routes -- deliberate deviation, see Decision 6 |
| Wire a real LLM advisor for this deployment | ❌ (deferred) | Not required for correctness (the governor, not advisor determinism, enforces safety); needs a separately-scoped inference API key decision |
| Wire a real payment rail so `:advance/fund` moves real money | ❌ (out of scope, will not build) | Explicit instruction: this deployment makes the governed decision+transparency software live; it must not fabricate a real money-movement backend -- see the honesty boundary above |
