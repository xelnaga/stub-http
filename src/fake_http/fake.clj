(ns fake-http.fake
  (:import (okhttp3.mockwebserver MockWebServer Dispatcher MockResponse RecordedRequest)
           (fake_http.fake FakeServer))
  (:require [clojure.string :refer [split blank? subst]]))

(defn- index-of [coll item]
  "Get the index of an item in a collection"
  (count (take-while (partial not= item) coll)))

(defn- substring-after [str after]
  (let [index-of-after (.indexOf str after)]
    (if (= -1 index-of-after)
      str
      (.substring str (inc index-of-after)))))

(defn- keywordize-keys
  "Recursively transforms all map keys from strings to keywords."
  [m]
  (let [f (fn [[k v]] (if (string? k) [(keyword k) v] [k v]))]
    (clojure.walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))

(defn- substring-before [str before]
  "Return the substring of string <str> before string <before>. If no match then <str> is returned."
  (let [index-of-before (.indexOf str before)]
    (if (= -1 index-of-before)
      str
      (.substring str 0 index-of-before))))

(defn- query-params->map [param-string]
  (if-not (blank? param-string)
    (let [params-splitted-by-and (split param-string #"&")
          ; Handle no-value parameters. If a no-value parameter is found then use nil as parameter value
          params-as-tuples (map #(let [index-of-= (.indexOf % "=")]
                                  (if (and
                                        (> index-of-= 0)
                                        ; Check if the first = char is the last char of the param.
                                        (< (inc index-of-=) (.length %)))
                                    (split % #"=")
                                    [% nil])) params-splitted-by-and)
          param-list (flatten params-as-tuples)]
      (keywordize-keys (apply hash-map param-list)))))

(defn- request-path->map [request-path]
  "Takes a request path and transforms it into a map of its query parameters. Returns an empty map if no query parameters are defined"
  (if (.contains request-path "?")
    (let [param-string (substring-after request-path "?")]
      (query-params->map param-string))
    {}))

(defn- fake-response [{:keys [status headers body content-type]}]
  "Create a \"fake response\" (MockResponse) from the given map.

   path - The request path to mock, for example /search
   status - The status code
   headers - The response headers (list or vector of tuples specifying headers). For example ([\"Content-Type\" \"application/json\"], ...)
   body - The response body"

  (let [fake-response (MockResponse.)]
    (doto fake-response
      (.setResponseCode status))
    (if-not (nil? body) (.setBody fake-response body))
    (if-not (nil? content-type) (.setHeader fake-response "Content-Type" content-type))
    (doseq [header (or headers [])]
      (.setHeader fake-response (first header) (second header)))
    fake-response))

(defn- fake-route-matching? [request-params]
  (fn [potential-response]
    (let [route-matchers (:route-matcher potential-response)]
      (some #(if (= (get request-params (key %)) (val %)) potential-response) route-matchers))))

(defn- find-best-matching-fake-route [potential-responses request-path]
  "Matches the best "
  (condp = (count potential-responses)
    0 nil
    1 (first potential-responses)
    ; Several routes matches the path
    (let [query-map (request-path->map request-path)
          matching-response (some (fake-route-matching? query-map) potential-responses)]
      matching-response)))

(defn- record-request-to-fake-route [request fake-route]
  (fn [fake-routes] (let [matching-route-matcher (:route-matcher fake-route)
                          route-matchers (map #(:route-matcher %) fake-routes)
                          matching-route-index (index-of route-matchers matching-route-matcher)
                          body (.getUtf8Body request)
                          recored-request {:method       (.getMethod request)
                                           :request-line (.getRequestLine request)
                                           :path         (.getPath request)
                                           :body         body
                                           :params       (request-path->map (.getPath request))
                                           :form-params  (query-params->map body)}]
                      (update-in fake-routes [matching-route-index :recorded-requests] conj recored-request))))


(defn- create-dispatcher [fake-routes]
  "Create a MockWebServer dispatcher that will return the same response over and over on match"
  (proxy [Dispatcher] []
    (dispatch [^RecordedRequest request]
      (let [request-path (.getPath request)
            request-path-no-params (substring-before request-path "?")
            matches-request-path-fn #(or (= (:route-matcher %) request-path-no-params) (= (->> % :route-matcher :path) request-path-no-params))
            matching-fake-routes (filter matches-request-path-fn @fake-routes)
            best-match (find-best-matching-fake-route matching-fake-routes request-path)]
        (if-not (nil? best-match)
          ; Record the received request to the matched mock route
          (swap! fake-routes (record-request-to-fake-route request best-match)))
        (or (:response best-match) (fake-response {:status 500}))))))

(defprotocol FakeServer
  (uri [this])
  (recorded-requests [this route-matcher])
  (fake-route! [this route-matcher response])
  (shutdown! [this]))

(defn- record-route! [fake-routes route-matcher response]
  (let [fake-response (fake-response response)]
    (swap! fake-routes conj {:route-matcher     route-matcher
                             :response          fake-response
                             :recorded-requests []})))

(defn- new-fake-web-server [dispatcher]
  "Creates a new fake web server with the supplied dispatcher"
  (let [fake-web-server (MockWebServer.)]
    (.setDispatcher fake-web-server dispatcher)
    fake-web-server))

(defn start! []
  "Start a new fake web server on a random free port. Usage example:

  (let [fake-server (fake-server/start!)
        (fake-route! fake-server \"/x\" {:status 200 :content-type \"application/json\" :body (slurp (io/resource \"my.json\"))})
        (fake-route! fake-server {:path \"/y\" :q \"something\")} {:status 200 :content-type \"application/json\" :body (slurp (io/resource \"my2.json\"))})]
        ; Do actual HTTP request
         (shutdown! fake-server))"
  (let [fake-routes (atom [])
        dispatcher (create-dispatcher fake-routes)
        fake-web-server (new-fake-web-server dispatcher)
        _ (.start fake-web-server)
        web-server-port (.getPort fake-web-server)
        web-server-host (.getHostName fake-web-server)
        uri (str "http://" web-server-host ":" web-server-port)]
    (reify FakeServer
      (shutdown! [_]
        (.shutdown fake-web-server))
      (uri [_]
        uri)
      (recorded-requests [_ route-matcher]
        (let [fake-route (some #(if (= (:route-matcher %) route-matcher) %) @fake-routes)
              recorded-requests (:recorded-requests fake-route)]
          recorded-requests))
      (fake-route! [_ route-matcher response]
        (record-route! fake-routes route-matcher response)))))