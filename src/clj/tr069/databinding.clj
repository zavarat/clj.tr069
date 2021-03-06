(ns clj.tr069.databinding
  (:require [clojure.string :as string])
  (:use (clj.tr069 datatype))
  (:import (org.apache.axiom.soap SOAPEnvelope
                                  SOAPHeaderBlock)
           (org.apache.axiom.om OMElement)
           (org.apache.axiom.soap.impl.builder StAXSOAPModelBuilder)
           (java.io InputStream)
           (javax.xml.namespace QName)
           (javax.xml.stream XMLInputFactory)))


(def ^:private xml-ns-xsd "http://www.w3.org/2001/XMLSchema")
(def ^:private xml-ns-xsi "http://www.w3.org/2001/XMLSchema-instance")
(def ^:private xml-ns-soap "http://schemas.xmlsoap.org/soap/envelope/")
(def ^:private xml-ns-soapenc "http://schemas.xmlsoap.org/soap/encoding/")
(def ^:private xml-ns-cwmp "urn:dslforum-org:cwmp-1-1")

(def ^:private ^XMLInputFactory xml-input-factory (XMLInputFactory/newFactory))

(def xsi-type (QName. xml-ns-xsi "type"))

; TR-069 Databinding protocol
(defprotocol TR069Databinding
  "TR-069 databinding protocol"
  (to-slurp [this] "Serialize the object to XML"))

(extend-protocol TR069Databinding
  nil
  (to-slurp [this] []))

(defrecord TypedValue
  [type value]
  TR069Databinding
  (to-slurp [this]
    [:Value 
     (if (nil? type)
       {}
       {:xsi:type (str "xsd:" type)})
     value]))

; Parsing methods
(defmulti do-binding
  "Do databinding"
  (fn [^OMElement om] (keyword (.getLocalName om))))

; Helper functions
(defn- xml-string [coll]
  (let [elems (partition 3 coll)]
    (apply str
      (map (fn [[tag attrs body]]
             (str "<" (name tag)
                  (string/join
                    " " (cons "" (for [[k v] attrs]
                                   (str (name k) "=\"" v "\""))))
                  ">"
                  (if (coll? body)
                    (str "\n" (xml-string body))
                    (str body))
                  "</" (name tag) ">\n"
                  ))
           elems))))

(defn parse-type [xsd-type]
  (string/replace xsd-type "xsd:" ""))

(defn array-type [type coll]
  {:soapenc:arrayType (str (name type) "[" (count coll) "]")})

; Macros for OMElement operations
(defmacro qname [local-name]
  `(QName. (name ~local-name)))

(defmacro first-elem
  ([om]
   `(.getFirstElement ~om))
  ([om local-name]
   `(.getFirstChildWithName ~om (qname ~local-name))))

(defmacro text [om local-name]
  `(.getText (.getFirstChildWithName ~om (qname ~local-name))))

(defmacro child-array-seq [om local-name]
  `(iterator-seq
     (.getChildElements (.getFirstChildWithName ~om (qname ~local-name)))))

(defmacro inline-array-seq [om local-name]
  `(iterator-seq
     (.getChildrenWithLocalName ~om (name ~local-name))))

(defmacro defbinding [om cls & more]
  "Macro for generating do-binding implementation
  according to data type schema"
  `(new ~cls
        ~@(map (fn [[type tag :as form]]
                 (case type
                   :child `(do-binding (first-elem ~om ~tag))
                   :child-array `(map do-binding (child-array-seq ~om ~tag))
                   :inline-array `(map do-binding (inline-array-seq ~om ~tag))
                   :string-array `(map #(.getText %) (child-array-seq ~om ~tag))
                   (:int :unsignedInt :dateTime :base64 :string :boolean)
                     `(parse-value ~type (text ~om ~tag))
                     :any-simple-value `(let [child# (.getFirstChildWithName
                                                      ~om (qname ~tag))
                                              type# (parse-type
                                                     (.getAttributeValue
                                                      child# xsi-type))]
                                          (TypedValue.
                                           type#
                                           (parse-value
                                            type#
                                            (.getText child#))))))
               more)))



(defmacro deftr069type [cls & more]
  "Macro for generating TR-069 datatypes.
  The code this macro generates contains a defrecord and a defmethod,
  the defrecord form generates a record class for the type, and the
  defmethod form implements do-databinding"
  (let [is-top-level (:top-level (meta cls))
        cls-name (name cls)
        root-tag-name (if is-top-level (str "cwmp:" cls-name) cls-name)]
    `(do
       (defrecord ~cls
         [~@(map first more)]
         TR069Databinding
         (to-slurp [this]
           [~(keyword root-tag-name) {}
            (concat
              ~@(map (fn [[field type tag :as form]]
                       (case type
                         :child `(to-slurp ~field)
                         :child-array `[~tag
                                        (array-type
                                          (keyword
                                            (str "cwmp:" ~(name (last form))))
                                          ~field)
                                        (mapcat to-slurp ~field)]
                         :string-array `[~tag
                                         (array-type :xsd:string ~field)
                                         (mapcat #(vector :string {} %) ~field)]
                         :inline-array `(mapcat to-slurp ~field)
                         (:int :unsignedInt :dateTime :base64 :string :boolean)
                           `[~tag {} (print-value ~type ~field)]
                         :any-simple-value `(to-slurp ~field))
                       )
                     more)
              )
            ]))
       (defmethod do-binding ~(keyword cls)
         [^OMElement om#]
         (defbinding om# ~cls ~@(map rest more))))))


; Functions for parsing SOAP envelope

(defn- parse-envelope
  [^InputStream in]
  (let [builder (StAXSOAPModelBuilder.
                  (.createXMLStreamReader xml-input-factory in))]
    (.getSOAPEnvelope builder)))

(defn- get-body
  [^SOAPEnvelope envelope]
  (let [body (.getBody envelope)
        fault (.getFault body)]
    (if (.hasFault envelope)
      {:fault {:fault-code (.getText (.getCode fault)) 
               :detail (do-binding (first-elem (.getDetail fault)))}}
      (do-binding (.getFirstElement body)))))

(defn- get-header
  [^SOAPEnvelope envelope]
  (let [header (.getHeader envelope)]
    (reduce (fn [hdr-map ^SOAPHeaderBlock hdr-blk]
              (assoc hdr-map (keyword (.getLocalName hdr-blk))
                     {:must-understand (.getMustUnderstand hdr-blk)
                      :name (.getLocalName hdr-blk)
                      :value (.getText hdr-blk)}))
            {}
            (iterator-seq (.extractAllHeaderBlocks header)))))

(defn parse-tr069-message
  "Parse a SOAP envelope to a TR-069 message map"
  [^InputStream in]
  (let [envelope (parse-envelope in)]
     {:header (get-header envelope)
      :body (get-body envelope)}))

(defn serialize-tr069-message
  "Serialize a TR-069 message map to a SOAP envelope XML"
  [{:keys [header body]}]
  (xml-string
    [:soap:Envelope {:xmlns:xsd  xml-ns-xsd
                     :xmlns:xsi  xml-ns-xsi
                     :xmlns:soap xml-ns-soap
                     :xmlns:soapenc xml-ns-soapenc
                     :xmlns:cwmp xml-ns-cwmp
                     }
     [:soap:Header {} (mapcat (fn [[hdr-name {:keys [must-understand value]}]]
                                [(str "cwmp:" (name hdr-name))
                                 {:soap:mustUnderstand (if must-understand 1 0)}
                                 value])
                              header)
      :soap:Body {} (if-let [{:keys [fault-code fault-string detail]}
                             (:fault body)]
                      [:Fault {}
                       [:faultcode {} fault-code
                        :faultstring {} "CWMP fault"
                        :detail {} (to-slurp detail)]]
                      (to-slurp body))] ]))

(defn create-tr069-message
  "Create a TR-069 message programatically."
  [body & {:as headers}]
  {:header (reduce (fn [hdrs [k v]]
                     (assoc hdrs k {:name k
                                    :must-understand true
                                    :value v}))
                   {}
                   headers)
   :body body})
