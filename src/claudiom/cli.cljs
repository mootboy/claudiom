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
      (if (= command "list") (print-json (store/list-memories session))
          (if-let [memory (store/recall id session)] (print-json memory)
              (do (js/console.error "Memory not found.") (set! (.-exitCode js/process) 1)))))
    (catch :default _
      (js/console.error "Invalid arguments or unsafe/corrupt native storage.")
      (set! (.-exitCode js/process) 2))))

(defn start [arguments version]
  (let [[command & args] (js->clj arguments)]
    (case command
      "status" (print-json (status version))
      ("list" "recall") (reader-command command args)
      ("--help" "-h" nil) (js/console.log "Usage: claudiom status | list [--session <id>] | recall <id> [--session <id>] | --help\nGeneration disabled. Retrieval is read-only; no model calls or hooks.")
      (do (js/console.error "Unknown command. Use claudiom --help.")
          (set! (.-exitCode js/process) 1)))))
