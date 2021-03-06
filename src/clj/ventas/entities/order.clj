(ns ventas.entities.order
  (:require
   [ventas.database :as db]
   [clojure.spec.alpha :as spec]
   [ventas.database.entity :as entity]
   [ventas.database.generators :as generators]
   [ventas.entities.product :as entities.product]
   [clojure.test.check.generators :as gen]))

(defn- get-amount [{:order/keys [lines]}]
  (->> lines
       (map entity/find)
       (map (fn [{:order.line/keys [product-variation quantity]}]
              (let [{:product/keys [price]} (entities.product/normalize-variation product-variation)
                    _ (assert price ::variation-has-no-price)
                    {:amount/keys [value]} (entity/find price)]
                (* value quantity))))
       (reduce +)))

(spec/def :order/user
  (spec/with-gen ::entity/ref #(entity/ref-generator :user)))

(def statuses
  #{:order.status/unpaid
    :order.status/paid
    :order.status/acknowledged
    :order.status/ready
    :order.status/shipped
    :order.status/draft})

(spec/def :order/status
  (spec/with-gen
   (spec/or :pull-eid ::db/pull-eid
            :kind statuses)
   #(gen/elements statuses)))

(spec/def :order/shipping-address
  (spec/with-gen ::entity/ref #(entity/ref-generator :address)))

(spec/def :order/billing-address
  (spec/with-gen ::entity/ref #(entity/ref-generator :address)))

(spec/def :order/shipping-method ::generators/keyword)

(spec/def :order/shipping-comments ::generators/string)

(spec/def :order/payment-method ::generators/keyword)

(spec/def :order/payment-reference ::generators/string)

(spec/def :order/payment-amount
  (spec/with-gen ::entity/ref #(entity/ref-generator :amount)))

(spec/def :order/lines
  (spec/with-gen ::entity/refs #(entity/refs-generator :order.line)))

(spec/def :schema.type/order
  (spec/keys :req [:order/user
                   :order/status]
             :opt [:order/shipping-comments
                   :order/payment-reference
                   :order/shipping-address
                   :order/billing-address
                   :order/shipping-method
                   :order/payment-method
                   :order/lines]))

(entity/register-type!
 :order
 {:attributes
  (concat
   [{:db/ident :order/user
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/one}

    {:db/ident :order/status
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/one}

    {:db/ident :order/shipping-address
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/one}

    {:db/ident :order/billing-address
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/one}

    {:db/ident :order/shipping-method
     :db/valueType :db.type/keyword
     :db/cardinality :db.cardinality/one}

    {:db/ident :order/payment-method
     :db/valueType :db.type/keyword
     :db/cardinality :db.cardinality/one}

    {:db/ident :order/shipping-comments
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}

    {:db/ident :order/lines
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/many
     :db/isComponent true}

    {:db/ident :order/payment-reference
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one}

    {:db/ident :order/payment-amount
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/one
     :db/isComponent true}]

   (map #(hash-map :db/ident %) statuses))

  :dependencies
  #{:address :user :amount}

  :to-json
  (fn [this params]
    (-> ((entity/default-attr :to-json) this params)
        (assoc :amount (get-amount this))))

  :from-json
  (fn [this]
    (-> this
        (dissoc :amount)
        ((entity/default-attr :from-json))))})

(spec/def :order.line/product-variation
  (spec/with-gen ::entity/ref #(entity/ref-generator :product.variation)))

(spec/def :order.line/quantity (spec/and integer? pos?))

(spec/def :schema.type/order.line
  (spec/keys :req [:order.line/product-variation
                   :order.line/quantity]))

(entity/register-type!
 :order.line
 {:attributes
  [{:db/ident :order.line/product-variation
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}

   {:db/ident :order.line/quantity
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}

   {:db/ident :order.line/discount
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}]

  :autoresolve? true

  :dependencies
  #{:order :product :product.variation :discount}})
