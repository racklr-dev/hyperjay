;; The registry wizard — guided creation from a tag list.
;;
;; pick image:tag -> weave (jolt-microvm pulls the image) -> the profile decides
;; what runs. Tags are fetched from the registry's tags/list on a shuttle thread
;; (network I/O must not block the GTK main thread), then the pane offers a
;; scrollable tag list, a free-text tag entry, and the three boot profiles.
;; Weave hands cards/make-card a full card to loom/weave-card!.
(ns jolt.hyperjay.wizard
  (:require [clojure.string :as str]
            [jolt.oci :as oci]
            [jolt.hyperjay.cards :as cards]
            [jolt.hyperjay.vm :as loom]
            [glimmer.ratom :as r]))

(defonce wizard
  "Wizard state: status in idle|loading|loaded|error, plus the fetched tags, a
  selected tag string, and a selected profile keyword."
  (r/atom {:status :idle
           :tags []
           :error nil
           :tag "3.20"
           :profile :desktop-x11}))

(def base-image
  "The bootable base the wizard offers. jolt-microvm seeds packages with apk, so
  only alpine-based images boot today."
  {:registry "docker.io" :repo "library/alpine" :ref "alpine"})

(defn tag-rank
  "Sort key: latest and edge first, then 3.x by descending minor, then the rest."
  [t]
  (cond
    (= t "latest") [0 0 ""]
    (= t "edge")   [1 0 ""]
    (re-matches #"3\.\d+.*" t)
    [2 (- (Long/parseLong (second (re-find #"3\.(\d+)" t)))) ""]
    :else [3 0 t]))

(defn load-tags!
  "Fetch the base image's tags from the registry on a shuttle thread."
  []
  (when-not (= :loading (:status @wizard))
    (swap! wizard assoc :status :loading :error nil)
    (future
      (try
        (let [tags (->> (oci/tags (:registry base-image) (:repo base-image))
                        (sort-by tag-rank)
                        vec)]
          (swap! wizard assoc :status :loaded :tags tags))
        (catch Exception e
          (swap! wizard assoc :status :error :error (or (ex-message e) (str e))))))))

(defn weave!
  "Weave the selected profile on the selected tag, and focus the new warp."
  []
  (let [{:keys [tag profile]} @wizard
        image (str (:ref base-image) ":" tag)
        card (cards/make-card profile image)]
    (reset! loom/focus (loom/weave-card! card))))

;; --- pane --------------------------------------------------------------------
(defn- tag-row [tag]
  (let [selected? (= tag (:tag @wizard))]
    [:button {:label (str (if selected? "▸ " "  ") tag)
              :hexpand true :halign :fill
              :on-click #(swap! wizard assoc :tag tag)}]))

(defn- profile-button [pid]
  (let [p (get cards/profiles pid)
        selected? (= pid (:profile @wizard))]
    [:button {:label (str (if selected? "▸ " "  ") (:name p))
              :hexpand true :halign :fill
              :tooltip (:warp p)
              :on-click #(swap! wizard assoc :profile pid)}]))

(defn wizard-pane
  "The guided-creation pane: pick a tag and a profile, then weave."
  []
  (let [{:keys [status tags error tag profile]} @wizard]
    [:vbox {:spacing 4 :margin 4}
     [:label {:markup [:i (str (:ref base-image) "  (") [:span "docker.io/"] (:repo base-image) ")"]
              :wrap true :xalign 0.0}]
     [:button {:label (case status
                        :idle "Load tags"
                        :loading "Loading…"
                        :loaded "Reload tags"
                        :error "Retry")
               :hexpand true
               :on-click #(load-tags!)}]
     (when (= :error status)
       [:label {:markup [:span {:foreground "#f85149"} (str "error: " error)]
                :wrap true :xalign 0.0}])
     [:label {:markup [:b "Tag"]}]
     [:scrolled {:vexpand true :height-request 130}
      [:vbox {:spacing 1}
       (if (seq tags)
         (for [t tags] [tag-row t])
         [:label {:markup [:i (case status
                                :loading "Fetching tags…"
                                :loaded "no tags"
                                "load tags to browse")]
                  :wrap true :xalign 0.0}])]]
     [:entry {:placeholder "tag (e.g. 3.20)"
              :text tag
              :on-change #(swap! wizard assoc :tag %)}]
     [:label {:markup [:b "Profile"]}]
     [:vbox {:spacing 1}
      (for [pid (cards/card-ids)] [profile-button pid])]
     [:button {:label (str "Weave " (:ref base-image) ":" tag)
               :hexpand true
               :on-click #(weave!)}]]))