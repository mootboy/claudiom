(ns claudiom.cli
  (:require [claudiom.store :as store]))

(defn status [version]
  {:name "claudiom" :version version
   :enabled false :implemented true
   :message "Native persistence and retrieval available; generation is not implemented."})

(defn print-json [value] (js/console.log (js/JSON.stringify (clj->js value))))

(defn reader-command [command args]
  (try
    (let [[id remaining] (if (= command "recall") [(first args) (rest args)] [nil args])
          session (cond (empty? remaining) nil
                        (and (= 2 (count remaining)) (= "--session" (first remaining))) (second remaining)
                        :else (store/fail))]
      (when (and (= command "recall") (nil? id)) (store/fail))
      (let [{:keys [result warnings incomplete]} (store/retrieve command id session)]
        (doseq [{:keys [kind session_hash]} warnings]
          (js/console.error
           (str (case kind "repair_needed" "Repair needed for session " "conflict" "Conflicting session " "Corrupt session ")
                session_hash "; results incomplete.")))
        (if (or (= command "list") result) (print-json result)
            (do (js/console.error (if incomplete "Memory unavailable; results incomplete." "Memory not found."))
                (set! (.-exitCode js/process) (if incomplete 2 1))))))
    (catch :default _
      (js/console.error "Invalid arguments or unsafe/corrupt native storage.")
      (set! (.-exitCode js/process) 2))))

(defn repair-command [args]
  (try
    (when-not (and (= 2 (count args)) (= "--session" (first args))) (store/fail))
    (print-json (store/repair! (second args)))
    (catch :default _
      (js/console.error "Invalid arguments or unsafe/corrupt native storage.")
      (set! (.-exitCode js/process) 2))))

(defn start [arguments version]
  (let [[command & args] (js->clj arguments)]
    (case command
      "status" (print-json (status version))
      ("list" "recall") (reader-command command args)
      "repair" (repair-command args)
      ("--help" "-h" nil) (js/console.log "Usage: claudiom status | list [--session <id>] | recall <urn> | recall <local-id> --session <id> | repair --session <id> | --help\nGeneration disabled. Retrieval is read-only; no model calls or hooks.")
      (do (js/console.error "Unknown command. Use claudiom --help.")
          (set! (.-exitCode js/process) 1)))))
