(ns himmelsstuermer.misc
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [missionary.core :as m]
    [resauce.core :as res]))


(defn dbg
  [x]
  (println "DBG\t" x) x)


(defmacro project-info

  "This macro expands in map with keys `group`, `name` and `version` of current project by information from project.clj"

  []
  (let [[_ ga version] (read-string (try (slurp "project.clj") (catch Exception _ "[]")))
        [ns name version] (try [(namespace ga) (name ga) version] (catch Exception _ []))]
    {:group ns
     :name name
     :version version}))


(defn- read-resource
  [resource-url]
  (with-open [stream (io/input-stream resource-url)]
    (-> stream
        io/reader
        java.io.PushbackReader. edn/read)))


(defn read-resource-dir
  ([dir] (read-resource-dir dir "edn"))
  ([dir ext]
   (m/via m/blk (into []
                      (comp (mapcat read-resource)
                            (filter #(str/ends-with? % (str "." ext))))
                      (some-> dir io/resource res/url-dir)))))


(defmulti remove-nils (fn [x]
                        (cond
                          (record? x) :default
                          (map? x)    :map
                          (vector? x) :vec
                          :else       :defalut)))


(defmethod remove-nils :map
  [m]
  (into {} (map (fn [[k v]]
                  (if (nil? v) nil [k (remove-nils v)])) m)))


(defmethod remove-nils :vec
  [v]
  (filterv some? (map #(if (some? %) (remove-nils %) nil) v)))


(defmethod remove-nils :default
  [x]
  (identity x))


(defmacro do-nanos
  [& body]
  `(let [~'t0 (System/nanoTime)]
     ~@body
     (- (System/nanoTime) ~'t0)))


(defmacro do-nanos*
  [& body]
  `(let [~'t0 (System/nanoTime)
         ~'r (do ~@body)]
     {:result ~'r
      :nanos (- (System/nanoTime) ~'t0)}))


(defn- char-range
  [lo hi]
  (range (int lo) (inc (int hi))))


(def ^:private hex
  (map char (concat (char-range \a \f)
                    (char-range \0 \9))))


(def ^:private alpha-numeric
  (map char (concat (char-range \a \z)
                    (char-range \A \Z)
                    (char-range \0 \9))))


(defn- create-generator
  [chars]
  (fn [num]
    (apply str (take num (repeatedly #(rand-nth chars))))))


(defn generate-hex
  [num]
  (m/sp ((create-generator hex) num)))


(defn generate-alpha-numeric
  [num]
  (m/sp ((create-generator alpha-numeric) num)))


(defn user->str
  [user]
  (let [username (:user/username user)]
    (if username
      (str "@" username)
      (str "id" (:user/id user)))))
