;; Weave the Wayland desktop card headlessly: boot the microVM, start the
;; desktop, print the VNC url, then wait for Enter to cut the thread.
;;
;;   jolt run cards/desktop-wayland.clj
(require '[jolt.hyperjay.cards :as cards]
         '[jolt.hyperjay.vm :as loom])

(let [id (loom/weave! :desktop-wayland)]
  (while (not (#{:running :failed} (get-in @loom/warp [id :state])))
    (Thread/sleep 250))
  (let [e (get @loom/warp id)]
    (if (= :running (:state e))
      (do
        (println (:url e))
        (println "Open the url above in a VNC client, then press Enter to cut the thread.")
        (read-line))
      (println "weave failed:" (:error e))))
  (loom/cut! id))
