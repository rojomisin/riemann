(ns riemann.transport.graphite
  (:import [io.netty.util CharsetUtil]
           [io.netty.handler.codec MessageToMessageDecoder]
           [io.netty.handler.codec.string StringDecoder StringEncoder]
           [io.netty.handler.codec DelimiterBasedFrameDecoder
                                   Delimiters])
  (:use [riemann.core :only [stream!]]
        [riemann.codec :only [->Event]]
        [riemann.transport.tcp :only [tcp-server
                                      gen-tcp-handler]]
        [riemann.transport.udp :only [udp-server
                                      gen-udp-handler]]
        [riemann.transport :only [channel-pipeline-factory
                                  channel-group
                                  shared-event-executor]]
        [slingshot.slingshot :only [try+ throw+]]
        [clojure.string :only [split
                               join]]
        [clojure.tools.logging :only [warn]]))

(defn decode-graphite-line
  "Decode a line coming from graphite.

  Graphite uses a simple scheme where each metric is given as a CRLF delimited
  line, whitespace split (space, tab) with three items:

  * The metric name
  * The metric value (optionally NaN)
  * The timestamp

  Decode-graphite-line will yield a simple event with just a service, metric,
  and timestamp."
  [line]
  (let [[service metric timestamp & garbage] (split line #"\s+")]
    ; Validate format
    (cond garbage
          (throw+ "too many fields")

          (= "" service)
          (throw+ "blank line")

          (not metric)
          (throw+ "no metric")

          (not timestamp)
          (throw+ "no timestamp")

          (re-find #"(?i)nan" metric)
          (throw+ "NaN metric"))

    ; Parse numbers
    (let [metric (try (Double. metric)
                      (catch NumberFormatException e
                        (throw+ "invalid metric")))
          timestamp (try (Long. timestamp)
                         (catch NumberFormatException e
                           (throw+ "invalid timestamp")))]

      ; Construct event
      (->Event nil
               service
               nil
               nil
               metric
               nil
               timestamp
               nil))))

(defn graphite-frame-decoder
  "Creates a netty MessageToMessage for graphite lines. Takes a parser-fn: a
  function which will transform events generated by the parser, prior to
  insertion into streams.  This can be used when graphite metrics have known
  patterns that you wish to extract more information (host, refined service
  name, tags) from; to fill in default states or TTLs, and so on.

  If parser-fn is nil, defaults to identity."
  [parser-fn]
  (let [parser-fn (or parser-fn identity)]
    (proxy [MessageToMessageDecoder] []
      (decode [context message out]
        (try+
          (.add out
                (-> message
                    decode-graphite-line
                    parser-fn))
          (catch Object e
            (throw (RuntimeException.
                     (str "Graphite server parse error (" e "): "
                          (pr-str message)))))))
      (isSharable [] true))))

(defn graphite-handler
  "Given a core, channel, and a message, applies the message to core."
  [core stats ctx message]
  (stream! core message))

(defn graphite-server
  "Start a graphite-server. Options:

  :host       \"127.0.0.1\"
  :port       2003
  :protocol   :tcp or :udp (default :tcp)
  :parser-fn  an optional function given to decode-graphite-line"
  ([] (graphite-server {}))
  ([opts]
     (let [core (get opts :core (atom nil))
           host (get opts :host "127.0.0.1")
           port (get opts :port 2003)
           protocol (get opts :protocol :tcp)
           server (if (= protocol :tcp) tcp-server udp-server)
           channel-group (channel-group (str "graphite server " host ":" port))
           graphite-message-handler (if (= protocol :tcp)
                                      (gen-tcp-handler
                                        core nil channel-group graphite-handler)
                                      (gen-udp-handler
                                        core nil channel-group graphite-handler))
           pipeline-factory
           (channel-pipeline-factory
             frame-decoder  (DelimiterBasedFrameDecoder.
                              1024
                              (Delimiters/lineDelimiter))
             ^:shared string-decoder (StringDecoder.
                                       CharsetUtil/UTF_8)
             ^:shared string-encoder (StringEncoder.
                                       CharsetUtil/UTF_8)
             ^:shared graphite-decoder (graphite-frame-decoder
                                         (:parser-fn opts))
             ^{:shared true :executor shared-event-executor} handler
             graphite-message-handler)]
       (server (merge opts
                      {:host host
                       :port port
                       :core core
                       :channel-group channel-group
                       :pipeline-factory pipeline-factory})))))
