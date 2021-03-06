(ns ventas.components.product-list
  (:require
   [re-frame.core :as rf]
   [ventas.components.base :as base]
   [ventas.components.cart :as cart]
   [ventas.components.image :as image]
   [ventas.events :as events]
   [ventas.routes :as routes]
   [ventas.utils.ui :as utils.ui]
   [ventas.utils.formatting :as utils.formatting]))

(defn product-list [products]
  [:div.product-list
   [base/grid
    (doall
     (for [{:keys [id images price name slug]} products]
       (do (assert id)
           [base/grid-column {:key id
                              :mobile 8
                              :tablet 4
                              :computer 4}
            [:div.product-list__product
             [:div.product-list__images-wrapper
              {:class (when (empty? images) "product-list__images-wrapper--no-image")}
              (when (seq images)
                [:a {:href (routes/path-for :frontend.product :id slug)}
                 [image/image (:id (first images)) :product-listing]])
              [:div.product-list__actions
               [base/icon {:name (if @(rf/subscribe [::events/users.favorites.favorited? id])
                                   "heart"
                                   "empty heart")
                           :on-click (utils.ui/with-handler
                                       #(rf/dispatch [::events/users.favorites.toggle id]))}]
               [base/icon {:name "shopping bag"
                           :on-click (utils.ui/with-handler
                                       #(rf/dispatch [::cart/add id]))}]]]
             [:a.product-list__content {:href (routes/path-for :frontend.product :id slug)}
              [:span.product-list__name
               name]
              [:div.product-list__price
               [:span (utils.formatting/amount->str price)]]]]])))]])
