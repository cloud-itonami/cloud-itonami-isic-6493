(ns factoring.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:advance/fund` and `:reserve/settle` must NEVER be a
  member of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [factoring.phase :as phase]))

(deftest advance-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in future entries, auto-commits a real cash advance"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :advance/fund))
          (str "phase " n " must not auto-commit :advance/fund")))))

(deftest settle-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-commits a real reserve release"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :reserve/settle))
          (str "phase " n " must not auto-commit :reserve/settle")))))

(deftest solvency-attest-never-auto-at-any-phase
  (testing "publishing a solvency attestation is a genuine decision, not just data normalization -- never auto-eligible even though it moves no capital"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :solvency/attest))
          (str "phase " n " must not auto-commit :solvency/attest")))))

(deftest verify-underwrite-collect-never-auto-at-any-phase
  (doseq [op [:receivable/verify :receivable/underwrite :receivable/collect]
          [n {:keys [auto]}] phase/phases]
    (is (not (contains? auto op)) (str "phase " n " must not auto-commit " op))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":receivable/intake moves no capital -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:receivable/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :receivable/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :advance/fund} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :reserve/settle} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :solvency/attest} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :receivable/intake} :commit)))))

(deftest gate-passes-reads-through-every-disposition
  (is (= :commit (:disposition (phase/gate 3 {:op :audit/report} :commit))))
  (is (= :hold (:disposition (phase/gate 3 {:op :audit/report} :hold)))))

(deftest gate-normalizes-an-out-of-range-phase-to-default
  (is (= :commit (:disposition (phase/gate 99 {:op :receivable/intake} :commit)))))
