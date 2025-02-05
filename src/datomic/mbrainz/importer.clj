;; Copyright (c) Cognitect, Inc.
;; All rights reserved.

(ns datomic.mbrainz.importer
  (:require
   [clojure.core.async :refer (<! <!! >!! chan go promise-chan)]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [cognitect.anomalies :as anom]
   [cognitect.xform.batch :as batch :refer (already-transacted filter-batches
                                            load-parallel reverse? tx-data->batches)]
   [cognitect.xform.async :refer (threaded-onto)]
   [cognitect.xform.async-edn :as aedn :refer (with-ex-anom)]
   [cognitect.xform.transducers :refer (dot)]
   [cognitect.xform.spec :refer (conform!)]
   [datomic.client.api :as d]
   [datomic.mbrainz.importer.entities :as ent]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; data and schema
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::attr-name qualified-keyword?)
(s/def :db/ident keyword?)

(s/def ::enum-type symbol?)
(s/def ::enums (s/map-of ::enum-type (s/map-of ::ent/name ::attr-name)))

(s/def ::super-type keyword?)
(s/def ::super-enum (s/map-of ::ent/name (s/keys :req [:db/ident])))
(s/def ::super-enums (s/map-of ::super-type ::super-enum))
(s/def ::importer (s/keys :req-un [::enums ::super-enums]))

(s/def ::manifest (s/keys :req-un [::client-cfg ::db-name ::basedir ::concurrency]
                          :opt-un [::import-order]))

(def import-order
  "Order of import for data types."
  [:schema :enums :super-enums :artists :areleases
   :areleases-artists :labels :releases :releases-artists
   :media])

(s/def ::type (set import-order))

(def enum-attrs
  "Pointer from attr name to in-memory table used to convert values
of that type."
  {:artist/type 'artist_type
   :artist/gender 'gender
   :abstractRelease/type 'release_group_type
   :release/packaging 'release_packaging
   :abtractRelease/type 'release_group_type
   :medium/format 'medium_format
   :label/type 'label_type})

(def super-attrs
  "Pointer from attr name to in-memory table used to convert values
of that type."
  {:artist/country :countries
   :release/country :countries
   :release/language :langs
   :release/script :scripts
   :label/country :countries})

(def artist-attrs
  "Name translation, see transform-entity."
  {:gid :artist/gid
   :country :artist/country
   :sortname :artist/sortName
   :name :artist/name
   :type :artist/type
   :gender :artist/gender
   :begin_date_year :artist/startYear
   :begin_date_month :artist/startMonth
   :begin_date_day :artist/startDay
   :end_date_year :artist/endYear
   :end_date_month :artist/endMonth
   :end_date_day :artist/endDay})

(def arelease-attrs
  "Name translation, see transform-entity."
  {:gid :abstractRelease/gid
   :name :abstractRelease/name
   :type :abstractRelease/type
   :artist_credit :abstractRelease/artistCredit})

(def release-attrs
  "Name translation, see transform-entity."
  {:gid :release/gid
   :artist_credit :release/artistCredit
   :name :release/name
   :label [:release/labels :label/gid]
   :packaging :release/packaging
   :status :release/status
   :country :release/country
   :language :release/language
   :script :release/script
   :barcode :release/barcode
   :date_year :release/year
   :date_month :release/month
   :date_day :release/day
   :release_group [:release/abstractRelease :abstractRelease/gid]})

(def label-attrs
  "Name translation, see transform-entity."
  {:gid :label/gid
   :name :label/name
   :sort_name :label/sortName
   :type :label/type
   :country :label/country
   :begin_date_year :label/startYear
   :begin_date_month :label/startMonth
   :begin_date_day :label/startDay   
   :end_date_year :label/endYear    
   :end_date_month :label/endMonth
   :end_date_day :label/endDay})

(def medium-attrs
  "Name translation, see transform-entity."
  {:release [:release/_media :release/gid]
   :position :medium/position
   :track_count :medium/trackCount
   :format :medium/format})

(def track-attrs
  "Name translation, see transform-entity."
  {:name :track/name
   :tracknum :track/position
   ;; :acid :track/artistCredit
   :length :track/duration
   :artist [:track/artists :artist/gid]})

(def track-tempid-keys [:id :tracknum])

(def release-artist-attrs
  "Name translation, see transform-entity."
  {:release [:db/id :release/gid]
   :artist [:release/artists :artist/gid]})

(def arelease-artist-attrs
  "Name translation, see transform-entity."
  {:artist [:abstractRelease/artists :artist/gid]
   :release_group [:db/id :abstractRelease/gid]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; mbrainz transducers and wiring
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol Importer
  (entities-file [_ type] "Returns the io/file for entity data of type.")
  (batch-file [_ type] "Returns the io/file for batch data of type.")
  (could-not-import [_ entity k] "Report and die on a problem converting key k under entity")
  (as-enum [_ ent attr-name v] "Convert value v for attr-name in ent into an enum keyword.")
  (as-super-enum [_ ent attr-name v] "Convert value v for attr-name in ent into a super-enum keyword.")
  (entity-data->tx-data [_ type] "Given an entity type, return a transducer from that type into tx-data.")
  (entity-data->ch [_ type ch] "Puts entity data for type onto ch, closing ch when data is done."))

(defn create-tempid
  "Create a tempid for entity based on values of ks."
  [entity prefix ks]
  (str prefix "-" (str/join "-" (map entity ks))))

(defn transform-entity
  "Transform entity e from input format to tx-data map.

Uses entity-type-specific name-map for attribute names.
Uses importer to lookup enums, super-enums.
Handles Datomic specifics: db/ids, refs, reverse refs."
  [importer e name-map]
  (reduce-kv
   (fn [ent k v]
     (if-let [attr (get name-map k)]
       (if (vector? attr)
         (let [[id uniq] attr]
           (if (= :db/id id)
             (assoc ent uniq v)
             (if (reverse? id)
               (assoc ent id [uniq v])
               (assoc ent id {uniq v}))))
         (assoc ent attr (or (as-enum importer ent attr v)
                             (as-super-enum importer ent attr v)
                             v)))
       ent))
   nil
   e))

(def enums->tx-data
  "xform from enums.edn to tx-data"
  (comp (map second)
        cat
        (map (fn [[str-val attr-name]]
               {:db/ident attr-name
                (keyword (namespace attr-name) "name") str-val}))))

(def super-enums->tx-data
  "xform from countries.edn, langs.edn, scripts.edn to tx-data"
  (comp (map second)
        (mapcat vals)))

(defrecord ImporterImpl
  [basedir enums super-enums]
  Importer
  (entities-file
   [_ type]
   (io/file basedir "entities" (str (name type) ".edn")))
  (batch-file
   [_ type]
   (io/file basedir "batches" (str (name type) ".edn")))
  (could-not-import
   [_ entity k]
   (throw (ex-info "Importer failed" {:entity entity :problem-key k})))
  (as-enum
   [this ent attr-name v]
   (when-let [lookup (some-> attr-name enum-attrs enums)]
     (or (get lookup v)
         (could-not-import this ent attr-name))))
  (as-super-enum
   [this ent attr-name v]
   (when-let [lookup (some-> attr-name super-attrs super-enums)]
     (or (-> (get lookup v) :db/ident)
         (could-not-import this ent attr-name))))
  (entity-data->tx-data
   [this type]
   (case type
         :schema cat
         :schema-cloud cat
         :enums enums->tx-data
         :super-enums super-enums->tx-data
         :artists (map #(transform-entity this % artist-attrs))
         :areleases (map #(transform-entity this % arelease-attrs)) 
         :releases (map #(transform-entity this % release-attrs))
         :labels (map #(transform-entity this % label-attrs))
         :media (comp
                 (partition-by :id)  ;; group tracks by medium
                 (map
                  (fn [ents]
                    (reduce
                     (fn [medium ent]
                       (update medium :medium/tracks conj
                               (assoc (transform-entity this ent track-attrs)
                                 ;; make a per-track-artist tempid so multi-artist tracks coalesce
                                 :db/id (create-tempid ent "track" track-tempid-keys))))
                     (transform-entity this (first ents) medium-attrs)
                     ents))))
         :releases-artists (map #(transform-entity this % release-artist-attrs))
         :areleases-artists (map #(transform-entity this % arelease-artist-attrs))))
  (entity-data->ch
   [this type ch]
   (case type
         :enums (threaded-onto ch (:enums this))
         :super-enums (threaded-onto ch (:super-enums this))
         (aedn/reader (entities-file this type) ch))))

(defn create-importer
  "Creates the master importer for data in basedir."
  [basedir]
  (let [conv (let [load #(-> (io/file basedir "entities" %) slurp edn/read-string)]
               (->ImporterImpl
                basedir
                (load "enums.edn")
                {:countries (load "countries.edn")
                 :langs (load "langs.edn")
                 :scripts (load "scripts.edn")}))]
    (conform! ::importer conv)
    (io/make-parents basedir "batches" "dummy")
    conv))

(def import-schema
  [{:db/ident :mbrainz.initial-import/batch-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/value}])

(def BATCH_ID :mbrainz.initial-import/batch-id)

(defn create-batch-file
  "Use the importer to make transaction data for entities of type,
with batch size of batch-size. Returns a map with the results from
the reader and writer threads."
  [importer batch-size type]
  (go
   (try
    (let [xform (comp (entity-data->tx-data importer type)
                      (tx-data->batches batch-size BATCH_ID (name type))
                      (dot 1000))
          ch (chan 1000 xform)
          inthread (entity-data->ch importer type ch)
          outthread (aedn/writer ch (batch-file importer type))]
      {:reader (<! inthread)
       :writer (<! outthread)})
    (catch Throwable t
      {::anom/category ::anom/fault
       ::anom/message (.getMessage t)}))))

(defn load-type
  "Loads data of type (keyword) to connection conn with concurrency n using
  importer. Returns a channel that contains a map with keys
  :process and :result, each of which contains the results of the reader and
  writer, respectively when successful or an anomaly if failure."
  [n conn importer type]
  (go
   (with-ex-anom
     (let [extant-batch-ids (<! (already-transacted conn BATCH_ID))]
       (println "Batches already completed: " (count extant-batch-ids))
       (if (::anom/category extant-batch-ids)
         extant-batch-ids
         (let [ch (chan 100 (comp
                             (dot)
                             (filter-batches BATCH_ID extant-batch-ids)))
               rdr (aedn/reader (batch-file importer type) ch)
               loader (load-parallel n conn (* 30 1000) ch)]
           {:process (<! rdr)
            :result (<! loader)}))))))

(defn -main
  "Run an mbrainz import. Manifest file must have

:client-cfg        args for d/client
:db-name           database name
:basedir           directory with batch data
:concurrency       number of batches in flight at a time, suggest 3
:import-order      override the default import-order - optional

The subsets directory of this project is a suitable basedir.

Do not call with different batch-size settings against the same db.

Idempotent. Prints to stdout as it goes, throws on error."
  [manifest-file & [opt]]
  (let [manifest (-> manifest-file slurp edn/read-string)]
    (conform! ::manifest manifest)
    (let [{:keys [client-cfg db-name basedir batch-size concurrency]
           import-order :import-order :or {import-order import-order}} manifest
          client (d/client client-cfg)
          importer (create-importer basedir)]
      (d/create-database client {:db-name db-name})
      (let [conn (d/connect client {:db-name db-name})]
        (d/transact conn {:tx-data import-schema})
        (time
         (doseq [type import-order]
           (println "Loading batch file for " type)
           (time
             (let [result (<!! (load-type concurrency conn importer type))]
               (if (::anom/category (:result result))
                 (throw (ex-info "Import failed" (:result result)))
                 (pp/pprint result)))))))))

  ;; until close API
  (System/exit 0))
