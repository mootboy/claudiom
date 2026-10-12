(ns claudiom.cli-test
  (:require [cljs.test :refer-macros [deftest is]]
            [claudiom.cli :as cli]))

(deftest generation-is-explicitly-disabled
  (is (= {:name "claudiom" :version "0.0.0"
          :enabled false :implemented true
          :message "Native persistence and retrieval available; generation is not implemented."}
         (cli/status "0.0.0"))))
