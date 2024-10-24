(defproject io.github.algoflora/himmelsstuermer "0.1.0"
  :description "Himmelsstuermer - A framework for complex Telegram Bots development"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[aero/aero "1.1.6"]
                 [cheshire "5.13.0"]
                 [com.hyperfiddle/rcf "20220926-202227"]
                 [com.taoensso/telemere "1.0.0-beta25"]
                 [datalevin "0.9.12"]
                 [http-kit/http-kit "2.8.0"]
                 [me.raynes/fs "1.4.6"]
                 [metosin/malli "0.16.3"]
                 [missionary "b.40"]
                 [org.clojure/clojure "1.12.0"]
                 [resauce "0.2.0"]
                 [tick/tick "1.0"]]

  :plugins [[jonase/eastwood "1.4.3"]]

  :clj-kondo {:config "./clj-kondo-config.edn"}

  :source-paths ["src"]
  :resource-paths ["resources"]
  :main ^:skip-aot himmelsstuermer.core

  :target-path "target/%s"

  :jvm-opts ["--add-opens=java.base/java.nio=ALL-UNNAMED"
             "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"]
  :profiles {:dev {:dependencies [[lambdaisland/kaocha "1.91.1392"]]
                   :source-paths ["src"]
                   :resource-paths ["resources" "test/resources"]
                   :jvm-opts ["-Dhimmelsstuermer.malli.instrument=true"
                              "-Dhimmelsstuermer.profile=test"
                              "--add-opens=java.base/java.nio=ALL-UNNAMED"
                              "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"]}
             :uberjar {:aot [himmelsstuermer.core]
                       :uberjar-name "himmelsstuermer.jar"
                       :uberjar-exclusions ["himmelsstuermer.aws.*"]
                       :env {:DTLV_COMPILE_NATIVE "true"
                             :USE_NATIVE_IMAGE_JAVA_PLATFORM_MODULE_SYSTEM "false"}
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}}
  :aliases {"test"   ["with-profile" "dev" "run" "-m" "kaocha.runner" "--fail-fast"]})
