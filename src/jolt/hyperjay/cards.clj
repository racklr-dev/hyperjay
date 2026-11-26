;; The jacquard cards.
;;
;; Each card is a punched recipe — data, not code — that describes what the
;; loom should weave. The warp threads are the long-lived microVMs; the weft
;; is the shuttle of status and log events that passes through them. This
;; namespace only holds the card catalogue: it starts nothing. The ./cards
;; scripts are just headless CLIs that weave one card and wait.
;;
;; This is the whole boot config hyperjay owns: each card's :opts is handed
;; verbatim to jolt.microvm/start!, and :finish runs once the VM is up.
;; Everything else — kernel, initrd, OCI image, mounts, sshd, egress,
;; networking — lives in the jolt-microvm library. To change WHAT boots, edit
;; :opts here; to learn HOW it boots, read start!'s menu at:
;;   https://github.com/racklr-dev/jolt-microvm/blob/4547630ef6ef5eebd79ae07c915d943f3ec89dad/src/jolt/microvm.clj#L466
(ns jolt.hyperjay.cards
  (:require [jolt.microvm :as vm]
            [jolt.microvm.desktop :as desktop]))

(def cards
  "The card catalogue: card-id -> card.

  A card map:
    :id      keyword id
    :name    human name shown in the loom
    :warp    one-line description of the thread it weaves
    :opts    base options handed to jolt.microvm/start! (the loom merges in
             :project-dir at weave time)
    :finish  (fn [vm] -> connection map) desktop/X11 wiring, or nil"
  {:desktop-x11
   {:id :desktop-x11
    :name "X11 desktop"
    :warp "Alpine X11 (Xvfb + openbox + xterm + x11vnc) served over VNC"
    :opts {:image "alpine:3.20"
           :packages (desktop/profile :x11)
           :memory-mib 1024
           :cpus 2}
    :finish desktop/start-x11!}

   :desktop-wayland
   {:id :desktop-wayland
    :name "Wayland desktop"
    :warp "Alpine Wayland (sway + foot + wayvnc) served over VNC"
    :opts {:image "alpine:3.20"
           :packages (desktop/profile :wayland)
           :memory-mib 1024
           :cpus 2}
    :finish desktop/start-wayland!}

   ;; Note: this card's :finish blocks in vm/x11-exec until the app closes, so
   ;; the warp thread reads :booting the whole time the app is on screen.
   :single-app
   {:id :single-app
    :name "Single X11 app"
    :warp "One xterm from the sandbox onto the host display over ssh -X"
    :opts {:image "alpine:3.20"
           :packages ["xterm" "xauth" "font-noto" "font-misc-misc"]
           :x11? true
           :memory-mib 512}
    :finish (fn [v] (vm/x11-exec v "xterm"))}})

(defn card-ids
  "The ids, in card order."
  []
  (keys cards))

(defn card-by-id
  "The card with :id id, or nil."
  [id]
  (get cards id))
