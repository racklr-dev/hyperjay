;; Real-VM e2e for the M4 snapshot/restore path. Exercises the hyperjay loom
;; wrapper (weave-card! -> snapshot! -> restore-snapshot!), not just the
;; jolt-microvm primitive: proves a stamped rootfs state is captured, restore
;; reverts to it after a fresh boot, and the cached base is left clean.
;;
;;   cd /home/jm/projects/jolt/hyperjay
;;   jolt run test/snapshot_e2e.clj
(ns hyperjay.snapshot-e2e
  (:require [jolt.hyperjay.vm :as loom]
            [jolt.microvm :as vm]
            [clojure.string :as str]))

(def marker "/root/e2e-marker.txt")

(defn poll-state [id]
  (loop [n 0]
    (let [s (get-in @loom/warp [id :state])]
      (if (and (< n 240) (not (#{:running :failed} s)))
        (do (Thread/sleep 500) (recur (inc n)))
        (get @loom/warp id)))))

(defn check! [label ok detail]
  (println (str (if ok "PASS " "FAIL ") label (when detail (str "  " detail))))
  (flush)
  (when-not ok (throw (ex-info (str "check failed: " label) {}))))

(defn exec [id cmd & {:keys [root?] :or {root? false}}]
  (vm/exec (get-in @loom/warp [id :vm]) cmd :root? root?))

(let [card {:id :snapshot-e2e
            :name "snapshot e2e"
            :recipe {:card-id :snapshot-e2e :image "alpine:3.20"}
            :opts {:image "alpine:3.20" :memory-mib 512 :cpus 1}
            :finish nil}
      id (loom/weave-card! card)]
  (println (str "weaving " id " ..."))
  (try
    (let [e (poll-state id)]
      (check! "boot to :running" (= :running (:state e)) (str "state=" (:state e) " err=" (:error e))))
    (check! "guest answers exec" (zero? (:exit (exec id "echo up"))) nil)
    (loom/snapshot! id "e2e-baseline")
    (check! "baseline snapshot saved" (some #{"e2e-baseline"} (loom/snapshots id)) nil)
    (vm/exec! (get-in @loom/warp [id :vm]) (str "sh -c 'echo MARKER > " marker "'") :root? true)
    (let [r (exec id (str "cat " marker) :root? true)]
      (check! "marker written + read back" (str/includes? (:stdout r) "MARKER") (str "exit=" (:exit r) " out=" (pr-str (:stdout r)))))
    (loom/snapshot! id "e2e-mutated")
    (check! "mutated snapshot saved" (= 2 (count (loom/snapshots id))) nil)
    (check! "restore returns :restoring" (= :restoring (loom/restore-snapshot! id "e2e-baseline")) nil)
    (let [e (poll-state id)]
      (check! "restore baseline -> :running" (= :running (:state e)) (str "state=" (:state e) " err=" (:error e))))
    (let [r (exec id (str "cat " marker) :root? true)]
      (check! "after baseline restore marker GONE" (not (zero? (:exit r))) (str "exit=" (:exit r))))
    (loom/restore-snapshot! id "e2e-mutated")
    (let [e (poll-state id)]
      (check! "restore mutated -> :running" (= :running (:state e)) (str "state=" (:state e) " err=" (:error e))))
    (let [r (exec id (str "cat " marker) :root? true)]
      (check! "after mutated restore marker BACK" (str/includes? (:stdout r) "MARKER") (str "exit=" (:exit r) " out=" (pr-str (:stdout r)))))
    (println "snapshot e2e: ALL PASS")
    (finally
      (let [v (get-in @loom/warp [id :vm])]
        (try (loom/cut! id) (catch Exception _ nil))
        (when v
          (try (vm/restore-snapshot! v "e2e-baseline") (catch Exception _ nil))
          (try (vm/delete-snapshot! v "e2e-baseline") (catch Exception _ nil))
          (try (vm/delete-snapshot! v "e2e-mutated") (catch Exception _ nil)))))))
