(ns ventas.server.api
  (:require
   [buddy.hashers :as hashers]
   [byte-streams :as bytes]
   [clojure.string :as str]
   [ventas.database :as db]
   [ventas.database.entity :as entity]
   [ventas.auth :as auth]
   [ventas.entities.user :as entities.user]
   [ventas.server.pagination :as pagination]
   [ventas.server.ws :as server.ws]
   [ventas.entities.product :as entities.product]
   [ventas.paths :as paths]
   [ventas.search :as search]
   [ventas.entities.file :as entities.file]
   [ventas.common.utils :as common.utils]))

(defn register-endpoint!
  ([kw f]
   (register-endpoint! kw {:binary? false} f))
  ([kw opts f]
   {:pre [(keyword? kw) (ifn? f) (map? opts)]}
   (let [{:keys [binary? middlewares] :or {middlewares []}} opts]
     (cond
       (not binary?)
       (defmethod server.ws/handle-request kw [request state]
         (:response (reduce (fn [acc middleware]
                              (middleware acc))
                            {:request request :state state :response (f request state)}
                            middlewares)))
       binary?
       (defmethod server.ws/handle-binary-request kw [request state]
         (f request state))))))

(defn get-user [session]
  (:user @session))

(defn get-culture [session]
  (let [user (get-user session)]
    (or (:user/culture user)
        (:db/id (db/entity [:i18n.culture/keyword :en_US])))))

(register-endpoint!
  :categories.get
  (fn [{{:keys [id]} :params} {:keys [session]}]
    (-> (cond
          (number? id) id
          (keyword? id) [:category/keyword id])
        (entity/find)
        (entity/to-json {:culture (get-culture session)}))))

(register-endpoint!
  :categories.list
  (fn [_ {:keys [session]}]
    (map #(entity/to-json % {:culture (get-culture session)})
         (entity/query :category))))

(register-endpoint!
  :configuration.get
  (fn [{:keys [params]} {:keys [session]}]
    {:pre [(keyword? (:keyword params))]}
    (let [kw (:keyword params)]
      (if-let [value (first (entity/query :configuration {:keyword kw}))]
        (entity/to-json value {:culture (get-culture session)})
        (throw (Error. (str "Could not find configuration with keyword: " kw)))))))

(register-endpoint!
  :entities.find
  (fn [{{:keys [id]} :params} {:keys [session]}]
    {:pre [(number? id)]}
    (entity/find-json id {:culture (get-culture session)})))

(register-endpoint!
  :enums.get
  (fn [{:keys [params]} _]
    (db/enum-values (name (:type params)))))

(register-endpoint!
  :i18n.cultures.list
  (fn [{:keys [params]} _]
    (->> (entity/query :i18n.culture)
         (map (fn [{:i18n.culture/keys [keyword name] :db/keys [id]}]
                {:keyword keyword
                 :name name
                 :id id})))))

(register-endpoint!
 :image-sizes.list
 (fn [_ {:keys [session]}]
   (->> (entity/query :image-size)
        (map #(entity/to-json % {:culture (get-culture session)}))
        (common.utils/index-by :keyword))))

(register-endpoint!
  :products.get
  (fn [{{:keys [id terms]} :params} {:keys [session]}]
    (-> (cond
          (number? id) id
          (keyword? id) [:product/keyword id])
        (entities.product/find-variation terms)
        (entity/to-json {:culture (get-culture session)}))))

(register-endpoint!
  :products.list
  {:middlewares [pagination/wrap-sort
                 pagination/wrap-paginate]}
  (fn [{{:keys [filters]} :params} {:keys [session]}]
    (let [{:keys [terms price]} filters]
      (assert (or (nil? terms) (set? terms)))

      (let [wheres
            (as-> (entity/filters->wheres :product {:terms terms}) wheres
                  (if (seq price)
                    (let [{:keys [min max] :or {min '?price max '?price}} price]
                      (concat wheres [['?id :product/price '?price]
                                      [[<= min '?price max]]]))
                    wheres))]
        (map #(entity/find-json (:id %) {:culture (get-culture session)})
             (db/nice-query {:find '[?id]
                             :where wheres}))))))

(defn- term-counts []
  (->> (db/q '[:find (count ?product-eid)
               ?term-eid
               ?term-translation-value
               ?term-taxonomy
               ?tax-translation-value
               ?tax-keyword
               :where
               [?product-eid :product/terms ?term-eid]
               [?term-eid :product.term/name ?term-name]
               [?term-eid :product.term/taxonomy ?term-taxonomy]
               [?term-name :i18n/translations ?term-translation]
               [?term-translation :i18n.translation/value ?term-translation-value]
               [?term-translation :i18n.translation/culture [:i18n.culture/keyword :en_US]]
               [?term-taxonomy :product.taxonomy/name ?tax-name]
               [?term-taxonomy :product.taxonomy/keyword ?tax-keyword]
               [?tax-name :i18n/translations ?tax-translation]
               [?tax-translation :i18n.translation/value ?tax-translation-value]
               [?tax-translation :i18n.translation/culture [:i18n.culture/keyword :en_US]]])
       (map (fn [[count term-id term-name tax-id tax-name tax-keyword]]
              {:count count
               :id term-id
               :name term-name
               :taxonomy {:id tax-id
                          :name tax-name
                          :keyword tax-keyword}}))))

(defn- prices []
  (let [[min max]
        (->> (db/q '[:find (min ?price) (max ?price)
                     :where [_ :product/price ?price]])
             (first))]
    {:min min :max max}))

(defn- resolve-ref
  "A ref is an identifier that refers to an entity.
   The possibilities are:
     - A keyword, like :category/keyword
     - A slug
     - An eid"
  [ref kw-ident]
  {:pre [(or (number? ref) (string? ref) (keyword? ref))
         (keyword? kw-ident)]}
  (cond
    (number? ref) ref
    (keyword? ref) (:db/id (db/entity [kw-ident ref]))
    (string? ref) (entity/resolve-by-slug ref)))

(defn- get-product-category-filter [categories]
  (mapcat (fn [category-ref]
            (let [category (resolve-ref category-ref :category/keyword)]
              [{:term {:product/categories category}}]))
          categories))

(defn- get-products-query [{:keys [terms categories name price]} culture-kw]
  {:pre [culture-kw]}
  (concat [{:term {:schema/type ":schema.type/product"}}]
          (when terms
            [{:bool {:should (mapcat (fn [term]
                                       [{:bool {:should [{:term {:product/terms term}}
                                                         {:term {:product/variation-terms term}}]}}])
                                     terms)}}])
          (get-product-category-filter categories)
          (when price
            [{:range {:product/price {:gte (:min price)
                                      :lte (:max price)}}}])
          (when name
            [{:match {(search/i18n-field :product/name culture-kw) name}}])))

(defn- term-aggregation->json [{:keys [doc_count buckets]} & [json-opts taxonomy-kw]]
  (entities.product/terms-to-json
   (for [{:keys [key doc_count]} buckets]
     (let [{:keys [taxonomy] :as term} (entity/find-json key json-opts)]
       (merge (dissoc term :keyword)
              {:count doc_count
               :taxonomy (or taxonomy
                             {:id taxonomy-kw
                              :keyword taxonomy-kw})})))))

(defn- aggregate-products [categories culture]
  (let [json-opts {:culture culture}
        aggs-result (search/search {:size 0
                                    :query {:bool {:must (get-product-category-filter categories)}}
                                    :aggs {:categories {:terms {:field "product/categories"}}
                                           :terms {:terms {:field "product/terms"}}
                                           :variation-terms {:terms {:field "product/variation-terms"}}
                                           :brands {:terms {:field "product/brand"}}}})
        aggs (get-in aggs-result [:body :aggregations])]
    (concat (term-aggregation->json (:categories aggs) json-opts :category)
            (term-aggregation->json (:terms aggs) json-opts)
            (term-aggregation->json (:variation-terms aggs) json-opts))))

(defn sorting-field->es [field]
  (get {:price "product/price"}
       field))

(defn- search-products [filters {:keys [items-per-page sorting page]} culture]
  (let [culture-kw (-> culture
                       entity/find
                       :i18n.culture/keyword)
        results (search/search
                 {:_source false
                  :from (* page items-per-page)
                  :size items-per-page
                  :sort [{(sorting-field->es (:field sorting))
                          (name (:direction sorting))}
                         "_score"]
                  :query {:bool {:must (get-products-query filters
                                                           culture-kw)}}})]
    {:can-load-more? (<= items-per-page (get-in results [:body :hits :total]))
     :items (->> (get-in results
                         [:body :hits :hits])
                 (map :_id)
                 (map (fn [v] (Long/parseLong v)))
                 (map #(entity/find-json % {:culture culture})))}))

(register-endpoint!
  :products.aggregations
  (fn [{{:keys [filters pagination]} :params} {:keys [session]}]
    (let [culture (get-culture session)
          {:keys [items can-load-more?]} (search-products filters pagination culture)]
      {:items items
       :can-load-more? can-load-more?
       :taxonomies (aggregate-products (:categories filters) culture)})))

(register-endpoint!
  :states.list
  {:middlewares [pagination/wrap-paginate]}
  (fn [_ {:keys [session]}]
    (map #(entity/to-json % {:culture (get-culture session)})
         (entity/query :state))))

(register-endpoint!
  :users.addresses
  (fn [{:keys [params] :as message} {:keys [session] :as state}]
    (let [user (get-user session)]
      (->> (entity/query :address {:user (:db/id user)})
           (map #(entity/to-json % {:culture (get-culture session)}))))))

(register-endpoint!
  :users.addresses.save
  (fn [{:keys [params] :as message} {:keys [session] :as state}]
    (let [user (get-user session)
          address (entity/find (:id params))]
      (when-not (= (:db/id user) (:address/user address))
        (throw (Exception. "Unauthorized")))
      (entity/upsert :address params))))

(register-endpoint!
  :users.addresses.remove
  (fn [{{:keys [id]} :params} {:keys [session]}]
    (let [user (get-user session)
          address (entity/find id)]
      (when-not (= (:db/id user) (:address/user address))
        (throw (Exception. "Unauthorized")))
      (entity/delete id))))

(register-endpoint!
  :users.login
  (fn [{:keys [params] :as message} {:keys [session]}]
    (let [{:keys [email password]} params]
      (when-not (and email password)
        (throw (Exception. "Email and password are required")))
      (let [user (first (entity/query :user {:email email}))]
        (when-not user
          (throw (Exception. "User not found")))
        (when-not (hashers/check password (:user/password user))
          (throw (Exception. "Invalid credentials")))
        (let [token (auth/user->token user)]
          (swap! session assoc :token token)
          {:user (entity/to-json user)
           :token token})))))

(register-endpoint!
  :users.session
  (fn [{:keys [params] :as message} {:keys [session] :as state}]
    (when-let [token (or (:token params) (get @session :token))]
      (when-let [user (auth/token->user token)]
        (when (:token params)
          (swap! session assoc :token token)
          (swap! session assoc :user user))
        {:identity (entity/to-json user)}))))

(register-endpoint!
  :users.logout
  (fn [{:keys [params] :as message} {:keys [session] :as state}]
    (let [token (:token session)]
      (when token
        (swap! session dissoc :token)))))

(register-endpoint!
  :users.register
  (fn [{:keys [params] :as message} {:keys [session] :as state}]
    (let [{:keys [email password name]} params]
      (when (or (empty? email) (empty? password) (empty? name))
        (throw (Exception. "Email, password and name are required")))
      (let [user (entity/create :user {:name name
                                       :email email
                                       :password password})
            token (auth/user->token user)]
        {:user (entity/to-json user)
         :token token}))))

(register-endpoint!
  :users.cart.get
  (fn [{:keys [params]} {:keys [session]}]
    (when-let [user (get-user session)]
      (let [cart (entities.user/get-cart user)]
        (entity/to-json cart {:culture (get-culture session)})))))

(defn- find-order-line [order product-variation]
  (-> (db/nice-query
       {:find '[?id]
        :in {'?order order
             '?variation product-variation}
        :where '[[?order :order/lines ?id]
                 [?id :order.line/product-variation ?variation]]})
      first
      :id
      entity/find))

(register-endpoint!
  :users.cart.add
  (fn [{{:keys [id]} :params} {:keys [session]}]
    {:pre [id]}
    (when-let [user (get-user session)]
      (let [cart (entities.user/get-cart user)]
        (if-let [line (find-order-line (:db/id cart) id)]
          (entity/update* (update line :order.line/quantity inc))
          (entity/update* {:db/id (:db/id cart)
                           :order/lines {:schema/type :schema.type/order.line
                                         :order.line/product-variation id
                                         :order.line/quantity 1}}))
        (entity/find-json (:db/id cart) {:culture (get-culture session)})))))

(register-endpoint!
  :users.cart.remove
  (fn [{{:keys [id]} :params} {:keys [session]}]
    {:pre [id]}
    (when-let [user (get-user session)]
      (let [cart (entities.user/get-cart user)]
        (when-let [line (find-order-line (:db/id cart) id)]
          (entity/delete (:db/id line)))
        (entity/find-json (:db/id cart) {:culture (get-culture session)})))))

(register-endpoint!
  :users.cart.set-quantity
  (fn [{{:keys [id quantity]} :params} {:keys [session]}]
    {:pre [id (< 0 quantity Integer/MAX_VALUE)]}
    (when-let [user (get-user session)]
      (let [cart (entities.user/get-cart user)]
        (if-let [line (find-order-line (:db/id cart) id)]
          (entity/update* (assoc line :order.line/quantity quantity))
          (entity/update* {:db/id (:db/id cart)
                           :order/lines {:schema/type :schema.type/order.line
                                         :order.line/product-variation id
                                         :order.line/quantity quantity}}))
        (entity/find-json (:db/id cart) {:culture (get-culture session)})))))

(register-endpoint!
  :users.cart.add-discount
  (fn [{{:keys [code]} :params} {:keys [session]}]
    {:pre [(string? code) (not (str/blank? code))]}
    (when-let [user (get-user session)]
      (if-let [discount (entity/query-one :discount {:code code})]
        (let [cart (entities.user/get-cart user)]
          (entity/update* (assoc cart :discount (:db/id discount)))
          (entity/find-json (:db/id cart) {:culture (get-culture session)}))
        (throw (Exception. "No discount found with the given code"))))))

(register-endpoint!
  :users.favorites.list
  (fn [{:keys [params]} {:keys [session]}]
    (let [user (get-user session)]
      (:user/favorites user))))

(defn- toggle-favorite [session product-id f]
  (when-let [user (get-user session)]
    (let [favorites (f (set (:user/favorites user)) product-id)]
      (entity/update* {:db/id (:db/id user)
                       :user/favorites favorites})
      favorites)))

(register-endpoint!
  :users.favorites.add
  (fn [{{:keys [id]} :params} {:keys [session]}]
    (toggle-favorite session id conj)))

(register-endpoint!
  :users.favorites.remove
  (fn [{{:keys [id]} :params} {:keys [session]}]
    (toggle-favorite session id disj)))

(register-endpoint!
  :search
  (fn [{{:keys [search]} :params} {:keys [session]}]
    (let [culture (get-culture session)
          {culture-kw :i18n.culture/keyword} (entity/find culture)
          shoulds (for [attr [:product/name
                              :category/name
                              :brand/name]]
                    {:match {(keyword (namespace attr)
                                      (str (name attr) "__" (name culture-kw)))
                             search}})
          hits (-> (search/search {:query {:bool {:should shoulds}}
                                   :_source false})
                   (get-in [:body :hits :hits]))]
      (->> hits
           (map :_id)
           (map (fn [v] (Long/parseLong v)))
           (map #(entity/find-json % {:culture culture
                                      :keep-type? true}))
           (map (fn [{:keys [images] :as result}]
                  (let [result (if images
                                 (assoc result :image (first images))
                                 result)]
                    (select-keys result [:id :type :name :images :image]))))))))

(register-endpoint!
  :upload
  {:binary? true}
  (fn [{:keys [params]} state]
    (let [{:keys [bytes is-first is-last file-id]} params
          file-id (if is-first (gensym "temp-file") file-id)
          path (str (paths/resolve paths/storage) "/" file-id)]
      (with-open [r (bytes/to-input-stream bytes)
                  w (-> (clojure.java.io/file path)
                        (clojure.java.io/output-stream :append (not is-first)))]
        (clojure.java.io/copy r w))
      (cond
        is-last (entities.file/create-from-file! path)
        is-first file-id
        :default true))))