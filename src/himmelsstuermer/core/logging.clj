(ns himmelsstuermer.core.logging
  (:require
    [cheshire.core :as json]
    [cheshire.generate :as gen]
    [clojure.walk :refer [prewalk]]
    [himmelsstuermer.core.config :as conf]
    [himmelsstuermer.misc :as misc]
    [malli.core :as malli]
    [me.raynes.fs :as fs]
    [taoensso.telemere :as tt]
    [taoensso.telemere.utils :as ttu]))


(gen/add-encoder Object
                 (fn [obj ^com.fasterxml.jackson.core.JsonGenerator json-generator]
                   (.writeString json-generator (str obj))))


(defonce ^:private nano-timer (atom nil))


(defn reset-nano-timer!
  []
  (tt/event! ::reset-nano-timer)
  (reset! nano-timer (System/nanoTime)))


(def console-event-id-handler
  (tt/handler:console
    {:output-fn
     (tt/pr-signal-fn {:pr-fn (ttu/format-signal-fn
                                {:incl-newline? false
                                 :content-fn (constantly nil)})})}))


(def console-json-handler
  (tt/handler:console {:output-fn (tt/pr-signal-fn {:pr-fn json/generate-string})}))


(defn- file-json-disposable-handler
  []
  (fs/delete "./logs.json")
  (tt/handler:file {:output-fn (tt/pr-signal-fn {:pr-fn json/encode})
                    :path "./logs.json"}))


(defn- file-edn-disposable-handler
  []
  (fs/delete "./logs.edn")
  (tt/handler:file {:output-fn (tt/pr-signal-fn {:pr-fn :edn})
                    :path "./logs.edn"}))


(defn- throwable->map
  [^Throwable t]
  {:error {:message    (.getMessage t)
           :type       (-> t .getClass .getName)
           :data       (ex-data t)
           :stackTrace (mapv str (.getStackTrace t))}})


(defn- transform-malli-scheme
  [sch]
  (let [form (malli/form sch)]
    (if (-> form str count (> 3000))
      "<BIG MALLI SCHEME>"
      form)))


(defn- walk
  [obj]
  (cond-> obj
    (instance? Throwable obj) throwable->map

    ;; (instance? datalevin.db.DB obj) ((constantly "<DATALEVIN DB>"))

    (or (instance? clojure.lang.Var obj)
        (instance? java.util.regex.Pattern obj)) str

    (malli/schema? obj) transform-malli-scheme))


(defn- shutdown-hook
  [& args]
  (tt/event! ::shutdown-hook {:data {:args args}})
  (tt/stop-handlers!))


(defn init-logging!
  [project-info profile]
  (tt/call-on-shutdown! shutdown-hook)
  (tt/remove-handler! :default/console)
  (when (= :aws profile)
    (tt/add-handler! :console-json console-json-handler))
  (when (= :test profile)
    (tt/add-handler! :console-event-id console-event-id-handler)
    ;; (tt/add-handler! :file-json-disposable (file-json-disposable-handler))
    (tt/add-handler! :file-edn-disposable (file-edn-disposable-handler)))
  (tt/set-ctx! {:project project-info :profile profile})
  (tt/set-middleware! (fn [signal]
                        (let [signal' (prewalk walk signal)
                              nt @nano-timer]
                          (cond-> signal'
                            (some? nt) (assoc-in [:data :millis-passed]
                                                 (-> (System/nanoTime) (- nt) (* 0.000001)))))))
  (tt/event! ::logging-initialized {:data {:handlers (tt/get-handlers)}}))


(init-logging! (misc/project-info) @conf/profile)
