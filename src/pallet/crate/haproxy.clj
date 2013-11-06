(ns pallet.crate.haproxy
  "HA Proxy installation and configuration"
  (:require
   [clojure.set :refer [difference]]
   [clojure.string :as string]
   [clojure.tools.logging :refer [debugf warnf]]
   [pallet.actions :refer [remote-file]]
   [pallet.api :as api :refer [plan-fn]]
   [pallet.compute :as compute]
   [pallet.crate
    :refer [assoc-settings defplan get-node-settings get-settings
            group-name target-node target targets update-settings
            service-phases]]
   [pallet.crate-install :as crate-install]
   [pallet.crate.etc-default :as etc-default]
   [pallet.crate.initd :as initd]
   [pallet.crate.service :as service]
   [pallet.node :refer [hostname id primary-ip private-ip]]
   [pallet.utils :refer [apply-map base64-md5 deep-merge]]
   [pallet.version-dispatch
    :refer [defmethod-version-plan defmulti-version-plan]]))

;;; # Settings

(def config-changed-flag "HAPROXY-CONFIG-CHANGED")
(def facility :haproxy)

(def default-global
  {:log ["127.0.0.1 local0" "127.0.0.1 local1 notice"]
   :maxconn 4096
   :user "haproxy"
   :group "haproxy"
   :daemon true})

(def default-defaults
  {:log "global"
   :mode "http"
   :option ["httplog" "dontlognull" "redispatch"]
   :retries 3
   :maxconn 2000
   :contimeout 5000
   :clitimeout 50000
   :srvtimeout 50000})

(defmulti-version-plan default-settings [version])

;; By default, install from system packages
(defmethod-version-plan default-settings {:os :linux}
  [os os-version version]
  {:config {:global  default-global
            :defaults default-defaults}
   :conf-file "/etc/haproxy/haproxy.cfg"
   :user "haproxy"
   :group "haproxy"
   :service-name "haproxy"
   :supervisor :initd
   :install-strategy :packages
   :packages ["haproxy"]
   :package-options {:disable-service-start true}})

(defplan settings
  "Build the configuration settings by merging the user supplied ones
  with the OS-related install settings and the default config settings
  for HAProxy.

  Configuration is via the `:config` map.  `:global` and `:defaults`
  both take maps of keyword value pairs.  `:listen` takes a map where
  the keys are of the form \"name\" and contain a `:server-address`
  key with a string containing \"ip:port\", and other
  keyword/value. Servers for each listen section can be declared with
  the proxied-by function."
  [{:keys [instance-id version config] :as settings}]
  (let [settings (deep-merge
                  {:version (or version :latest)
                   :proxy-group (keyword (group-name))}
                  (default-settings :latest)
                  (dissoc settings :instance-id))]
    (debugf "haproxy settings %s" settings)
    (assoc-settings facility settings {:instance-id instance-id})))

;;; # Install

(defplan install
  [{:keys [instance-id]}]
  (crate-install/install facility instance-id))

;;; # Configure
(defmulti format-kv (fn format-kv-dispatch [k v & _] (class v)))

(defmethod format-kv :default
  [k v sep]
  (format "%s %s%s" (name k) v sep))

(defmethod format-kv clojure.lang.IPersistentVector
  [k v sep]
  (reduce (fn format-kv-vector [s value] (str s (format-kv k value sep))) "" v))

(defmethod format-kv clojure.lang.Sequential
  [k v sep]
  (reduce (fn format-kv-vector [s value] (str s (format-kv k value sep))) "" v))

(defmethod format-kv Boolean
  [k v sep]
  (when v (format "%s%s" (name k) sep)))

(defn- config-values
  "Format a map as key value pairs"
  [m]
  (apply str (for [[k v] m] (format-kv k v \newline))))

(defn- config-section
  [[key values]]
  (if (#{:frontend :backend :listen} key)
    (reduce
     #(str
       %1
       (format
        "%s %s %s\n%s%s\n"
        (name key) (name (first %2)) (or (:server-address (second %2)) "")
        (config-values (select-keys (second %2) [:acl]))
        (config-values (dissoc (second %2) :server-address :acl))))
     ""
     values)
    (format "%s\n%s\n" (name key) (config-values values))))

(defn- config-server
  "Format a server configuration line"
  [{:keys [ip server-port] :as config}]
  {:pre [(:name config) ip]}
  (format
   "%s %s%s %s"
   (name (:name config))
   ip
   (if server-port (str ":" server-port) "")
   (apply
    str
    (for [[k v] (dissoc config :server-port :ip :name)]
      (format-kv k v " ")))))

(defn- config-backend-server
  "Format a backend server configuration line"
  [{:keys [ip server-port] :as config}]
  {:pre [(:name config) ip]}
  (format
   "%s %s%s %s"
   (name (:name config))
   ip
   (if server-port (str ":" server-port) "")
   (apply
    str
    (for [[k v] (dissoc config :server-port :ip :name)]
      (format-kv k v " ")))))

(defn proxied-map
  "Build a map from app to sequence of proxied configuration for the given
  haproxy group."
  [haproxy-group]
  (reduce
   (fn [m target]
     (debugf "proxied-map %s %s"
             (get-node-settings (:node target) facility)
             haproxy-group)
     (if-let [proxied (-> (get-node-settings (:node target) facility)
                            haproxy-group)]
       (reduce
        (fn [m [app config]]
          (update-in m [app] (fnil conj []) config))
        m proxied)
       m))
   {} (targets)))

(defn server-listen
  "Build a map for the listen configuration of haproxy"
  [group-name listen proxied-config]
  {:pre [(keyword? group-name)
         (or (nil? listen) (map? listen))
         (map? proxied-config)]}
  (let [apps (map keyword (keys listen))
        listen (zipmap apps (vals listen))
        app-keys (keys proxied-config)
        unconfigured (difference (set app-keys) (set apps))
        no-nodes (difference (set app-keys) (set apps))]
    (doseq [app unconfigured]
      (warnf "Unconfigured proxy %s %s" group-name app))
    (doseq [app no-nodes]
      (warnf "Configured proxy %s %s with no servers" group-name app))
    (reduce
     (fn [listen [app app-servers]]
       (if (get listen app)
         (update-in listen [app :server]
                    (fn [servers]
                      (concat servers (map config-server app-servers))))
         listen))
     listen
     proxied-config)))

(defn server-backend
  "Build a map for the backend configuration of haproxy"
  [group-name backend proxied-config]
  {:pre [(keyword? group-name)
         (or (nil? backend) (map? backend))
         (map? proxied-config)]}
  (let [apps (map keyword (keys backend))
        backend (zipmap apps (vals backend))
        app-keys (keys proxied-config)
        unconfigured (difference (set app-keys) (set apps))
        no-nodes (difference (set app-keys) (set apps))]
    (doseq [app unconfigured]
      (warnf "Unconfigured proxy %s %s" group-name app))
    (doseq [app no-nodes]
      (warnf "Configured proxy %s %s with no servers" group-name app))
    (reduce
     (fn [backend [app app-servers]]
       (if (get backend app)
         (update-in backend [app :server]
                    (fn [servers]
                      (concat servers (map config-server app-servers))))
         backend))
     backend
     proxied-config)))

(defn config-file
  "Returns a string containing the configuration file contents."
  [group-name config]
  (let [config (-> config
                   (update-in
                    [:listen]
                    #(server-listen group-name % (proxied-map group-name)))
                   (update-in
                    [:backend]
                    #(server-backend
                      group-name % (proxied-map group-name))))]
    (debugf "proxied-map %s" (proxied-map group-name))
    (debugf "config %s" config)
    (->>
     (filter config [:global :defaults :listen :frontend :backend])
     (map (juxt identity config))
     (map config-section)
     string/join)))

(defplan configure
  "Configure HAProxy."
  [{:keys [instance-id] :as options}]
  (let [{:keys [conf-file config group proxy-group user]}
        (get-settings facility {:instance-id instance-id})]
    (remote-file
     conf-file
     :content (config-file proxy-group config)
     :literal true :owner user :group group
     :flag-on-changed config-changed-flag)
    (etc-default/write "haproxy" :ENABLED 1)))

(defn target-name [{:keys [node]}]
  ((some-fn hostname (comp base64-md5 id)) node))

(defn target-ip [{:keys [node]}]
  ((some-fn private-ip primary-ip) node))

(defn target-config-map
  [target config]
  (merge
   {:name (target-name target)
    :ip (target-ip target)}
   config))

(defn proxied-by
  "Declare that a node is proxied by the given haproxy server.  This
  should be called in a phase that runs before :configure (such as
  :settings).

      (proxied-by :haproxy :app1 {:check true})."
  [proxy-group-name app-group
   {:keys [server-port addr backup check cookie disabled fall id
           inter fastinter downinter maxqueue minconn port redir
           rise slowstart source track weight]
    :as config}
   & {:keys [instance-id] :as options}]
  {:pre [(keyword? app-group)]}
  (update-settings
   facility options assoc-in [(keyword proxy-group-name) app-group]
   (target-config-map (target) config)))

(defplan service
  [& {:keys [instance-id] :as options}]
  (let [{:keys [supervision-options] :as settings}
        (get-settings facility {:instance-id instance-id})]
    (service/service settings (merge supervision-options
                                     (dissoc options :instance-id)))))

(defplan ensure-service
  [& {:keys [instance-id] :as options}]
  (service :instance-id instance-id :if-flag config-changed-flag)
  (service :instance-id instance-id :if-stopped true))

(defn server-spec
  [{:keys [instance-id] :as settings}]
  (let [options (select-keys settings [:instance-id])]
    (api/server-spec
     :phases (merge
              {:settings (plan-fn
                          (pallet.crate.haproxy/settings settings))
               :install (plan-fn (install options))
               :configure (plan-fn (configure options))
               :ensure-service (plan-fn (apply-map ensure-service options))}
              (service-phases facility options service))
     :default-phases [:install :configure :ensure-service]
     :roles (when-let [proxy-group (:proxy-group settings)]
              #{proxy-group}))))
