(ns ventas.themes.clothing.pages.frontend.profile
  (:require
   [ventas.components.base :as base]
   [ventas.i18n :refer [i18n]]
   [ventas.routes :as routes]
   [ventas.themes.clothing.components.skeleton :refer [skeleton]]
   [ventas.themes.clothing.pages.frontend.profile.account]
   [ventas.themes.clothing.pages.frontend.profile.addresses]
   [ventas.themes.clothing.pages.frontend.profile.orders]
   [ventas.themes.clothing.pages.frontend.profile.skeleton :as profile.skeleton]
   [ventas.session :as session]))

(defn content []
  (let [{:keys [first-name]} (session/get-identity)]
    [:div.profile-page

     [:h2.profile-page__name (str (i18n ::welcome first-name) "!")]

     [:a.profile-page__segment {:href (routes/path-for :frontend.profile.orders)}
      [base/segment
       [:h4 (i18n ::my-orders)]
       [:p (i18n ::my-orders-explanation)]]]

     [:a.profile-page__segment {:href (routes/path-for :frontend.profile.addresses)}
      [base/segment
       [:h4 (i18n ::my-addresses)]
       [:p (i18n ::my-addresses-explanation)]]]

     [:a.profile-page__segment {:href (routes/path-for :frontend.profile.account)}
      [base/segment
       [:h4 (i18n ::my-account)]
       [:p (i18n ::personal-data-explanation)]]]]))

(defn page []
  [profile.skeleton/skeleton
   [content]])

(routes/define-route!
  :frontend.profile
  {:name ::page
   :url ["profile"]
   :component page})
