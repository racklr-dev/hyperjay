;; The saved-warp library — reusable boot recipes persisted across sessions.
;;
;; Each saved warp is a named recipe that remembers how to rebuild a card. The
;; bench stores them as plain EDN in $XDG_CONFIG_HOME/hyperjay/library.edn
;; (default ~/.config/hyperjay/library.edn), so the sidebar's library survives
;; bench restarts. This namespace only owns the store: it starts nothing.
;;
;; A recipe is the serializable part of a card — {:card-id :desktop-x11
;; :image "alpine:3.21"} — because a card's :finish is a function and can't
;; round-trip through EDN. cards/recipe->card rebuilds the full card. The shape
;; is a vector, newest first: [{:name "..." :recipe {...}} ...]. save! upserts
;; by :name; remove! drops by :name. Persistence is write-through, so a crash
;; never loses a saved warp. The pre-recipe shape ({:name :card-id}) is migrated
;; on read.
(ns jolt.hyperjay.library
  (:require [clojure.edn :as edn]
            [jolt.fs :as fs]
            [glimmer.ratom :as r]))

(defn library-path
  "Where the library lives: $XDG_CONFIG_HOME/hyperjay/library.edn, defaulting
  to ~/.config/hyperjay/library.edn."
  []
  (let [base (or (System/getenv "XDG_CONFIG_HOME")
                 (str (System/getProperty "user.home") "/.config"))]
    (fs/path base "hyperjay" "library.edn")))

(defn- read-library
  "The saved warps as a vector, or [] when the file is missing or unreadable.
  Migrates the old {:name :card-id} shape to {:name :recipe}."
  []
  (try
    (let [v (edn/read-string (fs/slurp (library-path)))]
      (if (vector? v)
        (into []
              (keep (fn [e]
                      (cond
                        (:recipe e) e
                        (:card-id e) {:name (:name e)
                                      :recipe {:card-id (:card-id e)}}
                        :else nil)))
              v)
        []))
    (catch Exception _ [])))

(defonce saved
  "The saved warps: [{:name :recipe} ...], newest first."
  (r/atom (read-library)))

(defn- persist! []
  (let [p (library-path)]
    (fs/create-dirs (fs/parent p))
    (fs/spit p (pr-str @saved))))

(defn save!
  "Save (or re-save) a warp recipe under `name`. Upserts by name, moves it to
  the front, then writes the library to disk."
  [name recipe]
  (let [n (str name)]
    (when (seq n)
      (swap! saved (fn [xs] (into [{:name n :recipe recipe}]
                                  (remove #(= n (:name %)) xs))))
      (persist!))))

(defn remove!
  "Drop a saved warp by name, then write the library to disk."
  [name]
  (let [n (str name)]
    (swap! saved (fn [xs] (into [] (remove #(= n (:name %)) xs))))
    (persist!)))