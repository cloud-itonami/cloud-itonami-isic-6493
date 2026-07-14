(ns factoring.portable-cljs-test-runner
  "PRIMARY automated quality gate for this actor under a real
  ClojureScript host (cljs.main --target node) — the same runtime-
  priority rule as this superproject's CLAUDE.md and `credit.
  portable-cljs-test-runner`'s (`cloud-itonami-isic-6492`) own
  precedent:

      kotoba wasm runtime  >  clojurewasm  >  ClojureScript  >  nbb
      (JVM / babashka are last-resort compat, not the design target)

  The factoring test suite is portable .cljc and runs UNCHANGED here
  and on the JVM (`clojure -M:dev:test`, secondary compat gate). This
  includes `factoring.store-contract-test`, which exercises the
  langchain.db Datomic-API-compatible store — the kotoba-server /
  kotobase datom seam — under ClojureScript.

  Unlike `credit.portable-cljs-test-runner` (`6492`), this R0
  deliberately does NOT extract a `factoring.kernels.gate` safety-
  kernel layer (the `.kotoba`/WASM-subset decision core `credit.
  governor`/`credit.phase` and `vcfund.governor`/`vcfund.phase` use,
  ADR-2607101200) -- `factoring.governor`/`factoring.phase` decide
  directly in idiomatic `.cljc`, the same shape most of this fleet's
  actors use. See this repo's `docs/adr/0001-architecture.md`
  Alternatives table for why that extraction is deferred rather than
  ported in this R0.

  Invoke from the repo root (the :test alias's :main-opts would steal
  -m if combined, hence -Sdeps for the extra path):

    clojure -Sdeps '{:paths [\"src\" \"test\"]}' \\
      -M:dev:cljs -m cljs.main --target node \\
      -m factoring.portable-cljs-test-runner"
  (:require [clojure.test :as t :refer [run-tests]]
            [factoring.facts-test]
            [factoring.governor-contract-test]
            [factoring.phase-test]
            [factoring.registry-test]
            [factoring.store-contract-test]))

#?(:cljs
   (defmethod t/report [:cljs.test/default :end-run-tests] [m]
     (when-not (t/successful? m)
       (set! (.-exitCode js/process) 1))))

(defn -main []
  (run-tests 'factoring.facts-test
             'factoring.registry-test
             'factoring.phase-test
             'factoring.governor-contract-test
             'factoring.store-contract-test))
