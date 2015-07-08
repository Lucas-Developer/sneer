(ns sneer.convo

  (:require [rx.lang.clojure.core :as rx]
            [clojure.core.async :as async :refer [<! >! chan alts! pipe]]
            [sneer.async :refer [state-machine tap-state go-trace go-loop-trace sliding-chan close-with!]]
            [sneer.contacts :as contacts :refer [tap-id]]
            [sneer.rx :refer [pipe-to-subscriber! close-on-unsubscribe!]]
            [sneer.time :as time]
            [sneer.tuple.protocols :refer :all]
            [sneer.tuple-base-provider :refer :all]
            [sneer.tuple.persistent-tuple-base :refer [after-id]])

  (:import  [sneer.convos Convo ChatMessage]
            [rx Subscriber]
            [sneer.admin SneerAdmin]))

(defn- msg-ids [msg1 msg2]
  (compare (msg1 :id)
           (msg2 :id)))

(defn- handle-message [own-puk state message]
  (let [{:strs [id author timestamp label]} message
        own? (= author own-puk)
        message {:id id :own? own? :timestamp timestamp :text label}]
    (update state :messages conj message)))

(defn- handle-contact [state contact]
  (merge state contact))

(defn- handle-event [own-puk state event]
  (if (= (event "type") "message")
    (handle-message own-puk state event)
    (handle-contact state event)))

(defn- query-messages-by! [tuple-base author-puk audience-puk lease]
  (let [old (chan 1)
        new (chan 1)
        criteria {"type"     "message"
                  "author"   author-puk
                  "audience" audience-puk}]
    (query-with-history tuple-base criteria old new lease)
    [old new]))

(defn- query-messages! [tb own-puk contact-puk lease]
  (let [[old-sent new-sent] (query-messages-by! tb own-puk contact-puk lease)
        [old-rcvd new-rcvd] (query-messages-by! tb contact-puk own-puk lease)
        old-msgs (async/merge [old-sent old-rcvd])
        new-msgs (async/merge [new-sent new-rcvd])]
    [old-msgs new-msgs]))

(defn- pipe-until-contact-has-puk! [contact-in state-out]
  (go-loop-trace []
    (let [contact (<! contact-in)]
      (if (contact :puk)
        contact
        (do
          (>! state-out contact)
          (recur))))))

(defn- start!
  "`id' is the id of the first contact tuple for this party"
  [container id state-out lease]
  (let [admin (.produce container SneerAdmin)
        own-puk (.. admin privateKey publicKey)
        tb (tuple-base-of admin)
        contact-in (tap-id (contacts/from container) id lease)]
    (go-trace
      (let [contact (<! (pipe-until-contact-has-puk! contact-in state-out))
            state (assoc contact :messages (sorted-set-by msg-ids))
            [old-events new-events] (query-messages! tb own-puk (contact :puk) lease)
            _ (pipe contact-in new-events)
            machine (state-machine (partial handle-event own-puk) state old-events new-events)]
        (tap-state machine state-out)))))

(defn- ->ChatMessageList [messages]
  (if (empty? messages)
    []
    (let [pretty-time (time/pretty-printer)]
      (->> messages
           (mapv (fn [{:keys [id text own? timestamp]}]
                   (ChatMessage. id text own? (pretty-time timestamp))))))))

; Convo(long contactId, String nickname, String inviteCodePending, List<ChatMessage> messages, List<SessionSummary> sessionSummaries)
; SessionSummary(long id, String type, String title, String date, String unread)
; ChatMessage(long id, String text, boolean isOwn, String date)
(defn- to-foreign [{:keys [id nick invite-code messages]}]
  (Convo. id nick invite-code (->ChatMessageList messages) nil))

(defn convo-by-id [container id]
  (rx/observable*
   (fn [^Subscriber subscriber]
     (let [state-out (sliding-chan 1 (map to-foreign))
           lease (chan)]
       (close-on-unsubscribe! subscriber state-out lease)
       (pipe-to-subscriber! state-out subscriber "convo")
       (start! container id state-out lease)))))