;; Real-VM e2e: the :desktop-x11 card boots with BOTH a virtual keyboard and
;; a virtual touchscreen (the mouse) attached, and the desktop still comes up.
;; Proves the mouse device coexists with the keyboard and the virtio-gpu
;; scanout without breaking the X11 desktop — the integration the unit + bare
;; mouse e2e tests don't cover.
;;
;;   cd /home/jm/projects/jolt/hyperjay
;;   jolt run test/desktop_input_e2e.clj
(ns hyperjay.desktop-input-e2e
  (:require [jolt.hyperjay.vm :as loom]
            [jolt.hyperjay.cards :as cards]
            [jolt.microvm :as vm]
            [clojure.string :as str]))

(defn poll-state [id]
  (loop [n 0]
    (let [s (get-in @loom/warp [id :state])]
      (if (and (< n 600) (not (#{:running :failed} s)))
        (do (Thread/sleep 500) (recur (inc n)))
        (get @loom/warp id)))))

(defn check! [label ok detail]
  (println (str (if ok "PASS " "FAIL ") label (when detail (str "  " detail))))
  (flush)
  (when-not ok (throw (ex-info (str "check failed: " label) {}))))

(defn exec [id cmd & {:keys [root?] :or {root? false}}]
  (vm/exec (get-in @loom/warp [id :vm]) cmd :root? root?))

(let [card (cards/make-card :desktop-x11)
      id (loom/weave-card! card)]
  (println (str "weaving " id " ..."))
  (try
    (let [e (poll-state id)]
      (check! "desktop-x11 boots to :running (with :mouse true)" (= :running (:state e))
              (str "state=" (:state e) " err=" (:error e))))

    ;; both input devices enumerate in the guest
    (let [r (exec id "grep -c 'libkrun Virtual Keyboard' /proc/bus/input/devices")]
      (check! "guest sees the keyboard" (= "1" (str/trim (:stdout r)))
              (str "exit=" (:exit r) " out=" (pr-str (:stdout r)))))
    (let [r (exec id "grep -c 'libkrun Touchscreen' /proc/bus/input/devices")]
      (check! "guest sees the touchscreen" (= "1" (str/trim (:stdout r)))
              (str "exit=" (:exit r) " out=" (pr-str (:stdout r)))))

    ;; the X11 desktop is actually up (X server socket + a WM client)
    (let [r (exec id "[ -S /tmp/.X11-unix/X0 ] && echo up || echo down")]
      (check! "X11 server socket up" (= "up" (str/trim (:stdout r)))
              (str "out=" (pr-str (:stdout r)))))
    (let [r (exec id "ps aux | grep -c '[o]penbox'")]
      (check! "openbox running" (= "1" (str/trim (:stdout r)))
              (str "out=" (pr-str (:stdout r)))))

    (println "desktop input e2e: ALL PASS")
    (finally
      (try (loom/cut! id) (catch Exception _)))))
