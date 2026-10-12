(ns claudiom.cli-test
  (:require [cljs.test :refer-macros [deftest is]]
            [claudiom.cli :as cli]))

(deftest bootstrap-is-explicitly-disabled
  (is (= {:name "claudiom" :version "0.0.0"
          :enabled false :implemented false
          :message "Bootstrap only; memory generation is not implemented."}
         (cli/status "0.0.0"))))
