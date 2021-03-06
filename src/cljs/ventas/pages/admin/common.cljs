(ns ventas.pages.admin.common
  (:require
   [ventas.components.form :as form]
   [re-frame.core :as rf]
   [ventas.events :as events]
   [ventas.events.backend :as backend]
   [clojure.set :as set]))

(def state-key ::state)

(rf/reg-event-fx
 ::search
 (fn [_ [_ key attrs search]]
   {:dispatch [::backend/admin.search
               {:params {:search search
                         :attrs attrs}
                :success [::events/db [state-key :search-results key]]}]}))

(defn entity->option [entity]
  (-> entity
      (select-keys #{:id :name})
      (set/rename-keys {:id :value
                        :name :text})))

(defn entity-search-field [{:keys [db-path label key attrs selected-option]}]
  [form/field {:db-path db-path
               :label label
               :key key
               :type :entity
               :on-search-change #(rf/dispatch [::search key attrs (-> % .-target .-value)])
               :options (->> @(rf/subscribe [::events/db [state-key :search-results key]])
                             (map entity->option)
                             (into (if selected-option
                                     [(entity->option selected-option)]
                                     [])))}])