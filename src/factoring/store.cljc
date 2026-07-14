(ns factoring.store
  "SSoT for the factoring actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam every prior
  `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/factoring/store_contract_test.cljc), which is the whole point:
  the actor, the Factoring Governor and the audit ledger never know
  which SSoT they run on -- and, per this actor's own anti-Zentoshin
  design (see `factoring.registry`'s ns docstring), a client, an
  account debtor, or an outside observer with read access to a live
  deployment's Store can independently call `latest-solvency-
  attestation`/`debtor-outstanding-exposure`/etc. themselves, rather
  than trusting a privately-held set of books the way Zentoshin's
  merchants had no choice but to.

  TWO entity kinds, unlike `credit.store`'s single application entity:
  the RECEIVABLE (the dynamic entity every op acts on) and the FUNDER
  (a small, mostly-static reference registry -- each funding source's
  own committed capital line -- seeded once, not filed per-transaction,
  so this still respects the 'no dynamically-filed sub-record' shape
  `credit.store`/`casualty.store`/`reinsurance.store` established;
  funders are closer to `credit.facts`'s static reference data than to
  `pension.store`'s dynamically-filed disbursement sub-records).

  The ledger stays append-only on every backend: 'which receivable was
  verified/underwritten, which advance and settlement actually
  happened, on what jurisdictional basis, and every solvency
  attestation ever published' is always a query over an immutable log."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [factoring.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (receivable [s id])
  (all-receivables [s])
  (funder [s id])
  (all-funders [s])
  (verification-of [s receivable-id] "committed verification verdict for a receivable, or nil")
  (underwriting-of [s receivable-id] "committed underwriting verdict for a receivable, or nil")
  (ledger [s])
  (advance-history [s] "the append-only advance history (factoring.registry drafts, actuation 1)")
  (settlement-history [s] "the append-only settlement history (factoring.registry drafts, actuation 2)")
  (solvency-attestation-history [s] "the append-only, publicly-queryable solvency attestation history")
  (latest-solvency-attestation [s] "the most recently published attestation record, or nil")
  (next-sequence [s kind jurisdiction] "next :advance|:settlement|:attestation sequence number for a jurisdiction")
  (receivable-already-advanced? [s id])
  (receivable-already-settled? [s id])
  (debtor-outstanding-exposure [s debtor-id exclude-id] "ground-truth aggregate exposure for one account debtor")
  (funder-outstanding-exposure [s funder-id exclude-id] "ground-truth aggregate exposure for one funding source")
  (book-outstanding-exposure [s exclude-id] "ground-truth aggregate exposure for the whole book")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-receivables [s receivables] "replace/seed the receivable directory (map id->receivable)")
  (register-funder! [s funder] "administrative: add/update a funder record ({:id :name :funding-capacity}) -- ground truth entered by the operator, the SAME kind of static reference data `demo-funders`/`:debtor-credit-limit` already are. Deliberately NOT run through the LLM-advisor/governor pipeline: there is no proposal-fabrication or actuation risk in recording who a funding partner is and their own committed capacity, unlike a receivable's advance/settlement -- see `worker/routes.cljc`'s own docstring for the full reasoning behind this deployment's admin-vs-governed-op split")
  (settlement-account [s] "this deployment's own settlement account ({:iban :bic :account-holder-name}), or nil if not yet configured -- the ordering/debtor side `factoring.registry/build-settlement-instruction` needs to construct a real XS2A/MT103 settlement-instruction artifact")
  (register-settlement-account! [s account] "administrative, same admin-not-governed posture as register-funder! -- recording THIS factor's own bank account details carries no proposal-fabrication or actuation risk"))

;; ----------------------------- demo data -----------------------------

(def demo-funders
  "Three independent, named institutional funding sources -- the
  distributed-funding mitigation (see `factoring.registry`'s ns
  docstring): no single funder backs the whole book, so no single
  funder's collapse can cascade to every client the way Zentoshin's
  single-balance-sheet failure did. Fictional, representative names."
  {"funder-1" {:id "funder-1" :name "Harborlight Capital Partners" :funding-capacity 4000000}
   "funder-2" {:id "funder-2" :name "Meridian Trade Finance" :funding-capacity 3000000}
   "funder-3" {:id "funder-3" :name "Nordbrücke Receivables Fund" :funding-capacity 2000000}})

(def demo-settlement-account
  "This (fictional) demo factor's own settlement account, domiciled with a
  fictional German bank (a real, valid mod-97 IBAN shape + a structurally-
  valid BIC, matching that same fictional bank's shape -- no real bank
  named) -- the ordering/debtor side of every real settlement-instruction
  artifact `factoring.registry/build-settlement-instruction` constructs.
  Used for BOTH rails: XS2A for EUR/USD/GBP-ish receivables (an EU/PSD2-
  interoperable bank initiating its own payment), and MT103 for JPY
  receivables (the SAME bank sending an international correspondent wire
  to the client's Japanese bank -- realistic; German banks routinely
  originate SWIFT wires to Japanese beneficiaries)."
  {:iban "DE89370400440532013000"
   :bic "FCTRDEFFXXX"
   :account-holder-name "cloud-itonami-isic-6493 Demo Factoring Ltd"})

(def ^:private demo-corp-evidence
  "Shared demo eKYC evidence for the :corp-ro method (name+registered-
  office declaration + registry-information-lookup) -- see `kotoba.
  ekyc/method-catalog`'s corp-ro entry. Fictional refs, matching every
  other demo-data value in this catalog."
  [{:kind :name-and-registered-office-declaration :ref "decl-1"}
   {:kind :registry-information-lookup :ref "registry-1" :fields-confirmed [:name :registered-office]}])

(defn- demo-ekyc-fields
  "Shared demo eKYC fields (real, spec-conformant `kotoba.ekyc` method +
  complete evidence, corporate subject type) for both the client and the
  account debtor of a demo receivable -- see `factoring.registry/
  client-ekyc-verification`/`debtor-ekyc-verification`."
  [client-address debtor-address]
  {:client-address client-address :client-subject-type :corporate
   :client-ekyc-method :corp-ro :client-ekyc-evidence demo-corp-evidence
   :debtor-address debtor-address :debtor-subject-type :corporate
   :debtor-ekyc-method :corp-ro :debtor-ekyc-evidence demo-corp-evidence})

(defn demo-data
  "A small, self-contained receivable set so the actor + tests run
  offline. Every receivable now also carries real eKYC fields (`kotoba.
  ekyc`) and real settlement-instruction banking details (`kotoba.swift`/
  `kotoba.banking.api`) -- JPY receivables (no IBAN scheme in Japan) get
  a `:client-bic` and route through MT103; the rest get either a real
  IBAN (`:client-iban`, DEU/GBR) or a plain non-IBAN account number
  (USA -- genuinely has no IBAN scheme either, correctly falls back to
  `kotoba.banking.api`'s `other`/proprietary-ID accountReference form)."
  []
  {:receivables
   {"rcv-1" (merge {:id "rcv-1" :client-id "client-1" :client-name "GreenLeaf Logistics K.K."
                    :debtor-id "debtor-1" :debtor-name "Meiwa Trading Co." :debtor-credit-limit 5000000
                    :debtor-risk-tier :tier-a :fee-rate 0.02
                    :face-amount 2000000 :jurisdiction "JPN" :funder-id "funder-1" :currency "JPY"
                    :client-bic "GRLFJPJTXXX"
                    :client-sanctions-hit? false :debtor-sanctions-hit? false :status :submitted}
                   (demo-ekyc-fields "1-1 Otemachi, Chiyoda-ku, Tokyo" "2-2 Marunouchi, Chiyoda-ku, Tokyo"))
    "rcv-2" (merge {:id "rcv-2" :client-id "client-2" :client-name "Acme Textiles Ltd"
                    :debtor-id "debtor-2" :debtor-name "Camden Wholesale plc" :debtor-credit-limit 1000000
                    :debtor-risk-tier :tier-b :fee-rate 0.03
                    :face-amount 400000 :jurisdiction "ATL" :funder-id "funder-2" :currency "USD"
                    :client-iban "021000021200011122"
                    :client-sanctions-hit? false :debtor-sanctions-hit? false :status :submitted}
                   (demo-ekyc-fields "500 Fifth Ave, New York, NY" "10 Camden High St, London"))
    "rcv-3" (merge {:id "rcv-3" :client-id "client-3" :client-name "Rhein Fabrik GmbH"
                    :debtor-id "debtor-3" :debtor-name "Sanctioned Trading Corp" :debtor-credit-limit 1000000
                    :debtor-risk-tier :tier-c :fee-rate 0.05
                    :face-amount 500000 :jurisdiction "DEU" :funder-id "funder-3" :currency "EUR"
                    :client-iban "FR1420041010050500013M02606"
                    :client-sanctions-hit? false :debtor-sanctions-hit? true :status :submitted}
                   (demo-ekyc-fields "Rheinstraße 1, Frankfurt" "Unknown, Sanctioned Territory"))
    "rcv-4" (merge {:id "rcv-4" :client-id "client-4" :client-name "Cascade Builders Inc"
                    :debtor-id "debtor-1" :debtor-name "Meiwa Trading Co." :debtor-credit-limit 5000000
                    :debtor-risk-tier :tier-a :fee-rate 0.02
                    :face-amount 3500000 :jurisdiction "USA" :funder-id "funder-1" :currency "USD"
                    :client-iban "021000021200099887"
                    :client-sanctions-hit? false :debtor-sanctions-hit? false :status :submitted}
                   (demo-ekyc-fields "1200 Cascade Ave, Seattle, WA" "2-2 Marunouchi, Chiyoda-ku, Tokyo"))
    "rcv-5" (merge {:id "rcv-5" :client-id "client-5" :client-name "Solent Marine Supplies"
                    :debtor-id "debtor-4" :debtor-name "Northgate Retail Group" :debtor-credit-limit 2000000
                    :debtor-risk-tier :tier-a :fee-rate 0.99
                    :face-amount 300000 :jurisdiction "GBR" :funder-id "funder-2" :currency "GBP"
                    :client-iban "GB82WEST12345698765432"
                    :client-sanctions-hit? false :debtor-sanctions-hit? false :status :submitted}
                   (demo-ekyc-fields "Solent Way, Southampton" "1 Northgate Rd, Manchester"))
    "rcv-6" (merge {:id "rcv-6" :client-id "client-6" :client-name "Fujimori Precision Parts K.K."
                    :debtor-id "debtor-5" :debtor-name "Cascadia Assembly Group" :debtor-credit-limit 5000000
                    :debtor-risk-tier :tier-a :fee-rate 0.02
                    :face-amount 3000000 :jurisdiction "USA" :funder-id "funder-1" :currency "USD"
                    :client-iban "021000021200055443"
                    :client-sanctions-hit? false :debtor-sanctions-hit? false :status :submitted}
                   (demo-ekyc-fields "3-3 Nihonbashi, Chuo-ku, Tokyo" "800 Cascadia Blvd, Portland, OR"))
    "rcv-7" (merge {:id "rcv-7" :client-id "client-7" :client-name "Tidewater Exports LLC"
                    :debtor-id "debtor-6" :debtor-name "Baltic Freight Holdings" :debtor-credit-limit 5000000
                    :debtor-risk-tier :tier-b :fee-rate 0.03
                    :face-amount 200000 :jurisdiction "DEU" :funder-id "funder-3" :currency "EUR"
                    :client-iban "DE75512108001245126199"
                    :client-sanctions-hit? false :debtor-sanctions-hit? false :status :submitted}
                   (demo-ekyc-fields "1 Tidewater Way, Norfolk, VA" "Hafenstraße 5, Hamburg"))}})

;; ----------------------------- shared commit logic -----------------------------

(defn- settlement-instruction->record-entry
  "The keyword-keyed `factoring.registry/build-settlement-instruction`
  result -> a string-keyed entry consistent with the rest of this
  actor's JSON-shaped draft records. nil (no key added) when no
  settlement account is configured yet -- see `build-settlement-
  instruction`'s own docstring."
  [si]
  (when si
    (cond-> {"rail" (name (:settlement/rail si)) "leg" (name (:settlement/leg si))}
      (:settlement/payload si) (assoc "payload" (:settlement/payload si))
      (:settlement/error si)   (assoc "error" (:settlement/error si)))))

(defn- advance!
  "Backend-agnostic `:advance/mark-funded` -- looks up the receivable
  via the protocol and drafts the advance record (**actuation 1**),
  now including a REAL settlement-instruction artifact (XS2A payload or
  SWIFT MT103 wire string, per `factoring.registry/build-settlement-
  instruction` -- NEVER transmitted anywhere, see that fn's own
  honesty-boundary docstring), returning {:result .. :receivable-patch
  ..} for the caller to persist."
  [s receivable-id]
  (let [r (receivable s receivable-id)
        seq-n (next-sequence s :advance (:jurisdiction r))
        result (registry/register-advance
                receivable-id (:face-amount r) (:jurisdiction r) seq-n)
        advance-amount (get-in result ["record" "advance_amount"])
        si (registry/build-settlement-instruction
            r (settlement-account s) :advance advance-amount (get result "advance_number"))
        result (cond-> result si (update "record" assoc "settlement_instruction" (settlement-instruction->record-entry si)))]
    {:result result
     :receivable-patch {:status :advanced
                        :advance-number (get result "advance_number")}}))

(defn- settle!
  "Backend-agnostic `:settlement/mark-settled` -- looks up the
  receivable via the protocol and drafts the settlement record
  (**actuation 2**), now including a REAL settlement-instruction
  artifact for the reserve-release leg, same discipline as `advance!`
  above, returning {:result .. :receivable-patch ..} for the caller to
  persist."
  [s receivable-id]
  (let [r (receivable s receivable-id)
        seq-n (next-sequence s :settlement (:jurisdiction r))
        result (registry/register-settlement
                receivable-id (:face-amount r) (:fee-rate r) (:jurisdiction r) seq-n)
        settlement-amount (get-in result ["record" "settlement_amount"])
        si (registry/build-settlement-instruction
            r (settlement-account s) :settle settlement-amount (get result "settlement_number"))
        result (cond-> result si (update "record" assoc "settlement_instruction" (settlement-instruction->record-entry si)))]
    {:result result
     :receivable-patch {:status :settled
                        :settlement-number (get result "settlement_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (receivable [_ id] (get-in @a [:receivables id]))
  (all-receivables [_] (sort-by :id (vals (:receivables @a))))
  (funder [_ id] (get-in @a [:funders id]))
  (all-funders [_] (sort-by :id (vals (:funders @a))))
  (verification-of [_ id] (get-in @a [:verifications id]))
  (underwriting-of [_ id] (get-in @a [:underwritings id]))
  (ledger [_] (:ledger @a))
  (advance-history [_] (:advances @a))
  (settlement-history [_] (:settlements @a))
  (solvency-attestation-history [_] (:attestations @a))
  (latest-solvency-attestation [_] (last (:attestations @a)))
  (next-sequence [_ kind jurisdiction] (get-in @a [:sequences [kind jurisdiction]] 0))
  (receivable-already-advanced? [_ id] (contains? #{:advanced :collected :settled} (get-in @a [:receivables id :status])))
  (receivable-already-settled? [_ id] (= :settled (get-in @a [:receivables id :status])))
  (debtor-outstanding-exposure [s debtor-id exclude-id]
    (registry/debtor-exposure (all-receivables s) debtor-id exclude-id))
  (funder-outstanding-exposure [s funder-id exclude-id]
    (registry/funder-exposure (all-receivables s) funder-id exclude-id))
  (book-outstanding-exposure [s exclude-id]
    (registry/book-exposure (all-receivables s) exclude-id))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :receivable/upsert
      (swap! a update-in [:receivables (:id value)] merge value)

      :verification/set
      (swap! a assoc-in [:verifications (first path)] payload)

      :underwriting/set
      (swap! a (fn [state]
                 (-> state
                     (assoc-in [:underwritings (first path)] payload)
                     (assoc-in [:receivables (first path) :status] :underwritten))))

      :advance/mark-funded
      (let [receivable-id (first path)
            {:keys [result receivable-patch]} (advance! s receivable-id)
            jurisdiction (:jurisdiction (receivable s receivable-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences [:advance jurisdiction]] (fnil inc 0))
                       (update-in [:receivables receivable-id] merge receivable-patch)
                       (update :advances registry/append result))))
        result)

      :collection/mark-received
      (swap! a assoc-in [:receivables (first path) :status] :collected)

      :settlement/mark-settled
      (let [receivable-id (first path)
            {:keys [result receivable-patch]} (settle! s receivable-id)
            jurisdiction (:jurisdiction (receivable s receivable-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences [:settlement jurisdiction]] (fnil inc 0))
                       (update-in [:receivables receivable-id] merge receivable-patch)
                       (update :settlements registry/append result))))
        result)

      :attestation/publish
      (let [jurisdiction (or (:jurisdiction value) "GLOBAL")
            seq-n (next-sequence s :attestation jurisdiction)
            result (registry/register-attestation payload jurisdiction seq-n)]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences [:attestation jurisdiction]] (fnil inc 0))
                       (update :attestations registry/append result))))
        result)

      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-receivables [s receivables] (when (seq receivables) (swap! a assoc :receivables receivables)) s)
  (register-funder! [_ {:keys [id] :as funder}] (swap! a assoc-in [:funders id] funder) funder)
  (settlement-account [_] (:settlement-account @a))
  (register-settlement-account! [_ account] (swap! a assoc :settlement-account account) account))

(defn empty-state
  "The genuinely EMPTY book shape (no demo/fixture data) -- what a real
  production deployment starts from before any receivable or funder has
  ever been recorded. Distinct from `demo-data`/`demo-funders`, which
  are fixture data for tests/the local demo only and must never leak
  into a live deployment."
  []
  {:receivables {} :funders {} :verifications {} :underwritings {}
   :ledger [] :sequences {} :advances [] :settlements [] :attestations []})

(defn seed-db
  "A MemStore seeded with the demo receivable + funder set. The
  deterministic default for dev/tests/the local demo -- NEVER used for
  a live deployment's actual book (see `empty-db`/`from-snapshot`)."
  []
  (->MemStore (atom (assoc (empty-state) :receivables (:receivables (demo-data)) :funders demo-funders
                           :settlement-account demo-settlement-account))))

(defn empty-db
  "A MemStore with the genuinely empty book -- what a fresh production
  deployment (no KV snapshot yet) starts from. No demo/fixture data."
  []
  (->MemStore (atom (empty-state))))

(defn snapshot
  "The MemStore's raw EDN state -- for persisting to an external durable
  store (e.g. `worker/kv_store.cljs`'s KV-backed persistence layer)
  between requests. Pairs with `from-snapshot`. Plain EDN (not JSON): the
  book's `:status`/`:debtor-risk-tier`/etc. values are keywords, and a
  round-trip through JSON would silently turn them into strings, which
  every keyword-comparison HARD check in `factoring.governor` depends
  on -- `pr-str`/`edn/read-string` (via `worker/kv_store.cljs`'s
  `cljs.reader`) round-trips exactly."
  [store]
  @(:a store))

(defn from-snapshot
  "A MemStore hydrated from a previously-`snapshot`ted EDN state, or a
  genuinely empty book (`empty-state`) if `state` is nil -- the
  deserialization half of `snapshot`. The store returned is a REAL
  `factoring.store/Store` (a `MemStore`) -- every `factoring.governor`/
  `factoring.operation` call against it runs the exact same, already-
  tested synchronous business logic as `test/factoring/*`, whether the
  process is the JVM test suite or a Cloudflare Worker request that
  just hydrated this snapshot from KV."
  [state]
  (->MemStore (atom (or state (empty-state)))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (verification/underwriting payloads, ledger
  facts, advance/settlement/attestation records) are stored as EDN
  strings so `langchain.db` doesn't expand them into sub-entities --
  the same convention every sibling actor's store uses. `:sequence/key`
  is the `[kind jurisdiction]` pair encoded as a string
  (`\"advance:JPN\"`), avoiding a dependency on compound-tuple support."
  {:receivable/id             {:db/unique :db.unique/identity}
   :funder/id                 {:db/unique :db.unique/identity}
   :verification/receivable-id {:db/unique :db.unique/identity}
   :underwriting/receivable-id {:db/unique :db.unique/identity}
   :ledger/seq                {:db/unique :db.unique/identity}
   :advance/seq                {:db/unique :db.unique/identity}
   :settlement/seq              {:db/unique :db.unique/identity}
   :attestation/seq              {:db/unique :db.unique/identity}
   :sequence/key                  {:db/unique :db.unique/identity}
   :settlement-account/singleton   {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))
(defn- seq-key [kind jurisdiction] (str (name kind) ":" jurisdiction))

(defn- receivable->tx [{:keys [id client-id client-name debtor-id debtor-name debtor-credit-limit
                               debtor-risk-tier fee-rate face-amount jurisdiction funder-id
                               client-sanctions-hit? debtor-sanctions-hit? status advance-number settlement-number]}]
  (cond-> {:receivable/id id}
    client-id                 (assoc :receivable/client-id client-id)
    client-name                 (assoc :receivable/client-name client-name)
    debtor-id                     (assoc :receivable/debtor-id debtor-id)
    debtor-name                     (assoc :receivable/debtor-name debtor-name)
    debtor-credit-limit               (assoc :receivable/debtor-credit-limit debtor-credit-limit)
    debtor-risk-tier                    (assoc :receivable/debtor-risk-tier (enc debtor-risk-tier))
    fee-rate                              (assoc :receivable/fee-rate fee-rate)
    face-amount                             (assoc :receivable/face-amount face-amount)
    jurisdiction                              (assoc :receivable/jurisdiction jurisdiction)
    funder-id                                   (assoc :receivable/funder-id funder-id)
    (some? client-sanctions-hit?)                 (assoc :receivable/client-sanctions-hit? client-sanctions-hit?)
    (some? debtor-sanctions-hit?)                   (assoc :receivable/debtor-sanctions-hit? debtor-sanctions-hit?)
    status                                             (assoc :receivable/status (enc status))
    advance-number                                       (assoc :receivable/advance-number advance-number)
    settlement-number                                      (assoc :receivable/settlement-number settlement-number)))

(def ^:private receivable-pull
  [:receivable/id :receivable/client-id :receivable/client-name :receivable/debtor-id :receivable/debtor-name
   :receivable/debtor-credit-limit :receivable/debtor-risk-tier :receivable/fee-rate :receivable/face-amount
   :receivable/jurisdiction :receivable/funder-id :receivable/client-sanctions-hit? :receivable/debtor-sanctions-hit?
   :receivable/status :receivable/advance-number :receivable/settlement-number])

(defn- pull->receivable [m]
  (when (:receivable/id m)
    {:id (:receivable/id m) :client-id (:receivable/client-id m) :client-name (:receivable/client-name m)
     :debtor-id (:receivable/debtor-id m) :debtor-name (:receivable/debtor-name m)
     :debtor-credit-limit (:receivable/debtor-credit-limit m) :debtor-risk-tier (dec* (:receivable/debtor-risk-tier m))
     :fee-rate (:receivable/fee-rate m) :face-amount (:receivable/face-amount m)
     :jurisdiction (:receivable/jurisdiction m) :funder-id (:receivable/funder-id m)
     :client-sanctions-hit? (boolean (:receivable/client-sanctions-hit? m))
     :debtor-sanctions-hit? (boolean (:receivable/debtor-sanctions-hit? m))
     :status (dec* (:receivable/status m))
     :advance-number (:receivable/advance-number m) :settlement-number (:receivable/settlement-number m)}))

(defn- funder->tx [{:keys [id name funding-capacity]}]
  (cond-> {:funder/id id}
    name             (assoc :funder/name name)
    funding-capacity (assoc :funder/funding-capacity funding-capacity)))

(def ^:private settlement-account-singleton-key "settlement-account")

(defrecord DatomicStore [conn]
  Store
  (receivable [_ id]
    (pull->receivable (d/pull (d/db conn) receivable-pull [:receivable/id id])))
  (all-receivables [_]
    (->> (d/q '[:find [?id ...] :where [?e :receivable/id ?id]] (d/db conn))
         (map #(pull->receivable (d/pull (d/db conn) receivable-pull [:receivable/id %])))
         (sort-by :id)))
  (funder [_ id]
    (let [m (d/pull (d/db conn) [:funder/id :funder/name :funder/funding-capacity] [:funder/id id])]
      (when (:funder/id m)
        {:id (:funder/id m) :name (:funder/name m) :funding-capacity (:funder/funding-capacity m)})))
  (all-funders [_]
    (->> (d/q '[:find [?id ...] :where [?e :funder/id ?id]] (d/db conn))
         (map #(let [m (d/pull (d/db conn) [:funder/id :funder/name :funder/funding-capacity] [:funder/id %])]
                 {:id (:funder/id m) :name (:funder/name m) :funding-capacity (:funder/funding-capacity m)}))
         (sort-by :id)))
  (verification-of [_ receivable-id]
    (dec* (d/q '[:find ?p . :in $ ?rid
                :where [?k :verification/receivable-id ?rid] [?k :verification/payload ?p]]
              (d/db conn) receivable-id)))
  (underwriting-of [_ receivable-id]
    (dec* (d/q '[:find ?p . :in $ ?rid
                :where [?k :underwriting/receivable-id ?rid] [?k :underwriting/payload ?p]]
              (d/db conn) receivable-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (advance-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :advance/seq ?s] [?e :advance/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (settlement-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :settlement/seq ?s] [?e :settlement/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (solvency-attestation-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :attestation/seq ?s] [?e :attestation/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (latest-solvency-attestation [s] (last (solvency-attestation-history s)))
  (next-sequence [_ kind jurisdiction]
    (or (d/q '[:find ?n . :in $ ?k
              :where [?e :sequence/key ?k] [?e :sequence/next ?n]]
            (d/db conn) (seq-key kind jurisdiction))
        0))
  (receivable-already-advanced? [s id]
    (contains? #{:advanced :collected :settled} (:status (receivable s id))))
  (receivable-already-settled? [s id]
    (= :settled (:status (receivable s id))))
  (debtor-outstanding-exposure [s debtor-id exclude-id]
    (registry/debtor-exposure (all-receivables s) debtor-id exclude-id))
  (funder-outstanding-exposure [s funder-id exclude-id]
    (registry/funder-exposure (all-receivables s) funder-id exclude-id))
  (book-outstanding-exposure [s exclude-id]
    (registry/book-exposure (all-receivables s) exclude-id))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :receivable/upsert
      (d/transact! conn [(receivable->tx value)])

      :verification/set
      (d/transact! conn [{:verification/receivable-id (first path) :verification/payload (enc payload)}])

      :underwriting/set
      (d/transact! conn [{:underwriting/receivable-id (first path) :underwriting/payload (enc payload)}
                         {:receivable/id (first path) :receivable/status (enc :underwritten)}])

      :advance/mark-funded
      (let [receivable-id (first path)
            {:keys [result receivable-patch]} (advance! s receivable-id)
            jurisdiction (:jurisdiction (receivable s receivable-id))
            next-n (inc (next-sequence s :advance jurisdiction))]
        (d/transact! conn
                     [(receivable->tx (assoc receivable-patch :id receivable-id))
                      {:sequence/key (seq-key :advance jurisdiction) :sequence/next next-n}
                      {:advance/seq (count (advance-history s)) :advance/record (enc (get result "record"))}])
        result)

      :collection/mark-received
      (d/transact! conn [{:receivable/id (first path) :receivable/status (enc :collected)}])

      :settlement/mark-settled
      (let [receivable-id (first path)
            {:keys [result receivable-patch]} (settle! s receivable-id)
            jurisdiction (:jurisdiction (receivable s receivable-id))
            next-n (inc (next-sequence s :settlement jurisdiction))]
        (d/transact! conn
                     [(receivable->tx (assoc receivable-patch :id receivable-id))
                      {:sequence/key (seq-key :settlement jurisdiction) :sequence/next next-n}
                      {:settlement/seq (count (settlement-history s)) :settlement/record (enc (get result "record"))}])
        result)

      :attestation/publish
      (let [jurisdiction (or (:jurisdiction value) "GLOBAL")
            seq-n (next-sequence s :attestation jurisdiction)
            next-n (inc seq-n)
            result (registry/register-attestation payload jurisdiction seq-n)]
        (d/transact! conn
                     [{:sequence/key (seq-key :attestation jurisdiction) :sequence/next next-n}
                      {:attestation/seq (count (solvency-attestation-history s)) :attestation/record (enc (get result "record"))}])
        result)

      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-receivables [s receivables]
    (when (seq receivables) (d/transact! conn (mapv receivable->tx (vals receivables)))) s)
  (register-funder! [_ funder]
    (d/transact! conn [(funder->tx funder)])
    funder)
  (settlement-account [_]
    (dec* (d/q '[:find ?p . :in $ ?k
                :where [?e :settlement-account/singleton ?k] [?e :settlement-account/payload ?p]]
              (d/db conn) settlement-account-singleton-key)))
  (register-settlement-account! [_ account]
    (d/transact! conn [{:settlement-account/singleton settlement-account-singleton-key
                        :settlement-account/payload (enc account)}])
    account))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:receivables .. :funders .. :settlement-account ..}); empty when
  omitted."
  ([] (datomic-store {}))
  ([{:keys [receivables funders settlement-account]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (when (seq funders) (d/transact! (:conn s) (mapv funder->tx (vals funders))))
     (when settlement-account (register-settlement-account! s settlement-account))
     (with-receivables s receivables))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo receivable + funder + settlement-
  account set -- the Datomic-backed analog of `seed-db`, used to prove
  protocol parity."
  []
  (datomic-store (assoc (demo-data) :funders demo-funders :settlement-account demo-settlement-account)))
