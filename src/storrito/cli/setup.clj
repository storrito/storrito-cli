(ns storrito.cli.setup
  "`storrito setup claude`: connects the CLI to Claude Code by installing
   the Storrito skill (`resources/storrito/SKILL.md`, shipped in the jar)
   into Claude Code's user skills directory, `~/.claude/skills/storrito/`
   (`$CLAUDE_CONFIG_DIR/skills/storrito/` when that variable is set), and
   by making sure the user is logged in.

   A marker file next to the skill records the CLI version that wrote
   it. Files with the marker are ours: `storrito upgrade` and every
   start of a newer CLI rewrite them; a SKILL.md without the marker is
   somebody else's and is only replaced with `--force`."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [storrito.cli.config :as config]
            [storrito.cli.output :as output]
            [storrito.cli.version :as version]))

(def skill-name
  "storrito")

(def marker-name
  ".managed-by-storrito-cli")

(defn skill-markdown
  []
  (slurp (io/resource "storrito/SKILL.md")))

(defn claude-config-dir
  []
  (fs/path (or (config/env "CLAUDE_CONFIG_DIR")
               (fs/path (fs/home) ".claude"))))

(defn skill-dir
  []
  (fs/path (claude-config-dir) "skills" skill-name))

(defn skill-file
  []
  (fs/path (skill-dir) "SKILL.md"))

(defn marker-file
  []
  (fs/path (skill-dir) marker-name))

(defn managed-version
  "The CLI version that wrote the installed skill, nil when the skill is
   not ours (or not installed)."
  []
  (when (fs/exists? (marker-file))
    (str/trim (slurp (fs/file (marker-file))))))

(defn foreign-skill?
  "A SKILL.md exists that we did not write."
  []
  (and (fs/exists? (skill-file))
       (nil? (managed-version))))

(defn write-skill!
  []
  (fs/create-dirs (skill-dir))
  (spit (fs/file (skill-file)) (skill-markdown))
  (spit (fs/file (marker-file)) (str version/version "\n"))
  (str (skill-file)))

(defn install-skill!
  "Writes the skill. Refuses to replace a foreign SKILL.md unless
   `force`."
  [{:keys [force]}]
  (when (and (foreign-skill?)
             (not force))
    (output/throw-error (str "There is already a skill at " (skill-file) " that the Storrito CLI did not write")
                        {:exit :usage
                         :hint "Run `storrito setup claude --force` to replace it."}))
  (write-skill!))

(defn refresh-managed-skill!
  "Rewrites the installed skill when it was written by another CLI
   version, so that an upgrade also upgrades the skill. Called at every
   start; a no-op without an installed skill. Never throws."
  []
  (try
    (let [installed (managed-version)]
      (when (and installed
                 (not= installed version/version))
        (write-skill!)))
    (catch Exception _
      nil)))

(defn setup-claude
  "`storrito setup claude [--force] [--skill-only] [--print]`."
  [{:keys [flags] :as ctx} {:keys [login-fn]}]
  (cond
    (get flags "print")
    (skill-markdown)

    :else
    (let [path (install-skill! {:force (get flags "force")})
          credentials (config/read-credentials)
          logged-in? (boolean (or (config/env "STORRITO_TOKEN")
                                  (seq (config/org-uuids credentials))))
          login (when (and (not logged-in?)
                           (not (get flags "skill-only"))
                           login-fn)
                  (login-fn ctx))]
      {:agent "claude"
       :skill path
       :loggedIn (boolean (or logged-in? login))
       :login login
       :next (if (or logged-in? login)
               "Ask Claude Code to create a Storrito draft, for example: \"Create a Storrito draft from story.html\"."
               "Run `storrito login`, then ask Claude Code to create a Storrito draft.")})))

(def targets
  {"claude" setup-claude})

(defn setup
  [{:keys [positional] :as ctx} opts]
  (let [[target] positional]
    (if-let [f (get targets target)]
      (f ctx opts)
      (output/throw-error "Usage: storrito setup claude [--force] [--skill-only] [--print]"
                          {:exit :usage
                           :hint "Installs the Storrito skill for Claude Code and checks the login."}))))
