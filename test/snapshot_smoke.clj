;; Headless smoke for the M4 snapshot/stamp UI: namespaces load (including
;; ui.clj with the new snapshots-section), the vm.clj snapshot thin-passes are
;; nil-safe with no VM, restore-snapshot! fails fast on a missing snapshot
;; without mutating warp state, and the inspector renders the snapshots section
;; against a fake running warp. No GTK mount, no VM boot.
(ns hyperjay.snapshot-smoke
  (:require [clojure.string :as str]
            [jolt.hyperjay.vm :as loom]
            [jolt.hyperjay.ui :as ui]
            [glimmer.ratom :as r]))

;; nil-safe thin-passes: no VM in the warp -> [] / nil, never a throw
(assert (= [] (loom/snapshots :no-such-warp)) "snapshots should be [] with no VM")
(assert (nil? (loom/stamp! :no-such-warp)) "stamp! should be nil with no VM")
(assert (nil? (loom/snapshot! :no-such-warp "x")) "snapshot! should be nil with no VM")
(assert (nil? (loom/delete-snapshot! :no-such-warp "x")) "delete-snapshot! should be nil with no VM")
(assert (nil? (loom/restore-snapshot! :no-such-warp "x")) "restore-snapshot! should be nil with no VM")

;; restore-snapshot! fails fast on a missing snapshot, before touching warp state
(reset! loom/warp {})
(swap! loom/warp assoc :fake-warp {:id :fake-warp :vm {:cache-dir "/nonexistent"}})
(let [threw (try
              (loom/restore-snapshot! :fake-warp "nope")
              nil
              (catch Exception e (ex-message e)))]
  (assert threw "restore-snapshot! should throw on a missing snapshot")
  (assert (str/includes? threw "snapshot does not exist")
          (str "unexpected error: " threw))
  (assert (not (contains? (get @loom/warp :fake-warp) :state))
          "fail-fast restore must not mutate warp state"))

;; the inspector renders against a fake running warp (raw hiccup; function
;; components stay unexpanded there)
(reset! loom/warp {})
(swap! loom/warp assoc :fake-running
       {:id :fake-running :name "Fake" :card-id :desktop-x11 :state :running
        :vm {:cache-dir "/nonexistent"}})
(reset! loom/focus :fake-running)
(assert (vector? (ui/inspector)) "inspector did not render hiccup")

;; call the private snapshots-section component directly and check its widgets
(let [section (deref (resolve 'jolt.hyperjay.ui/snapshots-section))
      h (section {:id :fake-running :name "Fake" :card-id :desktop-x11
                  :state :running :vm {:cache-dir "/nonexistent"}})
      s (pr-str h)]
  (assert (vector? h) "snapshots-section did not render hiccup")
  (assert (str/includes? s "Snapshots") "snapshots section header missing")
  (assert (str/includes? s "Stamp now") "stamp button missing")
  (assert (str/includes? s "No snapshots yet") "empty-snapshots hint missing"))

(println "snapshot smoke: ns load + nil-safe thin-passes + fail-fast restore + inspector render OK")
