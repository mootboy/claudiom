(ns claudiom.source
  (:require [cljs.spec.alpha :as s]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:crypto" :as crypto]))

(def defaults {:line_bytes 1048576 :stream_bytes 268435456 :parsed_bytes 33554432
               :selected_bytes 33554432 :tokens 8388608 :tool_bytes 16384 :records 4096})
(defn fail [status] (throw (ex-info "Source unavailable." {:status status})))
(defn identifier? [x]
  (and (string? x) (pos? (count x)) (<= (js/Buffer.byteLength x) 256)
       (try (= x (js/decodeURIComponent (js/encodeURIComponent x))) (catch :default _ false))))
(defn timestamp? [x]
  (and (string? x) (boolean (re-matches #"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z" x))
       (js/Number.isFinite (js/Date.parse x))
       (= (subs x 0 19) (subs (.toISOString (js/Date. x)) 0 19))))
(s/def ::session_id identifier?)
(s/def ::agent_id #(and (identifier? %) (<= (js/Buffer.byteLength %) 233)))
(s/def ::absolute-path #(and (string? %) (path/isAbsolute %) (<= (js/Buffer.byteLength %) 4096)))
(s/def ::transcript_path ::absolute-path)
(s/def ::agent_transcript_path ::absolute-path)
(s/def ::cwd ::absolute-path)
(s/def ::agent_type string?)
(s/def ::locator
  (s/and (s/keys :req-un [::session_id ::transcript_path ::cwd])
         #(if (contains? % :agent_id)
            (s/valid? (s/keys :req-un [::agent_id ::agent_transcript_path] :opt-un [::agent_type]) %)
            (not (contains? % :agent_transcript_path)))))
(s/def ::limits
  (s/and map? #(every? (fn [[k v]] (and (contains? defaults k) (js/Number.isSafeInteger v)
                                        (pos? v) (or (not= k :selected_bytes) (<= 2 v))
                                        (<= v (get defaults k)))) %)))
(defn limits [options]
  (let [v (select-keys options (keys defaults))]
    (when-not (s/valid? ::limits v) (fail "invalid_limits"))
    (merge defaults v)))
(defn hash-new [] (crypto/createHash "sha256"))
(defn digest [^js h] (.digest ^js (.copy h) "hex"))
(defn read-chunk! [fd buffer offset length position] (fs/readSync fd buffer offset length position))
(defn check-cancel! [cancelled?] (when (and cancelled? (cancelled?)) (fail "cancelled")))
(defn open-source! [p]
  ;; O_NONBLOCK also prevents a race-swapped FIFO from hanging before fstat.
  (let [prior (fs/lstatSync p)]
    (when-not (.isFile prior) (fail "unreadable"))
    (let [fd (fs/openSync p (bit-or (.-O_RDONLY fs/constants) (.-O_NOFOLLOW fs/constants)
                                  (.-O_NONBLOCK fs/constants)))]
      (try
        (let [st (fs/fstatSync fd)]
          (when-not (and (.isFile st) (= (.-dev prior) (.-dev st)) (= (.-ino prior) (.-ino st)))
            (fail "unreadable"))
          {:fd fd :stat st})
        (catch :default e (fs/closeSync fd) (throw e))))))
(defn hash-prefix [fd end cancelled?]
  (let [buf (js/Buffer.alloc (min 65536 (max 1 end))) h (hash-new)]
    (loop [offset 0]
      (check-cancel! cancelled?)
      (when (< offset end)
        (let [n (read-chunk! fd buf 0 (min (.-length buf) (- end offset)) offset)]
          (when (zero? n) (fail "unreadable"))
          (.update h (.subarray buf 0 n))
          (recur (+ offset n)))))
    h))
(defn incarnation [locator fold]
  (when (and (:session fold) (not= (:session_id locator) (:session fold))) (fail "unreadable"))
  (let [key (if (:agent_id locator) (str "agent:" (:agent_id locator)) "parent") prefix (str key "#")
        entries (for [[[lane id] to] (:coverage fold)
                      :when (and (= lane "observation") (str/starts-with? id prefix))]
                  (let [suffix (subs id (count prefix)) n (js/Number suffix)]
                    (when-not (and (re-matches #"0|[1-9][0-9]*" suffix) (js/Number.isSafeInteger n))
                      (fail "unreadable"))
                    {:n n :input_id id :to to :prefix_sha256 (get-in fold [:prefixes id])}))
        latest (last (sort-by :n entries))]
    {:key key :latest latest}))

(defn scan-lines [fd size from prefix-hash bounds cancelled? usage]
  (let [buf (js/Buffer.alloc 65536)]
    (loop [position from start from line-size 0 fragments [] line-hash (hash-new)
           records [] end from last-prefix (digest prefix-hash)]
      (check-cancel! cancelled?)
      (if (or (>= position size) (>= (count records) (:records bounds)))
        {:records records :end end :prefix_sha256 last-prefix}
        (let [n (read-chunk! fd buf 0 (min 65536 (- size position)) position)]
          (when (zero? n) (fail "unreadable"))
          (let [step
                (loop [cursor 0 start start line-size line-size fragments fragments line-hash line-hash
                       records records end end last-prefix last-prefix]
                  (if (or (= cursor n) (>= (count records) (:records bounds)))
                    {:stop (>= (count records) (:records bounds)) :start start :line-size line-size :fragments fragments :line-hash line-hash
                     :records records :end end :prefix_sha256 last-prefix}
                    (let [lf (.indexOf buf 10 cursor)
                          complete? (and (<= 0 lf) (< lf n))
                          next (if complete? (inc lf) n) segment (.subarray buf cursor next)
                          length (+ line-size (.-length segment)) oversized? (> length (:line_bytes bounds))
                          fragments (if (or oversized? (> length (- (:parsed_bytes bounds) @usage)))
                                      [] (conj fragments (js/Buffer.from segment)))
                          _ (.update line-hash segment) _ (.update prefix-hash segment)]
                      (if complete?
                        (if (and (not oversized?) (> (+ @usage length) (:parsed_bytes bounds)))
                          {:stop true :records records :end end :prefix_sha256 last-prefix}
                          (let [end (+ position next) prefix (digest prefix-hash)
                                frame (cond-> {:start start :end end :sha256 (digest line-hash) :prefix_sha256 prefix}
                                        oversized? (assoc :reason "oversized")
                                        (not oversized?) (assoc :bytes (js/Buffer.concat (clj->js fragments))))]
                            (when-not oversized? (swap! usage + length))
                            (recur next end 0 [] (hash-new) (conj records frame) end prefix)))
                        (recur next start length fragments line-hash records end last-prefix)))))]
            (if (:stop step) (select-keys step [:records :end :prefix_sha256])
                (recur (+ position n) (:start step) (:line-size step) (:fragments step) (:line-hash step)
                       (:records step) (:end step) (:prefix_sha256 step)))))))))

(defn read-snapshot
  "Read-only, explicit stream snapshot. Fold is that session's recovered native state.
   Returns materialized bounded frames, not authoritative progress. No discovery."
  ([locator fold] (read-snapshot locator fold {}))
  ([locator fold options]
   (let [usage (atom 0)]
     (try
       (when-not (s/valid? ::locator locator) (fail "invalid_locator"))
       (let [bounds (limits options) cancelled? (:cancelled? options)
             p (or (:agent_transcript_path locator) (:transcript_path locator))
             _ (check-cancel! cancelled?) {:keys [fd ^js stat]} (open-source! p)]
         (try
           (let [size (.-size stat) _ (when (> size (:stream_bytes bounds)) (fail "stream_too_large"))
                 {:keys [key latest]} (incarnation locator fold)
                 to (or (:to latest) 0)
                 prefix (hash-prefix fd (min to size) cancelled?)
                 matched? (and latest (<= to size) (= (:prefix_sha256 latest) (digest prefix)))
                 n (cond matched? (:n latest) latest (inc (:n latest)) :else 0)
                 _ (when-not (js/Number.isSafeInteger n) (fail "incarnation_exhausted"))
                 from (if matched? to 0)
                 _ (when (pos? from)
                     (let [b (js/Buffer.alloc 1)]
                       (when-not (and (= 1 (read-chunk! fd b 0 1 (dec from))) (= 10 (aget b 0))) (fail "unreadable"))))
                 frames (scan-lines fd size from (if matched? prefix (hash-new)) bounds cancelled? usage)
                 after (fs/fstatSync fd) current (fs/lstatSync p)]
             (when (> (.-size after) (:stream_bytes bounds)) (fail "stream_too_large"))
             (when (or (< (.-size after) size) (not (.isFile current))
                       (not= (.-ino stat) (.-ino current)) (not= (.-dev stat) (.-dev current))
                       (not= (:prefix_sha256 frames) (digest (hash-prefix fd (:end frames) cancelled?))))
               (fail "unreadable"))
             (check-cancel! cancelled?)
             (merge frames {:status "ok" :locator locator :path p :from from :input_id (str key "#" n)
                            :parsed_bytes @usage :snapshot_size size}))
           (finally (fs/closeSync fd))))
       (catch :default e
         {:status (or (:status (ex-data e)) (if (= "ENOENT" (.-code e))
                                             (if (:agent_id locator) "missing_subagent" "missing") "unreadable"))
          :parsed_bytes @usage :records []})))))

(defn parse-record [line]
  (let [parsed (js->clj (js/JSON.parse line) :keywordize-keys true)
        tokens (re-seq #"\"(?:\\.|[^\"\\])*\"|[{}\[\]:,]|-?[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|true|false|null" line)]
    ;; JSON.parse validates syntax; this walk additionally rejects duplicate
    ;; object keys, including differently escaped spellings of the same key.
    (letfn [(value [ts]
              (case (first ts)
                "{" (loop [remaining (rest ts) seen #{}]
                      (if (= "}" (first remaining)) (rest remaining)
                          (let [key (js/JSON.parse (first remaining))]
                            (when (contains? seen key) (fail "malformed"))
                            (let [after (value (drop 2 remaining))]
                              (if (= "," (first after))
                                (recur (rest after) (conj seen key))
                                (rest after))))))
                "[" (loop [remaining (rest ts)]
                      (if (= "]" (first remaining)) (rest remaining)
                          (let [after (value remaining)]
                            (if (= "," (first after)) (recur (rest after)) (rest after)))))
                (rest ts)))]
      (value tokens))
    parsed))


(defn excerpt [text cap]
  (let [bytes (js/Buffer.from text)]
    (if (<= (.-length bytes) cap) {:text text}
        (loop [n cap]
          (let [value (try (.decode (js/TextDecoder. "utf-8" #js {:fatal true}) (.subarray bytes 0 n))
                           (catch :default _ nil))]
            (if (some? value) {:text value :excerpt true} (recur (dec n))))))))
(defn content-blocks
  ([content cap] (content-blocks content cap 0))
  ([content cap depth]
  (cond
    (> depth 8) [{:omitted "nested_block_limit"}]
    (string? content) [(merge {:kind "text"} (excerpt content cap))]
    (vector? content)
    (mapv (fn [b]
            (case (:type b)
              "text" (if (string? (:text b)) (merge {:kind "text"} (excerpt (:text b) cap)) {:omitted "unknown_block"})
              "tool_result" {:kind "tool_result" :blocks (content-blocks (:content b) cap (inc depth)) :is_error (true? (:is_error b))}
              "tool_use" (if (string? (:name b))
                           {:kind "tool_use" :name (:name b)
                            :input (excerpt (js/JSON.stringify (clj->js (:input b))) cap)}
                           {:omitted "unknown_block"})
              "image" {:omitted "image"}
              "attachment" {:omitted "attachment"}
              {:omitted "unknown_block"})) content)
    :else [{:omitted "unknown_block"}])))
(defn native-paths [content cwd]
  ;; Only documented native filesystem tool fields; never infer paths from prose/Bash.
  (vec (for [b (if (vector? content) content [])
             :let [field (case (:name b) ("Read" "Write" "Edit") :file_path
                               ("Glob" "Grep") :path "NotebookEdit" :notebook_path nil)
                   hint (when field (get-in b [:input field]))]
             :when (and (= "tool_use" (:type b)) (string? hint) (seq hint) (<= (js/Buffer.byteLength hint) 4096))]
         {:hint hint :resolved (cond (path/isAbsolute hint) (path/normalize hint)
                                    cwd (path/resolve cwd hint) :else nil)})))
(defn record-context [record locator bounds]
  (let [type (:type record) content (get-in record [:message :content])
        tool? (and (vector? content) (some #(= "tool_result" (:type %)) content))
        origin? (contains? record :origin) origin (get-in record [:origin :kind])
        user? (= type "user") subagent? (some? (:agent_id locator))]
    (cond
      (not (map? record)) {:reason "malformed"}
      (or (and (:sessionId record) (not= (:sessionId record) (:session_id locator)))
          (and (:agentId record) (not= (:agentId record) (:agent_id locator)))) {:reason "identity_mismatch"}
      (= type "attachment")
      (let [a (:attachment record)]
        (case (:type a)
          "queued_command" (if (and (= "prompt" (:commandMode a)) (= "human" (get-in a [:origin :kind]))
                                    (string? (:prompt a)))
                             {:attribution "user" :blocks [{:kind "text" :text (:prompt a)}]}
                             {:reason "attachment_excluded"})
          ("file" "edited_text_file")
          {:attribution "attachment_marker"
           :blocks [{:kind "filename" :attachment_type (:type a)
                     :name (when (string? (or (:displayPath a) (:filename a)))
                             (:text (excerpt (or (:displayPath a) (:filename a)) (:tool_bytes bounds))))}]}
          {:reason "attachment_excluded"}))
      (not (contains? #{"user" "assistant"} type)) {:reason "unknown_type"}
      (true? (:isMeta record)) {:reason "meta"}
      (and user? origin? (not= "human" origin)) {:reason "origin_excluded"}
      :else
      {:attribution (cond (true? (:isCompactSummary record)) "compaction summary"
                          tool? "tool" (= type "assistant") "assistant"
                          subagent? "delegating parent agent" origin? "user"
                          (= "sdk" (:promptSource record)) "SDK caller" :else "user, origin unrecorded")
       :model_generated (or (= type "assistant") (true? (:isCompactSummary record)))
       :blocks (content-blocks content (:tool_bytes bounds))})))

(defn select-context
  "Pure bounded selection; returned coverage is a candidate, never committed."
  ([snapshot] (select-context snapshot {}))
  ([snapshot options]
   (if (not= "ok" (:status snapshot)) (assoc (dissoc snapshot :records) :context [] :coverage nil)
       (let [bounds (limits options) locator (:locator snapshot)]
         (loop [frames (:records snapshot) context [] used 2 end (:from snapshot) prefix nil]
           (if-let [frame (first frames)]
             (let [record (when-not (:reason frame)
                            (try (let [text (.decode (js/TextDecoder. "utf-8" #js {:fatal true}) (:bytes frame))]
                                   ;; TextDecoder strips BOM; explicitly reject it rather than hiding source bytes.
                                   (when (= 239 (aget (:bytes frame) 0)) (fail "malformed"))
                                   (parse-record text))
                                 (catch :default _ nil)))
                   selected (if (:reason frame) {:reason (:reason frame)} (record-context record locator bounds))
                   citation {:citation_version 1 :stream (if (:agent_id locator) "subagent" "parent")
                             :agent_id (:agent_id locator) :path (:path snapshot)
                             :source_id (when (identifier? (:uuid record)) (:uuid record))
                             :locator {:input_id (:input_id snapshot) :start (:start frame) :end (:end frame) :sha256 (:sha256 frame)}}
                   cwd (when (s/valid? ::cwd (:cwd record)) (:cwd record))
                   entry (cond-> (assoc selected :citation citation)
                           (not (:reason selected)) (assoc :cwd cwd :source_time (when (timestamp? (:timestamp record)) (:timestamp record))
                                                        :paths (native-paths (get-in record [:message :content]) cwd)))
                   size (js/Buffer.byteLength (js/JSON.stringify (clj->js entry)))
                   next-used (+ used size (if (seq context) 1 0))]
               (if (or (> next-used (:selected_bytes bounds)) (> (/ next-used 4) (:tokens bounds)))
                 (recur nil context used end prefix)
                 (recur (rest frames) (conj context entry) next-used (:end frame) (:prefix_sha256 frame))))
             {:status "ok" :context context :selected_bytes used :estimated_tokens (/ used 4)
              :parsed_bytes (:parsed_bytes snapshot) :snapshot_size (:snapshot_size snapshot)
              :coverage (when (> end (:from snapshot))
                          {:lane "observation" :input_id (:input_id snapshot) :from (:from snapshot)
                           :to end :prefix_sha256 prefix})}))))))

(defn read-sources [locators fold options]
  (when (or (not (vector? locators)) (> (count locators) 256)) (fail "invalid_locator"))
  (let [bounds (limits options)]
    (loop [remaining locators parsed 0 selected 0 results []]
      (if-let [locator (first remaining)]
        (if (or (>= parsed (:parsed_bytes bounds)) (< (- (:selected_bytes bounds) selected) 2)
                (< (- (* 4 (:tokens bounds)) selected) 4)
                (>= (reduce + (map #(count (:context %)) results)) (:records bounds))) results
            (let [opts (assoc options :parsed_bytes (- (:parsed_bytes bounds) parsed)
                              :selected_bytes (- (:selected_bytes bounds) selected)
                              :records (- (:records bounds) (reduce + (map #(count (:context %)) results)))
                              :tokens (max 1 (js/Math.floor (- (:tokens bounds) (/ selected 4)))))
                  snapshot (read-snapshot locator fold opts) result (select-context snapshot opts)]
              (recur (rest remaining) (+ parsed (:parsed_bytes snapshot)) (+ selected (or (:selected_bytes result) 0))
                     (conj results result)))) results))))

(defn verify-range [citation cancelled?]
  (try
    (check-cancel! cancelled?)
    (let [{:keys [fd ^js stat]} (open-source! (:path citation))
          {:keys [start end sha256]} (:locator citation)]
      (try
        (if (> end (.-size stat)) "changed"
            (let [buf (js/Buffer.alloc (min 65536 (- end start))) h (hash-new)]
              (loop [offset start]
                (check-cancel! cancelled?)
                (when (< offset end)
                  (let [n (read-chunk! fd buf 0 (min (.-length buf) (- end offset)) offset)]
                    (when (zero? n) (fail "unreadable"))
                    (.update h (.subarray buf 0 n))
                    (recur (+ offset n)))))
              (let [after (fs/fstatSync fd) current (fs/lstatSync (:path citation))]
                (when (or (not (.isFile current)) (not= (.-ino stat) (.-ino current))
                          (not= (.-dev stat) (.-dev current)) (< (.-size after) end))
                  (fail "unreadable")))
              (if (= sha256 (digest h)) "verified" "changed")))
        (finally (fs/closeSync fd))))
    (catch :default e (if (= "ENOENT" (.-code e)) "missing" "unreadable"))))
(defn verify-memory
  "One shared budget across all returned citations, including reflection supports."
  ([memory] (verify-memory memory {}))
  ([memory options]
   (let [attempts (atom 0) bytes (atom 0)
         attempt-cap (min 256 (or (:attempts options) 256)) byte-cap (min 16777216 (or (:bytes options) 16777216))
         range-cap (min 1048576 (or (:range_bytes options) 1048576))]
     (letfn [(verify [c]
               (let [size (- (get-in c [:locator :end]) (get-in c [:locator :start]))]
                 (if (or (>= @attempts attempt-cap) (> size range-cap) (> (+ @bytes size) byte-cap))
                   (assoc c :source_status "verification_limited")
                   (do (swap! attempts inc) (swap! bytes + size)
                       (assoc c :source_status (verify-range c (:cancelled? options)))))))
             (walk [m]
               (cond-> m
                 (:citations m) (update :citations #(mapv verify %))
                 (:supporting_observations m) (update :supporting_observations #(mapv walk %))))]
       (walk memory)))))
