(ns worker
  "Cloudflare Worker entry for `cloud-itonami-isic-6493` (factoring
  activities, ISIC Rev.5 6493) -- `factoring.murakumo.cloud`. Mirrors
  `cloud-murakumo-fleet`'s `local-murakumo.worker` structurally (CORS,
  a real store-round-trip health check, Bearer-token auth gate, ESM
  `{fetch}` export), adapted to this actor's hydrate-compute-persist
  KV pattern -- see `kv-store`'s own docstring for why this differs
  from the template's per-key streaming KV pattern, and `routes.cljc`'s
  own docstring for why every write here runs the FULL, already-tested
  `factoring.operation` StateGraph rather than a hand-duplicated async
  reimplementation of the business logic.

  ## Public vs. gated (the anti-Zentoshin transparency boundary)

  `public-get-paths` are ALWAYS reachable with no credential, by
  design: the whole point of this actor's solvency-attestation/fee-
  schedule/funder-roster transparency machinery (see the repo README's
  '全東信 (Zentoshin)' section) is that anyone can independently verify
  it without trusting the operator -- gating it behind an API key would
  defeat that purpose. Every OTHER route (receivable reads -- these
  carry client/account-debtor identifying data -- and every write,
  governed or administrative) requires the `X-Api-Key` header or an
  `Authorization: Bearer <token>` header matching the `FACTORING_API_KEY`
  secret (`wrangler secret put FACTORING_API_KEY`).

  UNLIKE `local-murakumo.worker/auth-ok?` (which fails OPEN -- no
  configured secret means auth passes), gated routes here fail CLOSED:
  if `FACTORING_API_KEY` is not configured, gated routes 503 rather
  than silently accepting every caller. Deliberate deviation, not an
  oversight: this actor's gated routes include real-money-adjacent
  actuation DECISION points (see routes.cljc's own honesty-boundary
  note for what 'decision' does and does not mean here), a materially
  higher stakes class than the template's inference-credits routes.

  ## Honesty boundary

  See `routes.cljc`'s own docstring for the verbatim honesty-boundary
  paragraph this Worker inherits unmodified: this deployment makes the
  GOVERNED DECISION software live and durable; it attaches no real
  bank/payment rail, no real funder capital, no real KYC/AML provider,
  and fabricates none of those to make the demo look more complete than
  it is."
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [kv-store :as kv]
            [factoring.store :as store]
            [routes :as routes]))

;; ── CORS ──────────────────────────────────────────────────────────────────
;; Same posture as `local-murakumo.worker`'s own CORS header (see that ns's
;; docstring for the full reasoning): a wildcard origin does not widen what
;; is actually reachable -- every route here is either already public by
;; design (the transparency surface) or gated by its own Bearer-token check,
;; which a wildcard CORS origin does not bypass. Without this, any browser-
;; based caller (an operator's own admin console, a client-facing status
;; page reading /solvency/attestation) would silently fail CORS preflight.
(def ^:private cors-headers
  #js {"Access-Control-Allow-Origin" "*"
       "Access-Control-Allow-Methods" "GET, POST, OPTIONS"
       "Access-Control-Allow-Headers" "content-type, authorization, x-api-key"})

(defn- with-cors [^js headers]
  (doseq [k (js/Object.keys cors-headers)] (.set headers k (gobj/get cors-headers k)))
  headers)

(defn- jr [status body]
  (js/Response. (js/JSON.stringify (clj->js body))
                #js {:status status
                     :headers (with-cors (js/Headers. #js {"content-type" "application/json"}))}))

(defn- P [v] (js/Promise.resolve v))

;; ── public transparency surface (anti-Zentoshin -- see ns docstring) ──────
(def public-get-paths
  #{"/health" "/fee-schedule" "/solvency/attestation" "/solvency/attestations" "/funders"})

(defn- auth-ok? [^js env ^js request]
  (let [want (gobj/get env "FACTORING_API_KEY")
        auth-hdr (.get (.-headers request) "authorization")
        key-hdr (.get (.-headers request) "x-api-key")
        bearer (when (and auth-hdr (str/starts-with? auth-hdr "Bearer "))
                 (subs auth-hdr 7))]
    (cond
      (not want) :not-configured           ; fail CLOSED -- see ns docstring
      (or (= want bearer) (= want key-hdr)) true
      :else false)))

;; deep liveness: a REAL KV round-trip (load the book, run one cheap read),
;; so a missing/misconfigured FACTORING_KV binding is 503 here instead of
;; silently breaking every other route -- same discipline `local-murakumo.
;; worker`'s own `/health` documents ('a real store round-trip, so a missing
;; KV binding... is 503 here instead of silently breaking the data routes').
(defn- health! [env]
  (-> (kv/load-store! env)
      (.then (fn [st] (let [ok (sequential? (store/all-funders st))]
                        {:status (if ok 200 503)
                         :body {:ok ok :service "cloud-itonami-isic-6493-factoring"
                                :storage {:backend "cloudflare-kv" :ok ok}}})))
      (.catch (fn [e] {:status 503
                       :body {:ok false :service "cloud-itonami-isic-6493-factoring"
                              :storage {:backend "cloudflare-kv" :ok false :error (str e)}}}))))

(defn- parse-body!
  "-> Promise<map-or-nil>. Reads the request body as TEXT first, then
  JSON-parses it, so a bodyless POST (every governed-op action route
  except `POST /receivables` and `POST /funders` needs no body at all
  -- see `routes.cljc`'s `op!` docstring) degrades to `nil` instead of
  throwing a SyntaxError out of a blind `.json()` call. Same defensive
  'text first, then try/catch JSON.parse' discipline `local-murakumo.
  worker` uses for upstream responses, applied here to the INCOMING
  request instead."
  [^js request]
  (-> (.text request)
      (.then (fn [t]
               (if (seq t)
                 (try (js->clj (js/JSON.parse t) :keywordize-keys true)
                      (catch :default _ nil))
                 nil)))))

(defn- dispatch! [env method path body]
  (-> (kv/load-store! env)
      (.then (fn [st]
               (let [result (routes/handle st {:method method :path path :query {} :body body})]
                 (if (= method :get)
                   (P result)
                   (-> (kv/persist-store! env st) (.then (fn [_] result)))))))))

(defn ^:export fetch-handler [request env _ctx]
  (let [url (js/URL. (.-url request))
        method (keyword (str/lower-case (.-method request)))
        path (.-pathname url)]
    (cond
      (= method :options)
      (js/Response. nil #js {:status 204 :headers (with-cors (js/Headers.))})

      (= path "/health")
      (-> (health! env) (.then (fn [{:keys [status body]}] (jr status body))) (.catch (fn [e] (jr 500 {:error (str e)}))))

      :else
      (let [public? (and (= method :get) (contains? public-get-paths path))
            auth (when-not public? (auth-ok? env request))]
        (cond
          (= auth :not-configured)
          (P (jr 503 {:error "this deployment has no FACTORING_API_KEY configured -- gated routes are unreachable until `wrangler secret put FACTORING_API_KEY` is run (fail-closed by design, see worker.cljs's own docstring)"}))

          (false? auth)
          (P (jr 401 {:error "invalid or missing API key"}))

          :else
          (-> (if (= method :post) (parse-body! request) (P nil))
              (.then (fn [b] (dispatch! env method path b)))
              (.then (fn [{:keys [status body]}] (jr status body)))
              (.catch (fn [e] (jr 500 {:error (str e)})))))))))

;; ESM default export shape Cloudflare expects: { fetch }
(def default (clj->js {:fetch fetch-handler}))
