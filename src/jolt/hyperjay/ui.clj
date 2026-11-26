;; The bench — hyperjay's face.
;;
;; Hiccup views that read the warp atom and render the circuit box: one breaker
;; row per warp thread. Picking a card weaves it; each breaker lets you view the
;; desktop (VNC), copy its URL, refresh, reboot, cut, or discard a failed thread.
;;
;; Drawn by glimmer-gtk:  https://github.com/jolt-lang/glimmer-gtk
;; Reactive atoms:        https://github.com/jolt-lang/glimmer
(ns jolt.hyperjay.ui
  (:require [jolt.hyperjay.cards :as cards]
            [jolt.hyperjay.vm :as loom]
            [jolt.hyperjay.gtkx :as gtkx]))   ; registers :selectable-label + clipboard

(defn- state-color [state]
  (case state
    :running "#3fb950"
    :failed  "#f85149"
    :booting "#d29922"
    "#8e939d"))

(defn card-picker
  "The card rack: one button per punched card, plus the auto-view toggle."
  []
  [:frame {:label "Cards"}
   [:vbox {:spacing 4 :margin 6}
    (for [id (cards/card-ids)]
      (let [c (cards/card-by-id id)]
        [:button {:label (:name c)
                  :tooltip (:warp c)
                  :hexpand true
                  :halign :fill
                  :on-click #(loom/weave! id)}]))
    [:separator]
    [:checkbutton {:label "Auto-open VNC viewer"
                   :tooltip "Pop the desktop's VNC viewer when it is ready"
                   :active @loom/auto-view?
                   :on-toggled #(swap! loom/auto-view? not)}]]])

(defn breaker-row
  "One warp thread in the circuit box."
  [[id e]]
  [:frame {:label (str (:name e) " — " (name (:state e)))}
   [:vbox {:spacing 4 :margin 6}
    [:label {:markup [:span {:foreground (state-color (:state e))}
                      (name (:state e))]}]
    (when (:url e)
      [:hbox {:spacing 4}
       [:selectable-label {:label (str "VNC " (:url e)) :wrap true :xalign 0.0}]
       [:button {:label "Copy" :tooltip "Copy the VNC URL to the clipboard"
                 :on-click #(gtkx/copy! (get-in @loom/warp [id :url]))}]
       [:button {:label "View" :tooltip "Open the desktop in a VNC viewer"
                 :on-click #(loom/view! id)}]])
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
