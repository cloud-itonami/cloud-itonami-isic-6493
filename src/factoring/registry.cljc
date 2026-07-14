(ns factoring.registry
  "Pure-function advance/settlement/attestation draft-record
  construction, plus the pure exposure-aggregation, concentration and
  solvency math -- the factoring analog of `credit.registry` (`cloud-
  itonami-isic-6492`).

  Like every sibling actor's registry, there is no single international
  check-digit standard for an advance/settlement/attestation reference
  number -- every factor/jurisdiction assigns its own reference format.
  This namespace does NOT invent one; it builds a jurisdiction-scoped
  sequence number per kind and validates the record's required fields,
  the same honest, non-fabricating discipline `credit.registry` uses.

  ## Why this namespace exists in this shape: 全東信 (Zentoshin)

  In 2026-07, 全東信 (Zentoshin), a Japanese card-settlement/early-
  payment-advance company -- functionally a factoring-adjacent business
  that advanced merchants their card-sales proceeds early and collected
  from the card companies later -- filed for bankruptcy with **JPY
  115.1 billion** in liabilities. At least **~20 years of alleged
  accounting fraud (決算粉飾)** had hidden its true insolvency the whole
  time. When it collapsed, **~200,000 merchant stores nationwide** were
  affected and **~50,000+ merchants had JPY 5+ billion in payments
  stuck/unpaid**, with zero warning -- because merchants had NO way to
  independently verify the company's actual solvency; they could only
  trust its self-reported books, which were reportedly falsified for
  two decades.

  Two structural properties of that failure map directly onto design
  choices in this namespace and in `factoring.governor`:

  1. **The books were privately held and self-reported.** Nobody
     outside the company could independently recompute whether it
     could actually pay. `solvency-report` is the structural fix: it
     is a PURE ground-truth recompute from the actor's own receivable/
     advance history (never a stored/self-reported flag), callable by
     anyone with read access to the Store -- a client, an account
     debtor, or an outside observer -- not just an internal audit
     trail. `factoring.governor/solvency-attestation-mismatch-
     violations` additionally refuses to let the advisor publish an
     attestation whose numbers do not match this SAME independent
     recompute -- the advisor cannot self-report a rosier number than
     the ledger actually supports, the exact failure mode a 20-year
     books-falsification represents.
  2. **A single company's balance sheet backed every merchant's
     advance.** One entity's insolvency took the entire merchant base
     down at once. This actor never lets ONE funding source back the
     whole book: every receivable is attributed to a named funder
     (`:funder-id`), and `funder-concentration-exceeded?` blocks the
     book from concentrating too much aggregate exposure on any single
     funder, so one funder's collapse cannot cascade to every client
     the way Zentoshin's collapse did.

  `debtor-exposure`, `funder-exposure` and `book-exposure` are all the
  SAME pure primitive (`aggregate-exposure`) applied at three different
  scopes -- per-account-debtor, per-funding-source, and whole-book. This
  is this build's principal structural contribution: a family of HARD
  checks whose ground truth is an AGGREGATION over MULTIPLE stored
  records for one counterparty axis, never a single record's own field
  or a proposal's self-reported claim -- see `factoring.governor`'s own
  docstring for the full novelty argument (and its honest scope
  caveat: this is verified distinct from the two templates this build
  read directly, not audited against the whole ~140-actor fleet).

  `advance-rate` and `fee-schedule` are REAL, simplified but PUBLISHED,
  VERSIONED reference constants (see their own docstrings), never
  privately negotiated per-client secret terms -- the fair-pricing
  mitigation `factoring.governor/fee-rate-mismatch-violations` enforces
  structurally, not just documents.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any factoring/banking system. It builds the RECORDS a factor
  would keep, not the acts of advancing cash or releasing a reserve
  themselves (those are `factoring.operation`'s `:advance/fund` and
  `:reserve/settle`, always human-gated -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the licensed factor's act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

;; ----------------------------- advance/settlement economics -----------------------------

(def advance-rate
  "A REAL, well-known representative advance rate (0.85, i.e. 85% of a
  receivable's face amount), mirroring the commonly-cited industry
  range for commercial invoice factoring (roughly 70-90%, varying by
  receivable quality/industry/jurisdiction) -- an honest starting
  reference point, NOT a claim that every deal in `factoring.facts/
  catalog`'s jurisdictions uses this exact rate. A real underwriting
  desk additionally prices the rate per-deal from the account debtor's
  specific credit profile, receivable dilution history and industry
  dating conventions; this R0 uses a single representative rate (see
  `compute-advance-amount`), and applies it uniformly when converting
  face-amount exposure to actual cash-out exposure for the funder-
  concentration and solvency checks."
  0.85)

(defn compute-advance-amount
  "Pure computation of the cash amount advanced to the client at
  `:advance/fund` time: `face-amount` * `advance-rate`. The remainder
  (`face-amount` - advance-amount) is the RESERVE held back until
  `:reserve/settle`."
  [face-amount]
  (when (neg? face-amount)
    (throw (ex-info "compute-advance-amount: face-amount must be >= 0" {})))
  (* (double face-amount) advance-rate))

(defn compute-reserve-amount
  "Pure computation of the reserve held back at advance time:
  `face-amount` - `(compute-advance-amount face-amount)`."
  [face-amount]
  (- (double face-amount) (compute-advance-amount face-amount)))

(defn compute-settlement-amount
  "Pure computation of the amount released to the client at
  `:reserve/settle` time: the reserve held back at advance time, minus
  the factor's own fee (`face-amount` * `fee-rate`). Throws if the fee
  would exceed the reserve -- a fee rate this actor never lets exceed
  the held-back reserve, an honest validation rule rather than a
  silently negative payout."
  [face-amount fee-rate]
  (when-not (and (>= fee-rate 0) (<= fee-rate 1))
    (throw (ex-info "compute-settlement-amount: fee-rate must be within [0,1]" {})))
  (let [reserve (compute-reserve-amount face-amount)
        fee (* (double face-amount) (double fee-rate))]
    (when (> fee reserve)
      (throw (ex-info "compute-settlement-amount: fee exceeds the held-back reserve" {:fee fee :reserve reserve})))
    (- reserve fee)))

;; ----------------------------- fair, published pricing -----------------------------

(def fee-schedule
  "A PUBLISHED, VERSIONED fee/discount-rate schedule keyed by account-
  debtor risk tier -- the fair-pricing mitigation named in this ns's
  own docstring (直接 anti-Zentoshin motivation: opaque, privately
  negotiated per-client terms are exactly what a discretionary/
  discriminatory factor would use to hide unequal treatment; a
  published schedule that every receivable's applied fee-rate must
  exactly match removes that discretion structurally). `:version`
  exists so a future schedule revision (`:v2`) is an ADDITIVE new
  version, never a silent retroactive rewrite of what `:v1` meant for
  historical receivables. Three risk tiers, ascending fee with
  ascending risk -- a representative starting schedule, not a claim
  that every factor prices exactly these three bands at exactly these
  rates."
  {:version :v1
   :tiers {:tier-a 0.02
           :tier-b 0.03
           :tier-c 0.05}})

(defn scheduled-fee-rate
  "The published fee-rate for `risk-tier`, or nil if the tier is not on
  the schedule -- nil means no honest fee-rate exists for it, the
  advisor must not invent one."
  [risk-tier]
  (get-in fee-schedule [:tiers risk-tier]))

(defn fee-rate-matches-schedule?
  "Does a receivable's own `fee-rate` exactly match the published
  schedule's rate for its `risk-tier`? An unknown tier never matches
  (fail-closed -- no fabricated tier gets a free pass)."
  [risk-tier fee-rate]
  (= (scheduled-fee-rate risk-tier) fee-rate))

;; ----------------------------- exposure aggregation (the core primitive) -----------------------------

(defn aggregate-exposure
  "Pure aggregation: sum the `:face-amount` of every receivable in
  `receivables` (a coll of receivable maps) satisfying `pred`, whose
  `:status` is in `#{:advanced :collected}` (cash is out against that
  counterparty and not yet reconciled via settle), EXCLUDING the
  receivable whose `:id` is `exclude-id` (so a receivable never double-
  counts against itself before its own advance has committed). The ONE
  primitive `debtor-exposure`/`funder-exposure`/`book-exposure`/
  `solvency-report` all share -- see this ns's own docstring for why
  this aggregation shape (spanning MULTIPLE stored records) is this
  build's principal structural contribution."
  [receivables pred exclude-id]
  (->> receivables
       (remove #(= exclude-id (:id %)))
       (filter pred)
       (filter #(contains? #{:advanced :collected} (:status %)))
       (map :face-amount)
       (reduce + 0)))

(defn debtor-exposure
  "The account debtor's aggregate outstanding factored face-amount
  exposure, excluding `exclude-id`."
  [receivables debtor-id exclude-id]
  (aggregate-exposure receivables #(= debtor-id (:debtor-id %)) exclude-id))

(defn funder-exposure
  "The funding source's aggregate outstanding factored face-amount
  exposure, excluding `exclude-id`."
  [receivables funder-id exclude-id]
  (aggregate-exposure receivables #(= funder-id (:funder-id %)) exclude-id))

(defn book-exposure
  "The WHOLE BOOK's aggregate outstanding factored face-amount
  exposure (every debtor, every funder), excluding `exclude-id`."
  [receivables exclude-id]
  (aggregate-exposure receivables (constantly true) exclude-id))

;; ----------------------------- concentration checks (MAXIMUM-ceiling family) -----------------------------

(defn debtor-concentration-exceeded?
  "true when `existing-exposure` + `face-amount` (the account debtor's
  aggregate outstanding factored exposure INCLUDING the receivable
  under review) strictly exceeds `debtor-credit-limit` (that debtor's
  own recorded limit, a ground-truth field set at intake -- see README
  `Underwriting inversion`). A direct-comparison MAXIMUM-ceiling check
  -- the same comparison family every prior sufficiency/ceiling check
  in this fleet uses (see `factoring.governor`'s own docstring for why
  this build does NOT claim a new comparison-family, only a new INPUT
  shape: the aggregation, not the ceiling comparison itself, is novel)."
  [existing-exposure face-amount debtor-credit-limit]
  (> (+ (double existing-exposure) (double face-amount)) (double debtor-credit-limit)))

(defn total-funding-capacity
  "Sum of every funder's `:funding-capacity` -- the whole book's total
  committed funding line, ground truth from `factoring.store/all-
  funders`, never a self-reported aggregate."
  [funders]
  (reduce + 0 (map :funding-capacity funders)))

(defn funder-concentration-exceeded?
  "true when the CASH actually advanced against a single funding
  source (`existing-face-exposure` + `new-face-amount`, converted to
  cash-out terms via `advance-rate`) strictly exceeds that funder's own
  `funding-capacity`. Distributed-funding mitigation: this is what
  structurally prevents any ONE funder's collapse from cascading to
  the whole client base the way Zentoshin's single-balance-sheet
  failure did -- see this ns's own docstring."
  [existing-face-exposure new-face-amount funding-capacity]
  (> (* (+ (double existing-face-exposure) (double new-face-amount)) advance-rate)
     (double funding-capacity)))

;; ----------------------------- solvency (whole-book ground-truth recompute) -----------------------------

(def stale-after-n-advances
  "A solvency attestation is STALE once this many additional receivables
  have committed to `:advanced` since it was last published -- an
  advance actuation may never proceed on a stale-or-missing
  attestation. A small representative threshold (books must be
  re-verified frequently, not once and trusted for years the way
  Zentoshin's were for ~20)."
  2)

(defn solvency-report
  "PURE ground-truth recompute of the whole book's solvency, straight
  from `receivables` (a coll of receivable maps, the actor's own
  immutable history) and `funders` (ground-truth funding-capacity
  records) -- NEVER a stored/self-reported flag. `pending-face-amount`
  (default 0) optionally adds one not-yet-committed receivable's own
  face amount, for a LIVE check of whether advancing it right now would
  tip the book into insolvency (see `factoring.governor/solvency-
  attestation-stale-or-missing-violations`). `:advanced-count` is
  recorded so a later caller can detect staleness (`stale-after-n-
  advances`) without re-deriving it from the whole history again.

  This is the direct anti-Zentoshin structural fix: any client, account
  debtor or outside observer with read access to the Store can call
  this themselves and independently verify solvency -- the whole point
  being that Zentoshin's merchants had no equivalent capability for
  ~20 years."
  ([receivables funders] (solvency-report receivables funders 0))
  ([receivables funders pending-face-amount]
   (let [outstanding-face (+ (book-exposure receivables nil) (double pending-face-amount))
         outstanding (* outstanding-face advance-rate)
         capacity (total-funding-capacity funders)]
     {:outstanding-obligations outstanding
      :funding-capacity capacity
      :solvent? (<= outstanding capacity)
      :coverage-ratio (if (pos? capacity) (/ outstanding capacity) ##Inf)
      :advanced-count (count (filter #(contains? #{:advanced :collected} (:status %)) receivables))})))

;; ----------------------------- draft records -----------------------------

(defn register-advance
  "Validate + construct the ADVANCE registration DRAFT (**actuation
  1**) -- the factor's own legal act of advancing real cash to the
  client against a purchased receivable. Pure function -- does not
  touch any real banking/payment system; it builds the RECORD a factor
  would keep. `factoring.governor` independently re-verifies debtor
  concentration, funder concentration, book-wide solvency and the
  published fee schedule, and blocks a double-advance of the same
  receivable, before this is ever allowed to commit."
  [receivable-id face-amount jurisdiction sequence]
  (when-not (and receivable-id (not= receivable-id ""))
    (throw (ex-info "advance: receivable_id required" {})))
  (when (neg? face-amount)
    (throw (ex-info "advance: face-amount must be >= 0" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "advance: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "advance: sequence must be >= 0" {})))
  (let [advance-amount (compute-advance-amount face-amount)
        advance-number (str (str/upper-case jurisdiction) "-ADV-" (zero-pad sequence 6))
        record {"record_id" advance-number
                "kind" "factoring-advance-draft"
                "receivable_id" receivable-id
                "face_amount" face-amount
                "advance_amount" advance-amount
                "reserve_amount" (compute-reserve-amount face-amount)
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "advance_number" advance-number
     "certificate" (unsigned-certificate "FactoringAdvanceCertificate" advance-number advance-number)}))

(defn register-settlement
  "Validate + construct the SETTLEMENT registration DRAFT (**actuation
  2**) -- the factor's own legal act of releasing the held-back reserve
  (minus its fee) to the client once the account debtor has paid at
  maturity. Pure function -- does not touch any real banking/payment
  system. `factoring.governor` independently re-verifies the receivable
  was actually collected, and blocks a double-settlement of the same
  receivable, before this is ever allowed to commit."
  [receivable-id face-amount fee-rate jurisdiction sequence]
  (when-not (and receivable-id (not= receivable-id ""))
    (throw (ex-info "settlement: receivable_id required" {})))
  (when (neg? face-amount)
    (throw (ex-info "settlement: face-amount must be >= 0" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "settlement: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "settlement: sequence must be >= 0" {})))
  (let [settlement-amount (compute-settlement-amount face-amount fee-rate)
        settlement-number (str (str/upper-case jurisdiction) "-SETL-" (zero-pad sequence 6))
        record {"record_id" settlement-number
                "kind" "factoring-settlement-draft"
                "receivable_id" receivable-id
                "face_amount" face-amount
                "fee_amount" (* (double face-amount) (double fee-rate))
                "settlement_amount" settlement-amount
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "settlement_number" settlement-number
     "certificate" (unsigned-certificate "FactoringSettlementCertificate" settlement-number settlement-number)}))

(defn register-attestation
  "Validate + construct a SOLVENCY ATTESTATION record (NOT an
  actuation -- no cash moves; this PUBLISHES a ground-truth-recomputed
  transparency fact, the direct anti-Zentoshin mitigation). `report` is
  a `solvency-report` map; this fn only formats it into a numbered,
  citable record -- `factoring.governor/solvency-attestation-mismatch-
  violations` is what guarantees `report` itself was independently
  recomputed rather than trusted from the advisor's proposal."
  [report jurisdiction sequence]
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "attestation: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "attestation: sequence must be >= 0" {})))
  (let [attestation-number (str (str/upper-case jurisdiction) "-ATTEST-" (zero-pad sequence 6))
        record {"record_id" attestation-number
                "kind" "factoring-solvency-attestation"
                "jurisdiction" jurisdiction
                "outstanding_obligations" (:outstanding-obligations report)
                "funding_capacity" (:funding-capacity report)
                "solvent" (:solvent? report)
                "coverage_ratio" (:coverage-ratio report)
                "advanced_count" (:advanced-count report)
                "immutable" true}]
    {"record" record "attestation_number" attestation-number}))

(defn append
  "Append a draft record (advance, settlement or attestation) to a
  history vector, returning a NEW list (never mutate history in
  place)."
  [history result]
  (conj (vec history) (get result "record")))
