(ns sneer.admin
  (:require
   [rx.lang.clojure.core :as rx]
   [sneer.rx :refer [subject* observe-for-computation atom->observable flatmapseq shared-latest]]
   [sneer.core :as core :refer [connect dispose restarted]]
   [sneer.networking.client :as client]
   [sneer.persistent-tuple-base :as persistence]
   [clojure.java.io :as io])
  (:import
   [sneer.admin SneerAdmin]
   [sneer.commons.exceptions FriendlyException]
   [sneer Sneer PrivateKey PublicKey Party Contact Profile Conversation Message]
   [sneer.rx ObservedSubject]
   [sneer.tuples Tuple TupleSpace TuplePublisher TupleFilter]
   [sneer.impl.keys KeysImpl]
   [rx.schedulers TestScheduler]
   [rx.subjects Subject BehaviorSubject ReplaySubject PublishSubject]))

(defn behavior-subject [& [initial-value]]
  (BehaviorSubject/create initial-value))

(defn replay-last-subject []
  (ReplaySubject/createWithSize 1))

(defprotocol PartyImpl
  (party-name-subject [this]))

(defn new-party [puk]
  (let [name (replay-last-subject)]
    (reify
      Party
      (name [this] name)
      (publicKey [this]
        (.observed (ObservedSubject/create puk)))
      PartyImpl
      (party-name-subject [this] name)
      (toString [this]
        (str "#<Party " puk ">")))))

(defn party-puk [^Party party]
  (.. party publicKey current))

(defn produce-party [parties puk]
  (->
   (swap! parties update-in [puk] #(if (nil? %) (new-party puk) %))
   (get puk)))

(defn reify-profile [party ^TupleSpace tuple-space]

  (letfn [(payloads-of [type]
            (rx/map
             (fn [^Tuple tuple] (.payload tuple))
             (.. tuple-space
                 filter
                 (type type)
                 (author (party-puk party))
                 tuples)))

          (payload-subject [tuple-type]
            (let [latest (shared-latest (payloads-of tuple-type))
                  publish #(.. tuple-space
                               publisher
                               (type tuple-type)
                               (pub %))]
              (subject*
               latest
               (reify rx.Observer
                 (onNext [this value]
                   (publish value))))))]

    (let [^Subject preferred-nickname (payload-subject "profile/preferred-nickname")
          ^Subject own-name (payload-subject "profile/own-name")
          ^Subject selfie (payload-subject "profile/selfie")
          ^Subject city (payload-subject "profile/city")
          ^Subject country (payload-subject "profile/country")]

      (reify Profile
        (ownName [this]
          (.asObservable own-name))
        (setOwnName [this value]
          (rx/on-next own-name value))
        (selfie [this]
          (.asObservable selfie))
        (setSelfie [this value]
          (rx/on-next selfie value))
        (preferredNickname [this]
          (.asObservable preferred-nickname))
        (setPreferredNickname [this value]
          (rx/on-next preferred-nickname value))
        (city [this]
          (.asObservable city))
        (setCity [this value]
          (rx/on-next city value))
        (country [this]
          (.asObservable country))
        (setCountry [this value]
          (rx/on-next country value))))))

(defn produce-profile [tuple-space profiles party]
  (let [puk (party-puk party)]
	  (->
	   (swap! profiles update-in [puk] #(if (nil? %) (reify-profile party tuple-space) %))
	   (get puk))))


(defn reify-contact [nickname party]
  (let [party-name-subject (party-name-subject party)
        nickname-subject (ObservedSubject/createWithSubject party-name-subject)]
    (rx/on-next party-name-subject nickname)
    (reify Contact
      (party [this] party)
      (problemWithNewNickname [this new-nick]
        ;TODO
        )
      (nickname [this]
        (.observed nickname-subject))
      (setNickname [this new-nickname]
        (.set nickname-subject new-nickname))
      (toString [this]
        (str "#<Contact " (.. nickname-subject observed current) ">")))))

(defn tuple->contact [^Tuple tuple parties]
  (reify-contact (.payload tuple)
                 (produce-party parties (.get tuple "party"))))

(defn restore-contact-list [^TupleSpace tuple-space own-puk parties]
  (->>
   (.. tuple-space
       filter
       (author own-puk)
       (type "contact")
       localTuples
       toBlocking
       toIterable)
   (mapcat (fn [^Tuple tuple] [(.get tuple "party") (tuple->contact tuple parties)]))
   (apply hash-map)))

(defn nickname [^Contact contact]
  (.. contact nickname current))

(defn now []
  (.getTime (java.util.Date.)))

(defn tuple->message [own-puk ^Tuple tuple]
  (let [created (now)
        received (now)
        content (.payload tuple)
        own? (= own-puk (.author tuple))]
    (Message. created received content own?)))

(defn reify-conversation [^TupleSpace tuple-space ^rx.Observable conversation-menu-items ^PublicKey own-puk ^Party party]
  (let [^PublicKey party-puk (party-puk party)
        messages (atom [])
        observable-messages (atom->observable messages)
        message-filter (.. tuple-space filter (type "message"))]
    
    (rx/subscribe
      (rx/merge
        (.. message-filter (author own-puk) (audience party-puk) tuples)
        (.. message-filter (author party-puk) (audience own-puk) tuples))
      #(swap! messages conj (tuple->message own-puk %)))
    
    (reify
      Conversation      
      (party [this] party)
      
      (messages [this]
        observable-messages)
      
      (sendMessage [this content]
        (..
          tuple-space
          publisher
          (audience party-puk)
          (type "message")
          (pub content)))
      
      (mostRecentMessageContent [this]
        (.observed (ObservedSubject/create "hello")))
      
      (mostRecentMessageTimestamp [this]
        (.observed (ObservedSubject/create (now))))
    
      (menu [this] conversation-menu-items)
    
      (unreadMessageCount [this] (rx.Observable/just 1))
    
      (unreadMessageCountReset [this]))))

(defn new-sneer [^TupleSpace tuple-space ^PrivateKey own-prik ^rx.Observable followees]
  (let [own-puk (.publicKey own-prik)
        parties (atom {})
        profiles (atom {})
        conversation-menu-items (BehaviorSubject/create [])
        puk->contact (atom (restore-contact-list tuple-space own-puk parties))
        ->contact-list (fn [contact-map] (->> contact-map vals (sort-by nickname) vec))
        observable-contacts (atom->observable puk->contact ->contact-list)]

    (rx/subscribe
      (->> 
        observable-contacts
        ;observe-for-computation
        flatmapseq
        (rx/flatmap (fn [^Contact c] (.. c party publicKey observable))))
      (partial rx/on-next followees))
    
    (letfn [(duplicate-contact? [nickname party ^Contact contact]
              (or (identical? party (.party contact))
                  (= nickname (.. contact nickname current))))

            (add-contact [nickname party]
              (swap! puk->contact
                     (fn [cur]
                       (when (->> cur vals (some (partial duplicate-contact? nickname party)))
                         (throw (FriendlyException. "Duplicate contact!")))
                       (assoc cur
                         (party-puk party)
                         (reify-contact nickname party)))))
            
            (produce-conversation [party]
              (reify-conversation tuple-space (.asObservable conversation-menu-items) own-puk party))]


      (reify Sneer

        (self [this]
          (new-party own-puk))

        (profileFor [this party]
          (produce-profile tuple-space profiles party))

        (contacts [this]
          observable-contacts)

        (addContact [this nickname party]
          (add-contact nickname party)
          (.. tuple-space
              publisher
              (audience own-puk)
              (type "contact")
              (field "party" (party-puk party))
              (pub nickname)))

        (findContact [this party]
          (get @puk->contact (party-puk party)))

        (conversationsContaining [this type]
          (rx/never))

        (conversations [this]
          (->>
            observable-contacts
            (rx/map
              (partial map (fn [^Contact c] (produce-conversation (.party c)))))))
        
        (setConversationMenuItems [this menu-item-list]
          (rx/on-next conversation-menu-items menu-item-list))

        (produceParty [this puk]
          (produce-party parties puk))
        
        (tupleSpace [this]
          tuple-space)
        
        (produceConversationWith [this party] 
          (produce-conversation party))))))


(defprotocol Restartable
  (restart [this]))

(defn new-sneer-admin

  ([own-prik network]
     (new-sneer-admin own-prik network (ReplaySubject/create)))

  ([^PrivateKey own-prik network tuple-base]
     (let [puk (.publicKey own-prik)
           connection (connect network puk)
           followees (PublishSubject/create)
           tuple-space (core/reify-tuple-space puk tuple-base connection followees)
           sneer (new-sneer tuple-space own-prik followees)]
       (reify
         SneerAdmin
         (sneer [this] sneer)
         (privateKey [this] own-prik)
         (keys [this] this)
         sneer.keys.Keys
         (createPublicKey [this bytes-as-string]
           (KeysImpl/createPublicKey bytes-as-string))
         Restartable
         (restart [this]
           (rx/on-completed connection)
           (new-sneer-admin own-prik network (restarted tuple-base)))))))

(defn produce-private-key [db]
  (if-let [existing (second (persistence/db-query db ["SELECT * FROM keys"]))]
    (KeysImpl/createPrivateKey ^bytes (first existing))
    (let [new-key (KeysImpl/createPrivateKey)]
      (persistence/db-insert db :keys {"prik" (.bytes new-key)})
      new-key)))

(defn new-sneer-admin-over-db [network db]
  (let [tuple-base (persistence/create db)
        own-prik (produce-private-key db)]
    (new-sneer-admin own-prik network tuple-base)))

(defn create [db]
  (new-sneer-admin-over-db (client/create-network) db))

