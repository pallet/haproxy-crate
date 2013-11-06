(ns pallet.crate.haproxy-test
  (:require
   [clojure.test :refer :all]
   [pallet.api :refer [group-spec plan-fn server-spec]]
   [pallet.compute :as compute]
   [pallet.build-actions :refer [build-actions build-session]]
   [pallet.actions :refer [remote-file]]
   [pallet.core.session :refer [with-session session]]
   [pallet.crate.etc-default :as etc-default]
   [pallet.crate.haproxy :as haproxy]
   [pallet.crate.network-service :refer [wait-for-port-listen]]
   [pallet.node :refer [id]]
   [pallet.test-utils :as test-utils]
   [pallet.utils :refer [base64-md5]]))

(deftest format-k-v-test
  (is (= "a b "
         (#'haproxy/format-kv :a "b" " ")))
  (is (= "a b\n"
         (#'haproxy/format-kv :a "b" \newline)))
  (is (= "a b\na c\n"
         (#'haproxy/format-kv :a ["b" "c"] \newline))))

(deftest config-server-test
  (is (= "tag 1.2.3.4:80 fall 3 check "
         (#'haproxy/config-server
          {:name :tag :ip "1.2.3.4" :server-port 80 :fall 3 :check true}))))

(deftest proxied-by-test
  (let [node (test-utils/make-node "tag" :public-ips ["1.2.3.4"])]
    (is (= {:host
            {"tag-1-2-3-4"
             {:haproxy {nil {:tag1 {:app1 {:name "tag", :ip "1.2.3.4"}}}}}}}
           (with-session
             (build-session {:server (assoc (group-spec :tag) :node node)})
             (haproxy/proxied-by :tag1 :app1 {})
             (select-keys (:plan-state (session)) [:host]))))))

(deftest server-listen-test
  (let [node (test-utils/make-node "tag" :public-ips ["1.2.3.4"])]
    (is (= {:app1 {:server ["tag 1.2.3.4 check "]
                   :balance "round-robin"
                   :server-address "0.0.0.0:80"}}
           (#'haproxy/server-listen
            :tag1
            {:app1 {:server-address "0.0.0.0:80" :balance "round-robin"}}
            {:app1 [{:name "tag", :ip "1.2.3.4" :check true}]})))
    (is (= {:app1 {:balance "round-robin"}}
           (#'haproxy/server-listen
            :tag1
            {:app1 {:balance "round-robin"}}
            {})))))

(deftest config-section-test
  (is (= (str
          "listen app1 0.0.0.0:80\n"
          "server tag 1.2.3.4 name tag \n"
          "balance round-robin\n\n")
         (#'pallet.crate.haproxy/config-section
          [:listen {:app1 {:server ["tag 1.2.3.4 name tag "]
                           :server-address "0.0.0.0:80"
                           :balance "round-robin"}}])))
  (is (= (str
          "listen app1 0.0.0.0:80\n"
          "server tag 1.2.3.4 name tag \n"
          "balance round-robin\n\n")
         (apply str
                (map
                 #'pallet.crate.haproxy/config-section
                 {:listen {:app1 {:server ["tag 1.2.3.4 name tag "]
                                  :server-address "0.0.0.0:80"
                                  :balance "round-robin"}}})))))

(deftest configure-test
  (is (=
       (first
        (build-actions
         {:server {:image {:os-family :ubuntu}
                   :group-name :tag
                   :node (test-utils/make-node
                          "tag" :public-ips ["1.2.3.4"])}
          :phase-context "configure"}
         (remote-file
          "/etc/haproxy/haproxy.cfg"
          :content "global\ngroup haproxy\nmaxconn 4096\nlog 127.0.0.1 local0\nlog 127.0.0.1 local1 notice\ndaemon\nuser haproxy\n\ndefaults\nclitimeout 50000\nretries 3\nmaxconn 2000\nlog global\nsrvtimeout 50000\ncontimeout 5000\nmode http\noption httplog\noption dontlognull\noption redispatch\n\nlisten app 0.0.0.0:80\nserver h1 1.2.3.4:80 weight 1 maxconn 50 check\nserver h2 1.2.3.5:80 weight 1 maxconn 50 check\n\n"
          :literal true
          :owner "haproxy"
          :group "haproxy"
          :flag-on-changed haproxy/config-changed-flag)
         (etc-default/write "haproxy" :ENABLED 1)))
       (first
        (build-actions
         {:server {:image {:os-family :ubuntu}
                   :group-name :tag
                   :node (test-utils/make-node
                          "tag" :public-ips ["1.2.3.4"])}}
         (haproxy/settings
          {:config
           {:listen {:app
                     {:server-address "0.0.0.0:80"
                      :server ["h1 1.2.3.4:80 weight 1 maxconn 50 check"
                               "h2 1.2.3.5:80 weight 1 maxconn 50 check"]}}
            :defaults {:mode "http"}}})
         (haproxy/configure {}))))))

(deftest invocation-test
  (let [node (test-utils/make-node "tag" :ip "1.2.3.4")]
    (is (build-actions
         {:server {:image {:os-family :ubuntu}
                   :group-name :tag :node node
                   :node-id (keyword (id node))}}
         (haproxy/settings
          {:config
           {:listen {:app
                     {:server-address "0.0.0.0:80"
                      :server ["h1 1.2.3.4:80 weight 1 maxconn 50 check"
                               "h2 1.2.3.5:80 weight 1 maxconn 50 check"]}}}})
         (haproxy/install {})
         (haproxy/proxied-by :tag :app {})
         (haproxy/configure {})))))

(def test-spec
  (server-spec
   :extends [(haproxy/server-spec
              {:config {:listen {:app {:server-address "0.0.0.0:8080"}}}})]
   :phases {:verify (plan-fn (wait-for-port-listen 8080))}
   :default-phases [:install :configure :ensure-service :verify]))
