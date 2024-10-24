(ns himmelsstuermer.core
  (:gen-class)
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [datalevin.core :as d]
    [himmelsstuermer.api.db]
    [himmelsstuermer.api.vars]
    [himmelsstuermer.core.logging :refer [reset-nano-timer!]]
    [himmelsstuermer.core.state :as s]
    [himmelsstuermer.core.user :as u]
    [himmelsstuermer.impl.api :as api]
    [himmelsstuermer.impl.db]
    [himmelsstuermer.impl.state]
    [himmelsstuermer.spec :as spec]
    [himmelsstuermer.spec.telegram :as spec.tg]
    ;; [hyperfiddle.rcf :refer [tests tap %]]
    [malli.core :as malli]
    [missionary.core :as m]
    [org.httpkit.client :as http]
    [taoensso.telemere :as tt]))


(defmulti ^:private handle-update- (fn [a & _] a))


(defmethod handle-update- :message
  [_ {:keys [message] :as state}]
  (m/sp (tt/event! ::handle-message {:data {:message message}})
        (let [state' (m/? (u/load-to-state state (:from message)))]
          (s/modify-state state' #(update
                                    % :tasks
                                    conj (m/sp
                                           (m/?
                                             (api/delete-message (:user state') (:message_id message)))))))))


(defmethod handle-update- :callback-query
  [_ {:keys [callback-query] :as state}]
  (m/sp (tt/event! ::handle-callback-query {:callback-query callback-query})
        (m/? (let [st (u/load-to-state state (:from callback-query) (-> callback-query
                                                                        :data
                                                                        java.util.UUID/fromString))]
               (tt/event! ::test-state {:data {:st st}})
               st))))


(defmethod handle-update- :pre-checkout-query ; TODO: Add comprehensive processing of pre-checkout-query
  [_ {:keys [pre-checkout-query] :as state}]
  (m/sp (tt/event! ::handle-pre-checkout-query {:pre-checkout-query pre-checkout-query})
        (s/modify-state state #(update
                                 % :tasks
                                 conj (api/answer-pre-checkout-query (:id pre-checkout-query))))))


(malli/=> hyphenize-kw [:-> [:fn #(and (keyword? %) (not (qualified-keyword? %)))] :keyword])


(defn- hyphenize-kw
  [kw]
  (-> kw name (str/replace #"_" "-") keyword))


(malli/=> handle-update [:-> spec/State spec/MissionaryTask])


(defn- handle-update
  [{:keys [update] :as state}]
  (m/sp
    (tt/event! ::handle-update {:data {:update update}})
    (let [type  (some #{:message :callback_query :pre_checkout_query} (keys update))
          type' (hyphenize-kw type)]
      (m/? (handle-update- type' (s/modify-state state #(assoc % type' (type update))))))))


(malli/=> handle-action [:-> spec/State spec/MissionaryTask])


(defn- handle-action
  [{:keys [action] :as state}]
  (m/sp (tt/event! ::handle-action {:data {:action action}})
        (let [args (:arguments action)
              task (apply
                     (requiring-resolve (symbol (-> state :actions :namespace str) (:method action)))
                     (if (sequential? args) args [args]))]
          (s/modify-state state #(update % :tasks conj task)))))


(malli/=> handle [:=> [:cat spec/State spec/Record] spec/MissionaryTask])


(defn handle
  [state record]
  (m/sp (tt/event! ::handle-core {:data {:record record}}) ; TODO: check "private" chats, "/start" command, etc...
        (let [state'  (s/modify-state state #(assoc % :database (-> state :system :db-conn d/db)))
              _ (tt/event! ::loaded-database {:data {:database (-> state' :database)
                                                     :conn (-> state' :system :db-conn)}})
              body    (-> record :body (json/decode keyword))
              state'' (cond
                        (malli/validate spec.tg/Update body)
                        (m/? (handle-update (s/modify-state state' #(assoc % :update body))))

                        (malli/validate spec/ActionRequest body)
                        (m/? (handle-action (s/modify-state state' #(assoc % :action (:action body))))))
              tx-data (atom (:transaction state''))]
          (tt/event! ::executing-business-logic {})
          (binding [himmelsstuermer.api.db/*db*        (:database state'')
                    himmelsstuermer.api.vars/*user*    (:user state'')
                    himmelsstuermer.api.vars/*msg*     (some-> state'' :callback-query :message :message_id)
                    himmelsstuermer.api.vars/*config*  (some-> state'' :project :config)
                    himmelsstuermer.impl.db/*tx*       tx-data
                    himmelsstuermer.impl.state/*state* state'']
            (tt/event! ::running-tasks {:data {:tasks (:tasks state'')}})
            (let [report ((bound-fn [] (m/? (apply m/join vector (:tasks state'')))))]
              (tt/event! ::report-tasks {:data {:report report}})))
          ;; TODO: Research situation when message sent, button clicked but transaction still not complete
          (let [txd (vec @tx-data)
                _ (tt/event! ::transacting-data {:data {:tx-data txd}})
                tx-data' (into []
                               (map (comp vec seq))
                               (:tx-data (d/transact! (-> state'' :system :db-conn) (vec @tx-data))))]
            (tt/event! ::transacted-data {:data {:tx-data tx-data'}})))))


(def runtime-api-url (str "http://" (System/getenv "AWS_LAMBDA_RUNTIME_API") "/2018-06-01/runtime/"))


;; API says not to use timeout when getting next invocation, so make it a long one
(def timeout-ms (* 1000 60 60 24))


(defn- throwable->error-body
  [^Throwable t]
  {:errorMessage (.getMessage t)
   :errorType    (-> t .getClass .getName)
   :stackTrace   (mapv str (.getStackTrace t))})


(def invocations
  (m/seed (repeatedly @(http/get (str runtime-api-url "invocation/next")
                                 {:timeout timeout-ms}))))


(def invocations (m/seed [1]))


(def requests
  (let [initial-state (m/? s/state)]
    (tt/set-ctx! (merge tt/*ctx* {:state initial-state}))
    (try (m/eduction (map (fn [{:keys [body headers]}]
                            {:state   (s/modify-state initial-state #(assoc % :aws-context headers))
                             :records (:Records (json/decode body keyword))}))
                     invocations)
         (finally (s/shutdown! initial-state)))))


(def app
  (m/eduction
    (map (fn [{:keys [state records]}]
           (let [id (get-in state [:aws-context "lambda-runtime-aws-request-id"])]
             (try (m/? (m/reduce (constantly :processed)
                                 (m/ap (let [record (m/?> records)]
                                         (reset-nano-timer!)
                                         (handle state record)))))

                  (tt/event! ::invocation-response-ok {:invocation-id id})
                  (http/post (str runtime-api-url "invocation/" id "/response")
                             {:body "OK"})

                  (catch Exception ex
                    (tt/error! {:id ::unhandled-exception
                                :data ex}
                               ex)
                    (http/post (str runtime-api-url "invocation/" id "/error")
                               {:body (json/encode (throwable->error-body ex))}))))))
    requests))


;; (def app
;;   (m/ap (let [initial-state (m/? s/state)]
;;           (tt/set-ctx! (merge tt/*ctx* {:state initial-state}))
;;           (try (let [{:keys [body
;;                              headers]} (m/?> invocations)
;;                      _ (tt/event! ::invocation-received {:data {:body body :headers headers}})
;;                      id                (get headers "lambda-runtime-aws-request-id")
;;                      state             (s/modify-state initial-state #(assoc % :aws-context headers))]

;;                  (try (m/?
;;                         (m/reduce conj
;;                                   (m/ap
;;                                     (let [record (m/?> (m/seed (:Records body)))]
;;                                       (reset-nano-timer!)
;;                                       (m/? (handle state record))
;;                                       (tt/set-ctx! nil)))))
;;                       (tt/event! ::invocation-response-ok {:invocation-id id})
;;                       (http/post (str runtime-api-url "invocation/" id "/response")
;;                                  {:body "OK"})

;;                       (catch Exception ex
;;                         (tt/error! {:id ::unhandled-exception
;;                                     :data ex}
;;                                    ex)
;;                         (http/post (str runtime-api-url "invocation/" id "/error")
;;                                    {:body (json/encode (throwable->error-body ex))}))))
;;                (finally (s/shutdown! initial-state))))))


(defn -main
  [& _]
  (m/? (m/reduce conj app)))


(comment

  (def config-task (m/sp 2))
  (def data-flow (m/seed [1 2 3]))

  (def j (let [cfg (m/? config-task)]
           (m/eduction (map #(* cfg %))
                       data-flow)))

  (m/? (m/reduce conj j))


  (def f1 (m/seed [[1 2 3] [:a :b :c]]))

  (m/? (m/reduce conj (m/ap (let [is (m/?> f1)
                                  i (m/?> (m/seed is))]
                              (println i)))))

  (def f2 (m/eduction (comp (map :data) cat) f1))

  (m/? (m/reduce conj f2))
  
  )
