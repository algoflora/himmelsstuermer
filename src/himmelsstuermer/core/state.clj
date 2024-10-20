(ns himmelsstuermer.core.state
  (:require
    [datalevin.core :as d]
    [himmelsstuermer.core.config :as conf]
    [himmelsstuermer.core.init :as init]
    [himmelsstuermer.core.logging :refer [init-logging!]]
    [himmelsstuermer.spec :as spec]
    [malli.core :as malli]
    [malli.instrument :refer [instrument!]]
    [missionary.core :as m]
    [taoensso.telemere :as tt]))


(defmacro project-info

  "This macro expands in map with keys `group`, `name` and `version` of current project by information from project.clj"

  []
  (let [[_ ga version] (read-string (try (slurp "project.clj") (catch Exception _ "[]")))
        [ns name version] (try [(namespace ga) (name ga) version] (catch Exception _ []))]
    {:group ns
     :name name
     :version version}))


(malli/=> create-state [:=> [:cat :keyword [:* :map]] spec/State])


(defn- create-state
  [profile & args]
  (let [data (apply merge args)]
    {:profile profile
     :system {:db-conn (:db/conn data)
              :api-fn (:api/fn data)}
     :bot {:token (:bot/token data)
           :roles (:bot/roles data)
           :default-language-code (:bot/default-language-code data)}
     :actions {:namespace (:actions/namespace data)}
     :handlers {:main (:handler/main data)
                :payment (:handler/payment data)}
     :project (merge (project-info)
                     (:project/config data))
     :database nil
     :transaction #{}
     :action nil
     :update nil
     :message nil
     :callback-query nil
     :pre-checkout-query nil
     :user nil
     :tasks []
     :aws-context nil}))


(def state
  (m/sp (let [profile (m/? conf/profile)]
          (init-logging! (project-info) profile)
          (when (System/getProperty  "himmelsstuermer.malli.instrument" false)
            (instrument!))
          (let [state (m/? (m/join (partial create-state profile)
                                   init/api-fn
                                   init/db-conn
                                   init/bot-token
                                   init/bot-default-language-code
                                   init/bot-roles
                                   init/handler-main
                                   init/handler-payment
                                   init/actions-namespace
                                   init/project-config))]
            (tt/event! ::state-created state)
            state))))


(malli/=> modify-state [:-> spec/State spec/State])


(defn modify-state
  [state modify-fn]
  (let [state' (modify-fn state)]
    (tt/set-ctx! (assoc tt/*ctx* :state state'))
    (tt/event! ::state-modified)
    state'))


(defn shutdown!
  [state]
  (d/close (-> state :system :db-conn))
  (tt/stop-handlers!))


(comment
  (def x (atom 0))
  (def ^:dynamic *x* nil)

  (binding [*x* x]
    (swap! *x* inc))

  @x

  )
