;; The loom.
;;
;; Weaves cards into running microVMs and keeps the warp — the fleet of live
;; threads — in a reactive atom the UI reads. Long work (boot, first-run apk
;; seeding, the ssh wait) runs on a shuttle thread (future), never on the GTK
;; main thread, so the bench stays responsive while a card is being woven.
;;
;; A desktop card's :finish starts the guest desktop against the virtio-gpu
;; scanout and returns the frame source — {:ip :display-path :width :height} —
;; that the bench's embedded canvas reads from :display-path (a P6 PPM the
;; runner rewrites on every presented frame). No VNC server, no external viewer.
;;
;; shutdown! is the loom's off switch: the bench calls it when its window
;; closes, and it cuts every thread — live VMs and VMs still booting on a
;; shuttle — so no microVM outlives the process.
;;
;; Links:
;;   jolt-microvm (the boot engine)  https://github.com/racklr-dev/jolt-microvm
;;   the start! opts menu            https://github.com/racklr-dev/jolt-microvm/blob/6e282bf8e59031e08ec1c918d1851ae8e176eef5/src/jolt/microvm.clj#L466
(ns jolt.hyperjay.vm
  (:require [jolt.microvm :as vm]
            [jolt.microvm.desktop :as desktop]
            [jolt.hyperjay.cards :as cards]
            [glimmer.ratom :as r]))

(defonce warp
  "The warp: id -> {:id :card-id :name :state :vm :display :shuttle :error}.

  :state walks :queued -> :booting -> :running, or :failed. :display is the
  desktop frame source for display cards (nil for :single-app). :shuttle is the
  future doing this thread's slow work, so shutdown! can wait for it."
  (r/atom {}))

(defonce focus
  "The warp id the bench is focused on: its display fills the content pane, its
  details the inspector, and keyboard input routes to it. UI state, but kept
  here so display.clj and ui.clj can both read it without a dependency cycle."
  (r/atom nil))

(defn- next-id [] (keyword (gensym "warp-")))

(defn- err-str [e] (or (ex-message e) (str e)))

(defn- set-state! [id k v] (swap! warp assoc-in [id k] v))

(defn- take-warp!
  "Atomically remove and return every warp entry. Returns the map just removed.
  warp is a glimmer.ratom.RAtom (not a clojure.lang.IAtom, so compare-and-set!
  doesn't apply); its :state field is the host atom, and swap-vals! on that is
  the atomic read-and-replace. Watcher notification is deliberately skipped —
  at shutdown the window is gone and we don't want a re-render into it."
  []
  (first (swap-vals! (:state warp) (constantly {}))))

(defn- publish-vm!
  "Atomically publish a freshly booted VM v into id's entry, or refuse when the
  entry was cut/cleared while the shuttle was out. Returns v when published,
  nil when the caller must stop v itself. Atomic so a VM is never both missed
  by shutdown! and left for the process to leak."
  [id v]
  (let [m (swap! warp (fn [w] (if (get w id) (assoc-in w [id :vm] v) w)))]
    (when (= v (get-in m [id :vm])) v)))

;; -- weave / cut --------------------------------------------------------------
(defn weave-card!
  "Weave a card map into the warp: boot a microVM on a shuttle thread and move
  the thread through :queued/:booting to :running (or :failed). Returns the
  warp id immediately; poll @warp for progress."
  [card]
  (let [id (next-id)]
    (swap! warp assoc id {:id id
                          :card card
                          :card-id (:id card)
                          :name (:name card)
                          :recipe (:recipe card)
                          :state :queued
                          :vm nil
                          :display nil
                          :shuttle nil
                          :error nil})
    (set-state! id :shuttle
        (future
          (try
            (set-state! id :state :booting)
            ;; The entire boot config: the card's :opts from cards.clj is handed
            ;; straight to jolt.microvm/start!. That's the only place hyperjay
            ;; decides WHAT boots — start! decides HOW (see the link above).
            (let [opts (merge {:project-dir (System/getProperty "user.dir")}
                              (:opts card))
                  v (vm/start! opts)]
              (if (publish-vm! id v)
                (let [disp (when-let [finish (:finish card)] (finish v))]
                  (set-state! id :display disp)
                  (set-state! id :state :running))
                ;; the thread was cut while the shuttle was out — unwind quietly
                (vm/stop! v)))
            (catch Exception e
              (when (get @warp id)
                (set-state! id :state :failed)
                (set-state! id :error (err-str e)))))
            nil))
    id))

(defn weave!
  "Weave a static card (by id) into the warp. See weave-card!."
  [card-id]
  (if-let [card (cards/card-by-id card-id)]
    (weave-card! card)
    (throw (ex-info "unknown card" {:card-id card-id
                                    :known (cards/card-ids)}))))

(defn cut!
  "Cut a warp thread: stop the VM if it is alive, then drop its entry."
  [id]
  (when-let [v (get-in @warp [id :vm])]
    (try (vm/stop! v) (catch Exception _)))
  (when (= id @focus) (reset! focus nil))
  (swap! warp dissoc id))

(defn reboot!
  "Re-thread a running warp thread: reboot the VM in place, then re-establish
  the desktop (the TAP lease — and hence the guest IP — changes on every boot)."
  [id]
  (when-let [entry (get @warp id)]
    (let [card (:card entry)]
      ;; reflect the reboot in the UI immediately; the slow stop+start happens
      ;; on the shuttle thread.
      (set-state! id :state :booting)
      (set-state! id :display nil)
      (set-state! id :shuttle
        (future
          (try
            (let [new-vm (vm/reboot! (:vm entry))]
              (if (publish-vm! id new-vm)
                (let [disp (when-let [finish (:finish card)] (finish new-vm))]
                  (set-state! id :display disp)
                  (set-state! id :state :running))
                ;; cut while the reboot was in flight — stop the fresh VM
                (vm/stop! new-vm)))
            (catch Exception e
              (when (get @warp id)
                (set-state! id :state :failed)
                (set-state! id :error (err-str e)))))
            nil)))))

(defn refresh!
  "Heal a running thread without a reboot: stop the in-guest desktop, restart
  it, and re-derive the frame source. Fixes a dead or confused desktop in place."
  [id]
  (when-let [entry (get @warp id)]
    (let [card (:card entry)
          v (:vm entry)]
      (set-state! id :display nil)
      (set-state! id :state :booting)
      (set-state! id :shuttle
        (future
          (try
            (desktop/stop-desktop! v)
            ;; the thread may have been cut while the desktop restarted — don't
            ;; re-create its entry then (no new VM is at stake, so a plain check
            ;; is enough; shutdown!/cut! still own v)
            (when (get @warp id)
              (let [disp (when-let [finish (:finish card)] (finish v))]
                (set-state! id :display disp)
                (set-state! id :state :running)))
            (catch Exception e
              (when (get @warp id)
                (set-state! id :state :failed)
                (set-state! id :error (err-str e)))))
            nil)))))

(defn shutdown!
  "The loom's off switch, called when the bench window closes. Stops every live
  VM and empties the warp. In-flight boot shuttles stop their own VM once they
  see their entry is gone (publish-vm! refuses), and we wait for them. Nothing
  is left running for the exiting process to leak."
  []
  (let [old (take-warp!)
        vms (into [] (keep :vm) (vals old))
        shuttles (into [] (keep :shuttle) (vals old))]
    (doseq [v vms] (try (vm/stop! v) (catch Exception _)))
    (doseq [s shuttles]
      (try (deref s 300000 ::timeout) (catch Exception _)))
    (reset! warp {})
    (reset! focus nil)
    :shutdown))

(defn attach!
  "Attach the terminal to a warp thread's console (jolt-pty)."
  [id]
  (when-let [v (get-in @warp [id :vm])]
    (vm/attach! v)))

(defn send-key!
  "Forward a Linux key press/release to a warp thread's virtual keyboard. The
  VM must have been started with :keyboard (the desktop cards set it)."
  [id code pressed?]
  (when-let [v (get-in @warp [id :vm])]
    (vm/send-key! v code pressed?)))

(defn- stamp-name []
  (str "stamp-" (.format (java.text.SimpleDateFormat. "yyyyMMdd-HHmmss")
                         (java.util.Date.))))

(defn stamp!
  "Stamp a warp thread: save a timestamped snapshot, no name to remember. Runs
  the rootfs copy inline, so call it off the GTK main thread. Returns the name."
  [id]
  (when-let [v (get-in @warp [id :vm])]
    (let [base (stamp-name)
          name (loop [n 0]
                 (let [candidate (if (zero? n) base (str base "-" n))]
                   (if (some #{candidate} (vm/list-snapshots v))
                     (recur (inc n))
                     candidate)))]
      (vm/save-snapshot! v name)
      name)))

(defn snapshot!
  "Save a named snapshot of a warp thread. Runs the rootfs copy inline, so call
  it off the GTK main thread. Returns the name."
  [id name]
  (when-let [v (get-in @warp [id :vm])]
    (vm/save-snapshot! v name)
    name))

(defn snapshots
  "List a warp thread's snapshots (sorted)."
  [id]
  (if-let [v (get-in @warp [id :vm])]
    (vm/list-snapshots v)
    []))

(defn delete-snapshot!
  "Delete a warp thread's snapshot. Runs the tree delete inline, so call it off
  the GTK main thread. Returns the name."
  [id name]
  (when-let [v (get-in @warp [id :vm])]
    (vm/delete-snapshot! v name)
    name))

(defn restore-snapshot!
  "Restore a warp thread from a snapshot: stop the VM, restore the snapshot over
  the cached base, and boot a fresh VM from it — the snapshot takes effect on
  this next boot. Slow, so it runs on the shuttle thread; the warp moves through
  :booting back to :running. Returns :restoring."
  [id name]
  (when-let [entry (get @warp id)]
    (let [v (:vm entry)
          card (:card entry)]
      ;; fail fast before stopping the VM — a missing snapshot must not cut a
      ;; healthy warp
      (when-not (some #{name} (vm/list-snapshots v))
        (throw (ex-info "snapshot does not exist" {:name name})))
      (set-state! id :state :booting)
      (set-state! id :display nil)
      (set-state! id :shuttle
        (future
          (try
            (vm/stop! v)
            (vm/restore-snapshot! v name)
            (let [new-vm (vm/start! (:opts v))]
              (if (publish-vm! id new-vm)
                (let [disp (when-let [finish (:finish card)] (finish new-vm))]
                  (set-state! id :display disp)
                  (set-state! id :state :running))
                (vm/stop! new-vm)))
            (catch Exception e
              (when (get @warp id)
                (set-state! id :state :failed)
                (set-state! id :error (err-str e)))))
          nil))
      :restoring)))
