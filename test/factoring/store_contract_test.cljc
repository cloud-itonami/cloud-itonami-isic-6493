(ns factoring.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite -- see `credit.store-contract-
  test` (`cloud-itonami-isic-6492`) for the same pattern on the
  closest sibling."
  (:require [clojure.test :refer [deftest is testing]]
            [factoring.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "GreenLeaf Logistics K.K." (:client-name (store/receivable s "rcv-1"))))
      (is (= "JPN" (:jurisdiction (store/receivable s "rcv-1"))))
      (is (= 2000000 (:face-amount (store/receivable s "rcv-1"))))
      (is (= "debtor-1" (:debtor-id (store/receivable s "rcv-1"))))
      (is (= 7 (count (store/all-receivables s))))
      (is (= "Harborlight Capital Partners" (:name (store/funder s "funder-1"))))
      (is (= 4000000 (:funding-capacity (store/funder s "funder-1"))))
      (is (= 3 (count (store/all-funders s))))
      (is (nil? (store/verification-of s "rcv-1")))
      (is (nil? (store/underwriting-of s "rcv-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/advance-history s)))
      (is (= [] (store/settlement-history s)))
      (is (= [] (store/solvency-attestation-history s)))
      (is (nil? (store/latest-solvency-attestation s)))
      (is (zero? (store/next-sequence s :advance "JPN")))
      (is (false? (store/receivable-already-advanced? s "rcv-1")))
      (is (false? (store/receivable-already-settled? s "rcv-1")))
      (is (zero? (store/debtor-outstanding-exposure s "debtor-1" nil)) "nothing advanced yet")
      (is (zero? (store/funder-outstanding-exposure s "funder-1" nil)))
      (is (zero? (store/book-outstanding-exposure s nil))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :receivable/upsert
                                 :value {:id "rcv-1" :fee-rate 0.025}})
        (is (= 0.025 (:fee-rate (store/receivable s "rcv-1"))))
        (is (= "GreenLeaf Logistics K.K." (:client-name (store/receivable s "rcv-1"))) "client preserved"))
      (testing "verification / underwriting payloads commit and read back; underwrite advances status"
        (store/commit-record! s {:effect :verification/set :path ["rcv-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/verification-of s "rcv-1")))
        (store/commit-record! s {:effect :underwriting/set :path ["rcv-1"]
                                 :payload {:receivable-id "rcv-1" :verdict :within-limits}})
        (is (= :within-limits (:verdict (store/underwriting-of s "rcv-1"))))
        (is (= :underwritten (:status (store/receivable s "rcv-1")))))
      (testing "advance drafts a record, advances status, and now counts toward exposure"
        (store/commit-record! s {:effect :advance/mark-funded :path ["rcv-1"]})
        (is (= "JPN-ADV-000000" (get (first (store/advance-history s)) "record_id")))
        (is (= "factoring-advance-draft" (get (first (store/advance-history s)) "kind")))
        (is (= 1700000.0 (get (first (store/advance-history s)) "advance_amount")))
        (is (= :advanced (:status (store/receivable s "rcv-1"))))
        (is (= 1 (count (store/advance-history s))))
        (is (= 1 (store/next-sequence s :advance "JPN")))
        (is (true? (store/receivable-already-advanced? s "rcv-1")))
        (is (false? (store/receivable-already-advanced? s "rcv-2")))
        (is (= 2000000 (store/debtor-outstanding-exposure s "debtor-1" nil)) "now counts, self not excluded")
        (is (zero? (store/debtor-outstanding-exposure s "debtor-1" "rcv-1")) "excluding self, no OTHER receivable for this debtor")
        (is (= 2000000 (store/funder-outstanding-exposure s "funder-1" nil)))
        (is (= 2000000 (store/book-outstanding-exposure s nil))))
      (testing "collect advances status, no draft record (mirrors credit's approval-moves-no-capital shape)"
        (store/commit-record! s {:effect :collection/mark-received :path ["rcv-1"]})
        (is (= :collected (:status (store/receivable s "rcv-1")))))
      (testing "settlement drafts a record and advances status"
        (store/commit-record! s {:effect :settlement/mark-settled :path ["rcv-1"]})
        (is (= "JPN-SETL-000000" (get (first (store/settlement-history s)) "record_id")))
        (is (= 250000.0 (get (first (store/settlement-history s)) "settlement_amount"))
            "fee-rate was updated to 0.025 by the earlier upsert in this test: reserve 300000 - fee(2000000*0.025=50000) = 250000")
        (is (= :settled (:status (store/receivable s "rcv-1"))))
        (is (true? (store/receivable-already-settled? s "rcv-1")))
        (is (zero? (store/debtor-outstanding-exposure s "debtor-1" nil)) "settled receivables no longer count toward outstanding exposure"))
      (testing "attestation publishes a numbered, publicly-queryable record"
        (store/commit-record! s {:effect :attestation/publish
                                 :value {:jurisdiction "JPN"}
                                 :payload {:outstanding-obligations 0.0 :funding-capacity 9000000
                                          :solvent? true :coverage-ratio 0.0 :advanced-count 0}})
        (is (= "JPN-ATTEST-000000" (get (first (store/solvency-attestation-history s)) "record_id")))
        (is (= true (get (store/latest-solvency-attestation s) "solvent")))
        (is (= 1 (count (store/solvency-attestation-history s)))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/receivable s "nope")))
    (is (= [] (store/all-receivables s)))
    (is (= [] (store/all-funders s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/advance-history s)))
    (is (zero? (store/next-sequence s :advance "JPN")))
    (store/with-receivables s {"x" {:id "x" :client-id "c" :client-name "n" :debtor-id "d"
                                    :debtor-name "dn" :debtor-credit-limit 1000000
                                    :debtor-risk-tier :tier-a :fee-rate 0.02 :face-amount 100000
                                    :jurisdiction "JPN" :funder-id "funder-1"
                                    :client-sanctions-hit? false :debtor-sanctions-hit? false
                                    :status :submitted}})
    (is (= "n" (:client-name (store/receivable s "x"))))))
