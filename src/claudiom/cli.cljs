(ns claudiom.cli)

(defn status [version]
  {:name "claudiom" :version version
   :enabled false :implemented false
   :message "Bootstrap only; memory generation is not implemented."})

(defn start [arguments version]
  (case (first (js->clj arguments))
    "status" (js/console.log (js/JSON.stringify (clj->js (status version))))
    ("--help" "-h" nil) (js/console.log "Usage: claudiom status | --help\nBootstrap only. No model calls, hooks or memory writes.")
    (do (js/console.error "Unknown command. Use claudiom --help.")
        (set! (.-exitCode js/process) 1))))
