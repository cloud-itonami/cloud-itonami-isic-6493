(ns kv-store
  "The async Cloudflare KV persistence boundary around `factoring.store`'s
  SYNCHRONOUS `MemStore` -- the load/save half of the hydrate-compute-
  persist pattern `worker.cljs` drives on every request.

  Structural note vs. `cloud-murakumo-fleet`'s `local-murakumo.kv-store`
  (the template this build otherwise mirrors): that KV store implements
  a generic async `{:put :get :list :append :read}` op-map, because
  `local-murakumo.routes`'s ops are simple, independent CRUD reads/
  writes with no cross-record business logic. This actor's operations
  are NOT that: every write runs `factoring.governor`'s HARD checks
  (some of which -- `debtor-concentration-ceiling-exceeded-violations`,
  `funder-concentration-ceiling-exceeded-violations`,
  `solvency-attestation-*` -- independently AGGREGATE over every OTHER
  stored receivable, see `factoring.registry/aggregate-exposure`'s own
  docstring) through the same synchronous `factoring.operation`
  langgraph-clj StateGraph the JVM test suite exercises. Re-deriving
  that logic a second time in hand-written async JS-interop (the way
  `local-murakumo.worker/route` duplicates `local-murakumo.routes/
  handle`'s simpler CRUD op-by-op) would risk behavioral drift between
  the tested version and the deployed version -- exactly the kind of
  bug this actor's whole governed-and-audited design exists to prevent
  elsewhere. Instead: the ENTIRE book (every receivable, funder,
  verification, underwriting, and the ledger/advance/settlement/
  attestation histories) round-trips through ONE KV key as a `pr-str`d
  EDN snapshot (`factoring.store/snapshot`/`from-snapshot`), and the
  SAME synchronous `factoring.operation`/`factoring.governor`/
  `factoring.phase` code the JVM tests already prove correct runs
  UNCHANGED against the hydrated in-memory copy for the duration of one
  Worker request.

  Honest limitation (documented, not silently ignored -- the SAME
  category of caveat `local-murakumo.kv-store`'s own docstring makes
  about its `/itonami/trial` idempotency): Cloudflare KV is read-after-
  write EVENTUALLY consistent with no built-in optimistic-concurrency/
  locking primitive, so two requests racing against the SAME book
  snapshot within the propagation window could both read the same
  starting state and the second write could silently clobber the
  first's mutation (e.g. two concurrent `:advance/fund` calls on
  DIFFERENT receivables in the same book could lose one of the two
  mutations, though `factoring.governor`'s own HARD checks -- e.g.
  `double-advance-violations` -- still correctly prevent the SAME
  receivable from being double-actuated within a SINGLE request, since
  that check runs against the already-hydrated snapshot within that one
  request). A real high-throughput deployment would need a Durable
  Object (or D1 transaction) as the book's authoritative single writer;
  out of scope for this R0, matching the fleet's existing precedent
  (`local-murakumo.kv-store`'s `:trial` grant, and `factoring.
  registry`'s own `stale-after-n-advances` solvency-staleness window,
  both accept a small, documented, low-probability window rather than
  building a Durable Object)."
  (:require [cljs.reader :as edn]
            [factoring.store :as store]))

(def ^:private book-key "book")

(defn load-store!
  "-> Promise<MemStore>, hydrated from the Worker's `FLEET_KV`-analog
  binding (`env.FACTORING_KV`), or a genuinely empty book
  (`factoring.store/empty-db`) if no snapshot has ever been written."
  [^js env]
  (let [^js kv (.-FACTORING_KV env)]
    (-> (.get kv book-key "text")
        (.then (fn [s]
                 (store/from-snapshot (when (seq s) (edn/read-string s))))))))

(defn persist-store!
  "-> Promise<nil>. Writes `store`'s current snapshot back to KV as a
  `pr-str`d EDN string (NOT JSON -- see `factoring.store/snapshot`'s
  own docstring for why: this book's `:status`/`:debtor-risk-tier`/etc.
  are keywords, and every HARD check in `factoring.governor` compares
  them as such)."
  [^js env store]
  (let [^js kv (.-FACTORING_KV env)]
    (.put kv book-key (pr-str (store/snapshot store)))))
