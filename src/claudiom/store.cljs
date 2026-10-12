(ns claudiom.store
  (:require [cljs.spec.alpha :as s]
            [clojure.string :as str]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:crypto" :as crypto]))

(defn fail [] (throw (js/Error. "Unsafe, invalid or corrupt native storage.")))
(defn identifier? [x] (and (string? x) (pos? (count x)) (<= (js/Buffer.byteLength x) 256)))
(s/def ::version #{1})
(s/def ::event_id identifier?)
(s/def ::session_id identifier?)
(s/def ::recorded_at
  #(and (string? %) (boolean (re-matches #"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z" %))
        (js/Number.isFinite (js/Date.parse %))
        ;; Date.parse normalizes impossible dates and 24:00 into another day.
        (= (subs % 0 19) (subs (.toISOString (js/Date. %)) 0 19))))
(s/def ::kind #{"generation" "drop" "supersede"})
(s/def ::lane #{"observation" "reflection"})
(s/def ::input_id identifier?)
(s/def ::from #(and (js/Number.isSafeInteger %) (<= 0 %)))
(s/def ::to #(and (js/Number.isSafeInteger %) (pos? %)))
(s/def ::coverage (s/and (s/keys :req-un [::lane ::input_id ::from ::to])
                        #(= #{:lane :input_id :from :to} (set (keys %)))
                        #(< (:from %) (:to %))))
(defn exact-keys? [m ks] (= (set ks) (set (keys m))))
(defn bounded-text? [x] (and (string? x) (pos? (count x)) (<= (js/Buffer.byteLength x) 16384)))
(s/def ::id identifier?)
(s/def ::text bounded-text?)
(s/def ::source_time (s/nilable ::recorded_at))
(s/def ::start ::from)
(s/def ::end ::to)
(s/def ::sha256 #(and (string? %) (boolean (re-matches #"[0-9a-f]{64}" %))))
(s/def ::locator (s/and (s/keys :req-un [::input_id ::start ::end ::sha256])
                       #(exact-keys? % [:input_id :start :end :sha256]) #(< (:start %) (:end %))))
(s/def ::stream #{"parent" "subagent"})
(s/def ::agent_id (s/nilable identifier?))
(s/def ::source_id (s/nilable identifier?))
(s/def ::path #(and (string? %) (path/isAbsolute %) (<= (js/Buffer.byteLength %) 4096)))
(s/def ::citation (s/and (s/keys :req-un [::stream ::agent_id ::source_id ::path ::locator])
                        #(exact-keys? % [:stream :agent_id :source_id :path :locator])
                        #(if (= "parent" (:stream %)) (nil? (:agent_id %)) (identifier? (:agent_id %)))))
(s/def ::citations (s/coll-of ::citation :kind vector? :min-count 1 :max-count 256))
(s/def ::supports (s/coll-of identifier? :kind vector? :min-count 1 :max-count 256 :distinct true))
(s/def ::memory (s/and (s/keys :req-un [::id ::text ::source_time])
                      #(case (:kind %)
                         "observation" (and (exact-keys? % [:id :kind :text :source_time :citations]) (s/valid? ::citations (:citations %)))
                         "reflection" (and (exact-keys? % [:id :kind :text :source_time :supports]) (s/valid? ::supports (:supports %)))
                         false)))
(s/def ::memories (s/coll-of ::memory :kind vector? :max-count 256))
(s/def ::memory_id identifier?)
(s/def ::replacement_id identifier?)
(s/def ::reason bounded-text?)
(s/def ::event
  (s/and (s/keys :req-un [::version ::event_id ::session_id ::recorded_at ::kind])
         #(case (:kind %)
            "generation" (and (exact-keys? % [:version :event_id :session_id :recorded_at :kind :coverage :memories])
                              (s/valid? ::coverage (:coverage %)) (s/valid? ::memories (:memories %))
                              (every? (fn [m] (= (:kind m) (get-in % [:coverage :lane]))) (:memories %)))
            "drop" (and (exact-keys? % [:version :event_id :session_id :recorded_at :kind :memory_id])
                        (s/valid? ::memory_id (:memory_id %)))
            "supersede" (and (exact-keys? % [:version :event_id :session_id :recorded_at :kind :memory_id :replacement_id :reason])
                             (s/valid? ::memory_id (:memory_id %)) (s/valid? ::replacement_id (:replacement_id %))
                             (s/valid? ::reason (:reason %)))
            false)))

(defn add-memory [state event m]
  (let [definition (assoc m :session_id (:session_id event) :recorded_at (:recorded_at event))]
    (when (and (= "reflection" (:kind m))
               (not-every? #(= "observation" (:kind (get-in state [:definitions %]))) (:supports m))) (fail))
    (if-let [old (get-in state [:definitions (:id m)])]
      (if (= old definition) state (fail))
      (assoc-in state [:definitions (:id m)] definition))))

(defn apply-event [state event]
  (case (:kind event)
    "generation"
    (let [c (:coverage event) k [(:lane c) (:input_id c)]]
      (when-not (= (get-in state [:coverage k] 0) (:from c)) (fail))
      (assoc-in (reduce #(add-memory %1 event %2) state (:memories event)) [:coverage k] (:to c)))
    "drop" (update state :dropped conj (:memory_id event))
    "supersede"
    (let [id (:memory_id event) replacement (:replacement_id event)
          old (get-in state [:definitions id]) new (get-in state [:definitions replacement])
          previous (get-in state [:superseded id])]
      (when (or (nil? old) (nil? new) (= id replacement) (not= (:kind old) (:kind new))
                (and previous (not= replacement (:replacement_id previous)))) (fail))
      (loop [cursor replacement visited #{id}]
        (when (contains? visited cursor) (fail))
        (when-let [next (get-in state [:superseded cursor :replacement_id])]
          (recur next (conj visited cursor))))
      (assoc-in state [:superseded id] (select-keys event [:replacement_id :reason])))))

(defn fold-events [events]
  (reduce (fn [{:keys [seen coverage session] :as state} event]
            (when-not (s/valid? ::event event) (fail))
            (when (and session (not= session (:session_id event))) (fail))
            (if-let [old (get seen (:event_id event))]
              (if (= old event) state (fail))
              (-> (apply-event state event)
                  (assoc :session (:session_id event))
                  (assoc-in [:seen (:event_id event)] event))))
          {:seen {} :coverage {} :session nil :definitions {} :dropped #{} :superseded {}} events))

(defn check-entry! [p private? directory?]
  (let [st (fs/lstatSync p)]
    (when (or (.isSymbolicLink st)
              (if directory? (not (.isDirectory st)) (not (.isFile st)))
              (and private? (or (not= (.-uid st) (js/process.geteuid))
                                (not= 0 (bit-and (.-mode st) 63))))) (fail))
    st))

(defn root []
  (let [env (.-env js/process)
        xdg (.-XDG_DATA_HOME env)
        home (.-HOME env)
        base (if (some? xdg) xdg
                 (if (and home (path/isAbsolute home)) (path/join home ".local" "share") (fail)))]
    (when-not (and (seq base) (path/isAbsolute base)) (fail))
    (path/join base "claudiom" "v1" "sessions")))

(defn ensure-path! [p create?]
  (let [parts (rest (str/split p #"/"))]
    (loop [parent "/" remaining parts private? false]
      (when-let [part (first remaining)]
        (let [next (path/join parent part)
              owned? (or private? (= next (path/dirname (path/dirname (root)))))]
          (if (fs/existsSync next)
            (check-entry! next owned? true)
            (if create?
              (do (fs/mkdirSync next #js {:mode 448})
                  (let [fd (fs/openSync parent "r")]
                    (try (fs/fsyncSync fd) (finally (fs/closeSync fd)))))
              ;; lstat catches dangling links that existsSync follows.
              (try (fs/lstatSync next) (fail)
                   (catch :default e (when-not (= "ENOENT" (.-code e)) (throw e))))))
          (recur next (rest remaining) owned?))))))

(defn log-path [session]
  (when-not (identifier? session) (fail))
  (path/join (root) (.digest (.update (crypto/createHash "sha256") session) "hex") "events.jsonl"))

(defn parse-event [line]
  (let [parsed (js->clj (js/JSON.parse line) :keywordize-keys true)
        tokens (re-seq #"\"(?:\\.|[^\"\\])*\"|[{}\[\]:,]|-?[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|true|false|null" line)]
    ;; JSON.parse validates syntax; this walk additionally rejects duplicate
    ;; object keys, including differently escaped spellings of the same key.
    (letfn [(value [ts]
              (case (first ts)
                "{" (loop [remaining (rest ts) seen #{}]
                      (if (= "}" (first remaining)) (rest remaining)
                          (let [key (js/JSON.parse (first remaining))]
                            (when (contains? seen key) (fail))
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

(defn read-log [p]
  (ensure-path! (path/dirname p) false)
  (try
    (let [st (check-entry! p true false)]
      (when (> (.-size st) 16777216) (fail))
      (let [fd (fs/openSync p (bit-or (.-O_RDONLY fs/constants) (.-O_NOFOLLOW fs/constants)))]
        (try
          (let [opened (fs/fstatSync fd)]
            (when-not (and (= (.-ino st) (.-ino opened)) (= (.-dev st) (.-dev opened))) (fail))
            (let [bytes (js/Buffer.alloc 16777217)
                  n (loop [offset 0]
                      (if (= offset (.-length bytes)) offset
                          (let [n (fs/readSync fd bytes offset (- (.-length bytes) offset) nil)]
                            (if (zero? n) offset (recur (+ offset n))))))
                  _ (when (> n 16777216) (fail))
                  data (.subarray bytes 0 n)
                  end (inc (.lastIndexOf data 10))
                  complete (.subarray data 0 end)
                  text (.decode (js/TextDecoder. "utf-8" #js {:fatal true}) complete)
                  lines (if (zero? end) [] (butlast (str/split text #"\n" -1)))
                  events (mapv (fn [line]
                                 (when (or (empty? line) (> (inc (js/Buffer.byteLength line)) 1048576)) (fail))
                                 (parse-event line)) lines)]
              (when (> (- n end) 1048576) (fail))
              {:events events :state (fold-events events) :end end :size n}))
          (finally (fs/closeSync fd)))))
    (catch :default e
      (if (= "ENOENT" (.-code e)) {:events [] :state (fold-events []) :end 0 :size 0}
          (fail)))))

(declare read-states validate-global!)

(defn sync! [fd] (fs/fsyncSync fd))
(defn write-chunk! [fd bytes offset position]
  (fs/writeSync fd bytes offset (- (.-length bytes) offset) position))

(defn append! [event]
  (when-not (s/valid? ::event event) (fail))
  (let [p (log-path (:session_id event))
        {:keys [events state end size]} (read-log p)
        _ (when (and (:session state) (not= (:session_id event) (:session state))) (fail))
        next-state (fold-events (conj events event))
        replay? (contains? (:seen state) (:event_id event))
        bytes (js/Buffer.from (str (js/JSON.stringify (clj->js event)) "\n"))]
    (when (or (> (.-length bytes) 1048576)
              (> (+ end (if replay? 0 (.-length bytes))) 16777216)) (fail))
    (let [states (read-states nil)]
      (when (and (>= (count states) 256) (not (fs/existsSync (path/dirname p)))) (fail))
      (validate-global! (conj (vec (remove #(= (:session_id event) (:session %)) states)) next-state)))
    (ensure-path! (path/dirname p) true)
    (let [prior (when (fs/existsSync p) (check-entry! p true false))
          fd (fs/openSync p (bit-or (.-O_RDWR fs/constants) (.-O_CREAT fs/constants)
                                   (.-O_NOFOLLOW fs/constants)) 384)]
      (try
        (let [st (fs/fstatSync fd)]
          (when (or (not (.isFile st)) (not= (.-uid st) (js/process.geteuid))
                    (not= 0 (bit-and (.-mode st) 63)) (not= size (.-size st))
                    (and prior (or (not= (.-ino prior) (.-ino st)) (not= (.-dev prior) (.-dev st))))) (fail)))
        (when (> size end) (fs/ftruncateSync fd end) (sync! fd))
        (when-not replay?
          (loop [offset 0]
            (when (< offset (.-length bytes))
              (let [n (write-chunk! fd bytes offset (+ end offset))]
                (when (zero? n) (fail))
                (recur (+ offset n))))))
        (sync! fd)
        (finally (fs/closeSync fd))))
    (let [fd (fs/openSync (path/dirname p) "r")]
      (try (sync! fd) (finally (fs/closeSync fd))))
    next-state))

(defn read-states [session]
  (if session
    (let [state (:state (read-log (log-path session)))]
      (when (and (:session state) (not= session (:session state))) (fail))
      [state])
    (let [r (root)]
      (ensure-path! r false)
      (if-not (fs/existsSync r) []
        (let [entries (vec (array-seq (fs/readdirSync r)))]
          (when (> (count entries) 256) (fail))
          (mapv (fn [entry]
                  (when-not (re-matches #"[0-9a-f]{64}" entry) (fail))
                  (let [p (path/join r entry "events.jsonl")
                        state (:state (read-log p))]
                    (when (and (:session state) (not= p (log-path (:session state)))) (fail))
                    state)) entries))))))

(defn validate-global! [states]
  (reduce (fn [seen state]
            (reduce (fn [ids id]
                      (when (contains? ids id) (fail))
                      (conj ids id)) seen (keys (:definitions state)))) #{} states))

(defn lifecycle [state m]
  (cond
    (contains? (:superseded state) (:id m)) "superseded"
    (contains? (:dropped state) (:id m)) "dropped"
    (and (= "reflection" (:kind m)) (some #(contains? (:superseded state) %) (:supports m))) "support_changed"
    :else "active"))

(defn present [state m]
  (cond-> (assoc m :status (lifecycle state m))
    (:citations m) (update :citations #(mapv (fn [c] (assoc c :source_status "not_checked")) %))
    (get-in state [:superseded (:id m)]) (assoc :supersession (get-in state [:superseded (:id m)]))))

(defn list-memories [session]
  (let [states (read-states session)]
    (validate-global! states)
    (->> states
         (mapcat (fn [state] (for [m (vals (:definitions state)) :when (= "active" (lifecycle state m))]
                              (present state m))))
         (sort-by (juxt :recorded_at :id)) vec)))

(defn recall [id session]
  (when-not (identifier? id) (fail))
  (let [states (read-states session)]
    (validate-global! states)
    (some (fn [state]
            (when-let [m (get-in state [:definitions id])]
              (cond-> (present state m)
                (:supports m) (assoc :supporting_observations
                                     (mapv #(present state (get-in state [:definitions %])) (:supports m)))))) states)))
