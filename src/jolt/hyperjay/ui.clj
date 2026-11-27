;; The bench — hyperjay's face, reshaped as a UTM-like three-pane product.
;;
;;   sidebar   — running warps (select to focus), the saved-warp library, and
;;               the card rack for weaving new threads
;;   content   — the focused thread's framebuffer, drawn natively in a GL canvas
;;   inspector — the focused thread's details and the actions you can take on it
;;
;; One reactive atom, loom/focus, selects which warp the content and inspector
;; show; keyboard input routes to it too. Drawn by glimmer-gtk; the GL canvas is
;; glimmer-gl's :gl-area; reactive atoms are glimmer's.
(ns jolt.hyperjay.ui
  (:require [jolt.hyperjay.cards :as cards]
            [jolt.hyperjay.vm :as loom]
            [jolt.hyperjay.library :as lib]
            [jolt.hyperjay.wizard :as wizard]
            [jolt.hyperjay.gtkx :as gtkx]        ; registers :selectable-label + clipboard
            [jolt.hyperjay.display :as display]  ; registers :gl-area via glimmer-gl
            [glimmer.ratom :as r]))

(defn- state-color [state]
  (case state
    :running "#3fb950"
    :failed  "#f85149"
    :booting "#d29922"
    "#8e939d"))

;; --- sidebar -----------------------------------------------------------------
(defn- warp-row
  "One running warp in the sidebar. Click to focus it."
  [[id e]]
  (let [focused? (= id @loom/focus)]
    [:button {:label (str (if focused? "▸ " "  ") (:name e) " — " (name (:state e)))
              :hexpand true :halign :fill
              :tooltip (:warp (:card e))
              :on-click #(reset! loom/focus id)}]))

(defn- library-row
  "One saved warp in the library: weave it, or forget it."
  [{:keys [name recipe]}]
  [:hbox {:spacing 4}
   [:button {:label name
             :hexpand true :halign :fill
             :tooltip (str "weave " recipe)
             :on-click #(let [id (loom/weave-card! (cards/recipe->card recipe))]
                          (reset! loom/focus id))}]
   [:button {:label "✕"
             :tooltip "forget this saved warp"
             :on-click #(lib/remove! name)}]])

(defn- new-card-button
  "One card from the rack: weaving it starts a fresh warp and focuses it."
  [id]
  (let [c (cards/card-by-id id)]
    [:button {:label (str "+ " (:name c))
              :hexpand true :halign :fill
              :tooltip (:warp c)
              :on-click #(let [warp-id (loom/weave! id)]
                           (reset! loom/focus warp-id))}]))

(defn sidebar
  "The left pane: running warps, saved warps, and the card rack."
  []
  [:vbox {:spacing 8 :margin 8 :width-request 250}
   [:frame {:label "Running"}
    [:scrolled {:vexpand true}
     [:vbox {:spacing 2 :margin 4}
      (if (seq @loom/warp)
        (for [[id e] @loom/warp]
          [warp-row [id e]])
        [:label {:markup [:i "Nothing running yet."] :wrap true :xalign 0.0}])]]]
   [:frame {:label "Library"}
    [:scrolled {:vexpand true}
     [:vbox {:spacing 2 :margin 4}
      (if (seq @lib/saved)
        (for [w @lib/saved]
          [library-row w])
        [:label {:markup [:i "No saved warps — focus one and Save."] :wrap true :xalign 0.0}])]]]
   [:frame {:label "New"}
    [:vbox {:spacing 2 :margin 4}
     (for [id (cards/card-ids)]
       [new-card-button id])]]
   [:frame {:label "New from registry"}
    [wizard/wizard-pane]]])

;; --- content -----------------------------------------------------------------
(defn- empty-content []
  [:vbox {:spacing 8 :margin 12 :hexpand true :vexpand true
          :valign :center :halign :center}
   [:label {:markup [:span {:foreground "#8e939d"}
                     (cond
                       (empty? @loom/warp)
                       "Weave a card from the sidebar to begin."

                       (nil? @loom/focus)
                       "Select a running warp to see its display."

                       (= :booting (:state (get @loom/warp @loom/focus)))
                       "Booting the desktop…"

                       (= :failed (:state (get @loom/warp @loom/focus)))
                       "Boot failed — details in the inspector."

                       :else
                       "This thread has no display; attach its console from the inspector.")]
            :wrap true :xalign 0.5}]])

(defn display-pane
  "The embedded canvas: one persistent GL area that uploads the focused thread's
  PPM frame to a texture and draws it, letterboxed to the frame's aspect.
  tick! and the key controller both follow loom/focus, so switching selection
  re-aims the canvas in place."
  []
  [:gl-area {:version [3 2]
             :depth-buffer false
             :hexpand true :vexpand true
             :width-request 640 :height-request 480
             :on-realize (fn [area]
                           (display/realize! area)
                           (display/ensure-keyboard! area))
             :on-render (fn [area] (display/render! area))
             :on-tick (fn [area] (display/tick! area))
             :on-motion (fn [area x y] (display/motion! area x y))
             :on-button (fn [area btn pressed? x y]
                          (display/button! area btn pressed? x y))}])

(defn content-pane
  "The middle pane: the focused thread's display, or guidance when none."
  []
  (let [sel @loom/focus
        entry (get @loom/warp sel)
        show-display? (and sel entry (= :running (:state entry)) (:display entry))]
    [:frame {:label "Display" :hexpand true :vexpand true}
     (if show-display?
       [display-pane]
       [empty-content])]))

;; --- inspector ---------------------------------------------------------------
(defonce save-name (r/atom ""))

;; Snapshot state. snap-version is a counter the snapshots section derefs so it
;; re-renders (and re-lists the directory) after a snapshot action; snap-msg is
;; a [warp-id message] pair, scoped so a stale message never shows under another
;; warp. Snapshot saves/deletes copy whole rootfs trees, so they run on a future
;; off the GTK main thread; glimmer-gtk marshals the resulting ratom writes back
;; onto the main loop.
(defonce snap-name (r/atom ""))
(defonce snap-version (r/atom 0))
(defonce snap-msg (r/atom nil))

(defn- action-buttons [{:keys [id state card]}]
  [:vbox {:spacing 4}
   (when (= :running state)
     [:button {:label "Attach console" :hexpand true :on-click #(loom/attach! id)}])
   (when (= :running state)
     [:button {:label "Reboot" :hexpand true :on-click #(loom/reboot! id)}])
   (when (= :running state)
     [:button {:label "Refresh desktop" :hexpand true :on-click #(loom/refresh! id)}])
   (when (= :running state)
     [:button {:label "Cut" :hexpand true :on-click #(loom/cut! id)}])
   (when (= :failed state)
     [:button {:label "Re-weave" :hexpand true
               :on-click #(let [nid (loom/weave-card! card)]
                            (reset! loom/focus nid))}])
   (when (= :failed state)
     [:button {:label "Discard" :hexpand true :on-click #(loom/cut! id)}])])

(defn- save-form [{:keys [name recipe]}]
  [:vbox {:spacing 4}
   [:label {:markup [:b "Save to library"]}]
   [:entry {:placeholder (str "name (default: " name ")")
            :text @save-name
            :on-change #(reset! save-name %)}]
   [:button {:label "Save"
             :hexpand true
             :on-click #(let [n (or (seq @save-name) name)]
                          (lib/save! n recipe)
                          (reset! save-name ""))}]])

(defn- snapshots-section [{:keys [id state]}]
  (when (= :running state)
    @snap-version ; subscribe: re-render when a snapshot action finishes
    (let [snaps (loom/snapshots id)]
      [:vbox {:spacing 4}
       [:separator]
       [:label {:markup [:b "Snapshots"]}]
       (when (and @snap-msg (= id (first @snap-msg)))
         [:selectable-label {:label (second @snap-msg) :wrap true}])
       [:button {:label "Stamp now" :hexpand true
                 :tooltip "save a timestamped snapshot of this warp"
                 :on-click #(future
                              (try
                                (let [n (loom/stamp! id)]
                                  (reset! snap-msg [id (str "stamped " n)])
                                  (swap! snap-version inc))
                                (catch Exception e
                                  (reset! snap-msg [id (str "stamp failed: " (or (ex-message e) (str e)))]))))}]
       [:hbox {:spacing 4}
        [:entry {:placeholder "snapshot name" :text @snap-name :hexpand true
                 :on-change #(reset! snap-name %)}]
        [:button {:label "Save"
                  :tooltip "save a named snapshot of this warp"
                  :on-click #(future
                               (try
                                 (let [n (loom/snapshot! id @snap-name)]
                                   (reset! snap-name "")
                                   (reset! snap-msg [id (str "saved " n)])
                                   (swap! snap-version inc))
                                 (catch Exception e
                                   (reset! snap-msg [id (str "save failed: " (or (ex-message e) (str e)))]))))}]]
       (if (seq snaps)
         [:vbox {:spacing 2}
          (for [s snaps]
            [:hbox {:spacing 4}
             [:label {:label s :hexpand true :xalign 0.0}]
             [:button {:label "Restore"
                       :tooltip (str "restore " s " — stops and re-boots this warp")
                       :on-click #(try
                                    (loom/restore-snapshot! id s)
                                    (catch Exception e
                                      (reset! snap-msg [id (str "restore failed: " (or (ex-message e) (str e)))])))}]
             [:button {:label "✕"
                       :tooltip (str "delete " s)
                       :on-click #(future
                                    (try
                                      (loom/delete-snapshot! id s)
                                      (reset! snap-msg [id (str "deleted " s)])
                                      (swap! snap-version inc)
                                      (catch Exception e
                                        (reset! snap-msg [id (str "delete failed: " (or (ex-message e) (str e)))]))))}]])]
         [:label {:markup [:i "No snapshots yet — stamp one."] :wrap true :xalign 0.0}])])))

(defn inspector
  "The right pane: the focused warp's details and actions."
  []
  [:frame {:label "Inspector"}
   [:vbox {:spacing 4 :margin 6 :width-request 270}
    (if-let [entry (get @loom/warp @loom/focus)]
      [:vbox {:spacing 4}
       [:label {:markup [:b (:name entry)] :wrap true}]
       [:selectable-label {:label (str "state:  " (name (:state entry)))}]
       [:selectable-label {:label (str "card:   " (:card-id entry))}]
       (when-let [d (:display entry)]
         [:vbox {:spacing 0}
          [:selectable-label {:label (str "ip:     " (:ip d))}]
          [:selectable-label {:label (str "frame:  " (:width d) "×" (:height d))}]])
       (when (:error entry)
         [:selectable-label {:label (str "error:  " (:error entry)) :wrap true}])
       [:separator]
       [action-buttons entry]
       [:separator]
       [save-form entry]
       [snapshots-section entry]]
      [:label {:markup [:i "Select a warp from the sidebar."] :wrap true :xalign 0.5}])]])

;; --- the bench ---------------------------------------------------------------
(defn bench
  "The whole bench: sidebar, content, inspector."
  []
  [:hbox {:spacing 8 :margin 8}
   [sidebar]
   [:separator {:orientation :vertical :vexpand true}]
   [content-pane]
   [:separator {:orientation :vertical :vexpand true}]
   [inspector]])
