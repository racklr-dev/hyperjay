;; hyperjay — the loom.
;;
;; Weave jacquard cards into a warp of jolt-microVMs and work the circuit box
;; from a GTK4 bench. jolt from top to bottom: glimmer-gtk draws, jolt-microvm
;; runs, libkrun hosts — native Clojure on Chez Scheme, no JVM.
(ns jolt.hyperjay
  (:require [glimmer.core :as ui]
            [glimmer-gtk.core]        ; installs the GTK4 backend
            [jolt.hyperjay.ui :as bench]))

(defn -main [& _]
  (ui/run bench/bench
          :title "hyperjay — the loom"
          :width 720
          :height 520))
