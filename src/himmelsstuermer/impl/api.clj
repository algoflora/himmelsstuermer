(ns ^:no-doc himmelsstuermer.impl.api
  (:require
    [cheshire.core :refer [generate-string parse-string]]
    [himmelsstuermer.api.buttons :as b]
    [himmelsstuermer.impl.buttons :as ib]
    [himmelsstuermer.impl.callbacks :as clb]
    [himmelsstuermer.impl.db :as db]
    [himmelsstuermer.impl.state :refer [*state*]]
    [himmelsstuermer.misc :as misc]
    [himmelsstuermer.spec :as spec]
    [himmelsstuermer.spec.telegram :as spec.tg]
    [malli.core :as malli]
    [me.raynes.fs :as fs]
    [missionary.core :as m]
    [org.httpkit.client :as http]
    [taoensso.telemere :as tt]))


(defn request
  [method data]
  (let [url  (format "https://api.telegram.org/bot%s/%s" (-> *state* :bot :token) (name method))

        {:keys [result nanos]}
        (misc/do-nanos* @(http/post url {:headers {:content-type "application/json"}
                                         :body (generate-string data)}))

        resp (update result :body #(try (parse-string % true)
                                        (catch Throwable _ %)))]
    (tt/event! ::telegram-api-response
               {:data {:method method
                       :data data
                       :response resp
                       :time-millis (* 0.000001 nanos)}})
    (if (-> resp :body :ok)
      (-> resp :body :result)
      (tt/error! ::bad-telegram-api-response (ex-info "Bad Telegram API response!"
                                                      {:method method
                                                       :data data
                                                       :response resp})))))


(defn api-task
  [method data]
  (let [api-fn (-> *state* :system :api-fn)]
    (tt/event! ::calling-api-fn
               {:data {:function api-fn
                       :method method
                       :data data}})
    #_(m/via m/blk (api-fn method data)) ; TODO: Trade-off for now to make things work
    (m/sp (api-fn method data))))


(malli/=> prepare-keyboard [:=>
                            [:cat
                             [:maybe spec/Buttons]
                             spec/User
                             :boolean]
                            [:map
                             [:inline_keyboard [:vector
                                                [:vector
                                                 spec.tg/Button]]]]])


(defn prepare-keyboard
  [kbd user temp?]
  (when kbd
    {:inline_keyboard
     (cond-> (mapv (fn [btns] (mapv #(b/to-map % user) btns)) kbd)
       temp? (conj [(b/to-map (ib/->XButton) user)]))}))


(defn- set-callbacks-message-id
  [user msg]
  (clb/set-new-message-ids
    user
    (:message_id msg)
    (->> msg
         :reply_markup :inline_keyboard flatten
         (map #(some-> % :callback_data java.util.UUID/fromString))
         (filterv some?))))


(defn- to-edit?
  [options user]
  (when (and (some? (:user/msg-id user)) (= (:message-id options) (:user/msg-id user)))
    (throw (ex-info "Manual editing of Main Message is forbidden!"
                    {:event ::manual-main-message-edit-error})))
  (if (:temp options)
    (some? (:message-id options))
    (pos-int? (:user/msg-id user))))


(defn- prepare-body
  [body options user]
  (cond-> body
    true                    (assoc :chat_id (:user/id user))
    (:markdown options)     (assoc :parse_mode "Markdown")
    (to-edit? options user) (assoc :message_id (or (:message-id options)
                                                   (:user/msg-id user)))))


(defmulti ^:private send-to-chat (fn [& args] (identity (first args))))


;; Media

;; (defn- create-media
;;   [type data]
;;   {:type (name type)
;;    :caption (:caption data)
;;    :media (:file data)})


;; (defn- prepare-to-multipart
;;   [args-map]
;;   (let [files     (atom {})
;;         args-map# (walk/postwalk
;;                     (fn [x]
;;                       (cond (instance? java.io.File x)
;;                             (let [file-id (keyword (str "fid" (java.util.UUID/randomUUID)))]
;;                               (swap! files assoc file-id x)
;;                               (str "attach://" (name file-id)))
;;                             (number? x) (str x)
;;                             :else x))
;;                     (assoc args-map :content-type :multipart))]
;;     (cond-> args-map#
;;       (contains? args-map# :media)        (update :media generate-string)
;;       (contains? args-map# :reply_markup) (update :reply_markup generate-string)
;;       true (merge @files))))


;; (defn- send-new-media-to-chat
;;   [type args-map media]
;;   (let [media-is-file? (instance? java.io.File (:media media))
;;         method         (keyword (str "send" (-> type name str/capitalize)))
;;         args-map#      (cond-> (merge args-map media)
;;                          true (dissoc :media)
;;                          true (assoc type (:media media))
;;                          media-is-file? (prepare-to-multipart))]
;;     (api-wrap method args-map#)))


;; (defn- edit-media-in-chat
;;   [type args-map media]
;;   (let [media-is-file? (instance? java.io.File (:media media))
;;         args-map#      (cond-> args-map
;;                          true (assoc :media media)
;;                          media-is-file? (prepare-to-multipart))]
;;     (try (api-wrap :editMessageMedia args-map#)
;;          (catch clojure.lang.ExceptionInfo ex
;;            (if (= 400 (-> ex ex-data :status))
;;              (send-new-media-to-chat type args-map media)
;;              (throw (ex-info "Request to :editMessageMedia failed!"
;;                              {:event ::edit-message-media-error
;;                               :args args-map#
;;                               :error ex})))))))


;; (defn- send-media-to-chat
;;   [type user data kbd optm]
;;   (let [media    (create-media type data)
;;         args-map (prepare-body {:reply_markup kbd} optm user)
;;         new-msg  (if (to-edit? optm user)
;;                    (edit-media-in-chat type args-map media)
;;                    (send-new-media-to-chat type args-map media))]
;;     (set-callbacks-message-id user new-msg)
;;     new-msg))


;; (defmethod send-to-chat :photo
;;   [& args]
;;   (apply send-media-to-chat args))


;; (defmethod send-to-chat :document
;;   [& args]
;;   (apply send-media-to-chat args))


(defmethod send-to-chat :invoice
  [_ user b options]
  (let [body (prepare-body b options user)
        new-msg (m/? (api-task :sendInvoice body))]
    (set-callbacks-message-id user new-msg)))


(malli/=> send-message- [:-> :map spec/MissionaryTask])


(defn- send-message-
  [body]
  (api-task :sendMessage body))


(malli/=> edit-message-text- [:-> :map spec/MissionaryTask])


(defn- edit-message-text-
  [body]
  (m/sp (try (m/? (api-task :editMessageText body))
             (catch clojure.lang.ExceptionInfo ex
               (when (not= 400 (-> ex ex-data :status))
                 (throw (ex-info "Request to :editMessageText failed!"
                                 {:event ::edit-message-text-error
                                  :args body
                                  :error ex})))
               (tt/event! ::edit-message-failed
                          {:data {:request body
                                  :error ex}})
               (m/? (send-message- body))))))


(defmethod send-to-chat :text
  [_ user b options]
  (m/sp (let [body       (prepare-body b options user)
              msg-task   (if (to-edit? options user)
                           (edit-message-text- body)
                           (send-message- body))
              new-msg    (m/? msg-task)
              new-msg-id (:message_id new-msg)]
          (tt/event! ::text-sent-to-chat {:data {:user user
                                                 :body body
                                                 :new-msg new-msg
                                                 :new-message-id new-msg-id}})
          (when (and (not (:temp options)) (not= new-msg-id (:msg-id user)))
            (tt/event! ::set-user-msg-id {:data {:message new-msg
                                                 :message-id new-msg-id}})
            (db/transact [[:db/add (:db/id user) :user/msg-id new-msg-id]]))
          (m/? (set-callbacks-message-id user new-msg))
          new-msg-id)))


(defmulti process-args (fn [a & _] a))


(defmethod process-args :text
  [_ user & args]
  (let [opts (->> args (filter keyword?) set)]
    {:body    {:text         (->> args (filter string?) first)
               :reply_markup (prepare-keyboard
                               (->> args (filter vector?) first misc/remove-nils)
                               user (boolean (some #{:temp} opts)))
               :entities     (->> args (filter set?) first vec)}
     :options (into {:message-id (->> args (filter int?) first)}
                    (map #(vector % true) opts))}))


(defmethod process-args :invoice
  ([_ user text data] (process-args :invoice user text data []))
  ([_ user text data kbd]
   {:body    (merge data {:reply_markup (prepare-keyboard
                                          (into [[(b/pay-btn text)]] kbd)
                                          user true)})
    :options {:temp true}}))


(malli/=> send! [:=> [:cat :keyword spec/User [:* :any]] spec/MissionaryTask])


(defn send!
  [type user & args]
  (let [{:keys [body options]} (apply process-args type user args)]
    (send-to-chat type user body options)))


(defn- download-file
  [file-path]
  (let [uri (format "https://api.telegram.org/file/bot%s/%s"
                    (-> *state* :bot :token) file-path)
        ^java.io.ByteArrayInputStream bis  (-> uri http/get deref :body)
        file (fs/file fs/temp-dir (java.util.UUID/randomUUID))
        fos  (java.io.FileOutputStream. file)]
    (try
      (.transferTo bis fos)
      (finally
        (.close fos)))
    file))


(defn get-file
  [file-id]
  (m/via m/blk (let [file-path (m/? (api-task :getFile file-id))]
                 (if (fs/exists? file-path)
                   (fs/file file-path)
                   (download-file file-path)))))


(defn delete-message
  [user mid]
  (m/join (constantly nil)
          (clb/delete user mid)
          (api-task :deleteMessage {:chat_id (:user/id user)
                                    :message_id mid})))


(defn answer-pre-checkout-query
  ([pcq-id] (answer-pre-checkout-query pcq-id nil))
  ([pcq-id error]
   (api-task :answerPrecheckoutQuery (into {:pre_checkout_query_id pcq-id
                                            :ok (nil? error)}
                                           (when (some? error)
                                             [:error_message error])))))
