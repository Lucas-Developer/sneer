(ns sneer.conversations
  (:require
    [clojure.core.async :refer [go chan close! <! >! sliding-buffer timeout]]
    [clojure.stacktrace :refer [print-stack-trace]]
    [rx.lang.clojure.core :as rx]
    [sneer.async :refer [sliding-chan go-while-let go-loop-trace]]
    [sneer.commons :refer [produce! descending]]
    [sneer.contact :refer [get-contacts puk->contact]]
    [sneer.conversation :refer :all]
    [sneer.rx :refer [pipe-to-subscriber! close-on-unsubscribe! subscribe-on-io latest shared-latest combine-latest switch-map behavior-subject]]
    [sneer.party :refer [party->puk]]
    [sneer.serialization :refer [serialize deserialize]]
    [sneer.tuple.protocols :refer :all]
    [sneer.tuple.space :refer [payload]]
    [sneer.tuple-base-provider :refer :all])
  (:import
    [rx Observable]
    [rx.subjects BehaviorSubject]
    [sneer Conversations Conversation Conversations$Notification]
    [sneer.rx ObservedSubject]))

(defn- reify-notification [conversations title text subText]
  (reify Conversations$Notification
    (conversations [_] conversations)
    (title [_] title)
    (text [_] text)
    (subText [_] subText)))

(defn- unread-messages-label [count]
  (str count " unread message" (when-not (= 1 count) "s")))

(defn- notification-for-single [[^Conversation convo unread-messages]]
  (rx/map
   (fn [nick]
     (let [text (message-label (first unread-messages))
           subText (unread-messages-label (count unread-messages))]
       (reify-notification [convo] nick text subText)))
   (.. convo contact nickname observable)))

(defn- notification-for-many [unread-conversations]
  (let [conversations (mapv first unread-conversations)
        text ""
        unread-count (->> unread-conversations (map second) (map count) (reduce +))
        subText (unread-messages-label unread-count)]
    (rx/return
     (reify-notification conversations "New messages" text subText))))

(defn- notification-for-none []
  (rx/return
    (reify-notification [] nil nil nil)))

(defn- most-recent-message-timestamp [^Conversation conv]
  (let [^ObservedSubject subject (ObservedSubject/create 0)]
    (.subscribe ^Observable (.mostRecentMessageTimestamp conv) subject)
    (-> subject .observed .current (or 0))))

(defn reify-conversations [own-puk space contacts-state]
  (let [convos (atom {})
        reify-conversation (partial reify-conversation space own-puk)
        ignored-conversation (behavior-subject)
        contacts (get-contacts contacts-state)
        produce-convo (fn [contact] (produce! reify-conversation convos contact))]

    (reify Conversations

      (all [_]
        (->> contacts
             (rx/map (partial mapv produce-convo))
             (rx/map (partial sort-by most-recent-message-timestamp #(compare %2 %1)))
             shared-latest))

      (ofType [_ _type]
        (rx/never))

      (withParty [this party]
        (some->> party
                 .publicKey
                 .current
                 (puk->contact contacts-state)
                 (.withContact this)))

      (withContact [_ contact]
        (produce-convo contact))

      (notifications [this]
        (->> [(.all this) ignored-conversation]

             ;; ([Conversation], Conversation)
             (combine-latest (fn [[all ignored]] (remove #(identical? % ignored) all)))

             ;; [Conversation]
             (rx/map (fn [conversations]
                       (->> conversations
                            (mapv (fn [^Conversation c]
                                    (->> (.unreadMessages c)
                                         (rx/map (partial vector c))))))))

             ;; [Observable (Conversation, [Message])]
             (switch-map
              (partial combine-latest
                       (partial filterv (comp not empty? second))))

             ;; [(Conversation, [unread Message])]
             (switch-map
              (fn [unread-pairs]
                (case (count unread-pairs)
                  0 (notification-for-none)
                  1 (notification-for-single (first unread-pairs))
                  (notification-for-many unread-pairs))))))

      (notificationsStartIgnoring [_ conversation] (.onNext ^BehaviorSubject ignored-conversation conversation))
      (notificationsStopIgnoring  [_]              (.onNext ^BehaviorSubject ignored-conversation nil))

      (findSessionById [_ id]
        (reify-session-by-id space own-puk id)))))
