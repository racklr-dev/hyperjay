;; The bench — hyperjay's face.
;;
;; Hiccup views that read the warp atom and render the circuit box: one breaker
;; row per warp thread. Picking a card weaves it; each breaker shows the running
;; desktop's framebuffer natively in an embedded canvas, and lets you attach,
;; refresh, reboot, cut, or discard a failed thread.
;;
;; Drawn by glimmer-gtk:  https://github.com/jolt-lang/glimmer-gtk
;; GL canvas by glimmer-gl: https://github.com/jolt-lang/glimmer-gl
;; Reactive atoms:        https://github.com/jolt-lang/glimmer
(ns jolt.hyperjay.ui
  (:require [jolt.hyperjay.cards :as cards]
            [jolt.hyperjay.vm :as loom]
            [jolt.hyperjay.gtkx :as gtkx]        ; registers :selectable-label + clipboard
            [jolt.hyperjay.display :as display])) ; registers :gl-area via glimmer-gl

(defn- state-color [state]
  (case state
    :running "#3fb950"
    :failed  "#f85149"
    :booting "#d29922"
    "#8e939d"))

(defn card-picker
  "The card rack: one button per punched card."
  []
  [:frame {:label "Cards"}
   [:vbox {:spacing 4 :margin 6}
    (for [id (cards/card-ids)]
      (let [c (cards/card-by-id id)]
        [:button {:label (:name c)
                  :tooltip (:warp c)
                  :hexpand true
                  :halign :fill
                  :on-click #(loom/weave! id)}]))]])

(defn display-pane
  "The embedded canvas for a running display thread: a GL area that uploads the
  runner's PPM frame to a texture and draws it. The handlers close over the
  stable warp id; they wire once at mount."
  [id {:keys [display-path width height]}]
  [:frame {:label "Display"}
   [:gl-area {:version [3 2]
              :depth-buffer false
              :hexpand true
              :vexpand true
              :width-request (or width 640)
              :height-request (or height 480)
              :on-realize (fn [area] (display/realize! area))
              :on-render  (fn [area] (display/render! area))
              :on-tick    (fn [area] (display/tick! area id))}]])

(defn breaker-row
  "One warp thread in the circuit box."
  [[id e]]
  [:frame {:label (str (:name e) " — " (name (:state e)))}
   [:vbox {:spacing 4 :margin 6}
    [:label {:markup [:span {:foreground (state-color (:state e))}
                      (name (:state e))]}]
    (when (and (= :running (:state e)) (:display e))
      [display-pane id (:display e)])
    (when (:error e)
      [:label {:label (:error e) :wrap true :max-width-chars 48}])
    [:hbox {:spacing 4}
     (when (= :running (:state e))
       [:button {:label "Attach" :on-click #(loom/attach! id)}])
     (when (= :running (:state e))
       [:button {:label "Refresh" :on-click #(loom/refresh! id)}])
     (when (= :running (:state e))
       [:button {:label "Reboot" :on-click #(loom/reboot! id)}])
     (when (= :running (:state e))
       [:button {:label "Cut" :on-click #(loom/cut! id)}])
     (when (= :failed (:state e))
       [:button {:label "Re-weave" :on-click #(loom/weave! (:card-id e))}])
     (when (= :failed (:state e))
       [:button {:label "Discard" :on-click #(loom/cut! id)}])]]])

(defn circuit-box
  "The warp, as breakers."
  []
  [:frame {:label "Circuit box"}
   [:scrolled {:vexpand true :hexpand true}
    [:vbox {:spacing 6 :margin 6}
     (if (seq @loom/warp)
       (for [[id e] @loom/warp]
         [breaker-row [id e]])
       [:label {:markup [:i "No threads on the warp yet — pick a card from the rack."]}])]]])

(defn bench
  "The whole bench: card rack on the left, circuit box on the right."
  []
  [:hbox {:spacing 12 :margin 12}
   [card-picker]
   [circuit-box]])
