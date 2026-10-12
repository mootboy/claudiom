(ns claudiom.store-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [claudiom.store :as store]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def event {:version 1 :event_id "empty-1" :session_id "synthetic"
            :recorded_at "2026-10-12T12:00:00Z" :kind "generation"
            :coverage {:lane "observation" :input_id "parent-1" :from 0 :to 100}
            :memories []})

(defn private-state [f]
  (let [root (fs/mkdtempSync (path/join (os/tmpdir) "claudiom-store-"))
        old (.-XDG_DATA_HOME js/process.env)]
    (set! (.-XDG_DATA_HOME js/process.env) root)
    (try (f root)
         (finally
           (if (some? old) (set! (.-XDG_DATA_HOME js/process.env) old)
               (js/Reflect.deleteProperty js/process.env "XDG_DATA_HOME"))
           (fs/rmSync root #js {:recursive true :force true})))))

(deftest empty-commit-restart-and-replay
  (private-state
   (fn [_]
     (let [p (store/log-path "synthetic")]
       (is (= {} (:coverage (:state (store/read-log p)))))
       (is (not (fs/existsSync (store/root))))
       (store/append! event)
       (let [before (fs/readFileSync p "utf8")]
         (is (= {["observation" "parent-1"] 100} (:coverage (:state (store/read-log p)))))
         (store/append! event)
         (is (= before (fs/readFileSync p "utf8")))
         (is (= 384 (bit-and 511 (.-mode (fs/statSync p)))))
         (is (= 448 (bit-and 511 (.-mode (fs/statSync (path/dirname p)))))))))))

(deftest failed-commits-do-not-change-coverage
  (private-state
   (fn [_]
     (store/append! event)
     (let [p (store/log-path "synthetic") before (fs/readFileSync p "utf8")]
       (doseq [bad [(assoc event :recorded_at "invalid")
                    (assoc event :unknown true)
                    (assoc event :memories [{:id "not-supported-yet"}])
                    (assoc-in event [:coverage :to] 200)
                    (-> event (assoc :event_id "gap") (assoc-in [:coverage :from] 101))]]
         (testing (str "invalid commit " (:event_id bad))
           (is (thrown? js/Error (store/append! bad)))
           (is (= before (fs/readFileSync p "utf8")))))))))

(deftest tail-repair-and-corruption
  (private-state
   (fn [_]
     (store/append! event)
     (let [p (store/log-path "synthetic") before (fs/readFileSync p "utf8")]
       (fs/appendFileSync p "{\"unfinished\":")
       (is (= {["observation" "parent-1"] 100} (:coverage (:state (store/read-log p)))))
       (is (> (.-size (fs/statSync p)) (js/Buffer.byteLength before)))
       (store/append! event)
       (is (= before (fs/readFileSync p "utf8")))
       (fs/appendFileSync p "not-json\n")
       (is (thrown? js/Error (store/read-log p)))
       (is (thrown? js/Error (store/append! event)))))))

(deftest contiguous-independent-progress
  (private-state
   (fn [_]
     (store/append! event)
     (store/append! (-> event (assoc :event_id "empty-2")
                        (assoc :coverage {:lane "observation" :input_id "parent-1" :from 100 :to 200})))
     (store/append! (-> event (assoc :event_id "reflection-empty")
                        (assoc :coverage {:lane "reflection" :input_id "feed-1" :from 0 :to 1})))
     (is (= {["observation" "parent-1"] 200 ["reflection" "feed-1"] 1}
            (:coverage (:state (store/read-log (store/log-path "synthetic")))))))))

(def observation
  {:id "observation-1" :kind "observation" :text "Synthetic decision." :source_time nil
   :citations [{:stream "parent" :agent_id nil :source_id nil :path "/synthetic/source.jsonl"
                :locator {:input_id "parent-1" :start 0 :end 100 :sha256 (apply str (repeat 64 "a"))}}]})

(defn lifecycle-event [id kind fields]
  (merge (select-keys event [:version :session_id :recorded_at])
         {:event_id id :kind kind} fields))

(deftest memory-lifecycle-and-independent-recall
  (private-state
   (fn [_]
     (store/append! (assoc event :memories [observation]))
     (store/append! (assoc event :event_id "reflection-1"
                          :coverage {:lane "reflection" :input_id "feed-1" :from 0 :to 1}
                          :memories [{:id "reflection-1" :kind "reflection" :text "Synthetic inference."
                                      :source_time nil :supports ["observation-1"]}]))
     (is (= 2 (count (store/list-memories nil))))
     (store/append! (lifecycle-event "drop-1" "drop" {:memory_id "observation-1"}))
     (is (= ["reflection-1"] (mapv :id (store/list-memories nil))))
     (is (= "dropped" (:status (first (:supporting_observations (store/recall "reflection-1" nil))))))
     (is (= "not_checked" (:source_status (first (:citations (store/recall "observation-1" nil))))))
     (store/append! (assoc event :event_id "correction-definition"
                          :coverage {:lane "observation" :input_id "parent-1" :from 100 :to 200}
                          :memories [(assoc observation :id "observation-2" :text "Corrected synthetic decision.")]))
     (store/append! (lifecycle-event "correction" "supersede"
                                    {:memory_id "observation-1" :replacement_id "observation-2" :reason "Synthetic correction."}))
     (is (= "support_changed" (:status (store/recall "reflection-1" nil))))
     (is (= ["observation-2"] (mapv :id (store/list-memories nil))))
     (is (thrown? js/Error (store/append! (lifecycle-event "cycle" "supersede"
                                         {:memory_id "observation-2" :replacement_id "observation-1" :reason "Cycle."})))))))

(deftest drop-before-definition-and-invalid-support
  (private-state
   (fn [_]
     (store/append! (lifecycle-event "early-drop" "drop" {:memory_id "observation-1"}))
     (is (nil? (store/recall "observation-1" nil)))
     (store/append! (assoc event :memories [observation]))
     (is (= [] (store/list-memories nil)))
     (is (= "dropped" (:status (store/recall "observation-1" nil))))
     (let [p (store/log-path "synthetic") before (fs/readFileSync p "utf8")]
       (is (thrown? js/Error
                    (store/append! (assoc event :event_id "invalid-reflection"
                                          :coverage {:lane "reflection" :input_id "feed-1" :from 0 :to 1}
                                          :memories [{:id "unsupported" :kind "reflection" :text "Invalid."
                                                      :source_time nil :supports ["missing"]}]))))
       (is (= before (fs/readFileSync p "utf8")))))))

(deftest malformed-frames-and-bounds
  (doseq [suffix ["\n" "{}\n" "{\"version\":1,\"version\":1}\n"
                  "{\"a\":{\"x\":1,\"\\u0078\":2}}\n" "[]\n"]]
    (private-state
     (fn [_]
       (store/append! event)
       (fs/appendFileSync (store/log-path "synthetic") suffix)
       (is (thrown? js/Error (store/read-log (store/log-path "synthetic")))))))
  (private-state
   (fn [_]
     (store/append! event)
     (let [p (store/log-path "synthetic")]
       (fs/appendFileSync p (js/Buffer.from #js [255 10]))
       (is (thrown? js/Error (store/read-log p))))))
  (private-state
   (fn [_]
     (is (thrown? js/Error (store/append! (assoc event :memories [(assoc observation :text (apply str (repeat 16385 "x")))])))))))

(deftest short-writes-and-uncertain-acknowledgement
  (private-state
   (fn [_]
     (with-redefs [store/write-chunk! (fn [fd bytes offset position]
                                        (fs/writeSync fd bytes offset (min 7 (- (.-length bytes) offset)) position))]
       (store/append! event))
     (is (= 100 (get-in (store/read-log (store/log-path "synthetic")) [:state :coverage ["observation" "parent-1"]])))))
  (private-state
   (fn [_]
     (let [p (store/log-path "synthetic")]
       (with-redefs [store/sync! (fn [_] (throw (js/Error. "Synthetic acknowledgement loss.")))]
         (is (thrown? js/Error (store/append! event))))
       ;; A complete frame survives an uncertain acknowledgement: replay must
       ;; recover it, not append a second logical generation.
       (let [before (fs/readFileSync p "utf8")]
         (store/append! event)
         (is (= before (fs/readFileSync p "utf8"))))))))

(deftest interrupted-write-never-covers-source
  (private-state
   (fn [_]
     (with-redefs [store/write-chunk! (fn [fd bytes offset position]
                                      (if (zero? offset) (fs/writeSync fd bytes offset 10 position)
                                          (throw (js/Error. "Synthetic interrupted write."))))]
       (is (thrown? js/Error (store/append! event))))
     (is (= {} (:coverage (:state (store/read-log (store/log-path "synthetic"))))))
     (store/append! event)
     (is (= 1 (count (:events (store/read-log (store/log-path "synthetic")))))))))

(deftest cross-session-memory-identity-conflicts
  (private-state
   (fn [_]
     (store/append! (assoc event :memories [observation]))
     (is (thrown? js/Error (store/append! (assoc event :session_id "other" :memories [observation]))))
     (is (= ["observation-1"] (mapv :id (store/list-memories nil)))))))

(deftest unsafe-storage-is-rejected
  (doseq [scenario [:relative :empty :symlink :permissions :directory-log :dangling]]
    (private-state
     (fn [root]
       (case scenario
         :relative (set! (.-XDG_DATA_HOME js/process.env) "relative")
         :empty (set! (.-XDG_DATA_HOME js/process.env) "")
         :symlink (fs/symlinkSync root (path/join root "claudiom"))
         :dangling (fs/symlinkSync (path/join root "missing") (path/join root "claudiom"))
         :permissions (do (store/append! event) (fs/chmodSync (store/log-path "synthetic") 420))
         :directory-log (do (store/append! event) (fs/unlinkSync (store/log-path "synthetic"))
                            (fs/mkdirSync (store/log-path "synthetic") #js {:mode 448})))
       (testing (name scenario) (is (thrown? js/Error (store/append! event))))))))
