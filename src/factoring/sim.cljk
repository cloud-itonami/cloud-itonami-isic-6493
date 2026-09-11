(ns factoring.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean receivable
  through intake -> verify -> underwrite -> a solvency-attestation-
  gated cash advance (**actuation 1**) -> collect -> reserve
  settlement (**actuation 2**), then a solvency attestation publish,
  then SEVEN HARD-hold cases that never reach a human at all (stale/
  missing solvency attestation, debtor+funder concentration co-firing,
  an isolated funder-concentration breach, an isolated fee-rate-
  schedule mismatch, spec-basis, sanctions-hit, and evidence-
  incomplete), then double-advance and double-settle attempts on the
  already-completed receivable, and prints the audit ledger + the
  draft advance/settlement/attestation records."
  (:require [langgraph.graph :as g]
            [factoring.store :as store]
            [factoring.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :factor :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== receivable/intake rcv-1 (auto-commits, no capital risk) ==")
    (println (exec! actor "t1" {:op :receivable/intake :subject "rcv-1"
                                :patch {:id "rcv-1"}} operator))

    (println "== receivable/verify rcv-1 (JPN, clean; escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :receivable/verify :subject "rcv-1"} operator))
    (println (approve! actor "t2"))

    (println "== receivable/underwrite rcv-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :receivable/underwrite :subject "rcv-1"} operator))
    (println (approve! actor "t3"))

    (println "== advance/fund rcv-1 attempt #1 -- NO solvency attestation published yet -> HARD hold, never reaches a human ==")
    (println (exec! actor "t4" {:op :advance/fund :subject "rcv-1"} operator))

    (println "== solvency/attest -- ground-truth recompute (anti-Zentoshin); escalates -- human approves ==")
    (println (exec! actor "t5" {:op :solvency/attest :subject "book"} operator))
    (println (approve! actor "t5"))

    (println "== advance/fund rcv-1 attempt #2 -- fresh attestation on file -> escalates (actuation) -- human approves ==")
    (println (exec! actor "t6" {:op :advance/fund :subject "rcv-1"} operator))
    (println (approve! actor "t6"))

    (println "== receivable/verify + underwrite rcv-4 (SAME debtor as rcv-1, still outstanding) ==")
    (println (exec! actor "t7" {:op :receivable/verify :subject "rcv-4"} operator))
    (println (approve! actor "t7"))
    (println "== receivable/underwrite rcv-4 -> debtor-concentration AND funder-concentration co-fire -> HARD hold, never reaches a human ==")
    (println (exec! actor "t8" {:op :receivable/underwrite :subject "rcv-4"} operator))

    (println "== receivable/verify + underwrite rcv-6 (different debtor, SAME funder as rcv-1) ==")
    (println (exec! actor "t9" {:op :receivable/verify :subject "rcv-6"} operator))
    (println (approve! actor "t9"))
    (println "== receivable/underwrite rcv-6 -> ISOLATED funder-concentration-exceeded -> HARD hold ==")
    (println (exec! actor "t10" {:op :receivable/underwrite :subject "rcv-6"} operator))

    (println "== receivable/verify + underwrite rcv-5 (clean) ==")
    (println (exec! actor "t11" {:op :receivable/verify :subject "rcv-5"} operator))
    (println (approve! actor "t11"))
    (println (exec! actor "t12" {:op :receivable/underwrite :subject "rcv-5"} operator))
    (println (approve! actor "t12"))
    (println "== advance/fund rcv-5 -> ISOLATED fee-rate-mismatch (0.99 vs published tier-a 0.02) -> HARD hold ==")
    (println (exec! actor "t13" {:op :advance/fund :subject "rcv-5"} operator))

    (println "== receivable/verify rcv-3 -> debtor-sanctions-hit -> HARD hold, never reaches a human ==")
    (println (exec! actor "t14" {:op :receivable/verify :subject "rcv-3"} operator))

    (println "== receivable/verify rcv-2 (no official spec-basis for its jurisdiction) -> HARD hold ==")
    (println (exec! actor "t15" {:op :receivable/verify :subject "rcv-2" :no-spec? true} operator))

    (println "== receivable/underwrite rcv-7 WITHOUT verify (clean concentration/pricing) -> escalates -- human approves ==")
    (println (exec! actor "t16" {:op :receivable/underwrite :subject "rcv-7"} operator))
    (println (approve! actor "t16"))
    (println "== advance/fund rcv-7 -> ISOLATED evidence-incomplete (verify never ran) -> HARD hold ==")
    (println (exec! actor "t17" {:op :advance/fund :subject "rcv-7"} operator))

    (println "== receivable/collect rcv-1 (escalates -- human approves) ==")
    (println (exec! actor "t18" {:op :receivable/collect :subject "rcv-1"} operator))
    (println (approve! actor "t18"))

    (println "== reserve/settle rcv-1 -- actuation 2 -- escalates -- human approves ==")
    (println (exec! actor "t19" {:op :reserve/settle :subject "rcv-1"} operator))
    (println (approve! actor "t19"))

    (println "== advance/fund rcv-1 AGAIN (double-advance of an already-completed receivable) -> HARD hold ==")
    (println (exec! actor "t20" {:op :advance/fund :subject "rcv-1"} operator))

    (println "== reserve/settle rcv-1 AGAIN (double-settle) -> HARD hold ==")
    (println (exec! actor "t21" {:op :reserve/settle :subject "rcv-1"} operator))

    (println "== audit/report (read-only, always available) ==")
    (println (exec! actor "t22" {:op :audit/report :subject "book"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft advance records ==")
    (doseq [r (store/advance-history db)] (println r))

    (println "== draft settlement records ==")
    (doseq [r (store/settlement-history db)] (println r))

    (println "== solvency attestation history (publicly queryable) ==")
    (doseq [r (store/solvency-attestation-history db)] (println r))))
