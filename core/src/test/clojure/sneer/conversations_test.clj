(ns sneer.conversations-test
  (:require [midje.sweet :refer :all]
            [clojure.core.async :as async :refer [thread]]
            [rx.lang.clojure.core :as rx]
            [sneer.tuple.space :as space]
            [sneer.tuple.persistent-tuple-base :as base]
            [sneer.test-util :refer [<!!? observable->chan subscribe-chan]]
            [sneer.tuple.jdbc-database :as jdbc-database]
            [sneer.rx :refer [observe-for-io]]
            [sneer.keys :refer [->puk create-prik]]
            [sneer.impl :refer [new-sneer]])
  (:import [rx Observable]
           [sneer Sneer Party Conversation Conversations]
           [sneer.tuples TupleSpace]))

(defn- ->chan [^Observable o]
  (->> o observe-for-io observable->chan))

(facts "About reify-conversations"
  (with-open [db (jdbc-database/create-sqlite-db)
              tuple-base (base/create db)]

    (facts "#all"
      (let [own-prik       (create-prik)
            own-puk        (.publicKey own-prik)
            ^TupleSpace    tuple-space (space/reify-tuple-space own-puk tuple-base)
            ^Sneer         sneer       (new-sneer tuple-space own-prik)
            ^Conversations subject     (.conversations sneer)

            produce-party  #(.produceParty sneer (->puk %))
            ^Party         neide       (produce-party "neide")
            ^Party         carla       (produce-party "carla")

            ->chan #(doto (async/chan 10) (subscribe-chan %))
            poke           #(.. subject (with %) (sendMessage "ha!"))
            all            (->> (.all subject)
                                (rx/do #(println "TST:" %))
                                (rx/map (partial mapv (fn [^Conversation c] (.party c))))
                                (rx/do #(println "CHA:" %))
                                ->chan)]

        (fact "is initially empty"
          (<!!? all) => [])

        (fact "a new contact implies a new conversation"
          (. sneer addContact "neide" neide)
          (<!!? all) => [neide]

          (. sneer addContact "carla" carla)
          (<!!? all) => [carla neide])

        (fact "gets sorted by most recent conversation timestamp"
          (poke neide)
          (<!!? all) => [neide carla]

          (poke carla)
          (<!!? all) => [carla neide])))))
