(ns storrito.cli.setup-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [storrito.cli.config :as config]
            [storrito.cli.fake :as fake]
            [storrito.cli.output :as output]
            [storrito.cli.setup :as setup]
            [storrito.cli.version :as version]))

(defmacro with-skill-dirs
  "Temporary skill directories for every agent: a Claude Code config dir
   via CLAUDE_CONFIG_DIR and a shared Agent Skills dir via
   STORRITO_AGENTS_SKILLS_DIR."
  [& body]
  `(let [claude-dir# (fs/create-temp-dir {:prefix "claude-config"})
         agents-dir# (fs/create-temp-dir {:prefix "agents-skills"})]
     (try
       (fake/with-config-dir {"CLAUDE_CONFIG_DIR" (str claude-dir#)
                              "STORRITO_AGENTS_SKILLS_DIR" (str agents-dir#)}
         ~@body)
       (finally
         (fs/delete-tree claude-dir#)
         (fs/delete-tree agents-dir#)))))

(deftest the-skill-has-frontmatter-and-the-essentials
  (let [md (setup/skill-markdown)]
    (is (str/starts-with? md "---\nname: storrito\ndescription: "))
    (is (str/includes? md "storrito commands"))
    (is (str/includes? md "schedule-instagram-story"))
    (is (str/includes? md "exit code 3"))))

(deftest setup-claude-installs-the-skill-with-a-marker
  (with-skill-dirs
    (let [result (setup/setup {:positional ["claude"] :flags {"skill-only" true}} {})]
      (is (= "claude" (:agent result)))
      (is (fs/exists? (setup/skill-file setup/claude)))
      (is (= (setup/skill-markdown) (slurp (fs/file (setup/skill-file setup/claude)))))
      (is (= version/version (setup/managed-version setup/claude)))
      (is (false? (:loggedIn result)))
      (is (str/includes? (:next result) "storrito login"))
      (is (str/includes? (:next result) "Claude Code"))
      (is (= (str (setup/skill-file setup/claude)) (:skill result))
          "the path as the OS spells it, backslashes on Windows")
      (is (not (fs/exists? (setup/skill-file setup/codex))) "only Claude Code"))))

(deftest setup-codex-installs-the-skill-into-the-shared-agents-dir
  (with-skill-dirs
    (let [result (setup/setup {:positional ["codex"] :flags {"skill-only" true}} {})]
      (is (= "codex" (:agent result)))
      (is (= (str (fs/path (config/env "STORRITO_AGENTS_SKILLS_DIR") "storrito" "SKILL.md"))
             (:skill result))
          "~/.agents/skills/storrito/SKILL.md, not ~/.codex/skills")
      (is (= (setup/skill-markdown) (slurp (fs/file (setup/skill-file setup/codex)))))
      (is (= version/version (setup/managed-version setup/codex)))
      (is (false? (:loggedIn result)))
      (is (str/includes? (:next result) "ask Codex"))
      (is (not (fs/exists? (setup/skill-file setup/claude))) "only Codex"))))

(deftest setup-claude-logs-in-when-needed-and-reports-existing-logins
  (with-skill-dirs
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

(deftest setup-agents-installs-every-agent-with-one-login
  (with-skill-dirs
    (let [calls (atom 0)
          result (setup/setup {:positional ["agents"] :flags {}}
                              {:login-fn (fn [_] (swap! calls inc) {:loggedIn true :org fake/org-uuid})})]
      (is (= 1 @calls) "one login for all agents")
      (is (= [{:agent "claude" :skill (str (setup/skill-file setup/claude))}
              {:agent "codex" :skill (str (setup/skill-file setup/codex))}]
             (:agents result)))
      (is (nil? (:agent result)) "the single-agent keys are not in the agents result")
      (is (true? (:loggedIn result)))
      (is (= fake/org-uuid (get-in result [:login :org])))
      (is (str/includes? (:next result) "Ask Claude Code or Codex"))
      (doseq [agent setup/agents]
        (is (= (setup/skill-markdown) (slurp (fs/file (setup/skill-file agent)))))
        (is (= version/version (setup/managed-version agent)))))))

(deftest a-foreign-skill-is-kept-unless-forced
  (with-skill-dirs
    (fs/create-dirs (setup/skill-dir setup/claude))
    (spit (fs/file (setup/skill-file setup/claude)) "---\nname: storrito\n---\nsomebody else's")
    (let [e (try (setup/setup {:positional ["claude"] :flags {"skill-only" true}} {}) (catch Exception e e))]
      (is (= :usage (:exit (output/error-data e))))
      (is (str/includes? (:hint (output/error-data e)) "storrito setup claude --force"))
      (is (= "---\nname: storrito\n---\nsomebody else's" (slurp (fs/file (setup/skill-file setup/claude))))))
    (setup/setup {:positional ["claude"] :flags {"skill-only" true "force" true}} {})
    (is (= (setup/skill-markdown) (slurp (fs/file (setup/skill-file setup/claude)))))
    (is (= version/version (setup/managed-version setup/claude)))))

(deftest setup-agents-writes-nothing-when-one-skill-is-foreign
  (with-skill-dirs
    (fs/create-dirs (setup/skill-dir setup/codex))
    (spit (fs/file (setup/skill-file setup/codex)) "somebody else's")
    (let [e (try (setup/setup {:positional ["agents"] :flags {"skill-only" true}} {}) (catch Exception e e))]
      (is (= :usage (:exit (output/error-data e))))
      (is (str/includes? (:hint (output/error-data e)) "storrito setup codex --force"))
      (is (not (fs/exists? (setup/skill-file setup/claude))) "checked before anything is written")
      (is (= "somebody else's" (slurp (fs/file (setup/skill-file setup/codex))))))
    (let [result (setup/setup {:positional ["agents"] :flags {"skill-only" true "force" true}} {})]
      (is (= ["claude" "codex"] (map :agent (:agents result))))
      (is (= (setup/skill-markdown) (slurp (fs/file (setup/skill-file setup/codex))))))))

(deftest a-managed-skill-of-another-version-is-refreshed-at-start
  (with-skill-dirs
    (setup/refresh-managed-skill!)
    (is (not (fs/exists? (setup/skill-file setup/claude))) "nothing installed, nothing written")
    (is (not (fs/exists? (setup/skill-file setup/codex))))
    (setup/setup {:positional ["agents"] :flags {"skill-only" true}} {})
    (doseq [agent setup/agents]
      (spit (fs/file (setup/skill-file agent)) "stale content")
      (spit (fs/file (setup/marker-file agent)) "0.0.1\n"))
    (setup/refresh-managed-skill!)
    (doseq [agent setup/agents]
      (is (= (setup/skill-markdown) (slurp (fs/file (setup/skill-file agent))))
          (str "refreshed for " (:name agent)))
      (is (= version/version (setup/managed-version agent))))))

(deftest refresh-leaves-a-foreign-skill-alone-and-still-refreshes-the-others
  (with-skill-dirs
    (fs/create-dirs (setup/skill-dir setup/claude))
    (spit (fs/file (setup/skill-file setup/claude)) "somebody else's")
    (setup/setup {:positional ["codex"] :flags {"skill-only" true}} {})
    (spit (fs/file (setup/skill-file setup/codex)) "stale content")
    (spit (fs/file (setup/marker-file setup/codex)) "0.0.1\n")
    (setup/refresh-managed-skill!)
    (is (= "somebody else's" (slurp (fs/file (setup/skill-file setup/claude)))))
    (is (= (setup/skill-markdown) (slurp (fs/file (setup/skill-file setup/codex)))))))

(deftest print-and-usage
  (with-skill-dirs
    (is (= (setup/skill-markdown)
           (setup/setup {:positional ["claude"] :flags {"print" true}} {})))
    (is (= (setup/skill-markdown)
           (setup/setup {:positional ["agents"] :flags {"print" true}} {})))
    (doseq [agent setup/agents]
      (is (not (fs/exists? (setup/skill-file agent))) "--print writes nothing"))
    (let [e (try (setup/setup {:positional []} {}) (catch Exception e e))]
      (is (= :usage (:exit (output/error-data e))))
      (is (str/includes? (:error (output/error-data e)) "claude|codex|agents")))
    (let [e (try (setup/setup {:positional ["cursor"]} {}) (catch Exception e e))]
      (is (= :usage (:exit (output/error-data e)))))))
