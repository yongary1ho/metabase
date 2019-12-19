(ns metabase.driver.mongo.query-processor
  "Logic for translating MBQL queries into Mongo Aggregation Pipeline queries. See
  https://docs.mongodb.com/manual/reference/operator/aggregation-pipeline/ for more details."
  (:require [clojure
             [set :as set]
             [string :as str]
             [walk :as walk]]
            [clojure.tools.logging :as log]
            [flatland.ordered.map :as ordered-map]
            [java-time :as t]
            [metabase.driver.mongo.util :refer [*mongo-connection*]]
            [metabase.mbql
             [schema :as mbql.s]
             [util :as mbql.u]]
            [metabase.models.field :refer [Field]]
            [metabase.query-processor
             [error-type :as error-type]
             [interface :as i]
             [store :as qp.store]]
            [metabase.query-processor.middleware.annotate :as annotate]
            [metabase.util :as u]
            [metabase.util
             [date-2 :as u.date]
             [i18n :as ui18n :refer [tru]]
             [schema :as su]]
            [monger
             [collection :as mc]
             [operators :refer :all]]
            [schema.core :as s])
  (:import metabase.models.field.FieldInstance
           org.bson.types.ObjectId))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                     Schema                                                     |
;;; +----------------------------------------------------------------------------------------------------------------+

;; this is just a very limited schema to make sure we're generating valid queries. We should expand it more in the
;; future

(def ^:private $ProjectStage   {(s/eq $project)     {su/NonBlankString s/Any}})
(def ^:private $SortStage      {(s/eq $sort)        {su/NonBlankString (s/enum -1 1)}})
(def ^:private $MatchStage     {(s/eq $match)       {(s/constrained su/NonBlankString (partial not= $not)) s/Any}})
(def ^:private $GroupStage     {(s/eq $group)       {su/NonBlankString s/Any}})
(def ^:private $AddFieldsStage {(s/eq "$addFields") {su/NonBlankString s/Any}})
(def ^:private $LimitStage     {(s/eq $limit)       su/IntGreaterThanZero})
(def ^:private $SkipStage      {(s/eq $skip)        su/IntGreaterThanZero})

(defn- is-stage? [stage]
  (fn [m] (= (first (keys m)) stage)))

(def ^:private Stage
  (s/both
   (s/constrained su/Map #(= (count (keys %)) 1) "map with a single key")
   (s/conditional
    (is-stage? $project)     $ProjectStage
    (is-stage? $sort)        $SortStage
    (is-stage? $group)       $GroupStage
    (is-stage? "$addFields") $AddFieldsStage
    (is-stage? $match)       $MatchStage
    (is-stage? $limit)       $LimitStage
    (is-stage? $skip)        $SkipStage)))

(def ^:private Pipeline [Stage])


(def ^:private Projections
  "Schema for the `:projections` generated by the functions in this namespace. It should be a sequence of the original
  column names in the query (?)"
  [s/Keyword])

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                    QP Impl                                                     |
;;; +----------------------------------------------------------------------------------------------------------------+


;; TODO - We already have a *query* dynamic var in metabase.query-processor.interface. Do we need this one too?
(def ^:dynamic ^:private *query* nil)

(defn- log-aggregation-pipeline [form]
  (when-not i/*disable-qp-logging*
    (log/tracef "\nMongo aggregation pipeline:\n%s\n"
                (u/pprint-to-str 'green (walk/postwalk #(if (symbol? %) (symbol (name %)) %) form)))))


;;; # STRUCTURED QUERY PROCESSOR

;;; ## FORMATTING

;; We're not allowed to use field names that contain a period in the Mongo aggregation $group stage.
;; Not OK:
;;   {"$group" {"source.username" {"$first" {"$source.username"}, "_id" "$source.username"}}, ...}
;;
;; For *nested* Fields, we'll replace the '.' with '___', and restore the original names afterward.
;; Escaped:
;;   {"$group" {"source___username" {"$first" {"$source.username"}, "_id" "$source.username"}}, ...}

(defmulti ^:private ->rvalue
  "Format this `Field` or value for use as the right hand value of an expression, e.g. by adding `$` to a `Field`'s
  name"
  {:arglists '([x])}
  mbql.u/dispatch-by-clause-name-or-class)

(defmulti ^:private ->lvalue
  "Return an escaped name that can be used as the name of a given Field."
  {:arglists '([field])}
  mbql.u/dispatch-by-clause-name-or-class)

(defmulti ^:private ->initial-rvalue
  "Return the rvalue that should be used in the *initial* projection for this `Field`."
  {:arglists '([field])}
  mbql.u/dispatch-by-clause-name-or-class)

(defn field->name
  "Return a single string name for `field`. For nested fields, this creates a combined qualified name."
  ^String [^FieldInstance field, ^String separator]
  (if-let [parent-id (:parent_id field)]
    (str/join separator [(field->name (qp.store/field parent-id) separator)
                         (:name field)])
    (:name field)))

(defmacro ^:private mongo-let
  {:style/indent 1}
  [[field value] & body]
  {:$let {:vars {(keyword field) value}
          :in   `(let [~field ~(keyword (str "$$" (name field)))]
                   ~@body)}})


(defmethod ->lvalue (class Field)
  [field]
  (field->name field "___"))

(defmethod ->initial-rvalue (class Field)
  [{special-type :special_type, :as field}]
  (let [field-name (str \$ (field->name field "."))]
    (cond
      ;; TIMEZONE FIXME — use `java.time` classes
      (isa? (:special_type field) :type/UNIXTimestampMilliseconds)
      {$add [(java.util.Date. 0) field-name]}

      (isa? (:special_type field) :type/UNIXTimestampSeconds)
      {$add [(java.util.Date. 0) {$multiply [field-name 1000]}]}

      :else field-name)))

(defmethod ->rvalue (class Field)
  [field]
  (str \$ (->lvalue field)))

(defmethod ->lvalue         :field-id [[_ field-id]] (->lvalue          (qp.store/field field-id)))
(defmethod ->initial-rvalue :field-id [[_ field-id]] (->initial-rvalue  (qp.store/field field-id)))
(defmethod ->rvalue         :field-id [[_ field-id]] (->rvalue          (qp.store/field field-id)))

(defmethod ->lvalue         :field-literal [[_ field-name]] (name field-name))
(defmethod ->initial-rvalue :field-literal [[_ field-name]] (str \$ (name field-name)))
(defmethod ->rvalue         :field-literal [[_ field-name]] (str \$ (name field-name))) ; TODO - not sure if right?

;; Don't think this needs to implement `->lvalue` because you can't assign something to an aggregation e.g.
;;
;;    aggregations[0] = 20
;;
;; makes no sense. It doesn't have an initial projection either so no need to implement `->initial-rvalue`
(defmethod ->lvalue :aggregation
  [[_ index]]
  (annotate/aggregation-name (mbql.u/aggregation-at-index *query* index)))
;; TODO - does this need to implement `->lvalue` and `->initial-rvalue` ?


(defmethod ->lvalue :datetime-field
  [[_ field-clause unit]]
  (str (->lvalue field-clause) "~~~" (name unit)))

(defmethod ->initial-rvalue :datetime-field
  [[_ field-clause unit]]
  (let [field-id (mbql.u/field-clause->id-or-literal field-clause)
        field    (when (integer? field-id)
                   (qp.store/field field-id))]
    (mongo-let [column (->initial-rvalue field-clause)]
      (letfn [(stringify
                ([format-string]
                 (stringify format-string column))
                ([format-string fld]
                 {:___date {:$dateToString {:format format-string
                                            :date   fld}}}))]
        (case unit
          :default         column
          :minute          (stringify "%Y-%m-%dT%H:%M:00")
          :minute-of-hour  {$minute column}
          :hour            (stringify "%Y-%m-%dT%H:00:00")
          :hour-of-day     {$hour column}
          :day             (stringify "%Y-%m-%d")
          :day-of-week     {$dayOfWeek column}
          :day-of-month    {$dayOfMonth column}
          :day-of-year     {$dayOfYear column}
          :week            (stringify "%Y-%m-%d" {$subtract [column
                                                             {$multiply [{$subtract [{$dayOfWeek column}
                                                                                     1]}
                                                                         (* 24 60 60 1000)]}]})
          :week-of-year    {$add [{$week column}
                                  1]}
          :month           (stringify "%Y-%m")
          :month-of-year   {$month column}
          ;; For quarter we'll just subtract enough days from the current date to put it in the correct month and
          ;; stringify it as yyyy-MM Subtracting (($dayOfYear(column) % 91) - 3) days will put you in correct month.
          ;; Trust me.
          :quarter         (stringify "%Y-%m" {$subtract [column
                                                          {$multiply [{$subtract [{$mod [{$dayOfYear column}
                                                                                         91]}
                                                                                  3]}
                                                                      (* 24 60 60 1000)]}]})
          :quarter-of-year (mongo-let [month   {$month column}]
                             {$divide [{$subtract [{$add [month 2]}
                                                   {$mod [{$add [month 2]}
                                                          3]}]}
                                       3]})
          :year            (stringify "%Y"))))))


(defmethod ->rvalue :datetime-field
  [this]
  (str \$ (->lvalue this)))

;; Values clauses below; they only need to implement `->rvalue`

(defmethod ->rvalue nil [_] nil)

(defmethod ->rvalue :value
  [[_ value {base-type :base_type}]]
  (if (isa? base-type :type/MongoBSONID)
    (ObjectId. (str value))
    value))

(defmethod ->rvalue :absolute-datetime
  [[_ t unit]]
  (letfn [(stringify
            ([format-string]
             (stringify format-string t))
            ([format-string t]
             {:___date (t/format format-string t)}))
          (extract [unit]
            (u.date/extract t unit))]
    (case (or unit :default)
      :default         (t/to-java-date t)
      :minute          (stringify "yyyy-MM-dd'T'HH:mm:00")
      :minute-of-hour  (extract :minute)
      :hour            (stringify "yyyy-MM-dd'T'HH:00:00")
      :hour-of-day     (extract :hour)
      :day             (stringify "yyyy-MM-dd")
      :day-of-week     (extract :day-of-week)
      :day-of-month    (extract :day-of-month)
      :day-of-year     (extract :day-of-year)
      :week            (stringify "yyyy-MM-dd" (u.date/truncate t :week))
      :week-of-year    (extract :week-of-year)
      :month           (stringify "yyyy-MM")
      :month-of-year   (extract :month-of-year)
      :quarter         (stringify "yyyy-MM" (u.date/truncate t :quarter))
      :quarter-of-year (extract :quarter-of-year)
      :year            (stringify "yyyy"))))

;; TODO - where's the part where we handle include-current?
(defmethod ->rvalue :relative-datetime
  [[_ amount unit]]
  (->rvalue [:absolute-datetime (u.date/add (or unit :day) amount) unit]))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                               CLAUSE APPLICATION                                               |
;;; +----------------------------------------------------------------------------------------------------------------+


;;; ----------------------------------------------- initial projection -----------------------------------------------

(s/defn ^:private add-initial-projection :- {:projections Projections, :query Pipeline}
  [inner-query pipeline-ctx]
  (let [all-fields (distinct (mbql.u/match inner-query #{:field-id :datetime-field}))]
    (if-not (seq all-fields)
      pipeline-ctx
      (let [projection+initial-rvalue (for [field all-fields]
                                        [(->lvalue field) (->initial-rvalue field)])]
        (-> pipeline-ctx
            (assoc  :projections (doall (map (comp keyword first) projection+initial-rvalue)))
            (update :query conj {$project (into (ordered-map/ordered-map) projection+initial-rvalue)}))))))


;;; ----------------------------------------------------- filter -----------------------------------------------------

(defmethod ->rvalue ::not [[_ value]]
  {$not (->rvalue value)})

(defmulti ^:private parse-filter first)

(defmethod parse-filter :between [[_ field min-val max-val]]
  {(->lvalue field) {$gte (->rvalue min-val)
                     $lte (->rvalue max-val)}})

(defn- str-match-pattern [options prefix value suffix]
  (if (mbql.u/is-clause? ::not value)
    {$not (str-match-pattern options prefix (second value) suffix)}
    (let [case-sensitive? (get options :case-sensitive true)]
      (re-pattern (str (when-not case-sensitive? "(?i)") prefix (->rvalue value) suffix)))))

(defmethod parse-filter :contains    [[_ field v opts]] {(->lvalue field) (str-match-pattern opts nil v nil)})
(defmethod parse-filter :starts-with [[_ field v opts]] {(->lvalue field) (str-match-pattern opts \^  v nil)})
(defmethod parse-filter :ends-with   [[_ field v opts]] {(->lvalue field) (str-match-pattern opts nil v \$)})

(defmethod parse-filter :=  [[_ field value]] {(->lvalue field) {$eq  (->rvalue value)}})
(defmethod parse-filter :!= [[_ field value]] {(->lvalue field) {$ne  (->rvalue value)}})
(defmethod parse-filter :<  [[_ field value]] {(->lvalue field) {$lt  (->rvalue value)}})
(defmethod parse-filter :>  [[_ field value]] {(->lvalue field) {$gt  (->rvalue value)}})
(defmethod parse-filter :<= [[_ field value]] {(->lvalue field) {$lte (->rvalue value)}})
(defmethod parse-filter :>= [[_ field value]] {(->lvalue field) {$gte (->rvalue value)}})

(defmethod parse-filter :and [[_ & args]] {$and (mapv parse-filter args)})
(defmethod parse-filter :or  [[_ & args]] {$or (mapv parse-filter args)})


;; MongoDB doesn't support negating top-level filter clauses. So we can leverage the MBQL lib's `negate-filter-clause`
;; to negate everything, with the exception of the string filter clauses, which we will convert to a `{not <regex}`
;; clause (see `->rvalue` for `::not` above). `negate` below wraps the MBQL lib function
(defmulti ^:private negate first)

(defmethod negate :default [clause]
  (mbql.u/negate-filter-clause clause))

(defmethod negate :and [[_ & subclauses]] (apply vector :or  (map negate subclauses)))
(defmethod negate :or  [[_ & subclauses]] (apply vector :and (map negate subclauses)))

(defmethod negate :contains    [[_ field v opts]] [:contains field [::not v] opts])
(defmethod negate :starts-with [[_ field v opts]] [:starts-with field [::not v] opts])
(defmethod negate :ends-with   [[_ field v opts]] [:ends-with field [::not v] opts])

(defmethod parse-filter :not [[_ subclause]]
  (parse-filter (negate subclause)))

(defn- handle-filter [{filter-clause :filter} pipeline-ctx]
  (if-not filter-clause
    pipeline-ctx
    (update pipeline-ctx :query conj {$match (parse-filter filter-clause)})))

(defmulti ^:private parse-cond first)

(defmethod parse-cond :between [[_ field min-val max-val]]
  (parse-cond [:and [:>= field min-val] [:< field max-val]]))

(defn- indexOfCP
  [source needle case-sensitive?]
  (let [source (if case-sensitive?
                 (->rvalue source)
                 {$toLower (->rvalue source)})
        needle (if case-sensitive?
                 (->rvalue needle)
                 {$toLower (->rvalue needle)})]
    {"$indexOfCP" [source needle]}))

(defmethod parse-cond :contains    [[_ field value opts]] {$ne [(indexOfCP field value (get opts :case-sensitive true)) -1]})
(defmethod parse-cond :starts-with [[_ field value opts]] {$eq [(indexOfCP field value (get opts :case-sensitive true)) 0]})
(defmethod parse-cond :ends-with   [[_ field value opts]]
  (let [strcmp (fn [a b]
                 (if (get opts :case-sensitive true)
                   {$eq [a b]}
                   {$eq [{$strcasecmp [a b]} 0]}))]
    (strcmp {"$substrCP" [(->rvalue field)
                          {$subtract [{"$strLenCP" (->rvalue field)}
                                      {"$strLenCP" (->rvalue value)}]}
                          {"$strLenCP" (->rvalue value)}]}
            (->rvalue value))))

(defmethod parse-cond :=  [[_ field value]] {$eq [(->rvalue field) (->rvalue value)]})
(defmethod parse-cond :!= [[_ field value]] {$ne [(->rvalue field) (->rvalue value)]})
(defmethod parse-cond :<  [[_ field value]] {$lt [(->rvalue field) (->rvalue value)]})
(defmethod parse-cond :>  [[_ field value]] {$gt [(->rvalue field) (->rvalue value)]})
(defmethod parse-cond :<= [[_ field value]] {$lte [(->rvalue field) (->rvalue value)]})
(defmethod parse-cond :>= [[_ field value]] {$gte [(->rvalue field) (->rvalue value)]})

(defmethod parse-cond :and [[_ & args]] {$and (mapv parse-cond args)})
(defmethod parse-cond :or  [[_ & args]] {$or (mapv parse-cond args)})

(defmethod parse-cond :not [[_ subclause]]
  (parse-cond (negate subclause)))


;;; -------------------------------------------------- aggregation ---------------------------------------------------

(defn- aggregation->rvalue [ag]
  (mbql.u/match-one ag
    [:aggregation-options ag _]
    (recur ag)

    [:count]
    {$sum 1}

    [:count arg]
    {$sum {$cond {:if   (->rvalue arg)
                  :then 1
                  :else 0}}}
    [:avg arg]
    {$avg (->rvalue arg)}


    [:distinct arg]
    {$addToSet (->rvalue arg)}

    [:sum arg]
    {$sum (->rvalue arg)}

    [:min arg]
    {$min (->rvalue arg)}

    [:max arg]
    {$max (->rvalue arg)}

    [:sum-where arg pred]
    {$sum {$cond {:if   (parse-cond pred)
                  :then (->rvalue arg)
                  :else 0}}}

    [:count-where pred]
    (recur [:sum-where [:value 1] pred])

    :else
    (throw
     (ex-info (tru "Don''t know how to handle aggregation {0}" ag)
       {:type :invalid-query, :clause ag}))))

(defn- unwrap-named-ag [[ag-type arg :as ag]]
  (if (= ag-type :aggregation-options)
    (recur arg)
    ag))

(s/defn ^:private breakouts-and-ags->projected-fields :- [(s/pair su/NonBlankString "projected-field-name"
                                                                  s/Any             "source")]
  "Determine field projections for MBQL breakouts and aggregations. Returns a sequence of pairs like
  `[projectied-field-name source]`."
  [breakout-fields aggregations]
  (concat
   (for [field breakout-fields]
     [(->lvalue field) (format "$_id.%s" (->lvalue field))])
   (for [ag aggregations]
     [(annotate/aggregation-name ag) (if (mbql.u/is-clause? :distinct (unwrap-named-ag ag))
                                       {$size "$count"} ; HACK
                                       true)])))

(defmulti ^:private expand-aggregation (comp first unwrap-named-ag))

(defmethod expand-aggregation :share
  [[_ pred :as ag]]
  (let [count-where-name (name (gensym "count-where"))
        count-name    (name (gensym "count-"))
        pred          (if (= (first pred) :share)
                        (second pred)
                        pred)]
    [[[count-where-name (aggregation->rvalue [:count-where pred])]
      [count-name (aggregation->rvalue [:count])]]
     [[(annotate/aggregation-name ag) {$divide [(str "$" count-where-name) (str "$" count-name)]}]]]))

(defmethod expand-aggregation :default
  [ag]
  [[[(annotate/aggregation-name ag) (aggregation->rvalue ag)]]])

(defn- group-and-post-aggregations
  "Mongo is picky (and somewhat stupid) which top-level aggregations it alows with groups. Eg. even
   though [:/ [:coun-if ...] [:count]] is a perfectly fine reduction, it's not allowed. Therefore
   more complex aggregations are split in two: the reductions are done in `$group` stage after which
   we do postprocessing in `$addFields` stage to arrive at the final result. The intermitent results
   accrued in `$group` stage are discarded in the final `$project` stage."
  [id aggregations]
  (let [expanded-ags (map expand-aggregation aggregations)
        group-ags    (mapcat first expanded-ags)
        post-ags     (mapcat second expanded-ags)]
    [{$group (into (ordered-map/ordered-map "_id" id) group-ags)}
     (when (not-empty post-ags)
       {"$addFields" (into (ordered-map/ordered-map) post-ags)})]))

(defn- lvalue?
  [x]
  (try
    (some? (->lvalue x))
    (catch IllegalArgumentException _
      false)))

(defn- breakouts-and-ags->pipeline-stages
  "Return a sequeunce of aggregation pipeline stages needed to implement MBQL breakouts and aggregations."
  [projected-fields breakout-fields aggregations]
  (mapcat
   (partial remove nil?)
   [;; create a totally sweet made-up column called `___group` to store the fields we'd
    ;; like to group by
    (when (seq breakout-fields)
      [{$project (into
                  (ordered-map/ordered-map "_id"      "$_id"
                                           "___group" (into
                                                        (ordered-map/ordered-map)
                                                        (for [field breakout-fields]
                                                          [(->lvalue field) (->rvalue field)])))
                  (comp (map (comp second unwrap-named-ag))
                        (mapcat (fn [ag-fields]
                                  (for [ag-field (mbql.u/match ag-fields lvalue?)]
                                    [(->lvalue ag-field) (->rvalue ag-field)]))))
                  aggregations)}])
    ;; Now project onto the __group and the aggregation rvalue
    (group-and-post-aggregations (when (seq breakout-fields) "$___group") aggregations)
    [;; Sort by _id (___group)
     {$sort {"_id" 1}}
     ;; now project back to the fields we expect
     {$project (into
                (ordered-map/ordered-map "_id" false)
                projected-fields)}]]))

(defn- handle-breakout+aggregation
  "Add projections, groupings, sortings, and other things needed to the Query pipeline context (`pipeline-ctx`) for
  MBQL `aggregations` and `breakout-fields`."
  [{breakout-fields :breakout, aggregations :aggregation} pipeline-ctx]
  (if-not (or (seq aggregations) (seq breakout-fields))
    ;; if both aggregations and breakouts are empty, there's nothing to do...
    pipeline-ctx
    ;; determine the projections we'll need. projected-fields is like [[projected-field-name source]]`
    (let [projected-fields (breakouts-and-ags->projected-fields breakout-fields aggregations)]
      (-> pipeline-ctx
          ;; add :projections key which is just a sequence of the names of projections from above
          (assoc :projections (vec (for [[field] projected-fields]
                                     (keyword field))))
          ;; now add additional clauses to the end of :query as applicable
          (update :query into (breakouts-and-ags->pipeline-stages projected-fields breakout-fields aggregations))))))


;;; ---------------------------------------------------- order-by ----------------------------------------------------

(s/defn ^:private order-by->$sort :- $SortStage
  [order-by :- [mbql.s/OrderBy]]
  {$sort (into
          (ordered-map/ordered-map)
          (for [[direction field] order-by]
            [(->lvalue field) (case direction
                                :asc   1
                                :desc -1)]))})

(defn- handle-order-by [{:keys [order-by]} pipeline-ctx]
  (cond-> pipeline-ctx
    (seq order-by) (update :query conj (order-by->$sort order-by))))

;;; ----------------------------------------------------- fields -----------------------------------------------------

(defn- handle-fields [{:keys [fields]} pipeline-ctx]
  (if-not (seq fields)
    pipeline-ctx
    (let [new-projections (for [field fields]
                            [(->lvalue field) (->rvalue field)])]
      (-> pipeline-ctx
          (assoc :projections (map (comp keyword first) new-projections))
          ;; add project _id = false to keep _id from getting automatically returned unless explicitly specified
          (update :query conj {$project (into
                                         (ordered-map/ordered-map "_id" false)
                                         new-projections)})))))

;;; ----------------------------------------------------- limit ------------------------------------------------------

(defn- handle-limit [{:keys [limit]} pipeline-ctx]
  (if-not limit
    pipeline-ctx
    (update pipeline-ctx :query conj {$limit limit})))


;;; ------------------------------------------------------ page ------------------------------------------------------

(defn- handle-page [{{page-num :page, items-per-page :items, :as page-clause} :page} pipeline-ctx]
  (if-not page-clause
    pipeline-ctx
    (update pipeline-ctx :query concat (filter some? [(let [offset (* items-per-page (dec page-num))]
                                                        (when-not (zero? offset)
                                                          {$skip offset}))
                                                      {$limit items-per-page}]))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                 Process & Run                                                  |
;;; +----------------------------------------------------------------------------------------------------------------+

(s/defn ^:private generate-aggregation-pipeline :- {:projections Projections, :query Pipeline}
  "Generate the aggregation pipeline. Returns a sequence of maps representing each stage."
  [inner-query :- mbql.s/MBQLQuery]
  (reduce (fn [pipeline-ctx f]
            (f inner-query pipeline-ctx))
          {:projections [], :query []}
          [add-initial-projection
           handle-filter
           handle-breakout+aggregation
           handle-order-by
           handle-fields
           handle-limit
           handle-page]))

(s/defn ^:private create-unescaping-rename-map :- {s/Keyword s/Keyword}
  [original-keys :- Projections]
  (into
   (ordered-map/ordered-map)
   (for [k original-keys
         :let [k-str     (name k)
               unescaped (-> k-str
                             (str/replace #"___" ".")
                             (str/replace #"~~~(.+)$" ""))]
         :when (not (= k-str unescaped))]
     [k (keyword unescaped)])))

(defn- unescape-names
  "Restore the original, unescaped nested Field names in the keys of `results`.
  e.g. `:source___service` becomes `:source.service`"
  [results]
  ;; Build a map of escaped key -> unescaped key by looking at the keys in the first result
  ;; e.g. {:source___username :source.username}
  (let [replacements (create-unescaping-rename-map (keys (first results)))]
    ;; If the map is non-empty then map set/rename-keys over the results with it
    (if-not (seq replacements)
      results
      (do (log/debug "Unescaping fields:" (u/pprint-to-str 'green replacements))
          (for [row results]
            (set/rename-keys row replacements))))))


(defn- unstringify-dates
  "Convert string dates, which we wrap in dictionaries like `{:___date <str>}`, back to `Timestamps`.
  This can't be done within the Mongo aggregation framework itself."
  [results]
  (for [row results]
    (into
     (ordered-map/ordered-map)
     (for [[k v] row]
       [k (if (and (map? v)
                   (contains? v :___date))
            (u.date/parse (:___date v))
            v)]))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                Query Execution                                                 |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn mbql->native
  "Process and run an MBQL query."
  [{{source-table-id :source-table} :query, :as query}]
  (let [{source-table-name :name} (qp.store/table source-table-id)]
    (binding [*query* query]
      (let [{proj :projections, generated-pipeline :query} (generate-aggregation-pipeline (:query query))]
        (log-aggregation-pipeline generated-pipeline)
        {:projections proj
         :query       generated-pipeline
         :collection  source-table-name
         :mbql?       true}))))

(defn check-columns
  "Make sure there are no columns coming back from `results` that we weren't expecting. If there are, we did something
  wrong here and the query we generated is off."
  [columns results]
  (when (seq results)
    (let [expected-cols   (set columns)
          actual-cols     (set (keys (first results)))
          not-in-expected (set/difference actual-cols expected-cols)]
      (when (seq not-in-expected)
        (throw (Exception. (tru "Unexpected columns in results: {0}" (sort not-in-expected))))))))

(defn parse-query-string
  "Parse a serialized native query. Like a normal JSON parse, but handles BSON/MongoDB extended JSON forms."
  [^String s]
  (try
    (for [^org.bson.BsonValue v (org.bson.BsonArray/parse s)]
      (com.mongodb.BasicDBObject. (.asDocument v)))
    (catch Throwable e
      (throw (ex-info (tru "Unable to parse query: {0}" (.getMessage e))
               {:type  error-type/invalid-query
                :query s}
               e)))))

(defn execute-query
  "Process and run a native MongoDB query."
  [{{:keys [collection query mbql? projections]} :native}]
  {:pre [query (string? collection)]}
  (let [query      (cond-> query
                     (string? query) parse-query-string)
        results    (mc/aggregate *mongo-connection* collection query
                                 :allow-disk-use true
                                 ;; options that control the creation of the cursor object. Empty map means use default
                                 ;; options. Needed for Mongo 3.6+
                                 :cursor {})
        results    (if (sequential? results)
                     results
                     [results])
        ;; if we formed the query using MBQL then we apply a couple post processing functions
        results    (cond-> results
                     mbql? (-> unescape-names unstringify-dates))
        rename-map (create-unescaping-rename-map projections)
        ;; some of the columns may or may not come back in every row, because of course with mongo some key can be
        ;; missing. That's ok, the logic below where we call `(mapv row columns)` will end up adding `nil` results for
        ;; those columns.
        columns    (if-not mbql?
                     (keys (first results))
                     (for [proj projections]
                       (if (contains? rename-map proj)
                         (get rename-map proj)
                         proj)))]
    ;; ...but, on the other hand, if columns come back that we weren't expecting, our code is broken. Check to make
    ;; sure that didn't happen.
    (when mbql?
      (check-columns columns results))
    ;; The `annotate/result-rows-maps->vectors` middleware will handle converting result rows from maps to vectors in
    ;; the correct sort order
    {:rows results}))
