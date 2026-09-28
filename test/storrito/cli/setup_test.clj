(ns storrito.cli.setup-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [storrito.cli.config :as config]
            [storrito.cli.fake :as fake]
            [storrito.cli.output :as output]
            [storrito.cli.setup :as setup]
            [storrito.cli.version :as version]))

(defmacro with-claude-dir
  "A temporary Claude Code config dir, via CLAUDE_CONFIG_DIR."
  [& body]
  `(let [dir# (fs/create-temp-dir {:prefix "claude-config"})]
     (try
       (fake/with-config-dir {"CLAUDE_CONFIG_DIR" (str dir#)}
         ~@body)
       (finally
         (fs/delete-tree dir#)))))

(deftest the-skill-has-frontmatter-and-the-essentials
  (let [md (setup/skill-markdown)]
    (is (str/starts-with? md "---\nname: storrito\ndescription: "))
    (is (str/includes? md "storrito commands"))
    (is (str/includes? md "schedule-instagram-story"))
    (is (str/includes? md "exit code 3"))))

(deftest setup-claude-installs-the-skill-with-a-marker
  (with-claude-dir
    (let [result (setup/setup {:positional ["claude"] :flags {"skill-only" true}} {})]
      (is (= "claude" (:agent result)))
      (is (fs/exists? (setup/skill-file)))
      (is (= (setup/skill-markdown) (slurp (fs/file (setup/skill-file)))))
      (is (= version/version (setup/managed-version)))
      (is (false? (:loggedIn result)))
      (is (str/includes? (:next result) "storrito login"))
      (is (str/ends-with? (:skill result) "/skills/storrito/SKILL.md")))))

(deftest setup-claude-logs-in-when-needed-and-reports-existing-logins
  (with-claude-dir
    (let [calls (atom 0)
          result (setup/setup {:positional ["claude"] :flags {}}
                              {:login-fn (fn [_] (swap! calls inc) {:loggedIn true :org fake/org-uuid})})]
      (is (= 1 @calls) "no login stored: the login runs")
      (is (true? (:loggedIn result)))
      (is (= fake/org-uuid (get-in result [:login :org]))))
    (config/write-credentials! (config/put-org-entry (config/read-credentials)
                                                     fake/org-uuid
                                                     {:kind "api-credential" :token "id:secret"}))
    (let [calls (atom 0)
          result (setup/setup {:positional ["claude"] :flags {}}
                              {:login-fn (fn [_] (swap! calls inc))})]
      (is (= 0 @calls) "a stored login is enough")
      (is (true? (:loggedIn result)))
      (is (nil? (:login result))))))

(deftest a-foreign-skill-is-kept-unless-forced
  (with-claude-dir
    (fs/create-dirs (setup/skill-dir))
    (spit (fs/file (setup/skill-file)) "---\nname: storrito\n---\nsomebody else's")
    (let [e (try (setup/setup {:positional ["claude"] :flags {"skill-only" true}} {}) (catch Exception e e))]
      (is (= :usage (:exit (output/error-data e))))
      (is (= "---\nname: storrito\n---\nsomebody else's" (slurp (fs/file (setup/skill-file))))))
    (setup/setup {:positional ["claude"] :flags {"skill-only" true "force" true}} {})
    (is (= (setup/skill-markdown) (slurp (fs/file (setup/skill-file)))))
    (is (= version/version (setup/managed-version)))))

(deftest a-managed-skill-of-another-version-is-refreshed-at-start
  (with-claude-dir
    (setup/refresh-managed-skill!)
    (is (not (fs/exists? (setup/skill-file))) "nothing installed, nothing written")
    (setup/setup {:positional ["claude"] :flags {"skill-only" true}} {})
    (spit (fs/file (setup/skill-file)) "stale content")
    (spit (fs/file (setup/marker-file)) "0.0.1\n")
    (setup/refresh-managed-skill!)
    (is (= (setup/skill-markdown) (slurp (fs/file (setup/skill-file)))))
    (is (= version/version (setup/managed-version)))))

(deftest print-and-usage
  (with-claude-dir
    (is (= (setup/skill-markdown)
           (setup/setup {:positional ["claude"] :flags {"print" true}} {})))
    (is (not (fs/exists? (setup/skill-file))) "--print writes nothing")
    (let [e (try (setup/setup {:positional []} {}) (catch Exception e e))]
      (is (= :usage (:exit (output/error-data e)))))))
