(ns storrito.cli.version
  "The version of the Storrito CLI. `bin/build-cli` bumps it for a
   release.")

(def version
  "0.1.1")

(defn info
  "The version and the platform, as `storrito version` prints it."
  []
  {:version version
   :babashka (System/getProperty "babashka.version")
   :os (System/getProperty "os.name")
   :arch (System/getProperty "os.arch")})
