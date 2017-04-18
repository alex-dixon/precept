(ns ^:figwheel-always libx.core
    (:refer-clojure :exclude [send])
    (:require [libx.util :refer [entity-tuples->entity-map] :as util]
              [libx.listeners :refer [ops] :as l]
              [libx.schema :as schema]
              [clara.rules :refer [query fire-rules insert! insert-all!] :as cr]
              [clara.rules.accumulators :as acc]
              [libx.spec.core :refer [validate]]
              [libx.spec.sub :as sub]
              [libx.spec.lang :as lang]
              [libx.tuplerules :refer [def-tuple-session def-tuple-rule def-tuple-query]]
      #?(:clj [clojure.core.async :refer [<! >! put! take! chan go go-loop]])
      #?(:clj [reagent.ratom :as rr])
      #?(:cljs [cljs.core.async :refer [put! take! chan <! >!]])
      #?(:cljs [libx.todomvc.rules :refer [find-all-facts]])
      #?(:cljs [reagent.core :as r]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]])))

(enable-console-print!)

(defonce initial-state
  {:subscriptions {}
   :session nil
   :schema nil
   :session-history []
   :transitioning nil
   :pending-updates []})

(defonce state (atom initial-state))

(defn mk-ratom [args]
  #?(:clj (atom args) :cljs (r/atom args)))

(defonce store (mk-ratom {}))

(defn init-schema [schema]
  (swap! state assoc :schema (schema/by-ident schema)))

(def session->store (chan 1))
(def done-ch (chan 1))

;(defn transition-watcher [key watched old new])

;(add-watch state :pending-updates transition-watcher)
;(add-watch state :transitioning transition-watcher)

(defn set-transition [bool]
  (println "---> Transitioning state" bool)
  (swap! state assoc :transitioning bool))

(defn swap-session! [next]
  (println "Swapping session!")
  (swap! state assoc :session next))

(defn enqueue-update [f]
  (println "Enqueueing update. Cur / all" f (:pending-updates @state))
  (swap! state update :pending-updates conj f))

(defn dequeue-update []
  (println "Dequeueing update")
  (swap! state update :pending-updates (fn [updates] (rest updates))))

(def processing (chan 1))

(defn dispatch! [f]
  (enqueue-update f)
  (put! processing f))

(defn debug-id []
  (hash (first (:pending-updates @state))))

(defn process-updates []
  (go-loop []
   (let [processing (<! processing)]
      (if (:transitioning @state)
        (do (println "---> Looks like we're transitioning? I'll wait to process" (debug-id))
            (do (<! done-ch)
                (println "---> Hey, we're done! Check if we can process" (debug-id))
                (recur)))
        (if (empty? (:pending-updates @state))
            (do (println "--->  No more pending updates.") (recur))
            (do (println " ---> Kicking off!" (debug-id))
                (set-transition true)
                (>! session->store (first (:pending-updates @state)))
                (dequeue-update)
                (<! done-ch)
                (recur)))))))

(defn apply-changes-to-session [in]
  (let [out (chan)]
    (go-loop []
      (let [f (<! in)
            applied (f (:session @state))
            fired (fire-rules applied)]
        (>! out fired)
        (recur)))
    out))

(defn read-changes-from-session
  "Reads changes from session channel, fires rules, and puts resultant changes
  on changes channel. Updates session state atom with new session."
  [in]
  (let [out (chan)]
    (go-loop []
      (let [session (<! in)
            ops (l/ops session)
            next-session (l/replace-listener session)
            _ (println "Ops!" ops)]
        (swap-session! next-session)
        (>! out ops)
        (recur)))
    out))

(defn add [a change]
  "Merges change into atom"
  (println "About to add" change)
  (println "Adding" (l/change->av-map change) (.now js/Date))
  (swap! a update (:db/id change) (fn [ent] (merge ent (l/change->av-map change)))))

(defn del [a change]
  "Removes keys in change from atom"
  (println "Removing entity's keys" (:db/id change) (l/change->attrs change))
  (swap! a update (:db/id change) dissoc (l/change->attrs change)))

(defn apply-removals-to-store [in]
  "Reads changes from in channel and updates store
   * `in-ch` - core.async channel
   * `store` - atom"
  (let [out (chan)]
    (go-loop []
      (let [ops (<! in)
            removals (l/embed-op (:removed ops) :remove)
            _ (println "Removals?" removals)]
       (doseq [removal removals]
          (del store removal))
       (>! out ops)
       (recur)))
   out))

(defn apply-additions-to-store [in]
  "Reads changes from in channel and updates store
   * `in-ch` - core.async channel
   * `store` - atom"
  (go-loop []
    (let [changes (<! in)
          additions (l/embed-op (:added changes) :add)
          _ (println "Additions!" additions)]
      (doseq [addition additions]
        (add store addition))
      (set-transition false)
      (>! done-ch :hi)
      (recur)))
  nil)


(defn write-changes-to-store [in]
  "Reads changes from in channel and updates store
   * `in-ch` - core.async channel
   * `store` - atom"
  (go-loop []
    (let [change (<! in)
          op (:op change)]
      (condp = op
        :add (do (add store change) (recur))
        :remove (do (del store change) (recur))
        (do (println "No match for" change) (recur))))))

(process-updates)
(def realized-session (apply-changes-to-session session->store))
(def changes-out (read-changes-from-session realized-session))
(def removals-out (apply-removals-to-store changes-out))
(def addition-applier (apply-additions-to-store removals-out))

(defn unique-identity-attrs [schema tuples]
  (reduce (fn [acc cur]
           (if (schema/unique-attr? schema (second cur))
             (conj acc (second cur))
             acc))
    [] tuples))

(defn unique-value-attrs [schema tuples]
  (reduce (fn [acc cur]
            (if (schema/unique-value? schema (second cur))
              (conj acc (second cur))
              acc))
    [] tuples))

(defn unique-value-facts [session tups unique-attrs]
  (let [unique-tups (filter #((set unique-attrs) (second %)) tups)
        avs (map rest unique-tups)]
    (mapcat (fn [[a v]] (util/facts-where (:session @state) a v))
      avs)))


;; 1. Decide whether we require a schema in defsession
;; If we do...
;; 2. Check schema is not nil in state
;; 3. For each attr in facts
;;    * if unique,
;;        remove all facts with unique attr and insert new fact
;;        else insert new fact
;;TODO. Rename/move
(defn schema-insert
  "Inserts each fact according to conditions defined in schema.
  Currently supports: db.unique/identity, db.unique/value"
  [session facts]
  (let [schema (:schema @state)
        facts-v (if (coll? (first facts)) facts (vector facts))
        tuples (mapcat util/insertable facts-v)
        unique-attrs (unique-identity-attrs schema tuples)
        unique-values (unique-value-attrs schema tuples)
        existing-unique-identity-facts (mapcat #(util/facts-where session %)
                                         unique-attrs)
        existing-unique-value-facts (unique-value-facts session tuples unique-values)
        existing-unique-facts (into existing-unique-identity-facts existing-unique-value-facts)
        _ (println "Schema-insert unique values " unique-values)
        _ (println "Schema-insert unique value facts " existing-unique-value-facts)
        _ (println "Schema-insert removing " existing-unique-facts)
        _ (println "Schema-insert inserting " tuples)
        next-session (if (empty? existing-unique-facts)
                       (-> session
                         (cr/insert-all tuples))
                       (-> session
                         (util/retract existing-unique-facts)
                         (cr/insert-all tuples)))]
    next-session))

(defn insert-action [facts]
  (fn [current-session]
    (schema-insert current-session facts)))

;; TODO. Find equivalent in CLJ
(defn lens [a path]
  #?(:clj (atom (get-in @a path))
     :cljs (r/cursor a path)))

(defn register
  [req]
  (let [id (util/guid)
        name (first req)
        lens (lens store [id ::sub/response])]
    (dispatch! (insert-action [id ::sub/request name]))
    (swap! state assoc-in [:subscriptions id] {:id id :name name :lens lens})
    lens))

(defn find-sub-by-name [name]
  (second
    (first
      (filter
        (fn [[id sub]] (= name (:name sub)))
        (:subscriptions @state)))))

(defn subscribe
  "Returns lens that points to a path in the store. Sub is handled by a rule."
  ([req]
   (let [_ (validate ::sub/request req) ;;TODO. move to :pre
         name (first req)
         existing (find-sub-by-name name)
         _ (println "New sub name / existing if any" name existing)]
         ;_ (println "Existing / all subs" existing (:subscriptions @state))]
     (if existing
       (:lens existing)
       (register req)))))


;(defn create-session-ch!
;  ([] (binding [session-ch session-ch]
;        (set! session-ch (chan 1)))
;  ([ch] (set! session-ch ch)))
;
;(defn create-changes-ch! [ch]
;  ([] (binding [changes-ch changes-ch]
;        (set! changes-ch (chan 1)))
;  ([ch] (set! changes-ch ch)))

;(defn query? [x] (and (seq x) (= :where (first x))))
;
;(defn parse-query-params [exprs]
;  (println "Parsing query params" exprs)
;  (mapcat
;    (fn [expr]
;       (println "Expr" expr (s/valid? ::lang/variable-binding (second expr)))
;       (filter #(s/valid? ::lang/variable-binding %) expr))
;    exprs))
;
;(defn run-query [exprs]
;  (println "Run query")
;  (let [params (parse-query-params exprs)]
;    (println "params")))
;    ;(clara.rules/query @session-atom
;    ;  (clara.rules.dsl/parse-query params exprs)))) ;TODO. Used in clara test but passed
;    ; directly to mk-session
;
;(defn send-with-query [[op exprs]]
;  (println "Send with query" op exprs)
;  (let [query (second exprs)
;        query-result (run-query query)]
;    (condp = op
;      :remove (swap! state update :session (fn [old] (-> old (util/retract query-result))))
;      :replace  (swap! state update :session
;                  (fn [old]
;                   (-> old
;                     (util/retract query-result)
;                     (util/insert (second double))))))))

(defn then
  ([op facts]
   (condp = op
     :add (dispatch! (insert-action facts))
     (println "Unsupported op keyword " op)))
  ([facts] (then :add facts)))

;(defn send [& exprs]
;  (let [msgs (reduce
;                  (fn [acc cur]
;                     (if (query? (second cur))
;                       (conj acc [:__query (vector (first cur) (second (second cur)))])
;                       acc))
;                 [] (partition 2 exprs))]
;    (for [msg msgs]
;      (condp (first msg)
;        :__query (send-with-query (second msg))
;        :add (swap! state update :session (fn [old] (-> old (util/insert (second msg)))))
;        :remove (swap! state update :session (fn [old] (-> old (util/retract (second msg)))))
;        :replace  (swap! state update :session
;                    (fn [old] (-> old
;                                (util/retract (second msg))
;                                (util/insert (second msg)))))))))

(defn start! [options]
  (let [opts (or options (hash-map))]
    (swap-session! (l/replace-listener (:session opts)))
    (init-schema (into schema/libx-schema (:schema opts)))
    (dispatch! (insert-action (:facts opts)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; test-area
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;(def-tuple-rule subs-footer-controls
;  [:exists [_ :sub [:footer]]]
;  [[_ :done-count ?done-count]]
;  [[_ :active-count ?active-count]]
;  [[_ :ui/visibility-filter ?visibility-filter]]
;  =>
;  (insert! [:lens [:footer] {:active-count ?active-count
;                             :done-count ?done-count
;                             :visibility-filter ?visibility-filter}]))
;
;(def-tuple-rule subs-task-list
;  [:exists [_ :sub [:task-list]]]
;  [?visible-todos <- (acc/all) :from [:todo/visible]]
;  [[_ :active-count ?active-count]]
;  =>
;  (insert-all! [[:lens [:task-list] {:visible-todos (libx.util/tuples->maps ?visible-todos)
;                                     :all-complete? (> ?active-count 0)}]]))
;(def-tuple-rule subs-todo-app
;  [:exists [:sub/todo-app]]
;  [?todos <- (acc/all) :from [:todo/title]]
;  =>
;  (println "All todos" ?todos)
;  (insert! [-1 :lens/todo-app (libx.util/tuples->maps (:todos ?todos))]))

;(def-tuple-query find-all-facts
;  []
;  [?facts <- (acc/all) :from [:all]])

;; Init
;(def session->change (create-session->change-router! session-ch changes-ch))
;(def change->store (create-change->store-router! changes-ch))

;; Reset
;(reset! store {})
;(swap! state update :subscriptions (fn [old] {}))
;(swap! state update :session (fn [old] nil))
;(init-schema app-schema)
;(def-tuple-session my-sess 'libx.core)
;(swap-session! (l/replace-listener my-sess))

;; Write
;(def facts [[-1 :active-count 7]
;            [-2 :done-count 1]
;            [-3 :todo/visible :tag]
;            [-4 :todo/title "Hi"]
;            [-5 :ui/visibility-filter :done]])
;(def next-session (schema-insert facts))
;(advance-session! next-session)

;; Schema
;(init-schema app-schema)
;(schema-insert facts)

;; Read
;(:session @state)
;@store
;(:subscriptions @state)
;(:schema @state)

;(def ch (chan))
;(def ch2 (chan))
;;
;;(defn myfunc []
;;  (do
;;    (doseq [x (range 0 10)]
;;      (go (>! ch x) (println "foo")))
;      ;(put! ch x)
;    ;(println "bar"))
;(defn myfunc []
;  (go
;    (doseq [x (range 0 10)]
;      ;(go (>! ch x) (println "foo"))
;      (put! ch x))
;    (<! ch2)
;    (println "never")))
;
;(def state (atom nil))
;
;(defn rec-loop []
;  (go-loop []
;    (let [x (<! ch)]
;      (do (println "State is" @state) (recur)))))
;
;(def rec (rec-loop))
;(myfunc)
;(reset! state "Set")
;(put! ch "Hey")
;@store
;
;(:subscriptions @state)
;(find-sub-by-name [:todo-app])


;; 1. Advance session returns session and does not use core async...?
;; State update
;; 1. Call advance-session. Args will be a new session generated from snapshot of current session
;; (may be multiple calls)
;; 2. Every advance session call should enqueue a session update fn that is called with the
;; current session in the session update pipeline (ensures next state is a function of current
;; state)
