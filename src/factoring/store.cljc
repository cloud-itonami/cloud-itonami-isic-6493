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
  (with-receivables [s receivables] "replace/seed the receivable directory (map id->receivable)"))

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

(defn demo-data
  "A small, self-contained receivable set so the actor + tests run
  offline."
  []
  {:receivables
   {"rcv-1" {:id "rcv-1" :client-id "client-1" :client-name "GreenLeaf Logistics K.K."
             :debtor-id "debtor-1" :debtor-name "Meiwa Trading Co." :debtor-credit-limit 5000000
             :debtor-risk-tier :tier-a :fee-rate 0.02
             :face-amount 2000000 :jurisdiction "JPN" :funder-id "funder-1"
             :client-sanctions-hit? false :debtor-sanctions-hit? false :status :submitted}
    "rcv-2" {:id "rcv-2" :client-id "client-2" :client-name "Acme Textiles Ltd"
             :debtor-id "debtor-2" :debtor-name "Camden Wholesale plc" :debtor-credit-limit 1000000
             :debtor-risk-tier :tier-b :fee-rate 0.03
             :face-amount 400000 :jurisdiction "ATL" :funder-id "funder-2"
             :client-sanctions-hit? false :debtor-sanctions-hit? false :status :submitted}
    "rcv-3" {:id "rcv-3" :client-id "client-3" :client-name "Rhein Fabrik GmbH"
             :debtor-id "debtor-3" :debtor-name "Sanctioned Trading Corp" :debtor-credit-limit 1000000
             :debtor-risk-tier :tier-c :fee-rate 0.05
             :face-amount 500000 :jurisdiction "DEU" :funder-id "funder-3"
             :client-sanctions-hit? false :debtor-sanctions-hit? true :status :submitted}
    "rcv-4" {:id "rcv-4" :client-id "client-4" :client-name "Cascade Builders Inc"
             :debtor-id "debtor-1" :debtor-name "Meiwa Trading Co." :debtor-credit-limit 5000000
             :debtor-risk-tier :tier-a :fee-rate 0.02
             :face-amount 3500000 :jurisdiction "USA" :funder-id "funder-1"
             :client-sanctions-hit? false :debtor-sanctions-hit? false :status :submitted}
    "rcv-5" {:id "rcv-5" :client-id "client-5" :client-name "Solent Marine Supplies"
             :debtor-id "debtor-4" :debtor-name "Northgate Retail Group" :debtor-credit-limit 2000000
             :debtor-risk-tier :tier-a :fee-rate 0.99
             :face-amount 300000 :jurisdiction "GBR" :funder-id "funder-2"
             :client-sanctions-hit? false :debtor-sanctions-hit? false :status :submitted}
    "rcv-6" {:id "rcv-6" :client-id "client-6" :client-name "Fujimori Precision Parts K.K."
             :debtor-id "debtor-5" :debtor-name "Cascadia Assembly Group" :debtor-credit-limit 5000000
             :debtor-risk-tier :tier-a :fee-rate 0.02
             :face-amount 3000000 :jurisdiction "USA" :funder-id "funder-1"
             :client-sanctions-hit? false :debtor-sanctions-hit? false :status :submitted}
    "rcv-7" {:id "rcv-7" :client-id "client-7" :client-name "Tidewater Exports LLC"
             :debtor-id "debtor-6" :debtor-name "Baltic Freight Holdings" :debtor-credit-limit 5000000
             :debtor-risk-tier :tier-b :fee-rate 0.03
             :face-amount 200000 :jurisdiction "DEU" :funder-id "funder-3"
             :client-sanctions-hit? false :debtor-sanctions-hit? false :status :submitted}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- advance!
  "Backend-agnostic `:advance/mark-funded` -- looks up the receivable
  via the protocol and drafts the advance record (**actuation 1**),
  returning {:result .. :receivable-patch ..} for the caller to
  persist."
  [s receivable-id]
  (let [r (receivable s receivable-id)
        seq-n (next-sequence s :advance (:jurisdiction r))
        result (registry/register-advance
                receivable-id (:face-amount r) (:jurisdiction r) seq-n)]
    {:result result
     :receivable-patch {:status :advanced
                        :advance-number (get result "advance_number")}}))

(defn- settle!
  "Backend-agnostic `:settlement/mark-settled` -- looks up the
  receivable via the protocol and drafts the settlement record
  (**actuation 2**), returning {:result .. :receivable-patch ..} for
  the caller to persist."
  [s receivable-id]
  (let [r (receivable s receivable-id)
        seq-n (next-sequence s :settlement (:jurisdiction r))
        result (registry/register-settlement
                receivable-id (:face-amount r) (:fee-rate r) (:jurisdiction r) seq-n)]
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
  (with-receivables [s receivables] (when (seq receivables) (swap! a assoc :receivables receivables)) s))

(defn seed-db
  "A MemStore seeded with the demo receivable + funder set. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :funders demo-funders
                           :verifications {} :underwritings {} :ledger [] :sequences {}
                           :advances [] :settlements [] :attestations []))))

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
   :sequence/key                  {:db/unique :db.unique/identity}})

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
    (when (seq receivables) (d/transact! conn (mapv receivable->tx (vals receivables)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:receivables .. :funders ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [receivables funders]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (when (seq funders) (d/transact! (:conn s) (mapv funder->tx (vals funders))))
     (with-receivables s receivables))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo receivable + funder set -- the
  Datomic-backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (assoc (demo-data) :funders demo-funders)))
