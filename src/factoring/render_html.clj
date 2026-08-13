(ns factoring.render-html
  "Build-time operator-console renderer -- runs the REAL factoring actor
  and writes `docs/samples/operator-console.html` from what actually
  happened.

  This is NOT a mock page. Every entity id, status, amount, verdict,
  violation rule and ledger row on the emitted page is read back out of
  a live `factoring.store/MemStore` after a real
  `factoring.operation/build` graph has been driven over it with
  `langgraph.graph/run*` -- the same compiled StateGraph
  `factoring.sim` drives, the same `factoring.governor` censoring every
  proposal, the same `factoring.phase` rollout gate. If a value cannot
  be derived from the run, it is not printed.

  ## Build-time invariant (not a convention)

  `-main` THROWS and writes no file unless the run produced at least
  one HARD governor hold (`min-hard-holds`). A console that shows only
  happy paths would document a governor nobody has seen refuse
  anything. The guard is satisfiable: the scenario book below reaches
  eight distinct HARD refusal rules, and `run-book`'s `:omit` option
  exists so the guard can be shown to FAIL on demand (see
  `docs/samples/README` note in the emitted page's provenance section).

  ## Refusal classification

  Three different things end a scenario without committing, and this
  renderer counts them separately because conflating them
  misrepresents the governor:

    :governor-hard   -- `factoring.governor` refused. Un-overridable.
    :phase-gate      -- the governor was CLEAN; `factoring.phase`
                        declined because the op is not enabled at that
                        rollout phase. Not a compliance refusal.
    :human-rejection -- the governor was CLEAN, the actor escalated,
                        and the human approver said no.

  `classify-fact` keys off the fact TYPE first, deliberately: an
  `:approval-rejected` fact carries `{:rule :approver-rejected}` in its
  OWN `:violations` vector (see `factoring.operation`'s
  `:request-approval` node), so a classifier that keyed off
  `:violations` alone would silently count a human's refusal as a
  governor refusal.

  ## Approver attribution is MEASURED here, never assumed

  Whether a committed record still records WHICH human approved it is
  derived at render time by scanning each register the run actually
  wrote, for approver-shaped keys and for the specific approver id that
  scenario supplied (`approver-attribution`). Nothing about the
  outcome is hard-coded -- if the store is later changed to retain the
  approver on every effect, this page reports that instead, with no
  edit here.

  Note the trap this scan is built to avoid: every ledger fact carries
  `:actor`, which `factoring.operation/commit-fact` fills from
  `(:actor-id context)` -- the EXECUTING actor, not the approver. This
  book therefore gives every human approver an id DISTINCT from the
  executing actor id, so `:actor` can never be mistaken for approval
  attribution by agreeing with it coincidentally.

  ## Determinism

  The page is a pure function of the seed data plus this scenario book.
  Per-run identifiers do not exist here (record ids are sequence-
  derived, e.g. `JPN-ADV-000000`), but the settlement-instruction
  payload built by `factoring.registry/build-settlement-instruction`
  embeds a WALL-CLOCK value date (`registry`'s `today-yymmdd`, a
  `java.time.LocalDate/now` call that lands in the MT103 `:32A` field).
  That payload is therefore deliberately NOT rendered -- only its rail
  and leg are -- and the omission is disclosed on the page rather than
  hidden.

  Build:  clojure -M:render-html            (or -M:dev:render-html)
  Output: docs/samples/operator-console.html"
  (:require [clojure.string :as str]
            [langgraph.graph :as g]
            [css.core :as css]
            [html.core :as h]
            [factoring.facts :as facts]
            [factoring.operation :as op]
            [factoring.phase :as phase]
            [factoring.registry :as registry]
            [factoring.store :as store]))

;; ---------------------------------------------------------------------------
;; Identities
;; ---------------------------------------------------------------------------

(def executing-actor-id
  "The actor that RUNS each operation. Deliberately distinct from every
  approver id below -- see this ns's docstring."
  "op-1")

(def approvers
  "The human approvers this book uses. Distinct ids, and distinct from
  `executing-actor-id`, so that finding one of these strings inside a
  committed register is unambiguous evidence of approver attribution
  (and NOT an accidental match against a ledger fact's `:actor`)."
  {:underwriting "shinsa-yamada"
   :treasury     "kessai-suzuki"
   :finance      "cfo-tanaka"})

(defn- operator [phase] {:actor-id executing-actor-id :actor-role :factor :phase phase})

;; ---------------------------------------------------------------------------
;; The scenario book -- ordered, stateful, and narratively real
;; ---------------------------------------------------------------------------
;;
;; NOTE: no scenario carries any tag describing what its OUTCOME should
;; be. Classification on the page is derived purely from the audit facts
;; the run produces. `:id` exists only so `run-book`'s `:omit` option can
;; cut a subset for the discrimination experiment.

(def scenario-book
  [{:id :intake-1 :phase 3
    :request {:op :receivable/intake :subject "rcv-1" :patch {:id "rcv-1"}}
    :note "Phase 3 の唯一の auto-commit 対象 (資本リスク無し)"}

   {:id :verify-1 :phase 3
    :request {:op :receivable/verify :subject "rcv-1"}
    :resume {:status :approved :by (:underwriting approvers)}
    :note "JPN・制裁該当なし・eKYC pass"}

   {:id :underwrite-1 :phase 3
    :request {:op :receivable/underwrite :subject "rcv-1"}
    :resume {:status :approved :by (:underwriting approvers)}
    :note "債務者/資金提供者いずれの集中度も上限内"}

   {:id :advance-1-attempt-1 :phase 3
    :request {:op :advance/fund :subject "rcv-1"}
    :note "支払能力アテステーション未発行の状態で資金前渡しを試みる"}

   {:id :attest-1 :phase 3
    :request {:op :solvency/attest :subject "book"}
    :resume {:status :approved :by (:finance approvers)}
    :note "台帳からの独立再計算 (anti-Zentoshin)"}

   {:id :advance-1-attempt-2 :phase 3
    :request {:op :advance/fund :subject "rcv-1"}
    :resume {:status :approved :by (:treasury approvers)}
    :note "actuation 1 -- 実際の現金移動"}

   {:id :verify-4 :phase 3
    :request {:op :receivable/verify :subject "rcv-4"}
    :resume {:status :approved :by (:underwriting approvers)}
    :note "rcv-1 と同一債務者 (debtor-1)"}

   {:id :underwrite-4 :phase 3
    :request {:op :receivable/underwrite :subject "rcv-4"}
    :note "debtor-1 の累計与信 + funder-1 の累計供給、双方を試す"}

   {:id :verify-6 :phase 3
    :request {:op :receivable/verify :subject "rcv-6"}
    :resume {:status :approved :by (:underwriting approvers)}
    :note "別債務者だが funder-1 は rcv-1 と同一"}

   {:id :underwrite-6 :phase 3
    :request {:op :receivable/underwrite :subject "rcv-6"}
    :note "資金提供者集中のみを単独で試す"}

   {:id :verify-5 :phase 3
    :request {:op :receivable/verify :subject "rcv-5"}
    :resume {:status :approved :by (:underwriting approvers)}
    :note "GBR"}

   {:id :underwrite-5 :phase 3
    :request {:op :receivable/underwrite :subject "rcv-5"}
    :resume {:status :approved :by (:underwriting approvers)}
    :note "集中度は上限内"}

   {:id :advance-5 :phase 3
    :request {:op :advance/fund :subject "rcv-5"}
    :note "適用 fee-rate が公開スケジュールと一致するかを試す"}

   {:id :verify-3 :phase 3
    :request {:op :receivable/verify :subject "rcv-3"}
    :note "債務者が制裁スクリーニング該当"}

   {:id :verify-2 :phase 3
    :request {:op :receivable/verify :subject "rcv-2" :no-spec? true}
    :note "factoring.facts に未登録の法域を提示させる"}

   {:id :verify-7 :phase 3
    :request {:op :receivable/verify :subject "rcv-7"}
    :resume {:status :approved :by (:underwriting approvers)}
    :note "DEU -- 必要書類を充足させてから前渡しに進む"}

   {:id :underwrite-7 :phase 3
    :request {:op :receivable/underwrite :subject "rcv-7"}
    :resume {:status :approved :by (:underwriting approvers)}
    :note "funder-3 は未使用 -- 集中度に余裕"}

   {:id :advance-7-rejected :phase 3
    :request {:op :advance/fund :subject "rcv-7"}
    :resume {:status :rejected :by (:treasury approvers)}
    :note "ガバナー clean・エスカレーション後、人間の承認者が現金移動を拒否"}

   {:id :collect-1 :phase 3
    :request {:op :receivable/collect :subject "rcv-1"}
    :resume {:status :approved :by (:underwriting approvers)}
    :note "債務者からの入金 (factor からの出金ではない)"}

   {:id :settle-1 :phase 3
    :request {:op :reserve/settle :subject "rcv-1"}
    :resume {:status :approved :by (:treasury approvers)}
    :note "actuation 2 -- 留保金の解放"}

   {:id :advance-1-again :phase 3
    :request {:op :advance/fund :subject "rcv-1"}
    :note "完了済み債権への二重前渡しを試みる"}

   {:id :settle-1-again :phase 3
    :request {:op :reserve/settle :subject "rcv-1"}
    :note "二重精算を試みる"}

   {:id :verify-5-phase-1 :phase 1
    :request {:op :receivable/verify :subject "rcv-5"}
    :note "ガバナーは clean。phase 1 が :receivable/verify を書き込み許可していない"}

   {:id :report-phase-0 :phase 0
    :request {:op :audit/report :subject "book"}
    :note "read op は phase 0 でも通過する (phase は自律性のみを制限)"}])

(def governor-refusal-probe-ids
  "The scenarios whose purpose is to reach a HARD governor refusal.
  Used ONLY by the discrimination experiment (`run-book`'s `:omit`), so
  that cutting exactly these can be shown to drive the HARD-hold count
  to zero while leaving the phase-gate and human-rejection counts
  standing. Never consulted when classifying a fact."
  #{:advance-1-attempt-1 :underwrite-4 :underwrite-6 :advance-5
    :verify-3 :verify-2 :advance-1-again :settle-1-again})

;; ---------------------------------------------------------------------------
;; Running the real actor
;; ---------------------------------------------------------------------------

(def ^:private ssot-registers
  "Store registers that hold committed state. `:ledger` is the audit log
  (not SSoT) and `:sequences` are counters; both are excluded from the
  approver-attribution delta scan."
  [:receivables :funders :verifications :underwritings
   :advances :settlements :attestations])

(defn- register-delta
  "What `after` has that `before` did not, per SSoT register. Vectors are
  compared by appended tail, maps by changed entries."
  [before after]
  (into {}
        (keep (fn [k]
                (let [b (get before k) a (get after k)]
                  (cond
                    (and (vector? a) (vector? b) (> (count a) (count b)))
                    [k (vec (subvec a (count b)))]

                    (and (map? a) (map? b) (not= a b))
                    (let [changed (into {} (remove (fn [[ik iv]] (= iv (get b ik)))) a)]
                      (when (seq changed) [k changed]))

                    :else nil)))
              ssot-registers)))

(defn run-book
  "Drive the REAL compiled actor over `scenario-book` against a freshly
  seeded store. Returns {:store .. :scenarios [..]}.

  opts:
    :omit -- set of scenario `:id`s to skip (discrimination experiment)."
  ([] (run-book {}))
  ([{:keys [omit] :or {omit #{}}}]
   (let [db (store/seed-db)
         actor (op/build db)
         book (remove (comp omit :id) scenario-book)]
     {:store db
      :scenarios
      (vec
       (map-indexed
        (fn [i {:keys [id phase request resume note]}]
          (let [tid (str "t" (inc i) "-" (name id))
                before (store/snapshot db)
                ledger-before (count (store/ledger db))
                st (g/run* actor
                           {:request request :context (operator phase)}
                           {:thread-id tid})
                st (if resume
                     (g/run* actor {:approval resume}
                             {:thread-id tid :resume? true})
                     st)
                after (store/snapshot db)]
            {:id id
             :phase phase
             :request request
             :note note
             :approver (when resume (:by resume))
             :approval-status (when resume (:status resume))
             :disposition (:disposition st)
             :verdict (:verdict st)
             :facts (vec (drop ledger-before (store/ledger db)))
             :delta (register-delta before after)}))
        book))})))

;; ---------------------------------------------------------------------------
;; Classification -- fact TYPE first (see ns docstring)
;; ---------------------------------------------------------------------------

(defn classify-fact
  [{:keys [t violations phase-reason]}]
  (cond
    (= t :approval-rejected)                     :human-rejection
    (and (= t :governor-hold) phase-reason)      :phase-gate
    (and (= t :governor-hold) (seq violations))  :governor-hard
    (= t :governor-hold)                         :governor-hold-unclassified
    (= t :committed)                             :commit
    :else                                        :other))

(def class-label
  {:governor-hard              "ガバナー HARD 拒否"
   :phase-gate                 "phase ゲート (ガバナーは clean)"
   :human-rejection            "人間の承認者による拒否"
   :governor-hold-unclassified "分類不能な hold"
   :commit                     "コミット"
   :other                      "その他"})

(defn- all-facts [run] (mapcat :facts (:scenarios run)))

(defn- facts-of-class [run k] (filter #(= k (classify-fact %)) (all-facts run)))

(defn hard-holds [run] (vec (facts-of-class run :governor-hard)))

;; ---------------------------------------------------------------------------
;; Approver attribution -- measured, never assumed
;; ---------------------------------------------------------------------------

(defn- approver-shaped-key?
  "A key that claims to record WHO approved. Deliberately does NOT match
  `:actor` -- that is the executing actor, a different fact."
  [k]
  (and (or (keyword? k) (string? k) (symbol? k))
       (boolean (re-find #"(?i)approv" (name k)))))

(defn- deep-keys-matching [pred x]
  (cond
    (map? x)  (into (into #{} (filter pred) (keys x))
                    (mapcat #(deep-keys-matching pred %) (vals x)))
    (coll? x) (into #{} (mapcat #(deep-keys-matching pred %) x))
    :else     #{}))

(defn- deep-contains-string? [x s]
  (cond
    (string? x) (= x s)
    (map? x)    (boolean (some #(deep-contains-string? % s) (concat (keys x) (vals x))))
    (coll? x)   (boolean (some #(deep-contains-string? % s) x))
    :else       false))

(defn approver-attribution
  "For every scenario a human actually APPROVED, report -- per register
  the commit actually wrote -- whether the approver survived into the
  committed state. Two independent signals, both derived:

    :approver-keys -- approver-shaped keys present in the written entry
    :recoverable?  -- the specific approver id supplied is findable

  A register that reports neither has lost the human authorization."
  [run]
  (vec
   (for [{:keys [id request approver approval-status delta]} (:scenarios run)
         :when (and approver (= :approved approval-status))
         [register entries] delta]
     {:scenario id
      :op (:op request)
      :subject (:subject request)
      :register register
      :approver approver
      :approver-keys (sort (map str (deep-keys-matching approver-shaped-key? entries)))
      :recoverable? (deep-contains-string? entries approver)})))

(defn ledger-actor-reading
  "Derive -- rather than assert -- what a ledger fact's `:actor` field
  actually holds, so the page can warn against reading it as approval
  attribution."
  [run]
  (let [commits (filter #(= :committed (:t %)) (all-facts run))
        actors (into (sorted-set) (map :actor) commits)
        approver-ids (set (vals approvers))]
    {:commit-facts (count commits)
     :distinct-actors (vec actors)
     :any-actor-is-an-approver? (boolean (some approver-ids actors))
     :executing-actor executing-actor-id}))

;; ---------------------------------------------------------------------------
;; Formatting helpers
;; ---------------------------------------------------------------------------

(defn- fmt-int [n]
  (let [s (str (abs (long n)))
        grouped (->> (reverse s)
                     (partition-all 3)
                     (map (comp str/join reverse))
                     reverse
                     (str/join ","))]
    (str (when (neg? n) "-") grouped)))

(defn- fmt-num [n]
  (cond
    (nil? n) "—"
    (and (number? n) (zero? (- (double n) (Math/floor (double n))))) (fmt-int n)
    (number? n) (str n)
    :else (str n)))

(defn- kw->s [x]
  (cond (nil? x) "—"
        (keyword? x) (subs (str x) 1)
        :else (str x)))

(defn- pct [x] (str (Math/round (* 100.0 (double x))) "%"))

;; ---------------------------------------------------------------------------
;; Page sections -- each returns {:id :title :rows n :node hiccup}
;; ---------------------------------------------------------------------------

(defn- table [headers rows]
  [:table
   [:thead [:tr (for [th headers] [:th th])]]
   [:tbody
    (for [r rows]
      [:tr (for [c r]
             (if (and (vector? c) (= :cell (first c)))
               (let [[_ attrs content] c] [:td attrs content])
               [:td c]))])]])

(defn- badge [class text] [:span {:class class} text])

(defn- disposition-badge [d]
  (case d
    :commit   (badge "ok" "commit")
    :hold     (badge "err" "hold")
    :escalate (badge "warn" "escalate")
    (badge "muted" (kw->s d))))

(defn- section-run-summary [run]
  (let [scenarios (:scenarios run)
        counts (frequencies (map classify-fact (all-facts run)))
        rows [["実行シナリオ" (str (count scenarios))]
              ["監査ファクト総数" (str (count (all-facts run)))]
              ["コミット" (str (get counts :commit 0))]
              ["ガバナー HARD 拒否" (str (get counts :governor-hard 0))]
              ["phase ゲート拒否" (str (get counts :phase-gate 0))]
              ["人間の承認者による拒否" (str (get counts :human-rejection 0))]
              ["分類不能な hold" (str (get counts :governor-hold-unclassified 0))]]]
    {:id "summary" :title "実行サマリー" :rows (count rows)
     :node [:div.card
            [:h2 "実行サマリー"]
            [:p.muted
             "この表の全数値は、シードデータに対して "
             [:code "factoring.operation/build"]
             " のコンパイル済み StateGraph を "
             [:code "langgraph.graph/run*"]
             " で実際に駆動した結果の監査ファクトを数えたもの。"]
            (table ["指標" "値"] rows)]}))

(defn- section-scenarios [run]
  (let [rows (for [{:keys [id phase request disposition verdict approver
                           approval-status facts note]} (:scenarios run)]
               [(kw->s id)
                (str phase)
                (kw->s (:op request))
                (or (:subject request) "—")
                (disposition-badge disposition)
                (if approver
                  [:cell {} [:<> (kw->s approval-status) " / " [:code approver]]]
                  "—")
                (str (format "%.2f" (double (:confidence verdict 0.0))))
                (let [cs (distinct (map classify-fact facts))]
                  (str/join ", " (map class-label cs)))
                note])]
    {:id "scenarios" :title "シナリオ" :rows (count rows)
     :node [:div.card
            [:h2 "シナリオ (実行順・状態は前のシナリオを引き継ぐ)"]
            (table ["id" "phase" "op" "対象" "結果" "承認" "confidence" "分類" "備考"] rows)]}))

(defn- section-hard-holds [run]
  (let [holds (hard-holds run)
        rows (for [f holds
                   v (:violations f)]
               [(kw->s (:op f))
                (or (:subject f) "—")
                [:cell {} (badge "critical" (kw->s (:rule v)))]
                (:detail v)])]
    {:id "hard-holds" :title "ガバナー HARD 拒否" :rows (count rows)
     :node [:div.card
            [:h2 (str "ガバナー HARD 拒否 — " (count holds) " 件 / 拒否理由 "
                      (count rows) " 件")]
            [:p.muted
             "HARD 違反は人間の承認者が上書きできない。以下は "
             [:code "factoring.governor/check"]
             " が実際に返した違反そのもの (ledger から読み出したもので、再構成ではない)。"]
            (table ["op" "対象" "rule" "何を拒否したか"] rows)]}))

(defn- section-classifier [run]
  (let [facts (all-facts run)
        holds (remove #(= :commit (classify-fact %)) facts)
        rows (for [f holds]
               [(kw->s (:t f))
                (kw->s (:op f))
                (or (:subject f) "—")
                (if (:phase-reason f) (kw->s (:phase-reason f)) "—")
                (str (count (:violations f)))
                [:cell {} (badge (case (classify-fact f)
                                   :governor-hard "critical"
                                   :phase-gate "warn"
                                   :human-rejection "err"
                                   "muted")
                                 (class-label (classify-fact f)))]])]
    {:id "classifier" :title "拒否の分類" :rows (count rows)
     :node
     [:div.card
      [:h2 "拒否の分類 — ガバナー拒否と phase ゲートと人間の拒否は別物"]
      [:p
       "分類はファクトの "
       [:code ":t"]
       " を最初に見る。"
       [:code ":violations"]
       " だけを見る分類器は誤る: "
       [:code ":approval-rejected"]
       " ファクト自身が "
       [:code "{:rule :approver-rejected}"]
       " を "
       [:code ":violations"]
       " に持つため (" [:code "factoring.operation"] " の "
       [:code ":request-approval"] " ノード)、人間の拒否をガバナー拒否として数えてしまう。"]
      [:p.muted
       "また " [:code ":phase-reason"] " を持つ hold はガバナーが clean だったことを意味する "
       "(" [:code "factoring.phase/gate"]
       " はガバナーが hold のとき reason を nil にするため、両者は構造上排他)。"]
      (table ["fact :t" "op" "対象" ":phase-reason" ":violations 件数" "分類"] rows)]}))

(defn- section-approver [run]
  (let [attrib (approver-attribution run)
        reading (ledger-actor-reading run)
        lost (remove :recoverable? attrib)
        rows (for [{:keys [op subject register approver approver-keys recoverable?]} attrib]
               [(kw->s op)
                (or subject "—")
                (kw->s register)
                [:cell {} [:code approver]]
                (if (seq approver-keys) (str/join ", " approver-keys) "—")
                [:cell {} (if recoverable?
                            (badge "ok" "保持")
                            (badge "critical" "喪失"))]])]
    {:id "approver" :title "承認者の帰属" :rows (count rows)
     :node
     [:div.card
      [:h2 "承認者の帰属 — レンダリング時に実測"]
      [:p
       "各行は、そのシナリオが実際に書き込んだレジスタを走査した結果。"
       "「保持」= そのシナリオが渡した承認者 id が、コミット後の状態から実際に取り出せる。"
       "「喪失」= 取り出せない。"
       [:strong "この判定はハードコードされていない"]
       " — store が承認者を保持するよう修正されれば、このページは編集なしで「保持」を報告する。"]
      (table ["op" "対象" "レジスタ" "承認者 id" "承認者形状のキー" "判定"] rows)
      (when (seq lost)
        [:<>
         [:h2 {:style {:margin-top 20}} "開示: 現金移動経路で承認者の帰属が失われている"]
         [:p
          [:span {:class "critical"} "未修正"]
          " 上表のうち "
          [:strong (str (count lost) " 件")]
          " のレジスタ書き込みで、承認した人間の id がコミット後の状態から復元できない。"
          "内訳: "
          (str/join " / " (distinct (map #(str (kw->s (:op %)) " → " (kw->s (:register %))) lost)))
          "。"]
         [:p
          "原因は " [:code "factoring.operation/commit-record"] " が承認者を "
          [:code ":payload"] " にのみ載せる (" [:code ":value"] " には載せない) 一方、"
          [:code "factoring.store"] " の " [:code "commit-record!"]
          " が effect ごとに異なる経路を通ることにある: "
          [:code ":verification/set"] " と " [:code ":underwriting/set"] " は "
          [:code ":payload"] " をそのまま格納するため承認者が残るが、"
          [:code ":advance/mark-funded"] " と " [:code ":settlement/mark-settled"]
          " は payload を使わず " [:code "advance!"] "/" [:code "settle!"]
          " が組み立てたレコードを追記するため、承認者はどこにも書かれない。"]
         [:p
          [:strong "これは書類経路ではなく現金経路で起きている。"]
          " この actor が実世界で行う金銭移動は 2 つ (" [:code ":advance/fund"]
          " = クライアントへの現金前渡し、" [:code ":reserve/settle"]
          " = 留保金の解放) で、"
          [:strong "その 2 つがちょうど、どの人間が承認したかを SSoT から復元できない操作である"]
          "。両者は " [:code "factoring.phase"] " のどの phase でも auto-commit されず、"
          "必ず人間の承認を要求する設計であるにもかかわらず、その承認の記録が残らない。"]
         [:p
          "本タスクはレンダラの追加であり、この欠陥は"
          [:strong "修正せず開示する"]
          " (store の commit 経路の変更は本タスクの範囲外)。"]])
      [:h2 {:style {:margin-top 20}} "警告: ledger の :actor は承認者ではない"]
      [:p
       "監査 ledger の " [:code ":committed"] " ファクト " (str (:commit-facts reading))
       " 件が持つ " [:code ":actor"] " の値は "
       (str/join ", " (map #(str "`" % "`") (:distinct-actors reading)))
       " のみで、これは " [:code "factoring.operation/commit-fact"] " が "
       [:code "(:actor-id context)"] " から埋める"
       [:strong "実行アクター"]
       "であって承認者ではない。"
       "本ページはすべての承認者 id を実行アクター id (" [:code executing-actor-id]
       ") と異なる値にしているため、この 2 つが偶然一致して "
       [:code ":actor"] " を承認記録と誤読する余地が無い — 実測: 承認者 id が "
       [:code ":actor"] " に現れたか = "
       [:strong (if (:any-actor-is-an-approver? reading) "はい" "いいえ")] "。"]]}))

(defn- section-receivables [run]
  (let [db (:store run)
        rs (store/all-receivables db)
        rows (for [r rs]
               [(:id r)
                (:client-name r)
                (:debtor-name r)
                (:jurisdiction r)
                [:cell {:class "amt"} (fmt-num (:face-amount r))]
                (:currency r)
                (kw->s (:debtor-risk-tier r))
                (str (:fee-rate r))
                (:funder-id r)
                [:cell {} (badge (case (:status r)
                                   :settled "ok"
                                   :advanced "warn"
                                   :collected "warn"
                                   :underwritten "muted"
                                   "muted")
                                 (kw->s (:status r)))]
                (or (:advance-number r) "—")
                (or (:settlement-number r) "—")])]
    {:id "receivables" :title "債権レジスタ" :rows (count rows)
     :node [:div.card
            [:h2 "債権レジスタ (実行後の状態)"]
            (table ["id" "クライアント" "account debtor" "法域" "額面" "通貨"
                    "tier" "fee-rate" "funder" "status" "advance#" "settlement#"]
                   rows)]}))

(defn- section-funders [run]
  (let [db (:store run)
        rs (store/all-receivables db)
        rows (for [f (store/all-funders db)
                   :let [exposure (store/funder-outstanding-exposure db (:id f) nil)]]
               [(:id f)
                (:name f)
                [:cell {:class "amt"} (fmt-num (:funding-capacity f))]
                [:cell {:class "amt"} (fmt-num exposure)]
                [:cell {:class "amt"} (fmt-num (* exposure registry/advance-rate))]
                (str (count (filter #(= (:id f) (:funder-id %)) rs)))])]
    {:id "funders" :title "資金提供者" :rows (count rows)
     :node [:div.card
            [:h2 "資金提供者レジスタ"]
            [:p.muted
             "現金化係数 " (str registry/advance-rate)
             " (" [:code "factoring.registry/advance-rate"] ") を額面エクスポージャに適用したものが"
             "資金供給残高。集中度チェックはこの値を funding-capacity と比較する。"]
            (table ["id" "名称" "funding-capacity" "額面エクスポージャ" "現金換算" "紐づく債権数"] rows)]}))

(defn- section-verifications [run]
  (let [db (:store run)
        rows (for [r (store/all-receivables db)
                   :let [v (store/verification-of db (:id r))]
                   :when v]
               [(:id r)
                (:jurisdiction v)
                (str (count (:checklist v)))
                (or (:spec-basis v) "—")
                (str (kw->s (get-in v [:ekyc :client :ekyc.result/disposition])) " / "
                     (kw->s (get-in v [:ekyc :debtor :ekyc.result/disposition])))
                (if (:approved-by v) [:cell {} [:code (:approved-by v)]] "—")])]
    {:id "verifications" :title "検証レジスタ" :rows (count rows)
     :node [:div.card
            [:h2 "検証レジスタ (spec-basis + eKYC)"]
            (table ["債権" "法域" "必要書類件数" "spec-basis 出典" "eKYC client/debtor" "承認者"] rows)]}))

(defn- section-underwritings [run]
  (let [db (:store run)
        rows (for [r (store/all-receivables db)
                   :let [u (store/underwriting-of db (:id r))]
                   :when u]
               [(:receivable-id u)
                [:cell {:class "amt"} (fmt-num (:debtor-exposure u))]
                [:cell {:class "amt"} (fmt-num (:debtor-credit-limit u))]
                [:cell {} (badge (if (= :within-limits (:verdict u)) "ok" "err")
                                 (kw->s (:verdict u)))]
                (if (:approved-by u) [:cell {} [:code (:approved-by u)]] "—")])]
    {:id "underwritings" :title "引受レジスタ" :rows (count rows)
     :node [:div.card
            [:h2 "引受レジスタ"]
            (table ["債権" "債務者エクスポージャ" "与信上限" "判定" "承認者"] rows)]}))

(defn- si-summary
  "Rail/leg only. The settlement-instruction PAYLOAD embeds a wall-clock
  value date (`factoring.registry`'s `today-yymmdd`), so it is
  deliberately excluded to keep this page deterministic."
  [record]
  (if-let [si (get record "settlement_instruction")]
    (str (get si "rail") " / " (get si "leg"))
    "—"))

(defn- section-advances [run]
  (let [rows (for [a (store/advance-history (:store run))]
               [(get a "record_id")
                (get a "receivable_id")
                (get a "jurisdiction")
                [:cell {:class "amt"} (fmt-num (get a "face_amount"))]
                [:cell {:class "amt"} (fmt-num (get a "advance_amount"))]
                [:cell {:class "amt"} (fmt-num (get a "reserve_amount"))]
                (si-summary a)
                [:cell {} (badge "critical" "承認者記録なし")]])]
    {:id "advances" :title "前渡し (actuation 1)" :rows (count rows)
     :node [:div.card
            [:h2 "前渡しレコード — actuation 1 (現金がクライアントへ出る)"]
            (table ["record_id" "債権" "法域" "額面" "前渡額" "留保額"
                    "決済指図" "承認者の帰属"] rows)]}))

(defn- section-settlements [run]
  (let [rows (for [s (store/settlement-history (:store run))]
               [(get s "record_id")
                (get s "receivable_id")
                (get s "jurisdiction")
                [:cell {:class "amt"} (fmt-num (get s "face_amount"))]
                [:cell {:class "amt"} (fmt-num (get s "fee_amount"))]
                [:cell {:class "amt"} (fmt-num (get s "settlement_amount"))]
                (si-summary s)
                [:cell {} (badge "critical" "承認者記録なし")]])]
    {:id "settlements" :title "留保金精算 (actuation 2)" :rows (count rows)
     :node [:div.card
            [:h2 "留保金精算レコード — actuation 2 (留保金がクライアントへ出る)"]
            (table ["record_id" "債権" "法域" "額面" "手数料" "精算額"
                    "決済指図" "承認者の帰属"] rows)]}))

(defn- section-attestations [run]
  (let [rows (for [a (store/solvency-attestation-history (:store run))]
               [(get a "record_id")
                [:cell {:class "amt"} (fmt-num (get a "outstanding_obligations"))]
                [:cell {:class "amt"} (fmt-num (get a "funding_capacity"))]
                (pct (get a "coverage_ratio"))
                (str (get a "advanced_count"))
                [:cell {} (badge (if (get a "solvent") "ok" "critical")
                                 (str (get a "solvent")))]])]
    {:id "attestations" :title "支払能力アテステーション" :rows (count rows)
     :node [:div.card
            [:h2 "支払能力アテステーション (公開クエリ可能)"]
            [:p.muted
             "アテステーションは " (str registry/stale-after-n-advances)
             " 件の前渡しが追加された時点で stale になる ("
             [:code "factoring.registry/stale-after-n-advances"]
             ")。stale/未発行の状態では前渡しは HARD 拒否される。"]
            (table ["record_id" "債務残高" "資金供給能力" "カバレッジ" "前渡し件数" "solvent"] rows)]}))

(defn- section-ledger [run]
  (let [rows (map-indexed
              (fn [i f]
                [(str (inc i))
                 (kw->s (:t f))
                 (kw->s (:op f))
                 (or (:subject f) "—")
                 (:actor f)
                 [:cell {} (disposition-badge (:disposition f))]
                 [:cell {} (badge (case (classify-fact f)
                                    :governor-hard "critical"
                                    :phase-gate "warn"
                                    :human-rejection "err"
                                    :commit "ok"
                                    "muted")
                                  (class-label (classify-fact f)))]
                 (if (seq (:basis f))
                   (str/join ", " (map #(let [s (kw->s %)]
                                          (if (> (count s) 60) (str (subs s 0 60) "…") s))
                                       (:basis f)))
                   "—")])
              (store/ledger (:store run)))]
    {:id "ledger" :title "監査台帳" :rows (count rows)
     :node [:div.card
            [:h2 "監査台帳 (append-only)"]
            [:p.muted
             [:code ":actor"] " 列は実行アクターであり承認者ではない (上の「承認者の帰属」節を参照)。"]
            (table ["#" "fact" "op" "対象" ":actor" "disposition" "分類" "basis"] rows)]}))

(defn- section-jurisdictions [_run]
  (let [cov (facts/coverage)
        rows (for [[iso3 m] (sort-by key facts/catalog)]
               [iso3
                (:name m)
                (:owner-authority m)
                (str (count (:required-evidence m)))
                [:cell {} [:code (:provenance m)]]])]
    {:id "jurisdictions" :title "法域カタログ" :rows (count rows)
     :node [:div.card
            [:h2 "法域カタログ (spec-basis の正本)"]
            [:p.muted
             "カバレッジは正直に報告される: " (str (:covered cov)) " / "
             (str (:requested cov))
             " 法域。未登録の法域には spec-basis が存在せず、助言者がそれを創作すれば "
             "ガバナーが HARD 拒否する (本ページの " [:code "verify-2"] " シナリオがその実測)。"]
            (table ["ISO3" "名称" "所管/根拠の担い手" "必要書類件数" "出典"] rows)]}))

(defn- section-phases [_run]
  (let [rows (for [[p m] (sort-by key phase/phases)]
               [(str p)
                (:label m)
                (if (seq (:writes m))
                  (str/join ", " (sort (map kw->s (:writes m))))
                  "—")
                (if (seq (:auto m))
                  (str/join ", " (sort (map kw->s (:auto m))))
                  "—")])]
    {:id "phases" :title "rollout phase" :rows (count rows)
     :node [:div.card
            [:h2 "rollout phase"]
            [:p.muted
             [:code ":advance/fund"] " と " [:code ":reserve/settle"]
             " はどの phase の auto 集合にも存在しない — これはロードマップ上の未達ではなく恒久的な構造。"]
            (table ["phase" "label" "書き込み可能な op" "auto-commit 可能な op"] rows)]}))

(defn- section-provenance [run]
  (let [rows [["actor グラフ" "factoring.operation/build — langgraph-clj StateGraph (interrupt-before #{:request-approval})"]
              ["実行" "langgraph.graph/run* — シナリオごとに thread-id を分離、承認は resume? true で再開"]
              ["ガバナー" "factoring.governor/check — 12 のチェック関数"]
              ["store" "factoring.store/MemStore (seed-db)"]
              ["シードデータ" (str "factoring.store/demo-data — 債権 "
                                  (str (count (store/all-receivables (:store run))))
                                  " 件 / demo-funders — 資金提供者 "
                                  (str (count (store/all-funders (:store run)))) " 件")]
              ["助言者" "factoring.factoringllm/mock-advisor (決定論的)"]
              ["意図的に非表示" "決済指図の payload/wire — factoring.registry の today-yymmdd (java.time.LocalDate/now) が MT103 :32A に実時刻を埋めるため、決定性のため rail/leg のみ表示"]
              ["未検証" "DatomicStore 経路・worker デプロイ・実 LLM 助言者はこのページの実行では駆動していない"]]]
    {:id "provenance" :title "由来と限界" :rows (count rows)
     :node [:div.card
            [:h2 "由来と限界"]
            [:p
             "このページはビルド時に生成される。"
             [:strong "モックの HTML は含まれない"]
             " — 表示されている id・状態・金額・判定・違反はすべて上記の実行から読み出されたもの。"
             "導出できない値は表示しない。"]
            (table ["項目" "内容"] rows)]}))

;; ---------------------------------------------------------------------------
;; Page
;; ---------------------------------------------------------------------------

(def ^:private extra-css
  {"header.bar .sub"   {:font-size 12 :color "#666" :margin-left 8}
   ".idx"              {:display :flex :flex-wrap :wrap :gap 8 :margin-bottom 16}
   ".idx a"            {:font-size 12 :color "#264af4" :text-decoration :none
                        :border "1px solid #c5d7fb" :border-radius 999
                        :padding "3px 10px" :background "#fff"}
   ".card h2"          {:border-bottom "1px solid #f0f0f0" :padding-bottom 8}
   "code"              {:font-family "ui-monospace,SFMono-Regular,Menlo,monospace"
                        :font-size 12 :background "#f4f6f8" :padding "1px 4px"
                        :border-radius 3}
   "p"                 {:font-size 13 :line-height 1.7}
   "td"                {:vertical-align :top}
   ".lead"             {:font-size 13 :color "#444" :line-height 1.8}})

(defn page [run]
  (let [sections [(section-run-summary run)
                  (section-scenarios run)
                  (section-hard-holds run)
                  (section-classifier run)
                  (section-approver run)
                  (section-receivables run)
                  (section-funders run)
                  (section-verifications run)
                  (section-underwritings run)
                  (section-advances run)
                  (section-settlements run)
                  (section-attestations run)
                  (section-ledger run)
                  (section-jurisdictions run)
                  (section-phases run)
                  (section-provenance run)]]
    {:sections sections
     :html
     (h/html5
      [:html {:lang "ja"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:meta {:name "color-scheme" :content "light"}]
        [:title "オペレーターコンソール | cloud-itonami-isic-6493 (Factoring)"]
        [:meta {:name "description"
                :content "cloud-itonami-isic-6493 factoring actor -- 実行時に生成されたオペレーターコンソール"}]
        (css/style-node (css/merge-theme extra-css))]
       [:body
        [:header.bar
         [:h1 "Factoring オペレーターコンソール"]
         [:span.sub "cloud-itonami-isic-6493 · ISIC 6493 · :finance/factoring"]
         [:span.badge "ビルド時生成 — 実行結果"]]
        [:main
         [:div.card
          [:p.lead
           "このコンソールは "
           [:code "factoring.operation"]
           " の実際の actor 実行から生成されている。"
           (str (count (:scenarios run)))
           " 件のシナリオを 1 つのシード済み台帳に対して順に流し、"
           "助言者の提案 → ガバナーによる検閲 → rollout phase ゲート → "
           "コミット / hold / 人間承認、という経路をそのまま通したうえで、"
           "残った台帳とレジスタを読み出している。"]
          [:div.idx
           (for [{:keys [id title rows]} sections]
             [:a {:href (str "#" id)} (str title " (" rows ")")])]]
         (for [{:keys [id node]} sections]
           [:section {:id id} node])]]])}))

;; ---------------------------------------------------------------------------
;; Build-time invariant + entry point
;; ---------------------------------------------------------------------------

(def min-hard-holds
  "A console that never shows the governor refusing anything documents a
  governor nobody has watched work. Build fails below this."
  1)

(defn build!
  "Run the actor, enforce the HARD-hold invariant, render, and write.
  Throws (writing NOTHING) if the run produced fewer than
  `min-hard-holds` HARD governor holds."
  [out-dir]
  (let [run (run-book)
        holds (hard-holds run)]
    (when (< (count holds) min-hard-holds)
      (throw (ex-info
              (str "refusing to write the operator console: the run produced "
                   (count holds) " HARD governor hold(s), need at least "
                   min-hard-holds
                   ". A console with no observed refusal is not evidence of a governor.")
              {:hard-holds (count holds)
               :required min-hard-holds
               :classes (frequencies (map classify-fact (all-facts run)))})))
    (let [{:keys [sections html]} (page run)
          dir (java.io.File. ^String out-dir)
          f (java.io.File. dir "operator-console.html")]
      (.mkdirs dir)
      (spit f html)
      {:file (.getPath f)
       :bytes (count (.getBytes ^String html "UTF-8"))
       :sections (count sections)
       :rows (reduce + (map :rows sections))
       :hard-holds (count holds)
       :classes (frequencies (map classify-fact (all-facts run)))})))

(defn -main [& [out-dir]]
  (let [r (build! (or out-dir "docs/samples"))]
    (println "wrote" (:file r))
    (println "  bytes:      " (:bytes r))
    (println "  sections:   " (:sections r))
    (println "  rows:       " (:rows r))
    (println "  HARD holds: " (:hard-holds r))
    (println "  classes:    " (pr-str (:classes r)))))
