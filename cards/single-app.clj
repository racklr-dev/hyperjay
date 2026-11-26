;; Weave the single-app card headlessly: boot the microVM and run xterm on the
;; host display over ssh -X. The card's :finish blocks in x11-exec until the
;; app closes, so the warp thread reads :booting the whole time xterm is up.
;;
;;   jolt run cards/single-app.clj
(require '[jolt.hyperjay.cards :as cards]
         '[jolt.hyperjay.vm :as loom])

(let [id (loom/weave! :single-app)]
  (while (not (#{:running :failed} (get-in @loom/warp [id :state])))
    (Thread/sleep 250))
  (let [e (get @loom/warp id)]
    (if (= :running (:state e))
      (println "xterm closed; cutting the thread.")
      (println "weave failed:" (:error e))))
  (loom/cut! id))
