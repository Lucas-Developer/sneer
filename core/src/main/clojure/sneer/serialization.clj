(ns sneer.serialization
  (:refer-clojure :exclude [read write])
  (:import
    [java.io ByteArrayInputStream ByteArrayOutputStream]
    [sneer PublicKey]
    [sneer.commons.exceptions FriendlyException])
  (:require [cognitect.transit :as transit]
            [sneer.keys :as keys]))

(def ^:private transit-format :json) ; other options are :json-verbose and :msgpack

(def ^:private write-handlers
  {PublicKey
   (transit/write-handler
    (fn [_] "puk")
    (fn [^PublicKey puk] (.toBytes puk)))

   clojure.lang.PersistentQueue
   (transit/write-handler
    (fn [_] "queue")
    (fn [^clojure.lang.PersistentQueue q] (vec q)))})

(def ^:private read-handlers
  {"puk"
   (transit/read-handler
     (fn [^bytes rep] (keys/create-puk rep)))

   "queue"
   (transit/read-handler
     (fn [coll] (into clojure.lang.PersistentQueue/EMPTY coll)))})

(def ^:private write-opts {:handlers write-handlers})

(def ^:private read-opts {:handlers read-handlers})

(def write transit/write)
(def read  transit/read)

(defn writer [output-stream]
  (transit/writer output-stream transit-format write-opts))

(defn reader [input-stream]
  (transit/reader input-stream  transit-format read-opts))

(defn serialize [value]
  (let [out (ByteArrayOutputStream.)]
    (write (writer out) value)
    (.toByteArray out)))

(defn deserialize
  ([^bytes bytes]
     (deserialize bytes (alength bytes)))
  ([bytes length]
     (let [in (ByteArrayInputStream. bytes 0 length)]
       (read (reader in)))))

(defn roundtrip [value max-size]
  (let [^bytes bytes (serialize value)
        size (alength bytes)]
    (when (> size max-size)
      (throw (FriendlyException. (str "Value too large (" size " bytes). Maximum is " max-size " bytes."))))
    (deserialize bytes)))
