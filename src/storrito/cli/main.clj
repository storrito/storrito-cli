(ns storrito.cli.main
  "The entry point of the Storrito CLI.

       storrito <command> [flags]

   Built-in commands: login, logout, status, org, upload, commands,
   version, doctor, help. Every API procedure of the catalog is a
   command as well, see `storrito.cli.commands`. Output is JSON on
   stdout, errors are JSON on stderr, see `storrito.cli.output` for the
   exit codes."
  (:require [clojure.string :as str]
            [storrito.cli.auth :as auth]
            [storrito.cli.catalog :as catalog]
            [storrito.cli.commands :as commands]
            [storrito.cli.config :as config]
            [storrito.cli.login :as login]
            [storrito.cli.output :as output]
            [storrito.cli.upgrade :as upgrade]
            [storrito.cli.upload :as upload]
            [storrito.cli.version :as version]))

(def boolean-flags
  "Flags that never take a value."
  #{"help" "compact" "refresh" "no-browser" "all" "version" "debug" "force"})

(defn api-context
  "The organization, token, catalog and base URL for a command that
   calls the API."
  [flags]
  (let [{:keys [org-uuid token]} (auth/resolve-auth {:org (get flags "org")})
        catalog (catalog/catalog {:refresh (get flags "refresh")})]
    {:org-uuid org-uuid
     :token token
     :catalog catalog
     :base-url (catalog/base-url catalog org-uuid)}))

(declare builtin-commands)

(defn catalog-or-nil
  [flags]
  (try
    (catalog/catalog {:refresh (get flags "refresh")})
    (catch Exception _
      nil)))

(defn commands-listing
  "`storrito commands`: every command as data, for agents."
  [{:keys [flags]}]
  (let [catalog (catalog-or-nil flags)]
    {:version version/version
     :commands (into (mapv (fn [{:keys [name doc]}]
                             {:name name
                              :kind "builtin"
                              :description doc})
                           builtin-commands)
                     (map (fn [{:keys [name doc inputSchema]}]
                            {:name name
                             :kind "procedure"
                             :description (commands/first-sentence doc)
                             :inputSchema inputSchema}))
                     (catalog/procedures catalog))
     :catalogAvailable (boolean catalog)}))

(defn check
  [name f]
  (try
    (let [result (f)]
      (merge {:check name
              :ok true}
             (when (map? result)
               result)))
    (catch Exception e
      {:check name
       :ok false
       :error (:error (output/error-data e))})))

(defn doctor
  "`storrito doctor`: the checks a support request needs."
  [{:keys [flags]}]
  (let [checks [(check "config-dir"
                       (fn []
                         {:path (str (config/ensure-config-dir!))}))
                (check "catalog"
                       (fn []
                         (let [catalog (catalog/catalog {:refresh true})]
                           {:url (catalog/catalog-url)
                            :procedures (count (catalog/procedures catalog))})))
                (check "login"
                       (fn []
                         (let [{:keys [org-uuid]} (auth/resolve-auth {:org (get flags "org")})]
                           {:org org-uuid})))
                (check "api"
                       (fn []
                         (let [{:keys [org-uuid] :as api} (api-context flags)]
                           (commands/invoke api "generate-uuid" {} {:retries 1})
                           {:baseUrl (:base-url api)
                            :org org-uuid})))]]
    {:version (version/info)
     :ok (every? :ok checks)
     :checks checks}))

(defn help-text
  [flags]
  (let [catalog (catalog-or-nil flags)]
    (str "storrito <command> [flags]\n\n"
         "The Storrito CLI: schedule Instagram Stories, Reels and TikTok posts\n"
         "through the Storrito API. Output is JSON. Docs:\n"
         "https://storrito.com/documentation/api/v1/\n\n"
         "Commands:\n"
         (str/join "\n"
                   (map (fn [{:keys [name doc]}]
                          (format "  %-28s %s" name doc))
                        builtin-commands))
         "\n\nAPI procedures:\n"
         (if catalog
           (str/join "\n"
                     (map (fn [{:keys [name doc]}]
                            (format "  %-28s %s" name (commands/first-sentence doc)))
                          (catalog/procedures catalog)))
           "  (the API catalog is not available, run `storrito commands --refresh` online)")
         "\n\nRun `storrito <command> --help` for the flags of a command.\n"
         "Flags of every command: --org <uuid>, --compact, --refresh (the catalog).\n")))

(def builtin-commands
  [{:name "login"
    :doc "Sign in with the browser, or --token id:secret --org <uuid> for an API credential."
    :run (fn [{:keys [flags]}]
           (login/login {:token (get flags "token")
                         :org (get flags "org")
                         :no-browser (get flags "no-browser")}))}
   {:name "logout"
    :doc "Forget the login of the organization (--org <uuid>), or --all."
    :run (fn [{:keys [flags]}]
           (login/logout {:org (get flags "org")
                          :all (get flags "all")}))}
   {:name "status"
    :doc "The stored logins and the default organization."
    :run login/status}
   {:name "org"
    :doc "org list, org default <uuid>: the organizations you are logged in to."
    :run login/org}
   {:name "upload"
    :doc "upload <file>: a local image, video or audio as a temp-blob, prints its url."
    :run (fn [{:keys [flags] :as ctx}]
           (upload/upload (api-context flags) ctx))}
   {:name "commands"
    :doc "Every command as JSON, for agents."
    :run commands-listing}
   {:name "doctor"
    :doc "Checks the installation, the login and the API connection."
    :run doctor}
   {:name "version"
    :doc "The version of the CLI."
    :run (fn [_]
           (version/info))}
   {:name "upgrade"
    :doc "Replaces this executable with the latest release (--force reinstalls)."
    :run upgrade/upgrade}
   {:name "help"
    :doc "This help. `storrito <command> --help` explains a command."
    :run nil}])

(defn find-builtin
  [name]
  (first (filter #(= name (:name %))
                 builtin-commands)))

(defn find-procedure
  "The catalog procedure named `name`, refreshing the catalog once when
   the name is unknown (a procedure added after the last download)."
  [name flags]
  (let [catalog (catalog/catalog {:refresh (get flags "refresh")})]
    (or (catalog/find-procedure catalog name)
        (catalog/find-procedure (catalog/catalog {:refresh true})
                                name))))

(defn run-procedure
  [procedure {:keys [flags]}]
  (let [params (commands/params procedure flags)
        api (api-context flags)]
    (commands/invoke api (:name procedure) params)))

(defn dispatch
  "Runs the command and returns the data to print, or a string to print
   as is (help)."
  [{:keys [flags positional] :as ctx}]
  (let [[command & rest-positional] positional
        ctx (assoc ctx :positional (vec rest-positional))]
    (cond
      (get flags "version")
      (version/info)

      (or (nil? command)
          (= "help" command))
      (help-text flags)

      :else
      (if-let [builtin (find-builtin command)]
        (if (get flags "help")
          (str "storrito " command "\n\n" (:doc builtin) "\n")
          ((:run builtin) ctx))
        (if-let [procedure (find-procedure command flags)]
          (if (get flags "help")
            (commands/help-text procedure)
            (run-procedure procedure ctx))
          (output/throw-error (str "Unknown command: " command)
                              {:exit :usage
                               :hint "Run `storrito help` for the commands."}))))))

(def no-update-hint
  "Commands after which the update hint makes no sense."
  #{"upgrade" "version" "help" "doctor"})

(defn maybe-update-hint!
  "The passive update check, only for a human in a terminal, never for
   a script or an agent reading the output."
  [{:keys [flags positional]}]
  (when (and (output/tty?)
             (not (get flags "help"))
             (not (contains? no-update-hint (first positional))))
    (try
      (upgrade/check-for-update!)
      (catch Exception _
        nil))))

(defn run
  "Runs the CLI with `args`, prints the result and returns the exit
   code."
  [args]
  (let [{:keys [flags] :as ctx} (commands/parse-flags args {:boolean? boolean-flags})
        pretty (and (not (get flags "compact"))
                    (output/tty?))]
    (some-> (upgrade/executable-path)
            (upgrade/cleanup-old-executable!))
    (try
      (let [result (dispatch ctx)]
        (if (string? result)
          (do (print result)
              (flush))
          (output/print-result result {:pretty pretty}))
        (maybe-update-hint! ctx)
        (if (and (map? result)
                 (false? (:ok result)))
          1
          0))
      (catch Exception e
        (when (or (get flags "debug")
                  (config/env "STORRITO_DEBUG"))
          (binding [*out* *err*]
            (println (str e))
            (.printStackTrace e)))
        (let [error (output/error-data e)]
          (output/print-error error)
          (output/exit-code error))))))

(defn -main
  [& args]
  (System/exit (run args)))
