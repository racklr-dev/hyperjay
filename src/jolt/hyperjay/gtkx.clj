;; Small GTK4 extensions glimmer-gtk doesn't ship, needed by the bench:
;;
;;   :selectable-label  a text label whose text can be mouse-selected and
;;                      copied with Ctrl+C (gtk_label_set_selectable)
;;   copy!              write a string to the default display's clipboard
;;
;; Both bind straight into libgtk-4 with jolt.ffi and register through
;; glimmer-gtk.widget's public extension points, so hyperjay never has to fork
;; glimmer-gtk.
;;
;; GTK4 docs for the bits bound here, if you want to poke at them:
;;   gtk_label_set_selectable  https://docs.gtk.org/gtk4/method.Label.set_selectable.html
;;   gdk_clipboard_set_text    https://docs.gtk.org/gdk4/method.Clipboard.set_text.html
(ns jolt.hyperjay.gtkx
  (:require [glimmer-gtk.widget :as w]
            [glimmer-gtk.ffi :as g]
            [jolt.ffi :as ffi]))

;; --- selectable label --------------------------------------------------------
(ffi/defcfn gtk-label-set-selectable "gtk_label_set_selectable" [:pointer :int] :void)

(defn- selectable-label-spec []
  {:ctor (fn [p] (g/gtk-label-new (or (:label p) (:text p) "")))
   :apply (fn [widget p]
            (gtk-label-set-selectable widget 1)
            (when (contains? p :label)  (g/gtk-label-set-label widget (:label p)))
            (when (contains? p :text)   (g/gtk-label-set-text widget (:text p)))
            (when (contains? p :xalign) (g/gtk-label-set-xalign widget (:xalign p)))
            (when (contains? p :wrap)   (g/gtk-label-set-wrap widget (if (:wrap p) 1 0))))
   :container :none})

(w/register-widget! :selectable-label (selectable-label-spec))

;; --- a11y silence ------------------------------------------------------------
(ffi/defcfn g-setenv "g_setenv" [:string :string :int] :int)

(defn silence-a11y!
  "Turn off the accessibility bus before GTK initialises; on hosts without an
  AT-SPI/a11y bus this silences the Gtk-WARNING/dbind-WARNING noise."
  []
  (g-setenv "GTK_A11Y" "none" 1)
  (g-setenv "NO_AT_BRIDGE" "1" 1))

;; --- clipboard ---------------------------------------------------------------
(ffi/defcfn gdk-display-get-default   "gdk_display_get_default"   [] :pointer)
(ffi/defcfn gdk-display-get-clipboard "gdk_display_get_clipboard" [:pointer] :pointer)
(ffi/defcfn gdk-clipboard-set-text    "gdk_clipboard_set_text"    [:pointer :string] :void)

(defn copy!
  "Put s on the default display's clipboard. Call from the GTK main loop (a
  signal handler) — that is where a default display exists."
  [s]
  (when (and s (seq (str s)))
    (let [d (gdk-display-get-default)]
      (when-not (ffi/null? d)
        (let [cb (gdk-display-get-clipboard d)]
          (when-not (ffi/null? cb)
            (gdk-clipboard-set-text cb (str s))))))))
