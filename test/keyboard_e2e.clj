;; Real-VM e2e for the keyboard path: host loom/send-key! -> .keys file ->
;; runner poller -> jolt.libkrun.input/send-event! -> virtio-input -> guest
;; evdev /dev/input/event0. Proves the full round-trip (press AND release),
;; not just the unit-tested jolt-libkrun device. No display, no desktop
;; packages: a bare alpine boot with :keyboard, so it stays fast.
;;
;; The guest's virtio-input driver only posts receive buffers once a reader
;; opens /dev/input/event0, so the test opens the device FIRST (as root, in
;; the background) and then injects keys — the same shape the real bench has
;; (the in-guest desktop opens the device at startup and keeps it open).
;;
;;   cd /home/jm/projects/jolt/hyperjay
;;   jolt run test/keyboard_e2e.clj
(ns hyperjay.keyboard-e2e
  (:require [jolt.hyperjay.vm :as loom]
            [jolt.microvm :as vm]
            [clojure.string :as str]))

(def key-a 30)
(def key-s 31)

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

(let [card {:id :kbd-e2e
            :name "keyboard e2e"
            :recipe {:card-id :kbd-e2e :image "alpine:3.20"}
            :opts {:image "alpine:3.20" :memory-mib 512 :cpus 1 :keyboard true}
            :finish nil}
      id (loom/weave-card! card)]
  (println (str "weaving " id " ..."))
  (try
    (let [e (poll-state id)]
      (check! "boot to :running" (= :running (:state e))
              (str "state=" (:state e) " err=" (:error e))))

    ;; the guest enumerates exactly one virtio keyboard named by our device
    (let [r (exec id "grep -c 'libkrun Virtual Keyboard' /proc/bus/input/devices")]
      (check! "guest sees libkrun Virtual Keyboard" (= "1" (str/trim (:stdout r)))
              (str "exit=" (:exit r) " out=" (pr-str (:stdout r)))))

    ;; open the device first (as root), so the guest driver posts receive
    ;; buffers; the reader blocks until 8 events (4 keys x EV_KEY+EV_SYN) land.
    (exec id "nohup dd if=/dev/input/event0 of=/tmp/ev.bin bs=24 count=8 </dev/null >/dev/null 2>/tmp/dd.err &" :root? true)
    (Thread/sleep 800)

    ;; host-side injection: A press, A release, S press, S release
    (check! "send-key A press wired" (vm/send-key! (get-in @loom/warp [id :vm]) key-a true) nil)
    (check! "send-key A release wired" (vm/send-key! (get-in @loom/warp [id :vm]) key-a false) nil)
    (check! "send-key S press wired" (vm/send-key! (get-in @loom/warp [id :vm]) key-s true) nil)
    (check! "send-key S release wired" (vm/send-key! (get-in @loom/warp [id :vm]) key-s false) nil)

    (Thread/sleep 2000)
    (let [r (exec id "od -An -tu1 -v /tmp/ev.bin" :root? true)
          dd-err (exec id "cat /tmp/dd.err 2>/dev/null" :root? true)
          bytes (mapv #(Integer/parseInt %) (str/split (str/trim (:stdout r)) #"\s+"))
          evs (parse-events bytes)]
      (check! "8 events read back" (= 8 (count evs))
              (str "got " (count evs) " events; dd.err=" (pr-str (:stdout dd-err)) " od=" (pr-str (:stdout r))))
      (when (= 8 (count evs))
        (check! "ev1 KEY_A press"   {:type 1 :code key-a :value 1} (nth evs 0))
        (check! "ev2 EV_SYN"        {:type 0 :code 0 :value 0} (nth evs 1))
        (check! "ev3 KEY_A release" {:type 1 :code key-a :value 0} (nth evs 2))
        (check! "ev4 EV_SYN"        {:type 0 :code 0 :value 0} (nth evs 3))
        (check! "ev5 KEY_S press"   {:type 1 :code key-s :value 1} (nth evs 4))
        (check! "ev6 EV_SYN"        {:type 0 :code 0 :value 0} (nth evs 5))
        (check! "ev7 KEY_S release" {:type 1 :code key-s :value 0} (nth evs 6))
        (check! "ev8 EV_SYN"        {:type 0 :code 0 :value 0} (nth evs 7))))
    (println "keyboard e2e: ALL PASS")
    (finally
      (try (loom/cut! id) (catch Exception _)))))
