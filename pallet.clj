;;; Pallet project configuration file

(require
 '[pallet.crate.haproxy-test :refer [test-spec]]
 '[pallet.crates.test-nodes :refer [node-specs]])

(defproject haproxy-crate
  :provider node-specs                  ; supported pallet nodes
  :groups [(group-spec "haproxy-live-test"
             :extends [with-automated-admin-user
                       test-spec]
             :roles #{:live-test :default})])
