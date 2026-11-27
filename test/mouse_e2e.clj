;; Real-VM e2e for the mouse path: host vm/send-mouse-event! -> .mouse file ->
;; runner poller -> jolt.libkrun.input/send-touch-event! -> virtio-input ->
;; guest evdev /dev/input/event0. Proves the full BTN_TOUCH + ABS_X/ABS_Y +
;; SYN_REPORT round-trip (press AND release), not just the unit-tested
;; jolt-libkrun device. No display needed — a bare alpine boot with :mouse, so
;; it stays fast.
;;
;; The guest's virtio-input driver only posts receive buffers once a reader
;; opens /dev/input/event0, so the test opens the device FIRST (as root, in
;; the background) and then injects events — the same shape the real bench has
;; (the in-guest desktop opens the device at startup and keeps it open).
;;
;;   cd /home/jm/projects/jolt/hyperjay
;;   jolt run test/mouse_e2e.clj
(ns hyperjay.mouse-e2e
  (:require [jolt.hyperjay.vm :as loom]
            [jolt.microvm :as vm]
            [clojure.string :as str]))

(def ev-key 1)
(def ev-abs 3)
(def ev-syn 0)
(def syn-report 0)
(def btn-touch 0x14a)   ; 330
(def abs-x 0)
(def abs-y 1)

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

(defn parse-events
  "Decode od -tu1 bytes into Linux input_event maps. struct input_event is
  24 bytes: tv_sec(8) tv_usec(8) type(u16 LE) code(u16 LE) value(u32 LE)."
  [bytes]
  (loop [i 0 out []]
    (if (>= i (count bytes))
      out
      (let [type (+ (nth bytes (+ i 16)) (* 256 (nth bytes (+ i 17))))
            code (+ (nth bytes (+ i 18)) (* 256 (nth bytes (+ i 19))))
            value (+ (nth bytes (+ i 20))
                     (* 256 (nth bytes (+ i 21)))
                     (* 65536 (nth bytes (+ i 22)))
                     (* 16777216 (nth bytes (+ i 23))))]
        (recur (+ i 24) (conj out {:type type :code code :value value}))))))

(let [card {:id :mouse-e2e
            :name "mouse e2e"
            :recipe {:card-id :mouse-e2e :image "alpine:3.20"}
            :opts {:image "alpine:3.20" :memory-mib 512 :cpus 1 :mouse true}
            :finish nil}
      id (loom/weave-card! card)]
  (println (str "weaving " id " ..."))
  (try
    (let [e (poll-state id)]
      (check! "boot to :running" (= :running (:state e))
              (str "state=" (:state e) " err=" (:error e))))

    ;; the guest enumerates exactly one virtio touchscreen named by our device
    (let [r (exec id "grep -c 'libkrun Touchscreen' /proc/bus/input/devices")]
      (check! "guest sees libkrun Touchscreen" (= "1" (str/trim (:stdout r)))
              (str "exit=" (:exit r) " out=" (pr-str (:stdout r)))))

    ;; open the device first (as root), so the guest driver posts receive
    ;; buffers; the reader blocks until 6 events land.
    (exec id "nohup dd if=/dev/input/event0 of=/tmp/ev.bin bs=24 count=6 </dev/null >/dev/null 2>/tmp/dd.err &" :root? true)
    (Thread/sleep 800)

    ;; host-side injection: press at (640,360), then release
    (check! "send BTN_TOUCH press wired" (vm/send-mouse-event! (get-in @loom/warp [id :vm]) {:type ev-key :code btn-touch :value 1}) nil)
    (check! "send ABS_X wired"          (vm/send-mouse-event! (get-in @loom/warp [id :vm]) {:type ev-abs :code abs-x :value 640}) nil)
    (check! "send ABS_Y wired"          (vm/send-mouse-event! (get-in @loom/warp [id :vm]) {:type ev-abs :code abs-y :value 360}) nil)
    (check! "send SYN wired"            (vm/send-mouse-event! (get-in @loom/warp [id :vm]) {:type ev-syn :code syn-report :value 0}) nil)
    (check! "send BTN_TOUCH release wired" (vm/send-mouse-event! (get-in @loom/warp [id :vm]) {:type ev-key :code btn-touch :value 0}) nil)
    (check! "send SYN wired (release)"  (vm/send-mouse-event! (get-in @loom/warp [id :vm]) {:type ev-syn :code syn-report :value 0}) nil)

    (Thread/sleep 2000)
    (let [r (exec id "od -An -tu1 -v /tmp/ev.bin" :root? true)
          dd-err (exec id "cat /tmp/dd.err 2>/dev/null" :root? true)
          bytes (mapv #(Integer/parseInt %) (str/split (str/trim (:stdout r)) #"\s+"))
          evs (parse-events bytes)]
      (check! "6 events read back" (= 6 (count evs))
              (str "got " (count evs) " events; dd.err=" (pr-str (:stdout dd-err)) " od=" (pr-str (:stdout r))))
      (when (= 6 (count evs))
        (check! "ev1 BTN_TOUCH press" {:type ev-key :code btn-touch :value 1} (nth evs 0))
        (check! "ev2 ABS_X"           {:type ev-abs :code abs-x :value 640} (nth evs 1))
        (check! "ev3 ABS_Y"           {:type ev-abs :code abs-y :value 360} (nth evs 2))
        (check! "ev4 EV_SYN"          {:type ev-syn :code syn-report :value 0} (nth evs 3))
        (check! "ev5 BTN_TOUCH release" {:type ev-key :code btn-touch :value 0} (nth evs 4))
        (check! "ev6 EV_SYN"          {:type ev-syn :code syn-report :value 0} (nth evs 5))))
    (println "mouse e2e: ALL PASS")
    (finally
      (try (loom/cut! id) (catch Exception _)))))
