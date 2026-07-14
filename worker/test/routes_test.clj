(ns routes-test
  "JVM tests for the pure `routes/handle` HTTP-API contract -- run against
  `factoring.store/seed-db` (MemStore), the SAME reference the deployed
  Worker's KV-hydrated MemStore satisfies. `.clj` (not `.cljc`): this
  suite is JVM-only, mirroring `factoring.portable-cljs-test-runner`'s
  own precedent of keeping non-portable test concerns out of the
  primary cljs-portable suite -- this one is JVM-only because it is
  never required to run under the cljs REFERENCE test path at all (the
  `routes.cljc` code under test IS already cljs-portable and IS what
  the Worker runs; only this particular JVM test-runner harness is not
  duplicated under cljs, since the deployed Worker's actual request
  path is exercised by the live smoke test instead, not a second cljs
  unit-test run)."
  (:require [clojure.test :refer [deftest is testing]]
            [factoring.store :as store]
            [routes :as routes]))

(defn- req
  ([method path] (req method path nil nil))
  ([method path query] (req method path query nil))
  ([method path query body]
   {:method method :path path :query (or query {}) :body body}))

(deftest health-is-public-and-clean
  (let [s (store/seed-db)
        r (routes/handle s (req :get "/health"))]
    (is (= 200 (:status r)))
    (is (true? (:ok (:body r))))))

(deftest fee-schedule-is-the-published-registry-constant
  (let [s (store/seed-db)
        r (routes/handle s (req :get "/fee-schedule"))]
    (is (= 200 (:status r)))
    (is (= :v1 (:version (:body r))))
    (is (= 0.02 (get-in r [:body :tiers :tier-a])))))

(deftest solvency-attestation-404s-before-any-publish
  (let [s (store/seed-db)
        r (routes/handle s (req :get "/solvency/attestation"))]
    (is (= 404 (:status r)))))

(deftest solvency-attest-publishes-and-then-attestation-endpoint-sees-it
  (let [s (store/seed-db)
        r1 (routes/handle s (req :post "/solvency/attest"))]
    (is (= 200 (:status r1)) "clean advisor recompute matches the governor's independent recompute -- commits")
    (is (= :commit (:disposition (:body r1))))
    (let [r2 (routes/handle s (req :get "/solvency/attestation"))]
      (is (= 200 (:status r2)))
      (is (true? (get (:body r2) "solvent"))))))

(deftest funders-list-is-public-and-seeded
  (let [s (store/seed-db)
        r (routes/handle s (req :get "/funders"))]
    (is (= 200 (:status r)))
    (is (= 3 (count (:body r))))))

(deftest funder-registration-is-admin-not-governed
  (let [s (store/seed-db)
        r (routes/handle s (req :post "/funders" {} {:id "funder-9" :name "New Capital" :funding-capacity 250000}))]
    (is (= 201 (:status r)))
    (is (= "New Capital" (:name (store/funder s "funder-9"))))))

(deftest funder-registration-rejects-incomplete-body
  (let [s (store/seed-db)
        r (routes/handle s (req :post "/funders" {} {:id "funder-9"}))]
    (is (= 400 (:status r)))))

(deftest receivables-list-and-get
  (let [s (store/seed-db)]
    (is (= 7 (count (:body (routes/handle s (req :get "/receivables"))))))
    (is (= 200 (:status (routes/handle s (req :get "/receivables/rcv-1")))))
    (is (= 404 (:status (routes/handle s (req :get "/receivables/nope")))))))

(deftest intake-coerces-json-string-risk-tier-to-a-keyword
  (testing "found live via this deployment's own smoke test: a JSON body's :debtor-risk-tier
            arrives as a STRING (JSON has no keyword type); without coercion, fee-rate-mismatch
            would spuriously fire forever because the schedule lookup is keyword-keyed"
    (let [s (store/seed-db)
          corp-evidence [{:kind "name-and-registered-office-declaration" :ref "d1"}
                        {:kind "registry-information-lookup" :ref "r1" :fields-confirmed ["name" "registered-office"]}]]
      (routes/handle s (req :post "/receivables" {}
                            {:id "json-intake-1" :client-id "c" :client-name "n" :debtor-id "d"
                             :debtor-name "dn" :debtor-credit-limit 1000000
                             :debtor-risk-tier "tier-a" :fee-rate 0.02 :face-amount 100000
                             :jurisdiction "JPN" :funder-id "funder-1" :currency "JPY" :client-bic "GRLFJPJTXXX"
                             :client-address "1 Main St" :client-subject-type "corporate"
                             :client-ekyc-method "corp-ro" :client-ekyc-evidence corp-evidence
                             :debtor-address "2 Side St" :debtor-subject-type "corporate"
                             :debtor-ekyc-method "corp-ro" :debtor-ekyc-evidence corp-evidence
                             :client-sanctions-hit? false :debtor-sanctions-hit? false}))
      (is (= :tier-a (:debtor-risk-tier (store/receivable s "json-intake-1"))) "coerced to a keyword, not left as a string")
      (routes/handle s (req :post "/receivables/json-intake-1/verify"))
      (routes/handle s (req :post "/receivables/json-intake-1/underwrite"))
      (routes/handle s (req :post "/solvency/attest"))
      (let [r (routes/handle s (req :post "/receivables/json-intake-1/advance"))]
        (is (= 200 (:status r)) "fee-rate-mismatch does not spuriously fire once the tier is a real keyword")))))

(deftest intake-auto-commits-no-approval-round-trip-needed
  (let [s (store/seed-db)
        r (routes/handle s (req :post "/receivables" {} {:id "rcv-1" :fee-rate 0.021}))]
    (is (= 200 (:status r)))
    (is (= :commit (:disposition (:body r))))
    (is (= 0.021 (:fee-rate (store/receivable s "rcv-1"))))))

(deftest verify-escalates-then-auto-approves-via-the-authenticated-caller
  (let [s (store/seed-db)
        r (routes/handle s (req :post "/receivables/rcv-1/verify"))]
    (is (= 200 (:status r)) "the escalate->auto-approve round trip settles inside ONE HTTP call")
    (is (= :commit (:disposition (:body r))))
    (is (some? (store/verification-of s "rcv-1")))))

(deftest fabricated-jurisdiction-still-hard-holds-through-the-http-layer
  (testing "rcv-2's own seeded jurisdiction (\"ATL\") has no spec-basis -- note the HTTP verify/underwrite/
            advance/collect/settle endpoints deliberately do NOT thread the POST body into the operation
            request (see `op!`'s call sites in routes.cljc): none of those ops need caller-supplied fields
            beyond the URL's :subject in production, and `factoring.factoringllm/propose-verification`'s
            :no-spec? is a test-only jurisdiction-forcing hook this API does not expose"
    (let [s (store/seed-db)
          r (routes/handle s (req :post "/receivables/rcv-2/verify"))]
      (is (= 409 (:status r)))
      (is (= :hold (:disposition (:body r))))
      (is (some #{:no-spec-basis} (mapv :rule (:violations (:body r))))))))

(deftest sanctions-hit-still-hard-holds-through-the-http-layer
  (let [s (store/seed-db)
        r (routes/handle s (req :post "/receivables/rcv-3/verify"))]
    (is (= 409 (:status r)))
    (is (some #{:sanctions-hit} (mapv :rule (:violations (:body r)))))))

(deftest full-two-actuation-lifecycle-through-the-http-api
  (let [s (store/seed-db)]
    (is (= 200 (:status (routes/handle s (req :post "/receivables/rcv-1/verify")))))
    (is (= 200 (:status (routes/handle s (req :post "/receivables/rcv-1/underwrite")))))
    (is (= 200 (:status (routes/handle s (req :post "/solvency/attest")))))
    (testing "actuation 1: advance -- the HTTP response carries the REAL settlement-instruction artifact"
      (let [r (routes/handle s (req :post "/receivables/rcv-1/advance"))]
        (is (= 200 (:status r)))
        (is (= :advanced (:status (:receivable (:body r)))))
        (is (= "swift-mt103" (get-in r [:body :settlement-instruction "rail"]))
            "rcv-1 is JPY -- auto-routed to MT103, and the caller can SEE it in the response")))
    (testing "actuation 1 cannot be repeated"
      (let [r (routes/handle s (req :post "/receivables/rcv-1/advance"))]
        (is (= 409 (:status r)))
        (is (some #{:double-advance} (mapv :rule (:violations (:body r)))))))
    (is (= 200 (:status (routes/handle s (req :post "/receivables/rcv-1/collect")))))
    (testing "actuation 2: settle"
      (let [r (routes/handle s (req :post "/receivables/rcv-1/settle"))]
        (is (= 200 (:status r)))
        (is (= :settled (:status (:receivable (:body r)))))))
    (testing "audit ledger recorded every step of this lifecycle, holds included"
      (let [r (routes/handle s (req :get "/ledger"))]
        (is (= 7 (count (:body r)))
            "verify+underwrite+attest+advance+collect+settle = 6 commits, plus the double-advance HOLD = 7")))))

(deftest not-found-for-unknown-route
  (let [s (store/seed-db)
        r (routes/handle s (req :get "/nope"))]
    (is (= 404 (:status r)))))

;; ----------------------------- real capability-library integration (kotoba.ekyc / kotoba.swift / kotoba.banking.api) -----------------------------

(deftest settlement-account-admin-endpoints
  (let [s (store/empty-db)]
    (is (= 404 (:status (routes/handle s (req :get "/settlement-account")))) "unconfigured by default")
    (let [r (routes/handle s (req :post "/settlement-account" {}
                                  {:iban "DE89370400440532013000" :bic "FCTRDEFFXXX"
                                   :account-holder-name "Demo Factor"}))]
      (is (= 201 (:status r))))
    (let [r (routes/handle s (req :get "/settlement-account"))]
      (is (= 200 (:status r)))
      (is (= "FCTRDEFFXXX" (:bic (:body r)))))))

(deftest verify-response-carries-a-real-ekyc-record
  (let [s (store/seed-db)
        r (routes/handle s (req :post "/receivables/rcv-1/verify"))
        ekyc (get-in r [:body :record :payload :ekyc])]
    (is (= 200 (:status r)))
    (is (= :pass (:ekyc.result/disposition (:client ekyc))))
    (is (= :pass (:ekyc.result/disposition (:debtor ekyc))))))

(deftest unrecognized-ekyc-method-hard-holds-through-the-http-layer-even-as-a-json-string
  (testing "a JSON-submitted (string) client-ekyc-method that isn't a real 犯収法 method -> HARD hold via the HTTP API"
    (let [s (store/seed-db)]
      (routes/handle s (req :post "/receivables" {} {:id "rcv-1" :client-ekyc-method "not-a-real-method"}))
      (let [r (routes/handle s (req :post "/receivables/rcv-1/verify"))]
        (is (= 409 (:status r)))
        (is (some #{:ekyc-verification-invalid} (mapv :rule (:violations (:body r)))))))))
