;; The loom.
;;
;; Weaves cards into running microVMs and keeps the warp — the fleet of live
;; threads — in a reactive atom the UI reads. Long work (boot, first-run apk
;; seeding, the ssh wait) runs on a shuttle thread (future), never on the GTK
;; main thread, so the bench stays responsive while a card is being woven.
;;
;; Every boot hands the guest a fresh TAP lease (tapmasq takes the next free
;; 10.44.<n>.0/24 subnet), so a thread's VNC URL changes every time the VM
;; reboots. The loom therefore re-derives the URL after every weave/reboot and
;; never caches it, and it manages the VNC viewer window for desktop cards:
;; open it when the desktop is ready, close and reopen it when the URL moves.
;;
;; shutdown! is the loom's off switch: the bench calls it when its window
;; closes, and it cuts every thread — viewers, live VMs, and VMs still booting
;; on a shuttle — so no microVM outlives the process.
;;
;; Links:
;;   jolt-microvm (the boot engine)  https://github.com/racklr-dev/jolt-microvm
;;   the start! opts menu            https://github.com/racklr-dev/jolt-microvm/blob/4547630ef6ef5eebd79ae07c915d943f3ec89dad/src/jolt/microvm.clj#L466
(ns jolt.hyperjay.vm
  (:require [jolt.microvm :as vm]
            [jolt.microvm.desktop :as desktop]
            [jolt.hyperjay.cards :as cards]
            [glimmer.ratom :as r]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defonce warp
  "The warp: id -> {:id :card-id :name :state :vm :conn :url :viewer :shuttle :error}.

  :state walks :queued -> :booting -> :running, or :failed. :shuttle is the
  future doing this thread's slow work, so shutdown! can wait for it."
  (r/atom {}))

(defonce auto-view?
  "When true, opening a desktop thread also pops its VNC viewer on the host
  display — the little mini jolt-vnc."
  (r/atom true))

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

;; -- VNC viewer ---------------------------------------------------------------
(def ^:private viewer-bin
  "Absolute path of the VNC viewer spawned for desktop threads, or nil when none
  is installed. HYPERJAY_VNC_VIEWER overrides the PATH search."
  (memoize
    (fn []
      (let [env (System/getenv "HYPERJAY_VNC_VIEWER")]
        (if (and env (seq env))
          env
          (let [r (shell/sh "sh" "-c"
                            "command -v gvncviewer vncviewer 2>/dev/null | head -1 || true")
                p (str/trim (:out r))]
            (when (seq p) p)))))))

(defn- destroy-viewer-proc! [p]
  (when p
    (try (.destroy p) (catch Exception _))))

(defn- kill-viewer! [id]
  (destroy-viewer-proc! (get-in @warp [id :viewer]))
  (swap! warp update-in [id] dissoc :viewer))

(defn- vnc-arg
  "The argument gvncviewer/gtk-vnc wants for a connection: host:display, where
  display N is served on port 5900+N. The warp's :url is host:5900 for humans,
  but gvncviewer reads that as display 5900 -> port 11800 (the refusal we saw).
  This converts the conn map's port back to the display form."
  [{:keys [ip port]}]
  (str ip ":" (- port 5900)))

(defn view!
  "Open (or reopen) the VNC viewer for a running thread's current connection on
  the host display, detaching the child process. Safe to call again — the old
  viewer is closed first. No-op for cards without a VNC conn (e.g. :single-app)."
  [id]
  (when-let [entry (get @warp id)]
    (let [conn (:conn entry)
          bin (viewer-bin)]
      (when (and conn bin (:ip conn) (:port conn))
        (kill-viewer! id)
        (let [pb (ProcessBuilder. (vec [bin (vnc-arg conn)]))
              env (.environment pb)]
          ;; silence the AT-SPI/a11y bus warnings on hosts without one
          (.put env "GTK_A11Y" "none")
          (.put env "NO_AT_BRIDGE" "1")
          (.inheritIO pb)
          (set-state! id :viewer (.start pb)))))))

;; -- weave / cut --------------------------------------------------------------
(defn weave!
  "Weave a card into the warp: boot a microVM on a shuttle thread and move the
  thread through :queued/:booting to :running (or :failed). Returns the warp
  id immediately; poll @warp for progress."
  [card-id]
  (let [card (cards/card-by-id card-id)]
    (when-not card
      (throw (ex-info "unknown card" {:card-id card-id
                                      :known (cards/card-ids)})))
    (let [id (next-id)]
      (swap! warp assoc id {:id id
                            :card-id card-id
                            :name (:name card)
                            :state :queued
                            :vm nil
                            :conn nil
                            :url nil
                            :viewer nil
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
                (let [conn (when-let [finish (:finish card)] (finish v))]
                  (set-state! id :conn conn)
                  (set-state! id :url (:url conn))
                  (set-state! id :state :running)
                  (when @auto-view? (view! id)))
                ;; the thread was cut while the shuttle was out — unwind quietly
                (vm/stop! v)))
            (catch Exception e
              (when (get @warp id)
                (set-state! id :state :failed)
                (set-state! id :error (err-str e)))))))
      id)))

(defn cut!
  "Cut a warp thread: close its viewer, stop the VM if it is alive, then drop
  its entry."
  [id]
  (kill-viewer! id)
  (when-let [v (get-in @warp [id :vm])]
    (try (vm/stop! v) (catch Exception _)))
  (swap! warp dissoc id))

(defn reboot!
  "Re-thread a running warp thread: reboot the VM in place, then re-establish
  the desktop and re-derive its VNC URL (the TAP lease — and hence the URL's
  IP — changes on every boot), and reopen the viewer."
  [id]
  (when-let [entry (get @warp id)]
    (let [card (cards/card-by-id (:card-id entry))]
      ;; reflect the reboot in the UI immediately; the slow stop+start happens
      ;; on the shuttle thread.
      (kill-viewer! id)
      (set-state! id :state :booting)
      (set-state! id :conn nil)
      (set-state! id :url nil)
      (set-state! id :shuttle
        (future
          (try
            (let [new-vm (vm/reboot! (:vm entry))]
              (if (publish-vm! id new-vm)
                (let [conn (when-let [finish (:finish card)] (finish new-vm))]
                  (set-state! id :conn conn)
                  (set-state! id :url (:url conn))
                  (set-state! id :state :running)
                  (when @auto-view? (view! id)))
                ;; cut while the reboot was in flight — stop the fresh VM
                (vm/stop! new-vm)))
            (catch Exception e
              (when (get @warp id)
                (set-state! id :state :failed)
                (set-state! id :error (err-str e))))))))))

(defn refresh!
  "Heal a running thread without a reboot: stop the in-guest desktop, restart
  it, re-derive the VNC URL, and reopen the viewer. Fixes a dead or confused
  VNC server (and any lease change) in place."
  [id]
  (when-let [entry (get @warp id)]
    (let [card (cards/card-by-id (:card-id entry))
          v (:vm entry)]
      (kill-viewer! id)
      (set-state! id :conn nil)
      (set-state! id :url nil)
      (set-state! id :state :booting)
      (set-state! id :shuttle
        (future
          (try
            (desktop/stop-desktop! v)
            ;; the thread may have been cut while the desktop restarted — don't
            ;; re-create its entry then (no new VM is at stake, so a plain check
            ;; is enough; shutdown!/cut! still own v)
            (when (get @warp id)
              (let [conn (when-let [finish (:finish card)] (finish v))]
                (set-state! id :conn conn)
                (set-state! id :url (:url conn))
                (set-state! id :state :running)
                (when @auto-view? (view! id))))
            (catch Exception e
              (when (get @warp id)
                (set-state! id :state :failed)
                (set-state! id :error (err-str e))))))))))

(defn shutdown!
  "The loom's off switch, called when the bench window closes. Closes every
  viewer, stops every live VM, and empties the warp. In-flight boot shuttles
  stop their own VM once they see their entry is gone (publish-vm! refuses),
  and we wait for them; a shuttle that published just before we took the warp
  can still spawn a viewer afterward, so a final sweep clears those too.
  Nothing is left running for the exiting process to leak."
  []
  (let [old (take-warp!)
        vms (into [] (keep :vm) (vals old))
        shuttles (into [] (keep :shuttle) (vals old))]
    (doseq [e (vals old)] (destroy-viewer-proc! (:viewer e)))
    (doseq [v vms] (try (vm/stop! v) (catch Exception _)))
    (doseq [s shuttles]
      (try (deref s 300000 ::timeout) (catch Exception _)))
    ;; sweep viewers a late shuttle wrote back into the (already emptied) warp
    (doseq [e (vals @warp)] (destroy-viewer-proc! (:viewer e)))
    (reset! warp {})
    :shutdown))

(defn attach!
  "Attach the terminal to a warp thread's console (jolt-pty)."
  [id]
  (when-let [v (get-in @warp [id :vm])]
    (vm/attach! v)))

(defn snapshot!
  "Save a named snapshot of a warp thread."
  [id name]
  (when-let [v (get-in @warp [id :vm])]
    (vm/save-snapshot! v name)))

(defn snapshots
  "List a warp thread's snapshots."
  [id]
  (when-let [v (get-in @warp [id :vm])]
    (vm/list-snapshots v)))
