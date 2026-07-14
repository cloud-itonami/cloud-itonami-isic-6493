(ns factoring.governor-contract-test
  "The governor contract as executable tests -- the factoring analog of
  `credit.governor-contract-test` (`cloud-itonami-isic-6492`). The
  single invariant under test:

    Factoring-LLM never advances cash or releases a reserve the
    Factoring Governor would reject, `:advance/fund`/`:reserve/
    settle`/`:solvency/attest` NEVER auto-commit at any phase,
    `:receivable/intake` (no capital risk) MAY auto-commit when clean,
    and every decision (commit OR hold) leaves exactly one ledger
    fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [factoring.governor :as governor]
            [factoring.store :as store]
            [factoring.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :factor :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- verify! [actor subject tid-prefix]
  (exec-op actor (str tid-prefix "-verify") {:op :receivable/verify :subject subject} operator)
  (approve! actor (str tid-prefix "-verify")))

(defn- underwrite! [actor subject tid-prefix]
  (exec-op actor (str tid-prefix "-underwrite") {:op :receivable/underwrite :subject subject} operator)
  (approve! actor (str tid-prefix "-underwrite")))

(defn- attest! [actor tid-prefix]
  (exec-op actor (str tid-prefix "-attest") {:op :solvency/attest :subject "book"} operator)
  (approve! actor (str tid-prefix "-attest")))

(defn- advance! [actor subject tid-prefix]
  (exec-op actor (str tid-prefix "-advance") {:op :advance/fund :subject subject} operator)
  (approve! actor (str tid-prefix "-advance")))

;; ----------------------------- baseline shape -----------------------------

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :receivable/intake :subject "rcv-1"
                   :patch {:id "rcv-1" :fee-rate 0.02}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 0.02 (:fee-rate (store/receivable db "rcv-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest verify-always-needs-approval
  (testing "verify is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :receivable/verify :subject "rcv-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/verification-of db "rcv-1")))))))

;; ----------------------------- legal / KYC -----------------------------

(deftest fabricated-jurisdiction-is-held
  (testing "a verify proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :receivable/verify :subject "rcv-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/verification-of db "rcv-1")) "no verification written"))))

(deftest sanctions-hit-is-held-and-unoverridable
  (testing "rcv-3's account debtor screens positive -> HOLD at verify, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :receivable/verify :subject "rcv-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:sanctions-hit} (-> (store/ledger db) first :basis)))
      (is (nil? (store/verification-of db "rcv-3"))))))

(deftest sanctions-hit-also-blocks-advance-directly-skipping-verify
  (testing "sanctions ground truth is re-checked at advance/fund too -- a receivable can never reach an actuation by skipping verify"
    (let [[db actor] (fresh)]
      (underwrite! actor "rcv-3" "t5pre")
      (let [res (exec-op actor "t5" {:op :advance/fund :subject "rcv-3"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:sanctions-hit} (-> (store/ledger db) last :basis)))))))

;; ----------------------------- status lifecycle -----------------------------

(deftest advance-without-underwrite-is-held
  (testing "advance before underwriting -> HOLD (status precondition), never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :advance/fund :subject "rcv-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:not-underwritten} (-> (store/ledger db) first :basis)))
      (is (empty? (store/advance-history db))))))

(deftest collect-without-advance-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t7" {:op :receivable/collect :subject "rcv-1"} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:not-advanced} (-> (store/ledger db) first :basis)))))

(deftest settle-without-collect-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t8" {:op :reserve/settle :subject "rcv-1"} operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:not-collected} (-> (store/ledger db) first :basis)))))

(deftest evidence-incomplete-is-held-when-underwritten-without-verify
  (testing "rcv-7 reaches :underwritten by skipping verify entirely -> advance HOLDs on evidence-incomplete alone"
    (let [[db actor] (fresh)]
      (underwrite! actor "rcv-7" "t9pre")
      (attest! actor "t9pre2")
      (let [res (exec-op actor "t9" {:op :advance/fund :subject "rcv-7"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:evidence-incomplete} (-> (store/ledger db) last :basis)))
        (is (empty? (store/advance-history db)))))))

;; ----------------------------- concentration (aggregation-based) -----------------------------

(deftest debtor-concentration-exceeded-on-underwrite-is-held-and-unoverridable
  (testing "screening op directly: rcv-1 fully advanced first (2,000,000 outstanding against debtor-1, limit 5,000,000); rcv-4 (same debtor, 3,500,000) breaches -> HOLD at underwrite itself"
    (let [[db actor] (fresh)]
      (verify! actor "rcv-1" "t10a")
      (underwrite! actor "rcv-1" "t10a")
      (attest! actor "t10a2")
      (advance! actor "rcv-1" "t10a")
      (is (= :advanced (:status (store/receivable db "rcv-1"))))
      (let [res (exec-op actor "t10" {:op :receivable/underwrite :subject "rcv-4"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:debtor-concentration-exceeded} (-> (store/ledger db) last :basis)))
        (is (nil? (store/underwriting-of db "rcv-4")))))))

(deftest funder-concentration-exceeded-in-isolation-is-held
  (testing "rcv-6 shares funder-1 with the already-advanced rcv-1 (2,000,000 + 3,000,000 face = 4,250,000 cash > funder-1's 4,000,000 capacity), but has its OWN debtor with plenty of headroom -- an isolated funder-only breach"
    (let [[db actor] (fresh)]
      (verify! actor "rcv-1" "t11a")
      (underwrite! actor "rcv-1" "t11a")
      (attest! actor "t11a2")
      (advance! actor "rcv-1" "t11a")
      (verify! actor "rcv-6" "t11b")
      (let [res (exec-op actor "t11" {:op :receivable/underwrite :subject "rcv-6"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:funder-concentration-exceeded} (-> (store/ledger db) last :basis)))
        (is (not (some #{:debtor-concentration-exceeded} (-> (store/ledger db) last :basis)))
            "isolated -- debtor-4's own limit is not implicated")))))

(deftest debtor-concentration-clears-once-the-earlier-receivable-settles
  (testing "settled receivables no longer count toward outstanding exposure -- rcv-4 clears once rcv-1's full lifecycle completes"
    (let [[_db actor] (fresh)]
      (verify! actor "rcv-1" "t12a")
      (underwrite! actor "rcv-1" "t12a")
      (attest! actor "t12a2")
      (advance! actor "rcv-1" "t12a")
      (exec-op actor "t12a-collect" {:op :receivable/collect :subject "rcv-1"} operator)
      (approve! actor "t12a-collect")
      (exec-op actor "t12a-settle" {:op :reserve/settle :subject "rcv-1"} operator)
      (approve! actor "t12a-settle")
      (verify! actor "rcv-4" "t12b")
      (let [res (exec-op actor "t12" {:op :receivable/underwrite :subject "rcv-4"} operator)]
        (is (= :interrupted (:status res)) "clears the concentration check now, escalates like any other clean underwrite")))))

;; ----------------------------- fair pricing -----------------------------

(deftest fee-rate-mismatch-is-held-and-unoverridable
  (testing "rcv-5's applied fee-rate (0.99) does not match the published tier-a schedule (0.02)"
    (let [[db actor] (fresh)]
      (verify! actor "rcv-5" "t13pre")
      (underwrite! actor "rcv-5" "t13pre")
      (attest! actor "t13pre2")
      (let [res (exec-op actor "t13" {:op :advance/fund :subject "rcv-5"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:fee-rate-mismatch} (-> (store/ledger db) last :basis)))
        (is (empty? (store/advance-history db)))))))

;; ----------------------------- solvency (anti-Zentoshin) -----------------------------

(deftest advance-without-any-solvency-attestation-is-held
  (testing "no attestation has ever been published -- an advance can never proceed on missing books, the direct anti-Zentoshin invariant"
    (let [[db actor] (fresh)]
      (verify! actor "rcv-1" "t14pre")
      (underwrite! actor "rcv-1" "t14pre")
      (let [res (exec-op actor "t14" {:op :advance/fund :subject "rcv-1"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:solvency-attestation-stale-or-missing} (-> (store/ledger db) last :basis)))
        (is (empty? (store/advance-history db)))))))

(deftest solvency-attest-escalates-and-publishes-a-ground-truth-recompute
  (let [[db actor] (fresh)
        res (exec-op actor "t15" {:op :solvency/attest :subject "book"} operator)]
    (is (= :interrupted (:status res)))
    (let [r2 (approve! actor "t15")]
      (is (= :commit (get-in r2 [:state :disposition])))
      (is (= 1 (count (store/solvency-attestation-history db))))
      (is (= true (get (store/latest-solvency-attestation db) "solvent"))))))

(deftest solvency-attestation-mismatch-is-held-and-unoverridable
  (testing "the advisor cannot publish a rosier solvency number than the ledger actually supports -- direct anti-Zentoshin mitigation"
    (let [[db _] (fresh)
          request {:op :solvency/attest :subject "book"}
          fabricated-proposal {:summary "fake" :rationale "fake" :cites [:solvency-report]
                               :effect :attestation/publish
                               :value {:outstanding-obligations 0.0 :funding-capacity 999999999.0 :solvent? true
                                      :coverage-ratio 0.0 :advanced-count 0}
                               :stake nil :confidence 0.95}
          verdict (governor/check request {} fabricated-proposal db)]
      (is (true? (:hard? verdict)))
      (is (some #{:solvency-attestation-mismatch} (mapv :rule (:violations verdict)))))))

;; ----------------------------- two-actuation happy path + double-guards -----------------------------

(deftest advance-always-escalates-then-human-decides
  (testing "a clean, underwritten, attested, fee-correct receivable STILL always interrupts for human approval -- actuation/advance-cash is never auto"
    (let [[db actor] (fresh)]
      (verify! actor "rcv-1" "t16pre")
      (underwrite! actor "rcv-1" "t16pre")
      (attest! actor "t16pre2")
      (let [r1 (exec-op actor "t16" {:op :advance/fund :subject "rcv-1"} operator)]
        (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
        (testing "approve -> commit, advance record drafted"
          (let [r2 (approve! actor "t16")]
            (is (= :commit (get-in r2 [:state :disposition])))
            (is (= :advanced (:status (store/receivable db "rcv-1"))))
            (is (= 1 (count (store/advance-history db))) "one draft advance record"))))))
  (testing "reject -> hold, nothing advanced"
    (let [[db actor] (fresh)]
      (verify! actor "rcv-1" "t17pre")
      (underwrite! actor "rcv-1" "t17pre")
      (attest! actor "t17pre2")
      (exec-op actor "t17" {:op :advance/fund :subject "rcv-1"} operator)
      (let [r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                       {:thread-id "t17" :resume? true})]
        (is (= :hold (get-in r2 [:state :disposition])))
        (is (empty? (store/advance-history db)) "nothing advanced on reject")))))

(deftest full-lifecycle-both-actuations-commit-then-double-guards-hold
  (let [[db actor] (fresh)]
    (verify! actor "rcv-1" "t18")
    (underwrite! actor "rcv-1" "t18")
    (attest! actor "t18b")
    (advance! actor "rcv-1" "t18")
    (is (= :advanced (:status (store/receivable db "rcv-1"))))

    (exec-op actor "t18-collect" {:op :receivable/collect :subject "rcv-1"} operator)
    (approve! actor "t18-collect")
    (is (= :collected (:status (store/receivable db "rcv-1"))))

    (let [r1 (exec-op actor "t18-settle" {:op :reserve/settle :subject "rcv-1"} operator)]
      (is (= :interrupted (:status r1)) "actuation/release-reserve is never auto either")
      (let [r2 (approve! actor "t18-settle")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= :settled (:status (store/receivable db "rcv-1"))))
        (is (= 1 (count (store/settlement-history db))))))

    (testing "double-advance -> HOLD, never reaches a human"
      (let [res (exec-op actor "t18-double-advance" {:op :advance/fund :subject "rcv-1"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:double-advance} (-> (store/ledger db) last :basis)))
        (is (= 1 (count (store/advance-history db))) "still only the one earlier advance")))

    (testing "double-settle -> HOLD, never reaches a human"
      (let [res (exec-op actor "t18-double-settle" {:op :reserve/settle :subject "rcv-1"} operator)]
        (is (= :hold (get-in res [:state :disposition])))
        (is (some #{:double-settle} (-> (store/ledger db) last :basis)))
        (is (= 1 (count (store/settlement-history db))) "still only the one earlier settlement")))))

;; ----------------------------- audit read op -----------------------------

(deftest audit-report-is-always-available-and-read-only
  (let [[db actor] (fresh)
        res (exec-op actor "t19" {:op :audit/report :subject "book"} operator)]
    (is (= :commit (get-in res [:state :disposition])) "reads pass through at every phase, no approval needed")
    (is (= 1 (count (store/ledger db))) "a ledger fact is still recorded, but its :effect is :noop -- no SSoT mutation")
    (is (= :submitted (:status (store/receivable db "rcv-1"))) "no receivable state was touched")))

;; ----------------------------- ledger invariant -----------------------------

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :receivable/intake :subject "rcv-1"
                          :patch {:id "rcv-1" :fee-rate 0.02}} operator)
      (exec-op actor "b" {:op :receivable/verify :subject "rcv-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
