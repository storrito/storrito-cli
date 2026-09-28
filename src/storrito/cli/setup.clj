(ns storrito.cli.setup
  "`storrito setup claude|codex|agents`: connects the CLI to a coding
   agent by installing the Storrito skill (`resources/storrito/SKILL.md`,
   shipped in the jar) into the agent's user skills directory, and by
   making sure the user is logged in. `setup agents` does it for every
   known agent at once, with a single login.

   Where the skill goes:

   - Claude Code: `~/.claude/skills/storrito/SKILL.md`
     (`$CLAUDE_CONFIG_DIR/skills/storrito/` when that variable is set).

   - Codex: the shared Agent Skills directory
     `~/.agents/skills/storrito/SKILL.md`. The Codex docs
     (https://developers.openai.com/codex/skills, which redirects to
     https://learn.chatgpt.com/docs/build-skills) list `$HOME/.agents/skills`
     as the user-level location; the Codex source
     (`codex-rs/ext/skills/src/host_roots.rs`) still reads
     `$CODEX_HOME/skills` too, but calls it the deprecated location kept
     for backward compatibility, and Codex would list a skill present
     in both directories twice. So the CLI writes the shared directory
     only, and `$CODEX_HOME` plays no role: Codex resolves `~/.agents`
     from the home directory. `$STORRITO_AGENTS_SKILLS_DIR` overrides the
     shared directory (the tests use it; Codex has no such variable).
     hey-cli does the same (`hey setup codex` writes
     `~/.agents/skills/hey/` and removes its old `~/.codex/skills/hey/`
     copy); unlike hey-cli, which symlinks the Claude Code skill to the
     shared one, this CLI writes two plain copies, which also works on
     Windows.

   A marker file next to each skill records the CLI version that wrote
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

(defn claude-skill-dir
  []
  (fs/path (claude-config-dir) "skills" skill-name))

(defn agents-skills-dir
  "The shared Agent Skills directory `~/.agents/skills`, which Codex
   reads. `$STORRITO_AGENTS_SKILLS_DIR` overrides it."
  []
  (fs/path (or (config/env "STORRITO_AGENTS_SKILLS_DIR")
               (fs/path (fs/home) ".agents" "skills"))))

(defn codex-skill-dir
  []
  (fs/path (agents-skills-dir) skill-name))

(def claude
  {:name "claude"
   :label "Claude Code"
   :skill-dir-fn claude-skill-dir})

(def codex
  {:name "codex"
   :label "Codex"
   :skill-dir-fn codex-skill-dir})

(def agents
  "Every known agent, in the order `setup agents` installs them."
  [claude codex])

(defn skill-dir
  [{:keys [skill-dir-fn]}]
  (skill-dir-fn))

(defn skill-file
  [agent]
  (fs/path (skill-dir agent) "SKILL.md"))

(defn marker-file
  [agent]
  (fs/path (skill-dir agent) marker-name))

(defn managed-version
  "The CLI version that wrote the agent's installed skill, nil when the
   skill is not ours (or not installed)."
  [agent]
  (when (fs/exists? (marker-file agent))
    (str/trim (slurp (fs/file (marker-file agent))))))

(defn foreign-skill?
  "A SKILL.md exists for the agent that we did not write."
  [agent]
  (and (fs/exists? (skill-file agent))
       (nil? (managed-version agent))))

(defn refuse-foreign-skill!
  "Throws when the agent has a foreign SKILL.md and `force` is not set."
  [agent {:keys [force]}]
  (when (and (foreign-skill? agent)
             (not force))
    (output/throw-error (str "There is already a skill at " (skill-file agent) " that the Storrito CLI did not write")
                        {:exit :usage
                         :hint (str "Run `storrito setup " (:name agent) " --force` to replace it.")})))

(defn write-skill!
  [agent]
  (fs/create-dirs (skill-dir agent))
  (spit (fs/file (skill-file agent)) (skill-markdown))
  (spit (fs/file (marker-file agent)) (str version/version "\n"))
  (str (skill-file agent)))

(defn install-skill!
  "Writes the agent's skill. Refuses to replace a foreign SKILL.md
   unless `force`."
  [agent opts]
  (refuse-foreign-skill! agent opts)
  (write-skill! agent))

(defn install-skills!
  "Writes the skill for every agent in `agents`, after checking all of
   them, so that a foreign skill stops the install before anything is
   written. One `{:agent :skill}` per agent."
  [agents opts]
  (run! #(refuse-foreign-skill! % opts) agents)
  (mapv (fn [agent]
          {:agent (:name agent)
           :skill (write-skill! agent)})
        agents))

(defn refresh-managed-skill!
  "Rewrites every installed skill that was written by another CLI
   version, so that an upgrade also upgrades the skills. Called at every
   start; a no-op without installed skills. Never throws."
  []
  (doseq [agent agents]
    (try
      (let [installed (managed-version agent)]
        (when (and installed
                   (not= installed version/version))
          (write-skill! agent)))
      (catch Exception _
        nil))))

(defn login-state
  "Whether a login is stored; runs `login-fn` when none is and
   `--skill-only` is not set. `{:loggedIn :login}` as the result
   reports them."
  [{:keys [flags] :as ctx} {:keys [login-fn]}]
  (let [credentials (config/read-credentials)
        logged-in? (boolean (or (config/env "STORRITO_TOKEN")
                                (seq (config/org-uuids credentials))))
        login (when (and (not logged-in?)
                         (not (get flags "skill-only"))
                         login-fn)
                (login-fn ctx))]
    {:loggedIn (boolean (or logged-in? login))
     :login login}))

(defn next-step
  "What to do next, naming the agents by their labels."
  [agents logged-in?]
  (let [who (str/join " or " (map :label agents))]
    (if logged-in?
      (str "Ask " who " to create a Storrito draft, for example: \"Create a Storrito draft from story.html\".")
      (str "Run `storrito login`, then ask " who " to create a Storrito draft."))))

(defn setup-agent
  "`storrito setup <agent> [--force] [--skill-only] [--print]`."
  [agent {:keys [flags] :as ctx} opts]
  (if (get flags "print")
    (skill-markdown)
    (let [path (install-skill! agent {:force (get flags "force")})
          {:keys [loggedIn login]} (login-state ctx opts)]
      {:agent (:name agent)
       :skill path
       :loggedIn loggedIn
       :login login
       :next (next-step [agent] loggedIn)})))

(defn setup-agents
  "`storrito setup agents [--force] [--skill-only] [--print]`: the skill
   for every known agent, one login."
  [{:keys [flags] :as ctx} opts]
  (if (get flags "print")
    (skill-markdown)
    (let [installed (install-skills! agents {:force (get flags "force")})
          {:keys [loggedIn login]} (login-state ctx opts)]
      {:agents installed
       :loggedIn loggedIn
       :login login
       :next (next-step agents loggedIn)})))

(def targets
  (into {"agents" setup-agents}
        (map (fn [agent]
               [(:name agent) (partial setup-agent agent)])
             agents)))

(defn setup
  [{:keys [positional] :as ctx} opts]
  (let [[target] positional]
    (if-let [f (get targets target)]
      (f ctx opts)
      (output/throw-error "Usage: storrito setup claude|codex|agents [--force] [--skill-only] [--print]"
                          {:exit :usage
                           :hint "Installs the Storrito skill for Claude Code, Codex or both, and checks the login."}))))
