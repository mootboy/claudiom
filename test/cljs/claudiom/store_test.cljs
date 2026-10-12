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
       (let [tailed (fs/readFileSync p "utf8")]
         (is (:repair_needed (store/read-log p)))
         (is (thrown? js/Error (store/append! event)))
         (is (= tailed (fs/readFileSync p "utf8")))
         (is (= {:repaired true :removed_bytes 14} (store/repair! "synthetic"))))
       (is (= before (fs/readFileSync p "utf8")))
       (store/append! event)
       (is (= {:repaired false :removed_bytes 0} (store/repair! "synthetic")))
       (fs/appendFileSync p "not-json\n")
       (is (thrown? js/Error (store/read-log p)))
       (is (thrown? js/Error (store/append! event)))
       (is (thrown? js/Error (store/repair! "synthetic")))))))

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
   :citations [{:citation_version 1 :stream "parent" :agent_id nil :source_id nil :path "/synthetic/source.jsonl"
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
     (is (= ["reflection-1"] (mapv :local_id (store/list-memories nil))))
     (is (= "dropped" (:status (first (:supporting_observations (store/recall "reflection-1" "synthetic"))))))
     (is (= "not_checked" (:source_status (first (:citations (store/recall "observation-1" "synthetic"))))))
     (store/append! (assoc event :event_id "correction-definition"
                          :coverage {:lane "observation" :input_id "parent-1" :from 100 :to 200}
                          :memories [(assoc observation :id "observation-2" :text "Corrected synthetic decision.")]))
     (store/append! (lifecycle-event "correction" "supersede"
                                    {:memory_id "observation-1" :replacement_id "observation-2" :reason "Synthetic correction."}))
     (is (= "support_changed" (:status (store/recall "reflection-1" "synthetic"))))
     (is (= "urn:claudiom:synthetic:observation-2"
            (get-in (store/recall "observation-1" "synthetic") [:supersession :replacement_id])))
     (is (= ["observation-2"] (mapv :local_id (store/list-memories nil))))
     (is (thrown? js/Error (store/append! (lifecycle-event "cycle" "supersede"
                                         {:memory_id "observation-2" :replacement_id "observation-1" :reason "Cycle."})))))))

(deftest drop-before-definition-and-invalid-support
  (private-state
   (fn [_]
     (store/append! (lifecycle-event "early-drop" "drop" {:memory_id "observation-1"}))
     (is (nil? (store/recall "observation-1" "synthetic")))
     (store/append! (assoc event :memories [observation]))
     (is (= [] (store/list-memories nil)))
     (is (= "dropped" (:status (store/recall "observation-1" "synthetic"))))
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
     (is (thrown? js/Error (store/append! event)))
     (store/repair! "synthetic")
     (store/append! event)
     (is (= 1 (count (:events (store/read-log (store/log-path "synthetic")))))))))

(deftest session-qualified-memory-identities
  (private-state
   (fn [_]
     (doseq [session ["synthetic" "other" "session:/% é"]]
       (store/append! (assoc event :session_id session :memories [observation]))
       (let [urn (store/memory-urn session "observation-1")]
         (is (= urn (:id (store/recall urn nil))))
         (is (= session (:session_id (store/recall urn nil))))))
     (is (= 3 (count (set (map :id (store/list-memories nil))))))
     (is (thrown? js/Error (store/recall "observation-1" nil)))
     (is (thrown? js/Error (store/recall (store/memory-urn "synthetic" "observation-1") "other")))
     (is (= "observation-1" (:local_id (store/recall "observation-1" "synthetic"))))
     (is (thrown? js/Error (store/append! (assoc event :event_id "conflicting-definition"
                                               :coverage {:lane "observation" :input_id "parent-1" :from 100 :to 200}
                                               :memories [(assoc observation :text "Conflicting.")])))))))

(deftest urn-encoding-and-validation
  (private-state
   (fn [_]
     (let [session "session:/% é" id "memory:/% !'()*~ é"
           urn (store/memory-urn session id)]
       (store/append! (assoc event :session_id session :memories [(assoc observation :id id)]))
       (is (= "urn:claudiom:session%3A%2F%25%20%C3%A9:memory%3A%2F%25%20%21%27%28%29%2A~%20%C3%A9" urn))
       (is (= id (:local_id (store/recall urn nil))))
       (doseq [bad ["urn:claudiom::x" "urn:claudiom:s:" "urn:claudiom:s:x:y"
                    "urn:claudiom:s:%" "urn:claudiom:s:%FF" "urn:claudiom:s:%78"
                    "urn:claudiom:s:%2f" "urn:claudiom:s:x?query" "urn:claudiom:s:x#fragment"
                    "urn:other:s:x" (str "urn:claudiom:s:" (apply str (repeat 1600 "x")))]]
         (is (thrown? js/Error (store/recall bad nil)))))
     (let [session (apply str (repeat 128 "é")) id (apply str (repeat 256 ":"))
           urn (store/memory-urn session id)]
       (store/append! (assoc event :session_id session :memories [(assoc observation :id id)]))
       (is (= 1550 (count urn)))
       (is (= id (:local_id (store/recall urn nil))))))))

(deftest timestamps-must-not-normalize-invalid-dates
  (doseq [timestamp ["2026-02-30T12:00:00Z" "2026-10-12T24:00:00Z" "2026-13-12T12:00:00Z"]]
    (private-state
     (fn [_]
       (is (thrown? js/Error (store/append! (assoc event :recorded_at timestamp))))
       (is (not (fs/existsSync (store/root))))))))

(deftest session-limit-rejects-new-session-before-writing
  (private-state
   (fn [_]
     (store/ensure-path! (store/root) true)
     (doseq [n (range 256)]
       (fs/mkdirSync (path/dirname (store/log-path (str "session-" n))) #js {:mode 448}))
     (is (thrown? js/Error (store/append! event)))
     (is (not (fs/existsSync (store/log-path "synthetic"))))
     ;; The limit must not prevent progress in an existing session directory.
     (store/append! (assoc event :session_id "session-0"))
     (is (= 100 (get-in (store/read-log (store/log-path "session-0")) [:state :coverage ["observation" "parent-1"]]))))))

(deftest utf8-item-and-locator-boundaries
  (doseq [[label accepted rejected]
          [["identifier bytes" (assoc observation :id (apply str (repeat 128 "é")))
            (assoc observation :id (apply str (repeat 129 "é")))]
           ["text bytes" (assoc observation :text (apply str (repeat 8192 "é")))
            (assoc observation :text (apply str (repeat 8193 "é")))]
           ["locator bytes" (assoc-in observation [:citations 0 :path] (str "/" (apply str (repeat 4095 "x"))))
            (assoc-in observation [:citations 0 :path] (str "/" (apply str (repeat 4096 "x"))))]
           ["citation count" (assoc observation :citations (vec (repeat 256 (first (:citations observation)))))
            (assoc observation :citations (vec (repeat 257 (first (:citations observation)))))]
           ["subagent attribution" (-> observation (assoc-in [:citations 0 :stream] "subagent")
                                       (assoc-in [:citations 0 :agent_id] "synthetic-agent")
                                       (assoc-in [:citations 0 :source_id] "synthetic-source-id"))
            (assoc-in observation [:citations 0 :stream] "subagent")]]]
    (private-state
     (fn [_]
       (testing label
         (is (thrown? js/Error (store/append! (assoc event :memories [rejected]))))
         (is (not (fs/existsSync (store/root))))
         (store/append! (assoc event :memories [accepted]))
         (is (= (:citations accepted)
                (mapv #(dissoc % :source_status) (:citations (store/recall (:id accepted) "synthetic")))))))))
  (doseq [n [256 257]]
    (private-state
     (fn [_]
       (let [commit (assoc event :memories (mapv #(assoc observation :id (str "observation-" %)) (range n)))]
         (if (= n 256)
           (do (store/append! commit) (is (= n (count (store/list-memories nil)))))
           (is (thrown? js/Error (store/append! commit)))))))))

(deftest bounded-log-and-tail-reads
  (private-state
   (fn [_]
     (store/ensure-path! (path/dirname (store/log-path "synthetic")) true)
     (let [p (store/log-path "synthetic") json (js/JSON.stringify (clj->js event))
           frame (str json (apply str (repeat (- 1048576 (js/Buffer.byteLength json) 1) " ")) "\n")]
       ;; Sixteen maximum-sized valid frames, all identical event replays.
       (fs/writeFileSync p (apply str (repeat 16 frame)) #js {:mode 384})
       (is (= 100 (get-in (store/read-log p) [:state :coverage ["observation" "parent-1"]])))
       (store/append! event)
       (is (= 16777216 (.-size (fs/statSync p))))
       (is (thrown? js/Error (store/append! (assoc event :event_id "over-log-limit"
                                                :coverage {:lane "observation" :input_id "parent-1" :from 100 :to 200}))))
       (is (= 16777216 (.-size (fs/statSync p)))))))
  (private-state
   (fn [_]
     (store/append! event)
     (let [p (store/log-path "synthetic") fd (fs/openSync p "r+")]
       (try (fs/ftruncateSync fd 16777217) (finally (fs/closeSync fd)))
       (is (thrown? js/Error (store/read-log p))))))
  (private-state
   (fn [_]
     (store/append! event)
     (let [p (store/log-path "synthetic")]
       (fs/appendFileSync p (apply str (repeat 1048577 "x")))
       (is (thrown? js/Error (store/read-log p)))
       (is (thrown? js/Error (store/append! event)))))))

(defn generated-history [seed]
  ;; Reproducible bounded generator: several independent lanes/inputs, varying
  ;; increments and replay insertion positions. No host randomness or fixtures.
  (loop [seed seed index 0 coverage {} events []]
    (if (= index 30) events
      (let [seed (mod (* seed 48271) 2147483647)
            lane (if (even? seed) "observation" "reflection")
            input (str "input-" (mod seed 3)) key [lane input]
            from (get coverage key 0) to (+ from 1 (mod seed 97))
            e (assoc event :event_id (str "generated-" index)
                     :coverage {:lane lane :input_id input :from from :to to})]
        (recur seed (inc index) (assoc coverage key to) (conj events e))))))

(deftest generated-replay-invariants
  (doseq [seed (range 1 65)]
    (let [events (generated-history seed)
          expected (store/fold-events events)
          replayed (mapcat (fn [e] (repeat (inc (mod (+ seed (get-in e [:coverage :to])) 4)) e)) events)]
      (testing (str "seed " seed)
        (is (= expected (store/fold-events replayed)))
        (is (= expected (store/fold-events (concat events (reverse events)))))
        (is (thrown? js/Error (store/fold-events (conj events (assoc (first events) :recorded_at "2026-10-13T00:00:00Z")))))
        (is (every? pos? (vals (:coverage expected))))))))

(deftest zero-write-and-repair-sync-failure
  (private-state
   (fn [_]
     (with-redefs [store/write-chunk! (fn [& _] 0)]
       (is (thrown? js/Error (store/append! event))))
     (is (= {} (:coverage (:state (store/read-log (store/log-path "synthetic"))))))
     (store/append! event)
     (let [p (store/log-path "synthetic") next (assoc event :event_id "next"
                                                   :coverage {:lane "observation" :input_id "parent-1" :from 100 :to 200})]
       (fs/appendFileSync p "unfinished")
       (is (thrown? js/Error (store/append! next)))
       (with-redefs [store/sync! (fn [_] (throw (js/Error. "Synthetic repair sync failure.")))]
         (is (thrown? js/Error (store/repair! "synthetic"))))
       (is (= 100 (get-in (store/read-log p) [:state :coverage ["observation" "parent-1"]])))
       (store/append! next)
       (is (= 200 (get-in (store/read-log p) [:state :coverage ["observation" "parent-1"]])))))))

(deftest citation-version-is-required-and-independent
  (private-state
   (fn [_]
     (doseq [citation [(dissoc (first (:citations observation)) :citation_version)
                      (assoc (first (:citations observation)) :citation_version 2)]]
       (is (thrown? js/Error (store/append! (assoc event :memories [(assoc observation :citations [citation])])))))
     (is (not (fs/existsSync (store/root))))
     (store/append! (assoc event :memories [observation]))
     (is (= 1 (:citation_version (first (:citations (store/recall "observation-1" "synthetic")))))))))

(deftest complete-json-without-lf-is-uncommitted
  (private-state
   (fn [_]
     (store/append! event)
     (let [p (store/log-path "synthetic") next (assoc event :event_id "unterminated"
                                                   :coverage {:lane "observation" :input_id "parent-1" :from 100 :to 200})
           json (js/JSON.stringify (clj->js next))]
       (fs/appendFileSync p json)
       (is (= 100 (get-in (store/read-log p) [:state :coverage ["observation" "parent-1"]])))
       (is (= "repair_needed" (:kind (first (:warnings (store/retrieve "list" nil nil))))))
       (is (thrown? js/Error (store/append! next)))
       (is (= (js/Buffer.byteLength json) (:removed_bytes (store/repair! "synthetic"))))
       (store/append! next)
       (is (= 200 (get-in (store/read-log p) [:state :coverage ["observation" "parent-1"]])))))))

(deftest corrupt-session-does-not-block-unrelated-commits-or-recall
  (private-state
   (fn [_]
     (store/append! (assoc event :memories [observation]))
     (store/append! (assoc event :session_id "damaged" :memories [observation]))
     (fs/writeFileSync (store/log-path "damaged") "private corrupt content\n")
     (let [original store/read-log reads (atom [])]
       (with-redefs [store/read-log (fn [p] (swap! reads conj p) (original p))]
         (store/append! (assoc event :event_id "next"
                              :coverage {:lane "observation" :input_id "parent-1" :from 100 :to 200})))
       (is (= [(store/log-path "synthetic")] @reads)))
     (store/append! (assoc event :session_id "new-session" :memories [observation]))
     (is (thrown? js/Error (store/append! (assoc event :session_id "damaged"))))
     (is (thrown? js/Error (store/repair! "damaged")))
     (let [{:keys [result warnings incomplete]} (store/retrieve "list" nil nil)]
       (is incomplete)
       (is (= 2 (count result)))
       (is (= ["corrupt"] (mapv :kind warnings))))
     (let [{:keys [result warnings incomplete]} (store/retrieve "recall" (store/memory-urn "synthetic" "observation-1") nil)]
       (is (= "synthetic" (:session_id result)))
       (is (empty? warnings))
       (is (not incomplete))))))

(deftest repair-retry-syncs-after-uncertain-truncation
  (private-state
   (fn [_]
     (store/append! event)
     (fs/appendFileSync (store/log-path "synthetic") "tail")
     (with-redefs [store/sync! (fn [_] (throw (js/Error. "Synthetic failed repair sync.")))]
       (is (thrown? js/Error (store/repair! "synthetic"))))
     (is (not (:repair_needed (store/read-log (store/log-path "synthetic")))))
     (let [synced (atom 0)]
       (with-redefs [store/sync! (fn [fd] (swap! synced inc) (fs/fsyncSync fd))]
         (is (= {:repaired false :removed_bytes 0} (store/repair! "synthetic"))))
       (is (= 1 @synced))))))

(deftest small-log-read-allocation-follows-file-size
  (private-state
   (fn [_]
     (store/append! event)
     (let [p (store/log-path "synthetic") size (.-size (fs/statSync p))
           original (.-alloc js/Buffer) allocations (atom [])]
       (try
         (set! (.-alloc js/Buffer) (fn [n] (swap! allocations conj n) (.call original js/Buffer n)))
         (is (= 100 (get-in (store/read-log p) [:state :coverage ["observation" "parent-1"]])))
         (is (= [(inc size)] @allocations))
         (finally (set! (.-alloc js/Buffer) original)))))))

(deftest unsafe-storage-is-rejected
  (doseq [scenario [:relative :empty :symlink :permissions :directory-permissions :log-symlink :directory-log :dangling]]
    (private-state
     (fn [root]
       (case scenario
         :relative (set! (.-XDG_DATA_HOME js/process.env) "relative")
         :empty (set! (.-XDG_DATA_HOME js/process.env) "")
         :symlink (fs/symlinkSync root (path/join root "claudiom"))
         :dangling (fs/symlinkSync (path/join root "missing") (path/join root "claudiom"))
         :permissions (do (store/append! event) (fs/chmodSync (store/log-path "synthetic") 420))
         :directory-permissions (do (store/append! event) (fs/chmodSync (path/dirname (store/log-path "synthetic")) 493))
         :log-symlink (do (store/append! event) (fs/unlinkSync (store/log-path "synthetic"))
                          (fs/symlinkSync (path/join root "missing") (store/log-path "synthetic")))
         :directory-log (do (store/append! event) (fs/unlinkSync (store/log-path "synthetic"))
                            (fs/mkdirSync (store/log-path "synthetic") #js {:mode 448})))
       (testing (name scenario) (is (thrown? js/Error (store/append! event))))))))
