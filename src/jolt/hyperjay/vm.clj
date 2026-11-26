;; The loom.
;;
;; Weaves cards into running microVMs and keeps the warp — the fleet of live
;; threads — in a reactive atom the UI reads. Long work (boot, first-run apk
;; seeding, the ssh wait) runs on a shuttle thread (future), never on the GTK
;; main thread, so the bench stays responsive while a card is being woven.
(ns jolt.hyperjay.vm
  (:require [jolt.microvm :as vm]
            [jolt.hyperjay.cards :as cards]
            [glimmer.ratom :as r]))

(defonce warp
  "The warp: id -> {:id :card-id :name :state :vm :url :error}.

  :state walks :queued -> :booting -> :running, or :failed."
  (r/atom {}))

(defn- next-id []
  (keyword (gensym "warp-")))

(defn- err-str [e]
  (or (ex-message e) (str e)))

(defn- set-state! [id k v]
  (swap! warp assoc-in [id k] v))

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
                            :url nil
                            :error nil})
      (future
        (try
          (set-state! id :state :booting)
          (let [opts (merge {:project-dir (System/getProperty "user.dir")}
                            (:opts card))
                v (vm/start! opts)]
            (if-not (get @warp id)
              ;; the thread was cut while the shuttle was out — unwind quietly
              (vm/stop! v)
              (let [conn (when-let [finish (:finish card)] (finish v))]
                (set-state! id :vm v)
                (set-state! id :url (:url conn))
                (set-state! id :state :running))))
          (catch Exception e
            (when (get @warp id)
              (set-state! id :state :failed)
              (set-state! id :error (err-str e))))))
      id)))

(defn cut!
  "Cut a warp thread: stop the VM if it is alive, then drop its entry."
  [id]
  (when-let [v (get-in @warp [id :vm])]
    (vm/stop! v))
  (swap! warp dissoc id))

(defn reboot!
  "Re-thread a running warp thread: reboot the VM in place."
  [id]
  (when-let [v (get-in @warp [id :vm])]
    (future
      (set-state! id :state :booting)
      (try
        (vm/reboot! v)
        (set-state! id :state :running)
        (catch Exception e
          (set-state! id :state :failed)
          (set-state! id :error (err-str e)))))))

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
