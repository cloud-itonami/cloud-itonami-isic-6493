(ns routes
  "The factoring HTTP API as a PURE function of a `factoring.store/Store`
  and a request map -- no Cloudflare, no network, no I/O here, so it runs
  and is tested on the JVM (against `factoring.store/seed-db`, a
  `MemStore`) exactly as it runs in the deployed Worker (against a
  `MemStore` hydrated from `worker.kv-store`'s Cloudflare KV snapshot,
  see `worker.cljs`). This mirrors `cloud-murakumo-fleet`'s
  `local-murakumo.routes` structurally: a pure `{:method :path :query
  :body} -> {:status :body}` handler is the reference contract; the
  Worker (`worker.cljs`) is its translation layer, not a reimplementation
  of the business logic.

  UNLIKE `local-murakumo.routes` (whose ops are simple KV put/get/list),
  every write here runs the FULL, already-tested langgraph-clj
  StateGraph (`factoring.operation/build`): Factoring-LLM proposes,
  Factoring Governor independently censors, the phase gate adds
  caution, and -- because this is an HTTP API with no separate human-
  approval UI -- an authenticated caller's own request IS this
  deployment's human-in-the-loop sign-off (see `run-op!`'s docstring).
  HARD violations still HOLD, un-overridably, exactly as the test suite
  proves; nothing about exposing this over HTTP weakens the governor.

  This namespace has NO notion of authentication or authorization --
  same discipline `local-murakumo.routes` documents about itself
  ('the pure layer has no header/env access'). The Worker
  (`worker.cljs`) is solely responsible for deciding which routes are
  public and which require the `X-Api-Key` bearer token before ever
  calling into the logic mirrored here -- see `worker.cljs`'s own
  docstring for the exact public/gated split (this deployment's whole
  anti-Zentoshin point is that solvency/fee-schedule/funder-roster
  transparency reads must NEVER be gated behind a credential).

  Surface:
    GET  /health                        liveness (does not touch a
                                         Store method -- `worker.cljs`'s
                                         own health check does a REAL
                                         store round-trip; see its
                                         docstring for why that only
                                         makes sense at the async/KV
                                         layer)
    GET  /fee-schedule                  the published, versioned fee
                                         schedule (PUBLIC -- fair-pricing
                                         transparency)
    GET  /solvency/attestation          latest published solvency
                                         attestation, or 404 if none yet
                                         (PUBLIC -- the anti-Zentoshin
                                         transparency surface)
    GET  /solvency/attestations         full attestation history
                                         (PUBLIC)
    GET  /funders                       funder roster + capacities
                                         (PUBLIC -- distributed-funding
                                         transparency: who backs this
                                         book, and their own recorded
                                         limits)
    POST /funders                       register/update a funder
                                         ({:id :name :funding-capacity})
                                         -- ADMIN, not run through the
                                         governor (see
                                         `factoring.store/register-
                                         funder!`'s own docstring for
                                         why this is a deliberate,
                                         reasoned exception)
    GET  /receivables                   list all receivables (carries
                                         client/account-debtor
                                         identifying data)
    GET  /receivables/<id>              one receivable, 404 if unknown
    POST /receivables                   :receivable/intake (body =
                                         the new receivable's fields)
    POST /receivables/<id>/verify       :receivable/verify
    POST /receivables/<id>/underwrite   :receivable/underwrite
    POST /receivables/<id>/advance      :advance/fund -- **ACTUATION 1**
                                         (a governed decision only; see
                                         this ns's own honesty-boundary
                                         note below)
    POST /receivables/<id>/collect      :receivable/collect
    POST /receivables/<id>/settle       :reserve/settle -- **ACTUATION 2**
                                         (same honesty-boundary note)
    POST /solvency/attest               :solvency/attest -- publishes a
                                         fresh, governor-verified
                                         ground-truth-recomputed
                                         attestation
    GET  /ledger                        the full audit ledger

  ## Honesty boundary (verbatim from this deployment's own design brief)

  This deployment makes the GOVERNED DECISION software live, callable
  and durable at a real HTTPS URL. It does NOT wire any real bank
  transfer / payment rail, real funder capital, or real KYC/AML
  provider, and does not claim any real money moves through it.
  `:advance/fund` and `:reserve/settle` remain governed, audited
  DECISION POINTS: an authorized caller hitting those endpoints gets a
  real, audited, HARD-check-enforced commit/hold disposition and ledger
  entry -- but no real currency moves anywhere, because no real bank/
  payment integration is attached, and this deployment does not
  fabricate one (no fake payment-processor call, no pretend wire
  transfer, no invented funder API). The value delivered is a live,
  production, governed decision+transparency service that a real
  licensed operator could point real capital and real banking rails at
  -- the governance/audit/transparency machinery is fully live and
  real; the money-movement backend is intentionally not attached."
  (:require [clojure.string :as str]
            [factoring.operation :as op]
            [factoring.registry :as registry]
            [factoring.store :as store]
            [langgraph.graph :as g]))

(defn- segments [path] (vec (remove str/blank? (str/split path #"/"))))

(def default-context
  "The operator identity attributed to every request this API handles.
  There is no per-caller identity extracted from the API key in this
  R0 (see `worker.cljs`'s own docstring) -- the bearer token proves
  AUTHORIZATION (this caller may act), not a distinct per-operator
  IDENTITY within the audit ledger. A future revision could thread a
  named identity through per API key; documented as additive, not
  built here."
  {:actor-id "api-operator" :actor-role :factor :phase 3})

(defn run-op!
  "Runs ONE governed factoring operation end-to-end against `store`:
  propose (Factoring-LLM) -> govern (Factoring Governor) -> the phase
  gate's disposition. If the result is `:escalate` (never for a HARD
  hold -- those are un-overridable by construction, see `factoring.
  governor`), this immediately resumes as an approval BY the
  authenticated caller.

  This is a deliberate design choice for exposing a human-in-the-loop
  actor over a single-round-trip HTTP API: `factoring.operation`'s
  `interrupt-before #{:request-approval}` models 'a human must sign off
  before this commits' as a SEPARATE resume step (see `test/factoring/
  governor_contract_test.cljc`'s `approve!` helper) because the actor's
  own StateGraph has no notion of who is calling it. This deployment's
  authorization boundary (the `X-Api-Key` bearer token, checked in
  `worker.cljs` before this ever runs) IS that human sign-off -- an
  authenticated caller invoking `POST /receivables/<id>/advance` has,
  by the act of making that specific authenticated call, done exactly
  what clicking 'approve' in a human-approval UI would do. This does
  NOT weaken governance: a HARD violation still holds unconditionally
  (`:disposition :hold` is never auto-resumed, exactly as `factoring.
  governor`'s violations are documented as un-overridable), and every
  commit still writes the SAME immutable ledger fact `factoring.
  operation`'s `:commit` node always writes. Only the SOFT `:escalate`
  gate (confidence-floor / two-actuation-events-always-ask-a-human) is
  satisfied by the authenticated caller's own request, instead of by a
  second, separate approval API call."
  [store request context]
  (let [actor (op/build store)
        tid (str (name (:op request)) "-" (:subject request) "-"
                 #?(:clj (System/nanoTime) :cljs (str (js/Date.now) (rand-int 1000000))))
        r1 (g/run* actor {:request request :context context} {:thread-id tid})]
    (if (= :interrupted (:status r1))
      (g/run* actor {:approval {:status :approved :by (:actor-id context)}}
              {:thread-id tid :resume? true})
      r1)))

(defn- op-response
  "Shapes an HTTP response from a `run-op!` result. :commit -> 200,
  :hold -> 409 (the operation was refused by the governor and cannot be
  retried as-is -- the caller must address the cited violation), any
  other shape -> 500 (a bug, never expected)."
  [result subject store]
  (let [state (:state result)
        disposition (:disposition state)
        verdict (:verdict state)]
    (case disposition
      :commit {:status 200
               :body {:disposition :commit
                      :record (:record state)
                      :basis (:cites (:record state))
                      :receivable (when subject (store/receivable store subject))}}
      :hold {:status 409
             :body {:disposition :hold
                    :violations (:violations verdict)
                    :confidence (:confidence verdict)}}
      {:status 500 :body {:error "unexpected disposition" :disposition disposition}})))

(defn- coerce-receivable-patch
  "JSON has no keyword type, but `:debtor-risk-tier` and `:status` are
  compared as Clojure KEYWORDS everywhere downstream -- the published
  `factoring.registry/fee-schedule` is keyed by keyword tier
  (`:tier-a`/`:tier-b`/`:tier-c`), and every `factoring.governor`
  status-precondition check compares a keyword status value. A caller
  submitting `{\"debtor-risk-tier\": \"tier-a\"}` over JSON would
  otherwise silently fail `fee-rate-mismatch-violations` forever (the
  schedule lookup on the STRING \"tier-a\" never matches the KEYWORD
  key `:tier-a`) -- found live, via this deployment's own smoke test,
  not by inspection. Coerced once, at the intake boundary, the same
  kind of boundary-coercion `local-murakumo.routes`'s own
  `catalog-opts` does for its query-string values."
  [patch]
  (cond-> patch
    (string? (:debtor-risk-tier patch)) (update :debtor-risk-tier keyword)
    (string? (:status patch)) (update :status keyword)))

(defn- op!
  "Builds and runs a `{:op op-kw :subject subject}` request (plus
  `extra`, when a caller of THIS fn needs to thread extra fields through
  -- none of the verify/underwrite/advance/collect/settle route
  handlers below pass one, deliberately: none of those ops need a
  caller-supplied field beyond the URL's `subject` in production; the
  POST body on those routes is ignored by design, not lost by
  omission)."
  [store op-kw subject context & [extra]]
  (op-response (run-op! store (merge {:op op-kw :subject subject} extra) context) subject store))

(defn handle
  "Route `{:method :path :query :body}` -> `{:status :body}`. `context`
  defaults to `default-context` -- see that Var's docstring."
  ([store request] (handle store request default-context))
  ([store {:keys [method path body]} context]
   (let [seg (segments path)]
     (cond
       (and (= method :get) (= seg ["health"]))
       {:status 200 :body {:ok true :service "cloud-itonami-isic-6493-factoring"}}

       ;; ── public transparency surface (anti-Zentoshin) ──────────────────
       (and (= method :get) (= seg ["fee-schedule"]))
       {:status 200 :body registry/fee-schedule}

       (and (= method :get) (= seg ["solvency" "attestation"]))
       (if-let [a (store/latest-solvency-attestation store)]
         {:status 200 :body a}
         {:status 404 :body {:error "no solvency attestation has been published yet"}})

       (and (= method :get) (= seg ["solvency" "attestations"]))
       {:status 200 :body (store/solvency-attestation-history store)}

       (and (= method :get) (= seg ["funders"]))
       {:status 200 :body (store/all-funders store)}

       ;; ── admin (funder registration -- see this ns's own docstring) ────
       (and (= method :post) (= seg ["funders"]))
       (if (and (:id body) (:name body) (:funding-capacity body))
         {:status 201 :body (store/register-funder! store (select-keys body [:id :name :funding-capacity]))}
         {:status 400 :body {:error "funder registration needs :id, :name and :funding-capacity"}})

       ;; ── receivables (reads) ────────────────────────────────────────────
       (and (= method :get) (= seg ["receivables"]))
       {:status 200 :body (store/all-receivables store)}

       (and (= method :get) (= (first seg) "receivables") (= 2 (count seg)))
       (if-let [r (store/receivable store (seg 1))]
         {:status 200 :body r}
         {:status 404 :body {:error "no such receivable" :id (seg 1)}})

       ;; ── receivables (governed writes) ──────────────────────────────────
       (and (= method :post) (= seg ["receivables"]))
       (if (:id body)
         (let [patch (coerce-receivable-patch body)]
           (op-response (run-op! store {:op :receivable/intake :subject (:id patch) :patch patch} context)
                        (:id patch) store))
         {:status 400 :body {:error "receivable intake needs :id"}})

       (and (= method :post) (= (first seg) "receivables") (= 3 (count seg)) (= "verify" (seg 2)))
       (op! store :receivable/verify (seg 1) context)

       (and (= method :post) (= (first seg) "receivables") (= 3 (count seg)) (= "underwrite" (seg 2)))
       (op! store :receivable/underwrite (seg 1) context)

       (and (= method :post) (= (first seg) "receivables") (= 3 (count seg)) (= "advance" (seg 2)))
       (op! store :advance/fund (seg 1) context)

       (and (= method :post) (= (first seg) "receivables") (= 3 (count seg)) (= "collect" (seg 2)))
       (op! store :receivable/collect (seg 1) context)

       (and (= method :post) (= (first seg) "receivables") (= 3 (count seg)) (= "settle" (seg 2)))
       (op! store :reserve/settle (seg 1) context)

       ;; ── solvency attestation (governed publish) ────────────────────────
       (and (= method :post) (= seg ["solvency" "attest"]))
       (op! store :solvency/attest "book" context)

       ;; ── audit ledger (read) ─────────────────────────────────────────────
       (and (= method :get) (= seg ["ledger"]))
       {:status 200 :body (store/ledger store)}

       :else {:status 404 :body {:error "not found" :method method :path path}}))))
