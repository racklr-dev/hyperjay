;; hyperjay — the loom.
;;
;; Weave jacquard cards into a warp of jolt-microVMs and work the circuit box
;; from a GTK4 bench. jolt from top to bottom: glimmer-gtk draws, jolt-microvm
;; runs, libkrun hosts — native Clojure on Chez Scheme, no JVM.
;;
;; Reading guide — each file is small on purpose, because the heavy lifting is
;; borrowed from jolt libraries:
;;   cards.clj  what you can boot              (the card catalogue)
;;   vm.clj     how it boots and gets cut down (the loom)
;;   ui.clj     what you see                   (the bench)
;;   gtkx.clj   small GTK4 extras              (selectable text + clipboard)
;;
;; Links:
;;   jolt-microvm (the boot engine)  https://github.com/racklr-dev/jolt-microvm
;;   glimmer / glimmer-gtk (the UI)  https://github.com/jolt-lang/glimmer
;;   jolt docs                       https://nodecode.ai/docs
(ns jolt.hyperjay
  (:require [glimmer.core :as ui]
            [glimmer-gtk.core]        ; installs the GTK4 backend
            [jolt.hyperjay.ui :as bench]
            [jolt.hyperjay.vm :as loom]
            [jolt.hyperjay.gtkx :as gtkx]))

(defn -main [& _]
  (gtkx/silence-a11y!)   ; before GTK initialises
  (try
    ;; ui/run blocks until the bench window closes under `jolt run`, then
    ;; returns; shutdown! then cuts every warp thread (live microVMs).
    ;; Under an nREPL dev session ui/run hops to the main thread and returns
    ;; immediately — close the bench there with (jolt.hyperjay.vm/shutdown!).
    (ui/run bench/bench
            :title "hyperjay — the loom"
            :width 720
            :height 520)
    (finally
      (loom/shutdown!))))
