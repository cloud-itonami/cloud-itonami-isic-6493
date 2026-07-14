(ns factoring.factoringllm
  "Factoring-LLM client -- the *contained intelligence node* for the
  factoring actor.

  It normalizes receivable intake, drafts a per-jurisdiction invoice-
  authenticity/duplicate-pledge/KYC verification checklist, drafts the
  account-debtor concentration underwriting verdict, drafts the cash-
  ADVANCE action, drafts the collection-receipt confirmation, drafts
  the reserve-SETTLEMENT action, and drafts the solvency ATTESTATION
  (a ground-truth recompute it must get right, or `factoring.
  governor/solvency-attestation-mismatch-violations` holds it -- see
  that check's own docstring for why, the direct anti-Zentoshin
  mitigation). CRITICAL: it is a smart-but-untrusted advisor. It
  returns a *proposal* (with a rationale + the fields it cited), never
  a committed record or a real cash movement. Every output is censored
  downstream by `factoring.governor` before anything touches the SSoT,
  and `:advance/fund`/`:reserve/settle` proposals NEVER auto-commit at
  any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/advance-cash | :actuation/release-reserve | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [factoring.facts :as facts]
            [factoring.registry :as registry]
            [factoring.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the client, debtor, face amount, credit limit, risk
  tier, funder or jurisdiction. High confidence, low stakes."
  [_st {:keys [patch]}]
  {:summary    (str "ファクタリング申込レコード更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :receivable/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- propose-verification
  "Per-jurisdiction invoice-authenticity/duplicate-pledge/KYC checklist
  draft, PLUS the client/account-debtor sanctions-screening ground
  truth (already on the receivable -- this advisor only surfaces it,
  it does not run the actual screen). `:no-spec?` injects the failure
  mode we must defend against: proposing a checklist for a jurisdiction
  with NO official spec-basis in `factoring.facts` -- the Factoring
  Governor must reject this (never invent a jurisdiction's law)."
  [st {:keys [subject no-spec?]}]
  (let [r (store/receivable st subject)
        iso3 (if no-spec? "ATL" (:jurisdiction r))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "factoring.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :verification/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :verification/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)
                    :client-sanctions-hit? (:client-sanctions-hit? r)
                    :debtor-sanctions-hit? (:debtor-sanctions-hit? r)}
       :stake      nil
       :confidence (if (or (:client-sanctions-hit? r) (:debtor-sanctions-hit? r)) 0.3 0.9)})))

(defn- propose-underwriting
  "Account-debtor concentration underwriting draft -- computes the
  debtor's aggregate exposure via `registry/debtor-exposure` and the
  funder's aggregate exposure via `registry/funder-exposure`. Injects
  the failure mode: the Factoring Governor must HOLD, un-overridably,
  on any receivable whose account debtor or funding source would be
  overconcentrated."
  [st {:keys [subject]}]
  (let [r (store/receivable st subject)
        debtor-existing (store/debtor-outstanding-exposure st (:debtor-id r) subject)
        funder-existing (store/funder-outstanding-exposure st (:funder-id r) subject)
        funder-rec (store/funder st (:funder-id r))
        debtor-ok? (not (registry/debtor-concentration-exceeded?
                         debtor-existing (:face-amount r) (:debtor-credit-limit r)))
        funder-ok? (and funder-rec
                       (not (registry/funder-concentration-exceeded?
                             funder-existing (:face-amount r) (:funding-capacity funder-rec))))]
    {:summary    (str subject ": debtor-exposure=" (+ debtor-existing (:face-amount r))
                      "/" (:debtor-credit-limit r)
                      (if debtor-ok? " (許容範囲内)" " (上限超過)"))
     :rationale  (str "debtor-id=" (:debtor-id r) " funder-id=" (:funder-id r)
                      " debtor-existing-exposure=" debtor-existing
                      " funder-existing-exposure=" funder-existing)
     :cites      [:debtor-concentration :funder-concentration]
     :effect     :underwriting/set
     :value      {:receivable-id subject
                 :debtor-exposure (+ debtor-existing (:face-amount r))
                 :debtor-credit-limit (:debtor-credit-limit r)
                 :verdict (if (and debtor-ok? funder-ok?) :within-limits :concentration-exceeded)}
     :stake      nil
     :confidence (if (and debtor-ok? funder-ok?) 0.9 0.3)}))

(defn- propose-advance
  "Draft the actual cash-ADVANCE action (**actuation 1**) -- advancing
  real cash to the client against a purchased receivable. ALWAYS
  `:stake :actuation/advance-cash` -- this is a REAL-WORLD act (real
  money leaves the factor), never a draft the actor may auto-run. See
  README `Actuation`: no phase ever adds this op to a phase's `:auto`
  set (`factoring.phase`); the governor also always escalates on
  `:actuation/advance-cash`. Two independent layers agree,
  deliberately."
  [st {:keys [subject]}]
  (let [r (store/receivable st subject)]
    {:summary    (str subject " 向け資金前渡し提案 (face-amount=" (:face-amount r)
                      ", advance=" (registry/compute-advance-amount (:face-amount r)) ")")
     :rationale  (str "status=" (:status r) " fee-rate=" (:fee-rate r)
                      " debtor-risk-tier=" (:debtor-risk-tier r))
     :cites      [subject]
     :effect     :advance/mark-funded
     :value      {:receivable-id subject}
     :stake      :actuation/advance-cash
     :confidence (if (= :underwritten (:status r)) 0.9 0.3)}))

(defn- propose-collection
  "Draft the collection-receipt confirmation -- recording that the
  account debtor paid at maturity. NOT an actuation: no cash leaves the
  factor here (the debtor's payment is an inbound receipt into the
  factor's own account, not an act the factor originates), so this
  differs from `:advance/fund`/`:reserve/settle` -- see README
  `Actuation` for why only two of this actor's six write ops move real
  money out."
  [st {:keys [subject]}]
  (let [r (store/receivable st subject)]
    {:summary    (str subject " の債務者からの回収を記録")
     :rationale  (str "status=" (:status r))
     :cites      [subject]
     :effect     :collection/mark-received
     :value      {:receivable-id subject}
     :stake      nil
     :confidence (if (= :advanced (:status r)) 0.9 0.3)}))

(defn- propose-settlement
  "Draft the reserve-SETTLEMENT action (**actuation 2**) -- releasing
  the held-back reserve (minus the factor's fee) to the client. ALWAYS
  `:stake :actuation/release-reserve` -- a REAL-WORLD act, never a
  draft the actor may auto-run."
  [st {:keys [subject]}]
  (let [r (store/receivable st subject)]
    {:summary    (str subject " 向け留保金精算提案")
     :rationale  (str "status=" (:status r) " fee-rate=" (:fee-rate r))
     :cites      [subject]
     :effect     :settlement/mark-settled
     :value      {:receivable-id subject}
     :stake      :actuation/release-reserve
     :confidence (if (= :collected (:status r)) 0.9 0.3)}))

(defn- propose-solvency-attestation
  "Draft the whole-book SOLVENCY ATTESTATION -- a ground-truth
  recompute (`registry/solvency-report`) the advisor MUST get right, or
  `factoring.governor/solvency-attestation-mismatch-violations` holds
  it (the advisor cannot self-report a rosier number than the ledger
  actually supports). NOT an actuation -- no cash moves; this
  PUBLISHES a transparency fact. The direct anti-Zentoshin mitigation,
  see `factoring.registry`'s ns docstring."
  [st _request]
  (let [report (registry/solvency-report (store/all-receivables st) (store/all-funders st))]
    {:summary    (str "支払能力アテステーション: outstanding=" (:outstanding-obligations report)
                      " capacity=" (:funding-capacity report)
                      " solvent=" (:solvent? report))
     :rationale  "store の receivable/funder 実台帳からの独立再計算 (自己申告なし)"
     :cites      [:solvency-report]
     :effect     :attestation/publish
     :value      report
     :stake      nil
     :confidence 0.95}))

(defn- propose-audit-report
  "Read-only ledger/history summary -- no SSoT mutation."
  [st _request]
  {:summary    "監査台帳サマリー"
   :rationale  (str "ledger-entries=" (count (store/ledger st))
                    " advances=" (count (store/advance-history st))
                    " settlements=" (count (store/settlement-history st))
                    " attestations=" (count (store/solvency-attestation-history st)))
   :cites      [:ledger]
   :effect     :noop
   :value      {}
   :stake      nil
   :confidence 0.99})

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [st {:keys [op] :as request}]
  (case op
    :receivable/intake     (normalize-intake st request)
    :receivable/verify       (propose-verification st request)
    :receivable/underwrite     (propose-underwriting st request)
    :advance/fund                 (propose-advance st request)
    :receivable/collect             (propose-collection st request)
    :reserve/settle                   (propose-settlement st request)
    :solvency/attest                    (propose-solvency-attestation st request)
    :audit/report                         (propose-audit-report st request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたはファクタリング(売掛債権買取)エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:receivable/upsert|:verification/set|:underwriting/set|"
       ":advance/mark-funded|:collection/mark-received|:settlement/mark-settled|"
       ":attestation/publish) "
       ":stake(:actuation/advance-cash か :actuation/release-reserve か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件、または台帳から独立再計算できない支払能力の"
       "数値を絶対に創作してはいけません。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :receivable/verify       {:receivable (store/receivable st subject)}
    :receivable/underwrite     {:receivable (store/receivable st subject)}
    :advance/fund                 {:receivable (store/receivable st subject)
                                   :verification (store/verification-of st subject)}
    :receivable/collect             {:receivable (store/receivable st subject)}
    :reserve/settle                   {:receivable (store/receivable st subject)}
    :solvency/attest                    {:receivables (store/all-receivables st) :funders (store/all-funders st)}
    {:receivable (store/receivable st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Factoring Governor
  escalates/holds -- an LLM hiccup can never auto-advance cash or
  auto-release a reserve."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :factoringllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
