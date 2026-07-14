(ns factoring.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [factoring.registry :as r]))

;; ----------------------------- advance/reserve/settlement economics -----------------------------

(deftest advance-amount-is-face-amount-times-advance-rate
  (is (= 1700000.0 (r/compute-advance-amount 2000000))))

(deftest reserve-is-the-remainder
  (is (= 300000.0 (r/compute-reserve-amount 2000000))))

(deftest settlement-amount-is-reserve-minus-fee
  (is (= 260000.0 (r/compute-settlement-amount 2000000 0.02))))

(deftest settlement-fee-exceeding-reserve-throws
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/compute-settlement-amount 2000000 0.99))))

(deftest advance-amount-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/compute-advance-amount -1))))

;; ----------------------------- fee schedule (fair pricing) -----------------------------

(deftest fee-schedule-is-published-and-versioned
  (is (= :v1 (:version r/fee-schedule)))
  (is (= 0.02 (r/scheduled-fee-rate :tier-a)))
  (is (= 0.03 (r/scheduled-fee-rate :tier-b)))
  (is (= 0.05 (r/scheduled-fee-rate :tier-c))))

(deftest unknown-tier-has-no-fabricated-fee-rate
  (is (nil? (r/scheduled-fee-rate :tier-z))))

(deftest fee-rate-matches-schedule-is-exact
  (is (true? (r/fee-rate-matches-schedule? :tier-a 0.02)))
  (is (false? (r/fee-rate-matches-schedule? :tier-a 0.99)))
  (is (false? (r/fee-rate-matches-schedule? :tier-z 0.02)) "unknown tier never matches"))

;; ----------------------------- exposure aggregation -----------------------------

(def ^:private receivables
  [{:id "a" :debtor-id "d1" :funder-id "f1" :face-amount 1000 :status :advanced}
   {:id "b" :debtor-id "d1" :funder-id "f2" :face-amount 2000 :status :collected}
   {:id "c" :debtor-id "d2" :funder-id "f1" :face-amount 500 :status :advanced}
   {:id "d" :debtor-id "d1" :funder-id "f1" :face-amount 9999 :status :settled}
   {:id "e" :debtor-id "d1" :funder-id "f1" :face-amount 9999 :status :submitted}])

(deftest aggregate-exposure-only-counts-advanced-or-collected
  (testing "settled and submitted receivables never count toward outstanding exposure"
    (is (= 3000 (r/debtor-exposure receivables "d1" nil)))))

(deftest debtor-exposure-excludes-the-subject-receivable
  (is (= 2000 (r/debtor-exposure receivables "d1" "a"))))

(deftest funder-exposure-sums-across-debtors-for-one-funder
  (is (= 1500 (r/funder-exposure receivables "f1" nil))))

(deftest book-exposure-sums-across-every-debtor-and-funder
  (is (= 3500 (r/book-exposure receivables nil))))

;; ----------------------------- concentration checks -----------------------------

(deftest debtor-concentration-exceeded-boundary
  (testing "at the ceiling clears (strict >, not >=)"
    (is (false? (r/debtor-concentration-exceeded? 4000000 1000000 5000000))))
  (testing "one unit over the ceiling exceeds"
    (is (true? (r/debtor-concentration-exceeded? 4000000 1000001 5000000)))))

(deftest funder-concentration-exceeded-applies-advance-rate
  (testing "5,000,000 face * 0.85 = 4,250,000 > 4,000,000 capacity -> exceeded"
    (is (true? (r/funder-concentration-exceeded? 2000000 3000000 4000000))))
  (testing "under the cash-out ceiling clears"
    (is (false? (r/funder-concentration-exceeded? 1000000 1000000 4000000)))))

(deftest total-funding-capacity-sums-every-funder
  (is (= 9000000 (r/total-funding-capacity [{:funding-capacity 4000000}
                                             {:funding-capacity 3000000}
                                             {:funding-capacity 2000000}]))))

;; ----------------------------- solvency (anti-Zentoshin) -----------------------------

(deftest solvency-report-is-a-pure-ground-truth-recompute
  (let [funders [{:funding-capacity 4000000} {:funding-capacity 3000000}]
        report (r/solvency-report receivables funders)]
    (is (= (* 3500.0 r/advance-rate) (:outstanding-obligations report)))
    (is (= 7000000 (:funding-capacity report)))
    (is (true? (:solvent? report)))))

(deftest solvency-report-detects-insolvency
  (let [huge [{:id "z" :debtor-id "dz" :funder-id "fz" :face-amount 100000000 :status :advanced}]
        report (r/solvency-report huge [{:funding-capacity 1000}])]
    (is (false? (:solvent? report)))
    (is (> (:outstanding-obligations report) (:funding-capacity report)))))

(deftest solvency-report-pending-face-amount-is-a-live-what-if
  (testing "adding a pending receivable's face amount can tip a solvent book into insolvent"
    (let [report (r/solvency-report [] [{:funding-capacity 100}] 1000)]
      (is (false? (:solvent? report))))))

;; ----------------------------- draft records -----------------------------

(deftest advance-is-a-draft-not-a-real-payment
  (let [result (r/register-advance "rcv-1" 2000000 "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest advance-assigns-advance-number-and-splits-reserve
  (let [result (r/register-advance "rcv-1" 2000000 "JPN" 7)]
    (is (= (get result "advance_number") "JPN-ADV-000007"))
    (is (= (get-in result ["record" "advance_amount"]) 1700000.0))
    (is (= (get-in result ["record" "reserve_amount"]) 300000.0))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest advance-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-advance "" 2000000 "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-advance "rcv-1" -1 "JPN" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-advance "rcv-1" 2000000 "" 0))))

(deftest settlement-assigns-settlement-number
  (let [result (r/register-settlement "rcv-1" 2000000 0.02 "JPN" 3)]
    (is (= (get result "settlement_number") "JPN-SETL-000003"))
    (is (= (get-in result ["record" "settlement_amount"]) 260000.0))))

(deftest attestation-record-carries-the-report-fields
  (let [report {:outstanding-obligations 1000.0 :funding-capacity 5000.0 :solvent? true
               :coverage-ratio 0.2 :advanced-count 3}
        result (r/register-attestation report "JPN" 0)]
    (is (= (get result "attestation_number") "JPN-ATTEST-000000"))
    (is (= (get-in result ["record" "outstanding_obligations"]) 1000.0))
    (is (= (get-in result ["record" "solvent"]) true))))

(deftest history-is-append-only
  (let [d1 (r/register-advance "rcv-1" 2000000 "JPN" 0)
        hist (r/append [] d1)
        d2 (r/register-advance "rcv-2" 100000 "JPN" 1)
        hist2 (r/append hist d2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-ADV-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-ADV-000001" (get-in hist2 [1 "record_id"])))))
