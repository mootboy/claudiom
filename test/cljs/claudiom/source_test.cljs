(ns claudiom.source-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [claudiom.source :as source]
            [claudiom.store :as store]
            [claudiom.store-test :refer [private-state]]
            [cljs.spec.alpha :as s]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:crypto" :as crypto]))

(defn temporary [f]
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "claudiom-source-"))]
    (try (f dir) (finally (fs/rmSync dir #js {:recursive true :force true})))))
(defn sha [bytes] (.digest (.update (crypto/createHash "sha256") bytes) "hex"))
(defn line [record] (str (js/JSON.stringify (clj->js record)) "\n"))
(defn user-record [text] {:type "user" :origin {:kind "human"} :message {:content [{:type "text" :text text}]}})
(defn locator [dir] {:session_id "session" :cwd dir :transcript_path (path/join dir "parent.jsonl")})
(def empty-fold (store/fold-events []))
(defn selected [loc fold opts] (source/select-context (source/read-snapshot loc fold opts) opts))
(defn commit [result id]
  {:version 1 :event_id id :session_id "session" :recorded_at "2026-10-12T12:00:00Z"
   :kind "generation" :coverage (:coverage result) :memories []})

(deftest explicit-snapshot-and-native-restart
  (temporary
   (fn [dir]
     (let [loc (locator dir) p (:transcript_path loc)
           a (line (assoc (user-record "one") :cwd dir :timestamp "2026-10-12T12:00:00.123Z" :uuid "uuid"))
           b (line (assoc (user-record "two") :cwd "/other" :timestamp "2026-10-12T11:00:00.000Z" :uuid "uuid"))]
       (fs/writeFileSync p (str a b "partial"))
       (let [before (fs/readFileSync p) result (selected loc empty-fold {})
             fold (store/fold-events [(commit result "first")])]
         (is (= ["one" "two"] (mapv #(get-in % [:blocks 0 :text]) (:context result))))
         (is (= [dir "/other"] (mapv :cwd (:context result))))
         (is (= "2026-10-12T12:00:00.123Z" (:source_time (first (:context result)))))
         (is (= (js/Buffer.byteLength (str a b)) (get-in result [:coverage :to])))
         (is (= (sha (str a b)) (get-in result [:coverage :prefix_sha256])))
         (is (= [0 (js/Buffer.byteLength a)] (mapv #(get-in % [:citation :locator :start]) (:context result))))
         (doseq [entry (:context result)]
           (let [{:keys [start end sha256]} (get-in entry [:citation :locator])]
             (is (= sha256 (sha (.subarray before start end))))
             (is (s/valid? :claudiom.store/citation (:citation entry)))))
         (is (nil? (:coverage (selected loc fold {}))))
         (is (.equals before (fs/readFileSync p)))
         ;; Finish the old partial tail with a malformed line, which is processed.
         (fs/appendFileSync p "\n")
         (let [next (selected loc fold {})]
           (is (= "parent#0" (get-in next [:coverage :input_id])))
           (is (= "malformed" (get-in next [:context 0 :reason]))))
         (let [relocated (path/join dir "relocated.jsonl")]
           (fs/writeFileSync relocated (str a b))
           (is (nil? (:coverage (selected (assoc loc :transcript_path relocated) fold {})))))
         ;; Rewrite only a middle record's content; committed prefix must mismatch.
         (fs/writeFileSync p (str a (str/replace b "two" "new")))
         (let [rewritten (selected loc fold {}) next-fold (store/fold-events [(commit result "first") (commit rewritten "rewrite")])]
           (is (= "parent#1" (get-in rewritten [:coverage :input_id])))
           (is (= 0 (get-in rewritten [:coverage :from])))
           (fs/writeFileSync p a)
           (is (= "parent#2" (get-in (selected loc next-fold {}) [:coverage :input_id])))))))))

(deftest omission-and-parsed-budget-interface
  (temporary
   (fn [dir]
     (let [loc (locator dir) p (:transcript_path loc) valid (line (user-record "kept"))
           huge (str (apply str (repeat 500 "x")) "\n") malformed "{bad}\n"
           unknown (line {:type "environment" :value "PAYLOAD"})]
       (fs/writeFileSync p (str huge malformed unknown valid "tail"))
       (let [result (selected loc empty-fold {:line_bytes 200 :parsed_bytes 200})]
         (is (= ["oversized" "malformed" "unknown_type" nil] (mapv :reason (:context result))))
         (is (= (+ (js/Buffer.byteLength malformed) (js/Buffer.byteLength unknown) (js/Buffer.byteLength valid)) (:parsed_bytes result)))
         (is (= (sha huge) (get-in result [:context 0 :citation :locator :sha256])))
         (is (not (str/includes? (js/JSON.stringify (clj->js result)) "PAYLOAD")))
         (is (= (sha (str huge malformed unknown valid)) (get-in result [:coverage :prefix_sha256]))))
       (fs/writeFileSync p (str valid valid))
       (let [result (selected loc empty-fold {:parsed_bytes (js/Buffer.byteLength valid)})]
         (is (= 1 (count (:context result))))
         (is (= (js/Buffer.byteLength valid) (get-in result [:coverage :to]))))
       (is (nil? (:coverage (selected loc empty-fold {:selected_bytes 3}))))
       (fs/writeFileSync p (str "\uFEFF" valid "{\"type\":\"user\",\"type\":\"assistant\"}\n"))
       (is (= ["malformed" "malformed"] (mapv :reason (:context (selected loc empty-fold {})))))))))

(deftest user-attribution-combinations
  (temporary
   (fn [dir]
     (doseq [sub? [false true] tool? [false true] meta [true false nil]
             origin ["human" "task-notification" "peer" "future" :malformed :absent]
             sdk? [false true]]
       (let [loc (cond-> (locator dir) sub? (assoc :agent_id "agent#id" :agent_transcript_path (path/join dir "agent.jsonl") :agent_type ""))
             p (or (:agent_transcript_path loc) (:transcript_path loc))
             record (cond-> (user-record "TEXT_SENTINEL")
                      tool? (assoc-in [:message :content] [{:type "text" :text "SURROUNDING"} {:type "tool_result" :content "OUTPUT"}])
                      (some? meta) (assoc :isMeta meta)
                      (= origin :absent) (dissoc :origin)
                      (= origin :malformed) (assoc :origin {})
                      (string? origin) (assoc :origin {:kind origin})
                      sdk? (assoc :promptSource "sdk"))
             omitted? (or (true? meta) (not (contains? #{"human" :absent} origin)))
             expected (cond omitted? nil tool? "tool" sub? "delegating parent agent"
                            (= origin "human") "user" sdk? "SDK caller" :else "user, origin unrecorded")]
         (fs/writeFileSync p (line record))
         (let [result (selected loc empty-fold {}) entry (first (:context result))]
           (is (= expected (:attribution entry)))
           (is (some? (:coverage result)))
           (when omitted?
             (is (some? (:reason entry)))
             (is (not (str/includes? (js/JSON.stringify (clj->js entry)) "SENTINEL"))))))))))

(deftest attachment-and-mixed-block-privacy
  (temporary
   (fn [dir]
     (let [loc (locator dir) p (:transcript_path loc)]
       (doseq [mode ["prompt" "bash" nil] origin ["human" "peer" "task-notification" nil]]
         (fs/writeFileSync p (line {:type "attachment" :attachment {:type "queued_command" :commandMode mode :origin {:kind origin} :prompt "PROMPT"}}))
         (let [entry (first (:context (selected loc empty-fold {})))]
           (is (= (when (and (= mode "prompt") (= origin "human")) "user") (:attribution entry)))))
       (fs/writeFileSync p
                        (apply str (map line
                                        [{:type "attachment" :attachment {:type "file" :displayPath "display" :filename "fallback" :content "SECRET" :snippet "SECRET"}}
                                         {:type "attachment" :attachment {:type "edited_text_file" :filename "fallback" :content "SECRET"}}
                                         {:type "attachment" :attachment {:type "environment" :content "SECRET"}}
                                         {:type "attachment" :attachment {:type "session_context" :content "SECRET"}}
                                         {:type "attachment" :attachment {:type "credential_org" :content "SECRET"}}
                                         {:type "attachment" :attachment {:type "future" :content "SECRET"}}
                                         {:type "assistant" :message {:id "shared" :content [{:type "text" :text "kept"} {:type "thinking" :thinking "SECRET"} {:type "image" :source {:data "SECRET"}} {:type "tool_result" :content "tool kept"}]}}
                                         {:type "assistant" :message {:id "shared" :content [{:type "text" :text "fragment"}]}}])))
       (let [result (selected loc empty-fold {}) entries (:context result)]
         (is (= ["display" "fallback"] (mapv #(get-in % [:blocks 0 :name]) (take 2 entries))))
         (is (= 8 (count entries)))
         (is (not (str/includes? (js/JSON.stringify (clj->js result)) "SECRET")))
         (is (= "tool" (:attribution (nth entries 6))))
         (is (= "fragment" (get-in entries [7 :blocks 0 :text]))))))))

(deftest source-safety-cancellation-and-stream-boundaries
  (temporary
   (fn [dir]
     (let [loc (locator dir) p (:transcript_path loc) data (line (user-record "bytes"))
           agent (assoc loc :agent_id "a" :agent_transcript_path (path/join dir "missing") :agent_type ""
                        :scratchpad_dir "/ignored" :prompt_id "ignored" :effort "ignored")]
       (is (= "missing_subagent" (:status (selected agent empty-fold {}))))
       (is (nil? (:coverage (selected agent {:session "session" :coverage {["observation" "agent:a#0"] 42}} {}))))
       (fs/writeFileSync p data)
       (is (= "stream_too_large" (:status (selected loc empty-fold {:stream_bytes (dec (js/Buffer.byteLength data))}))))
       (is (= "ok" (:status (selected loc empty-fold {:stream_bytes (js/Buffer.byteLength data)}))))
       (is (= "cancelled" (:status (selected loc empty-fold {:cancelled? (constantly true)}))))
       (is (= "invalid_locator" (:status (selected (assoc loc :transcript_path "~/no") empty-fold {}))))
       (let [symlink (path/join dir "link") ancestor (path/join dir "ancestor")]
         (fs/symlinkSync p symlink)
         (is (= "unreadable" (:status (selected (assoc loc :transcript_path symlink) empty-fold {}))))
         (fs/symlinkSync dir ancestor)
         (is (= "ok" (:status (selected (assoc loc :transcript_path (path/join ancestor "parent.jsonl")) empty-fold {})))))
       (is (= "unreadable" (:status (selected (assoc loc :transcript_path dir) empty-fold {}))))
       (with-redefs [source/read-chunk! (fn [& _] 0)]
         (is (= "unreadable" (:status (selected loc empty-fold {})))))
       (doseq [count [233 234]]
         (let [id (apply str (repeat count "a")) source-loc (assoc loc :agent_id id :agent_transcript_path p)
               input (str "agent:" id "#9007199254740991")
               fold {:session "session" :coverage {["observation" input] (js/Buffer.byteLength data)} :prefixes {input (sha data)}}]
           (is (= (if (= count 233) "ok" "invalid_locator") (:status (selected source-loc fold {}))))
           (is (= (+ count 23) (js/Buffer.byteLength input)))
           (when (= count 233)
             (is (= input (:input_id (source/read-snapshot source-loc fold {}))))
             (fs/appendFileSync p (line (user-record "more")))
             (is (= input (get-in (selected source-loc fold {}) [:coverage :input_id])))
             (fs/writeFileSync p (line (user-record "rewrite")))
             (is (= "incarnation_exhausted" (:status (selected source-loc fold {}))))
             (fs/writeFileSync p data))))
       (is (not (s/valid? :claudiom.source/agent_id (apply str (repeat 117 "é")))))
       (is (s/valid? :claudiom.source/agent_id (str (apply str (repeat 116 "é")) "a")))))))

(deftest shared-stream-budget-and-mutation-evidence
  (temporary
   (fn [dir]
     (let [loc (locator dir) p (:transcript_path loc) data (line (user-record "bytes"))
           agent (assoc loc :agent_id "a" :agent_transcript_path (path/join dir "agent"))]
       (fs/writeFileSync p data) (fs/writeFileSync (:agent_transcript_path agent) data)
       (let [results (source/read-sources [loc agent] empty-fold {:parsed_bytes (js/Buffer.byteLength data)})]
         (is (= 1 (count results)))
         (is (= (js/Buffer.byteLength data) (reduce + (map :parsed_bytes results)))))
       (let [original source/read-chunk! changed (atom false)]
         (with-redefs [source/read-chunk! (fn [fd buf offset length position]
                                          (let [n (original fd buf offset length position)]
                                            (when (compare-and-set! changed false true)
                                              (fs/writeFileSync p (str/replace data "bytes" "other"))) n))]
           (is (= "unreadable" (:status (selected loc empty-fold {}))))))
       (fs/writeFileSync p data)
       (let [original source/read-chunk! changed (atom false)]
         (with-redefs [source/read-chunk! (fn [fd buf offset length position]
                                          (let [n (original fd buf offset length position)]
                                            (when (compare-and-set! changed false true) (fs/appendFileSync p data)) n))]
           (is (= (js/Buffer.byteLength data) (get-in (selected loc empty-fold {}) [:coverage :to])))))))))

(deftest numerical-boundaries-paths-and-generated-restarts
  (temporary
   (fn [dir]
     (let [loc (locator dir) p (:transcript_path loc)
           base (line (user-record ""))
           exact (line (user-record (apply str (repeat (- 1048576 (js/Buffer.byteLength base)) "x"))))]
       (is (= 1048576 (js/Buffer.byteLength exact)))
       (fs/writeFileSync p exact)
       (is (nil? (get-in (selected loc empty-fold {}) [:context 0 :reason])))
       (fs/writeFileSync p (str (subs exact 0 (dec (count exact))) " \n"))
       (is (= "oversized" (get-in (selected loc empty-fold {}) [:context 0 :reason])))
       (let [fd (fs/openSync p "w")]
         (try (fs/ftruncateSync fd 268435457) (finally (fs/closeSync fd))))
       (is (= "stream_too_large" (:status (selected loc empty-fold {}))))
       (fs/writeFileSync p (apply str (repeat 4097 "\n")))
       (is (= 4096 (get-in (selected loc empty-fold {}) [:coverage :to])))
       (fs/writeFileSync p (line {:type "assistant" :cwd dir :message {:content [{:type "tool_use" :name "Read" :input {:file_path "relative.clj"}}]}}))
       (is (= (path/join dir "relative.clj") (get-in (selected loc empty-fold {}) [:context 0 :paths 0 :resolved])))
       (fs/writeFileSync p (line {:type "assistant" :message {:content [{:type "tool_use" :name "Read" :input {:file_path "relative.clj"}}]}}))
       (is (nil? (get-in (selected loc empty-fold {}) [:context 0 :paths 0 :resolved])))
       (doseq [seed (range 12)]
         (fs/writeFileSync p "")
         (loop [index 0 fold empty-fold]
           (when (< index 12)
             (fs/appendFileSync p (str/replace (line (user-record (str "é-" seed "-" index))) "\n" "\r\n"))
             (let [result (selected loc fold {}) coverage (:coverage result) e (commit result (str seed "-" index))
                   next-fold (store/fold-events (conj (vec (sort-by #(get-in % [:coverage :from]) (vals (:seen fold)))) e))]
               (is (= (get-in fold [:coverage ["observation" "parent#0"]] 0) (:from coverage)))
               (is (= (sha (fs/readFileSync p)) (:prefix_sha256 coverage)))
               (is (= result (selected loc fold {})))
               (is (= next-fold (store/fold-events (concat (sort-by #(get-in % [:coverage :from]) (vals (:seen next-fold))) [e]))))
               (recur (inc index) next-fold)))))))))

(deftest citation-attempt-boundaries-and-list-no-source-io
  (temporary
   (fn [dir]
     (let [p (path/join dir "range") data (apply str (repeat 1048576 "x"))
           c {:path p :locator {:start 0 :end 1048576 :sha256 (sha data)}}]
       (fs/writeFileSync p data)
       (is (= "verified" (get-in (source/verify-memory {:citations [c]}) [:citations 0 :source_status])))
       (is (= "verification_limited" (get-in (source/verify-memory {:citations [(assoc-in c [:locator :end] 1048577)]}) [:citations 0 :source_status])))
       (is (= 16 (count (filter #(= "verified" (:source_status %)) (:citations (source/verify-memory {:citations (vec (repeat 17 c))}))))))
       (let [missing (-> c (assoc :path (path/join dir "missing")) (assoc-in [:locator :end] 1))
             result (source/verify-memory {:citations (vec (repeat 257 missing))})]
         (is (= 256 (count (filter #(= "missing" (:source_status %)) (:citations result))))))
       (let [original source/read-chunk! mutated (atom false)]
         (with-redefs [source/read-chunk! (fn [fd buf offset length position]
                                          (let [n (original fd buf offset length position)]
                                            (when (compare-and-set! mutated false true) (fs/appendFileSync p "change")) n))]
           (is (= "unreadable" (source/verify-range c nil))))))))
  (private-state
   (fn [_]
     (with-redefs [source/open-source! (fn [_] (throw (js/Error. "Unexpected source I/O")))]
       (store/append! (assoc (commit {:coverage {:lane "observation" :input_id "parent#0" :from 0 :to 3 :prefix_sha256 (sha "{}\n")}} "empty")
                             :memories [(assoc claudiom.store-test/observation :id "o")]))
       (is (= "not_checked" (get-in (first (store/list-memories "session")) [:citations 0 :source_status])))))))

(deftest bounded-read-only-citation-verification
  (temporary
   (fn [dir]
     (let [p (path/join dir "source") data "{}\r\n" c {:path p :locator {:start 0 :end 4 :sha256 (sha data)}}
           verify (fn [citation] (get-in (source/verify-memory {:citations [citation]}) [:citations 0 :source_status]))]
       (fs/writeFileSync p data)
       (is (= "verified" (verify c)))
       (is (= "verification_limited" (get-in (source/verify-memory {:citations [c]} {:bytes 3}) [:citations 0 :source_status])))
       (is (= ["verified" "verification_limited"]
              (mapv :source_status (:citations (source/verify-memory {:citations [c c]} {:attempts 1})))))
       (is (= "verification_limited"
              (get-in (source/verify-memory {:citations [c] :supporting_observations [{:citations [c]}]} {:attempts 1})
                      [:supporting_observations 0 :citations 0 :source_status])))
       (fs/writeFileSync p "[]\r\n") (is (= "changed" (verify c)))
       (fs/writeFileSync p "{}") (is (= "changed" (verify c)))
       (fs/rmSync p) (is (= "missing" (verify c)))
       (is (= ["missing" "verification_limited"]
              (mapv :source_status (:citations (source/verify-memory {:citations [c c]} {:attempts 1})))))
       (fs/symlinkSync (path/join dir "absent") p) (is (= "unreadable" (verify c)))
       (fs/rmSync p) (fs/mkdirSync p) (is (= "unreadable" (verify c)))
       (fs/rmSync p #js {:recursive true}) (fs/writeFileSync p data)
       (let [before (fs/readFileSync p)]
         (is (= "verified" (verify c))) (is (.equals before (fs/readFileSync p))))))))
