;; The jacquard cards.
;;
;; Each card is a punched recipe — data, not code — that describes what the
;; loom should weave. The warp threads are the long-lived microVMs; the weft
;; is the shuttle of status and log events that passes through them. This
;; namespace only holds the card catalogue: it starts nothing. The ./cards
;; scripts are just headless CLIs that weave one card and wait.
;;
;; A card is a profile plus an image. The profile decides WHAT runs once the
;; VM is up (the apk package set, the display/keyboard wiring, and the :finish
;; fn); the image decides WHAT boots. The static rack weaves each profile on
;; the default image; the registry wizard weaves any profile on any alpine tag.
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

(def default-image
  "The base image a card boots from when its recipe doesn't say otherwise."
  "alpine:3.20")

(def profiles
  "The boot profiles: profile-id -> what runs once the VM is up. Each holds the
  parts of a card that don't depend on which image tag was picked — the apk
  package set, the start! opts that wire the display/keyboard, and the :finish
  fn that turns a booted VM into a live thread."
  {:desktop-x11
   {:name "X11 desktop"
    :warp "Alpine X11 (Xorg modesetting + openbox + xterm) on the virtio-gpu scanout"
    :packages (desktop/profile :x11)
    :opts {:memory-mib 1024 :cpus 2 :display true :keyboard true :mouse true}
    :finish desktop/start-x11!}

   :desktop-wayland
   {:name "Wayland desktop"
    :warp "Alpine Wayland (sway + wlroots DRM backend + foot) on the virtio-gpu scanout"
    :packages (desktop/profile :wayland)
    :opts {:memory-mib 1024 :cpus 2 :display true :keyboard true :mouse true}
    :finish desktop/start-wayland!}

   ;; Note: this profile's :finish blocks in vm/x11-exec until the app closes,
   ;; so the warp thread reads :booting the whole time the app is on screen.
   :single-app
   {:name "Single X11 app"
    :warp "One xterm from the sandbox onto the host display over ssh -X"
    :packages ["xterm" "xauth" "font-noto" "font-misc-misc"]
    :opts {:memory-mib 512 :x11? true}
    :finish (fn [v] (vm/x11-exec v "xterm"))}})

(defn make-card
  "Build a card from a profile id and an image reference. The profile decides
  what runs (packages + finish); the image decides what boots. The card's
  :recipe is the serializable part — profile id + image — so a saved warp can
  rebuild the same card."
  ([profile-id] (make-card profile-id default-image))
  ([profile-id image]
   (let [p (get profiles profile-id)]
     (when-not p
       (throw (ex-info "unknown profile" {:profile profile-id
                                          :known (keys profiles)})))
     {:id profile-id
      :name (str (:name p) (when (not= image default-image) (str " · " image)))
      :warp (str image " — " (:warp p))
      :recipe {:card-id profile-id :image image}
      :opts (merge {:image image :packages (:packages p)} (:opts p))
      :finish (:finish p)})))

(def cards
  "The card catalogue: card-id -> card (the static rack, all on the default
  image)."
  (into {} (map (fn [[k p]] [k (make-card k)])) profiles))

(defn card-ids
  "The ids, in card order."
  []
  (keys cards))

(defn card-by-id
  "The card with :id id, or nil."
  [id]
  (get cards id))

(defn recipe->card
  "Rebuild a card from a serializable recipe: {:card-id ... :image ...}."
  [{:keys [card-id image]}]
  (make-card card-id (or image default-image)))