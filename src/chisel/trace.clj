(ns chisel.trace
  (:require [io.pedestal.log :as plog]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as chain]
            [io.pedestal.interceptor.trace :as trace]
            [chisel.correlation-ctx :as correlation-ctx]
            [chisel.async-utils :as async]))

;; This needs to be bounded before executing a handler
(def ^:dynamic *span* nil)

(defmacro with-request
  "Executes body, making the tracing span available in the called code"
  [request & body]
  `(binding [*span* (::span ~request)]
     ~@body))

(defn current-span
  "We avoid using ScopeManagers, as they could introduce memory leaks,
  when using ThreadLocal scope for keeping reference to the active span"
  []
  *span*)

(defmacro with-span
  "Wrap a calculation in a new span"
  [span-name & body]
  `(let [span# (plog/span ~span-name *span*)]
     (try
       (binding [*span* span#]
         ~@body)
       (finally
         (plog/finish-span span#)))))

(defmacro go-with-span
  "Wrap an async call with a new span"
  [span-name & body]
  `(async/go-try (with-span ~span-name ~@body)))

(defn make-tracing-ctx-interceptor [tracing-tags-fn error-status-fn]
  (interceptor/interceptor
    {:name  ::tracing-ctx
     :enter (fn [context]
              (correlation-ctx/with-context context
                (if-let [span (::plog/span context)]
                  (-> context
                      (assoc ::plog/span (plog/tag-span span (tracing-tags-fn context)))
                      (assoc-in [:request ::span] span))
                  context)))
     :error (fn [context throwable]
              (if-let [span (::plog/span context)]
                (assoc context ::plog/span (-> span
                                               (plog/tag-span "http.status_code"
                                                              (error-status-fn context throwable))
                                               (plog/tag-span "error" true))
                               ::chain/error throwable)
                (assoc context ::chain/error throwable)))}))

(def tracing-interceptor (trace/tracing-interceptor))

(def tracing-ctx-interceptor
  (make-tracing-ctx-interceptor
    (fn [context]
      {"flow_id"   (get correlation-ctx/*ctx* correlation-ctx/correlation-id-header "")
       "http.host" (get-in context [:request :server-name])})
    (fn [context _throwable]
      (get-in context [:response :status] 500))))
