(defproject com.palletops/haproxy-crate "0.8.0-SNAPSHOT"
  :description "Crate for haproxy installation"
  :url "http://github.com/pallet/haproxy-crate"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [com.palletops/pallet "0.8.0-SNAPSHOT"]]
  :resource {:resource-paths ["doc-src"]
             :target-path "target/classes/pallet_crate/haproxy_crate/"
             :includes [#"doc-src/USAGE.*"]}
  :prep-tasks ["resource" "crate-doc"])
