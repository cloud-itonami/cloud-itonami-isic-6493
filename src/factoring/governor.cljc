(ns factoring.governor
  "Factoring Governor -- the independent compliance layer that earns
  the Factoring-LLM the right to commit. The LLM has no notion of
  which jurisdiction's receivables-assignment law is official, whether
  an account debtor's aggregate exposure actually exceeds this factor's
  own recorded limit for them, whether a single funding source is about
  to be overconcentrated, or whether the whole book can actually still
  pay what it owes, so this MUST be a separate system able to *reject*
  a proposal and fall back to HOLD -- the factoring analog of `credit.
  governor` (`cloud-itonami-isic-6492`).

  ## Why: 全東信 (Zentoshin)

  See `factoring.registry`'s ns docstring for the full 2026-07 case
  (JPY 115.1B liabilities, ~20 years of alleged books-falsification,
  ~200,000 merchants affected, ~JPY 5B+ stuck). Two of this governor's
  checks exist BECAUSE of that case, not as generic hardening:
  `solvency-attestation-mismatch-violations` (the advisor cannot
  publish a solvency number the ledger does not actually support) and
  `solvency-attestation-stale-or-missing-violations` +
  `funder-concentration-ceiling-exceeded-violations` (an advance can
  never proceed on stale/missing books, and no single funder can ever
  back enough of the book to reproduce Zentoshin's single-balance-sheet
  cascade). `fee-rate-mismatch-violations` is the fairness mitigation:
  every advance's applied rate must exactly match the PUBLISHED,
  versioned schedule (`factoring.registry/fee-schedule`) for the
  debtor's risk tier -- no privately negotiated, discretionary terms.

  ## Underwriting inversion vs. `credit.governor` (`6492`)

  `credit.governor`'s affordability check is BORROWER-side: it
  recomputes the loan APPLICANT's own debt-to-income ratio. Factoring
  inverts this: the party whose repayment capacity actually matters is
  the ACCOUNT DEBTOR (the third party who owes the underlying invoice,
  and who never submits a proposal to this actor at all) -- the
  CLIENT's (the seller of the receivable) own creditworthiness is
  comparatively unimportant, because the factor is buying the
  receivable, not lending against the client's balance sheet.
  `debtor-concentration-ceiling-exceeded-violations` reflects this: its
  ground truth is the ACCOUNT DEBTOR's aggregate exposure, not the
  client's. The LEGAL spec-basis (`spec-basis-violations`), by
  contrast, still follows the CLIENT's jurisdiction -- this matches UCC
  Article 9's own choice-of-law rule (perfection follows the location
  of the party granting/selling the interest, not the account debtor)
  and is a DELIBERATE, non-symmetric design: legal domicile and credit-
  risk domicile are different parties in factoring, and this governor
  checks each on the correct side. See `factoring.facts`'s own
  docstring for more.

  ## Check-family taxonomy (honest scope)

  This fleet's established comparison families (per `90-docs/adr/
  2607080200-cloud-itonami-leasing-6491-coverage.md`, the most recent
  superproject ADR that names them): direct-comparison MINIMUM-
  threshold / MAXIMUM-ceiling / two-sided-range / ratio-based /
  unconditional-evaluation-screening. `debtor-concentration-ceiling-
  exceeded-violations` and `funder-concentration-ceiling-exceeded-
  violations` are BOTH direct-comparison MAXIMUM-ceiling checks --
  reusing an EXISTING family, not claiming a new one.
  `sanctions-hit-violations` reuses the unconditional-evaluation-
  screening family (`casualty.governor/sanctions-violations`'s
  original fix, cited by `6491`'s own ADR). `fee-rate-mismatch-
  violations` and `solvency-attestation-mismatch-violations` are
  exact-match checks (the `6629`/`6520`/`6820`/`6612` family). NONE of
  these check functions claim a new comparison family. What IS
  genuinely new, verified against the two templates this build read
  directly (`credit.governor` / `vcfund.governor`), is the INPUT shape
  underneath `debtor-concentration-ceiling-exceeded-violations`,
  `funder-concentration-ceiling-exceeded-violations` and
  `solvency-attestation-*`: their ground truth is an AGGREGATION over
  MULTIPLE stored receivable records for one counterparty axis (one
  account debtor, one funder, or the whole book), computed by
  `factoring.registry/aggregate-exposure`/`solvency-report` -- never a
  single record's own fields (like `credit.governor/affordability-
  exceeded-violations`) or a proposal's self-reported claim (like
  `vcfund.governor/clawback-exceeds-entitlement-violations`). This is
  this build's principal structural contribution, honestly scoped: it
  was NOT audited against the whole ~140-actor fleet, only against the
  two templates directly read for this build.

  Every HARD check below is a HARD violation: a human approver CANNOT
  override it. The confidence/actuation gate is SOFT: it asks a human
  to look, and the human may approve -- but see `factoring.phase`:
  for `:stake :actuation/advance-cash` and `:stake :actuation/release-
  reserve` (real cash movements) NO phase ever allows auto-commit
  either. Two independent layers agree that actuation is always a
  human call."
  (:require [factoring.facts :as facts]
            [factoring.registry :as registry]
            [factoring.store :as store]))

(def confidence-floor
  "Below this, a clean proposal still escalates to a human."
  0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Advancing real cash to a client (`:actuation/advance-cash`) and
  releasing the reserve to a client (`:actuation/release-reserve`) are
  the TWO real-world actuation events this actor performs -- see
  README `Actuation`, and `factoring.phase`'s permanent absence of
  both `:advance/fund` and `:reserve/settle` from every phase's `:auto`
  set."
  #{:actuation/advance-cash :actuation/release-reserve})

(def ^:private spec-checked-ops #{:receivable/verify :advance/fund})
(def ^:private sanctions-checked-ops #{:receivable/verify :advance/fund})
(def ^:private debtor-concentration-checked-ops #{:receivable/underwrite :advance/fund})
(def ^:private funder-concentration-checked-ops #{:receivable/underwrite :advance/fund})

;; ----------------------------- legal / KYC checks -----------------------------

(defn- spec-basis-violations
  "A `:receivable/verify` (or `:advance/fund`) proposal with no
  spec-basis citation is a HARD violation -- never invent a
  jurisdiction's receivables-assignment requirements. The governing
  jurisdiction is the CLIENT's (see `factoring.facts`'s docstring for
  why -- UCC Article 9's own choice-of-law rule, mirrored here)."
  [{:keys [op]} proposal]
  (when (contains? spec-checked-ops op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- sanctions-hit-violations
  "For `:receivable/verify` OR `:advance/fund`, independently re-reads
  the CLIENT's and the ACCOUNT DEBTOR's sanctions-screening ground
  truth directly off the store (never the proposal's self-report) --
  either party screening positive is a HARD, un-overridable violation.
  Re-checked at BOTH ops (not just verify) so a receivable can never
  reach an actuation by skipping verify -- the same 'ground truth,
  independently re-derived at every relevant op' discipline `casualty.
  governor/sanctions-violations` established (three reuses per that
  check's own history, cited by `6491`'s own ADR)."
  [{:keys [op subject]} st]
  (when (contains? sanctions-checked-ops op)
    (let [r (store/receivable st subject)]
      (when (or (:client-sanctions-hit? r) (:debtor-sanctions-hit? r))
        [{:rule :sanctions-hit
          :detail (str subject " のクライアントまたは債務者が制裁スクリーニングに該当")}]))))

(defn- evidence-incomplete-violations
  "For `:advance/fund`, the jurisdiction's required invoice/assignment-
  notification/duplicate-pledge/KYC evidence must actually be
  satisfied -- do not trust the advisor's self-reported confidence
  alone."
  [{:keys [op subject]} st]
  (when (= op :advance/fund)
    (let [r (store/receivable st subject)
          verification (store/verification-of st subject)]
      (when-not (and verification
                     (facts/required-evidence-satisfied?
                      (:jurisdiction r) (:checklist verification)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(請求書/譲渡通知/重複譲渡確認/KYC等)が充足していない状態での資金前渡し提案"}]))))

;; ----------------------------- status-lifecycle checks -----------------------------

(defn- receivable-status-precondition-violations
  "Generalizes `credit.governor/application-not-approved-violations`'s
  lesson (`6492`'s ADR Decision 5: check a status VALUE SET covering
  every state reachable only via the precondition, never a single
  terminal value, because status legitimately advances PAST the
  checked value after the next actuation) across THREE ops instead of
  one: `:advance/fund` requires the receivable was underwritten,
  `:receivable/collect` requires it was advanced, `:reserve/settle`
  requires it was collected. Applied proactively this time (unlike
  `6492`'s own build, which re-derived the wrong single-value check
  once before fixing it) -- each precondition set already includes
  every downstream status."
  [{:keys [op subject]} st]
  (let [status (:status (store/receivable st subject))]
    (case op
      :advance/fund
      (when-not (contains? #{:underwritten :advanced :collected :settled} status)
        [{:rule :not-underwritten
          :detail (str subject " は引受(underwrite)未完了のため、資金前渡しはできない")}])

      :receivable/collect
      (when-not (contains? #{:advanced :collected :settled} status)
        [{:rule :not-advanced
          :detail (str subject " は資金前渡し(advance)未完了のため、回収記録はできない")}])

      :reserve/settle
      (when-not (contains? #{:collected :settled} status)
        [{:rule :not-collected
          :detail (str subject " は債務者からの回収(collect)未完了のため、留保金の精算はできない")}])

      nil)))

(defn- double-advance-violations
  "For `:advance/fund`, refuses to advance the SAME receivable twice."
  [{:keys [op subject]} st]
  (when (= op :advance/fund)
    (when (store/receivable-already-advanced? st subject)
      [{:rule :double-advance
        :detail (str subject " は既に資金前渡し済み")}])))

(defn- double-settle-violations
  "For `:reserve/settle`, refuses to settle the SAME receivable twice."
  [{:keys [op subject]} st]
  (when (= op :reserve/settle)
    (when (store/receivable-already-settled? st subject)
      [{:rule :double-settle
        :detail (str subject " は既に留保金精算済み")}])))

;; ----------------------------- concentration checks (aggregation-based) -----------------------------

(defn- debtor-concentration-ceiling-exceeded-violations
  "For `:receivable/underwrite` OR `:advance/fund`, independently
  recompute the ACCOUNT DEBTOR's aggregate outstanding exposure
  (`factoring.store/debtor-outstanding-exposure`, an AGGREGATION over
  every OTHER receivable this actor has advanced against the same
  debtor) plus this receivable's own face amount, and refuse if it
  exceeds that debtor's own recorded `:debtor-credit-limit`. See this
  ns's own docstring for the underwriting-inversion rationale and the
  honest check-family-taxonomy scoping (MAXIMUM-ceiling family reuse;
  the aggregation input shape, not the comparison, is what's new)."
  [{:keys [op subject]} st]
  (when (contains? debtor-concentration-checked-ops op)
    (let [r (store/receivable st subject)
          existing (store/debtor-outstanding-exposure st (:debtor-id r) subject)]
      (when (registry/debtor-concentration-exceeded? existing (:face-amount r) (:debtor-credit-limit r))
        [{:rule :debtor-concentration-exceeded
          :detail (str (:debtor-id r) " への累計与信残高が上限(" (:debtor-credit-limit r) ")を超過している")}]))))

(defn- funder-concentration-ceiling-exceeded-violations
  "For `:receivable/underwrite` OR `:advance/fund`, independently
  recompute the FUNDING SOURCE's aggregate cash-out exposure
  (`factoring.store/funder-outstanding-exposure`, an AGGREGATION over
  every OTHER receivable this actor has funded through the same
  funder) plus this receivable's own advance amount, and refuse if it
  exceeds that funder's own recorded `:funding-capacity`. The
  distributed-funding mitigation: this is what structurally prevents
  any ONE funder's collapse from cascading to the whole client base
  the way 全東信 (Zentoshin)'s single-balance-sheet failure did (see
  this ns's own docstring)."
  [{:keys [op subject]} st]
  (when (contains? funder-concentration-checked-ops op)
    (let [r (store/receivable st subject)
          funder (store/funder st (:funder-id r))
          existing (store/funder-outstanding-exposure st (:funder-id r) subject)]
      (when (or (nil? funder)
                (registry/funder-concentration-exceeded?
                 existing (:face-amount r) (:funding-capacity funder)))
        [{:rule :funder-concentration-exceeded
          :detail (str (:funder-id r) " への累計資金供給残高が同資金提供者の funding-capacity を超過している (または資金提供者が未登録)")}]))))

;; ----------------------------- fair pricing -----------------------------

(defn- fee-rate-mismatch-violations
  "For `:advance/fund`, the receivable's own `:fee-rate` must EXACTLY
  match the PUBLISHED, versioned schedule (`factoring.registry/fee-
  schedule`) for its `:debtor-risk-tier` -- an exact-match check (the
  `6629`/`6520`/`6820`/`6612` family). Fairness mitigation: no
  privately negotiated, discretionary per-client pricing can ever
  reach an actuation, only a rate that matches the same schedule every
  other client on that risk tier gets."
  [{:keys [op subject]} st]
  (when (= op :advance/fund)
    (let [r (store/receivable st subject)]
      (when-not (registry/fee-rate-matches-schedule? (:debtor-risk-tier r) (:fee-rate r))
        [{:rule :fee-rate-mismatch
          :detail (str subject " の適用fee-rate(" (:fee-rate r) ")が公開スケジュール("
                       (registry/scheduled-fee-rate (:debtor-risk-tier r)) ", tier="
                       (:debtor-risk-tier r) ")と一致しない")}]))))

;; ----------------------------- solvency (anti-Zentoshin) -----------------------------

(defn- solvency-attestation-mismatch-violations
  "For `:solvency/attest`, independently recompute the whole book's
  solvency (`factoring.registry/solvency-report`, straight from the
  store's own receivable/funder ground truth) and refuse to let the
  proposal's claimed attestation numbers commit unless they EXACTLY
  match this independent recompute. The advisor can never publish a
  rosier solvency picture than the ledger actually supports -- the
  direct structural answer to 全東信 (Zentoshin)'s ~20 years of
  self-reported, unverified books (see this ns's own docstring)."
  [{:keys [op]} proposal st]
  (when (= op :solvency/attest)
    (let [claimed (:value proposal)
          actual (registry/solvency-report (store/all-receivables st) (store/all-funders st))]
      (when-not (and claimed
                     (= (:outstanding-obligations actual) (:outstanding-obligations claimed))
                     (= (:funding-capacity actual) (:funding-capacity claimed))
                     (= (:solvent? actual) (:solvent? claimed)))
        [{:rule :solvency-attestation-mismatch
          :detail "提案された支払能力の数値が、台帳からの独立再計算と一致しない"}]))))

(defn- solvency-attestation-stale-or-missing-violations
  "For `:advance/fund`, an advance may NEVER proceed unless (1) a
  solvency attestation exists, (2) it was published within the last
  `factoring.registry/stale-after-n-advances` advances (not once and
  trusted for years the way Zentoshin's books were), (3) it showed
  `:solvent? true`, AND (4) a LIVE recompute -- INCLUDING this specific
  advance's own face amount -- still shows solvent right now, even if
  the last attestation was clean a few advances ago. All four
  conditions are re-derived from the store's own ground truth every
  time; none of them trust a cached number."
  [{:keys [op subject]} st]
  (when (= op :advance/fund)
    (let [r (store/receivable st subject)
          latest (store/latest-solvency-attestation st)
          receivables (store/all-receivables st)
          funders (store/all-funders st)
          live (registry/solvency-report receivables funders (:face-amount r))
          stale? (or (nil? latest)
                     (> (- (:advanced-count live) (get latest "advanced_count" -1))
                        registry/stale-after-n-advances)
                     (false? (get latest "solvent")))]
      (when (or stale? (not (:solvent? live)))
        [{:rule :solvency-attestation-stale-or-missing
          :detail (str "直近の支払能力アテステーションが無い/古い、または本件を含めた即時再計算が"
                       "insolvent(outstanding=" (:outstanding-obligations live)
                       " > capacity=" (:funding-capacity live) ")を示している")}]))))

;; ----------------------------- check aggregation -----------------------------

(defn check
  "Censors a Factoring-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool}."
  [request _context proposal st]
  (let [violation-lists [(spec-basis-violations request proposal)
                         (sanctions-hit-violations request st)
                         (evidence-incomplete-violations request st)
                         (receivable-status-precondition-violations request st)
                         (double-advance-violations request st)
                         (double-settle-violations request st)
                         (debtor-concentration-ceiling-exceeded-violations request st)
                         (funder-concentration-ceiling-exceeded-violations request st)
                         (fee-rate-mismatch-violations request st)
                         (solvency-attestation-mismatch-violations request proposal st)
                         (solvency-attestation-stale-or-missing-violations request st)]
        hard (into [] (apply concat violation-lists))
        conf (:confidence proposal 0.0)
        stakes? (boolean (high-stakes (:stake proposal)))]
    {:ok?          (and (empty? hard) (>= conf confidence-floor) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        (boolean (seq hard))
     :escalate?    (and (empty? hard) (or (< conf confidence-floor) stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
