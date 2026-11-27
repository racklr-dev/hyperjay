;; Headless smoke for the M4 slice-2 wizard refactor: namespace loads, card
;; recipe round-trip, tag sort, and the new jolt.oci/tags resolving from the
;; direct pin. No GTK mount, no VM boot.
(ns hyperjay.wizard-smoke
  (:require [jolt.oci :as oci]
            [jolt.hyperjay.cards :as cards]
            [jolt.hyperjay.library :as lib]
            [jolt.hyperjay.wizard :as wizard]
            [jolt.hyperjay.vm :as loom]))

;; the new helper resolved from the direct jolt-oci pin
(assert (fn? oci/tags) "jolt.oci/tags did not resolve")

;; static rack keeps its exact ids
(assert (= [:desktop-x11 :desktop-wayland :single-app]
           (vec (cards/card-ids)))
        "static card ids changed")

;; recipe round-trip: a wizard card rebuilds identically (finish fns aside)
(let [c (cards/make-card :desktop-x11 "alpine:3.21")
      r (cards/recipe->card (:recipe c))]
  (assert (= (:id c) (:id r)))
  (assert (= "alpine:3.21" (get-in r [:opts :image])))
  (assert (= (get-in c [:opts :packages]) (get-in r [:opts :packages])))
  (assert (= (:recipe c) (:recipe r))))

;; wizard tag sort: latest, edge, then 3.x descending
(let [sorted (->> ["3.20" "2.6" "latest" "3.21" "edge" "3.19"]
                  (sort-by wizard/tag-rank)
                  vec)]
  (assert (= ["latest" "edge" "3.21" "3.20" "3.19" "2.6"] sorted)
          (str "unexpected sort: " sorted)))

;; wizard-pane renders hiccup without touching GTK
(assert (vector? (wizard/wizard-pane)) "wizard-pane did not render hiccup")

;; library round-trip: save a recipe, read it back, then clean up
(let [tmp "/tmp/hyperjay-smoke-library.edn"]
  (with-redefs [lib/library-path (constantly tmp)]
    (reset! lib/saved [])
    (lib/save! "my alpine 3.21 X11" {:card-id :desktop-x11 :image "alpine:3.21"})
    (let [entry (first @lib/saved)]
      (assert (= "my alpine 3.21 X11" (:name entry)))
      (assert (= {:card-id :desktop-x11 :image "alpine:3.21"} (:recipe entry))))))

(println "wizard smoke: ns load + recipe round-trip + tag sort + library OK")