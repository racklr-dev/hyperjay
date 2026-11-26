;; The bench — hyperjay's face.
;;
;; Hiccup views that read the warp atom and render the circuit box: one breaker
;; row per warp thread. Picking a card weaves it; each breaker lets you attach,
;; reboot, cut, or discard a failed thread.
(ns jolt.hyperjay.ui
  (:require [jolt.hyperjay.cards :as cards]
            [jolt.hyperjay.vm :as loom]))

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

(defn breaker-row
  "One warp thread in the circuit box."
  [[id e]]
  [:frame {:label (str (:name e) " — " (name (:state e)))}
   [:vbox {:spacing 4 :margin 6}
    [:label {:markup [:span {:foreground (state-color (:state e))}
                      (name (:state e))]}]
    (when (:url e)
      [:label {:label (str "VNC " (:url e)) :wrap true}])
    (when (:error e)
      [:label {:label (:error e) :wrap true :max-width-chars 48}])
    [:hbox {:spacing 4}
     (when (= :running (:state e))
       [:button {:label "Attach" :on-click #(loom/attach! id)}])
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
