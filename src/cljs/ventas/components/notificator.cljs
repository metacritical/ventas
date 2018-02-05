(ns ventas.components.notificator
  (:require
   [cljs.core.async :refer [<! timeout]]
   [re-frame.core :as rf]
   [ventas.components.base :as base]
   [ventas.events.backend :as backend]
   [ventas.events :as events])
  (:require-macros
   [cljs.core.async.macros :refer [go]]))

(rf/reg-event-db
 ::add
 (fn [db [_ notification]]
   (let [sym (gensym)
         notification (assoc notification :sym sym)]
     (go
      (<! (timeout (or (:timeout notification) 4000)))
      (rf/dispatch [::remove sym]))
     (if (seq (:notifications db))
       (update db :notifications #(conj % notification))
       (assoc db :notifications [notification])))))

(rf/reg-event-db
 ::remove
 (fn [db [_ sym]]
   (update db :notifications #(remove (fn [item] (= (:sym item) sym)) %))))

(defn notificator
  "Displays notifications"
  []
  [:div.notificator
   (let [notifications @(rf/subscribe [::events/db [:notifications]])]
     (for [{:keys [theme message icon sym component]} notifications]
       [:div.notificator__item {:key (gensym) :class theme}
        (if component
          component
          [:div
           [base/icon {:class "bu close"
                       :name icon
                       :on-click #(rf/dispatch [::remove sym])}]
           [:p.bu.message message]])]))])
