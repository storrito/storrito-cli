(ns storrito.cli.commands
  "The procedure commands, derived from the catalog: every API
   procedure is a command whose flags are the top-level properties of
   its input schema.

       storrito <procedure> --flag value ...
       storrito <procedure> --json '{...}'
       storrito <procedure> --json @params.json
       storrito <procedure> --json -            (stdin)

   A flag value that starts with `@` is read from that file (`@-` is
   stdin), for the `html` of a story. Values of array and object
   properties are JSON. `--json` gives the whole parameter map, flags
   override its entries."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [cheshire.core :as json]
            [storrito.cli.http :as http]
            [storrito.cli.output :as output]))

(def global-flags
  "Flags of every command, not passed to the procedure."
  #{"org" "json" "help" "compact" "refresh"})

(defn parse-flags
  "Parses `--flag value`, `--flag=value` and bare `--flag` (true) into
   `{:flags {\"flag\" \"value\"} :positional [...]}`. A bare flag followed
   by another flag is `true`; `boolean?` tells whether a flag takes no
   value even when a value-looking argument follows."
  [args & [{:keys [boolean?] :or {boolean? (constantly false)}}]]
  (loop [args (seq args)
         flags {}
         positional []]
    (if-not args
      {:flags flags
       :positional positional}
      (let [arg (first args)]
        (cond
          (= "--" arg)
          {:flags flags
           :positional (into positional (rest args))}

          (str/starts-with? arg "--")
          (let [[k v] (str/split (subs arg 2) #"=" 2)]
            (cond
              v (recur (next args) (assoc flags k v) positional)

              (boolean? k)
              (recur (next args) (assoc flags k true) positional)

              (let [n (second args)]
                (or (nil? n)
                    (str/starts-with? n "--")))
              (recur (next args) (assoc flags k true) positional)

              :else
              (recur (nnext args) (assoc flags k (second args)) positional)))

          :else
          (recur (next args) flags (conj positional arg)))))))

(defn read-at-value
  "A value starting with `@` is the content of that file, `@-` is
   stdin."
  [value]
  (if (and (string? value)
           (str/starts-with? value "@"))
    (let [path (subs value 1)]
      (if (= "-" path)
        (slurp *in*)
        (if (fs/exists? path)
          (slurp path)
          (output/throw-error (str "File not found: " path)
                              {:exit :usage}))))
    value))

(defn json-type
  "The JSON schema type of a property as a string, `\"json\"` when the
   schema has none (e.g. `oneOf`)."
  [{:keys [type] :as property}]
  (cond
    (string? type) type
    (sequential? type) (or (first (remove #{"null"} type)) "json")
    (:enum property) "string"
    :else "json"))

(defn flag-specs
  "One flag per top-level property of the procedure's input schema."
  [{:keys [inputSchema]}]
  (let [required (set (:required inputSchema))]
    (->> (:properties inputSchema)
         (map (fn [[k property]]
                (let [flag (name k)]
                  {:flag flag
                   :type (json-type property)
                   :required? (contains? required flag)
                   :description (:description property)
                   :format (:format property)
                   :enum (:enum property)})))
         (sort-by (juxt (complement :required?) :flag))
         (vec))))

(defn coerce-value
  "Converts the flag `value` (a string, or `true` for a bare flag) to
   the JSON type of the property."
  [{:keys [flag type]} value]
  (let [value (read-at-value value)
        usage (fn [expected]
                (output/throw-error (str "--" flag " expects " expected ", got: " value)
                                    {:exit :usage}))]
    (case type
      "boolean" (cond
                  (true? value) true
                  (contains? #{"true" "1" "yes"} value) true
                  (contains? #{"false" "0" "no"} value) false
                  :else (usage "true or false"))
      "integer" (try
                  (Long/parseLong (str/trim value))
                  (catch Exception _
                    (usage "an integer")))
      "number" (try
                 (Double/parseDouble (str/trim value))
                 (catch Exception _
                   (usage "a number")))
      "string" (if (true? value)
                 (usage "a value")
                 value)
      (if (true? value)
        (usage "JSON")
        (try
          (json/parse-string value true)
          (catch Exception _
            (usage "JSON (or @file with JSON)")))))))

(defn params-from-json-flag
  [json-flag]
  (when json-flag
    (let [content (read-at-value json-flag)
          parsed (try
                   (json/parse-string content true)
                   (catch Exception _
                     (output/throw-error "--json expects a JSON object"
                                         {:exit :usage})))]
      (if (map? parsed)
        parsed
        (output/throw-error "--json expects a JSON object"
                            {:exit :usage})))))

(defn params
  "The parameter map of a procedure call from the parsed `flags`."
  [procedure flags]
  (let [specs (flag-specs procedure)
        by-flag (into {} (map (juxt :flag identity)) specs)
        unknown (remove (fn [k]
                          (or (contains? by-flag k)
                              (contains? global-flags k)))
                        (keys flags))
        base (or (params-from-json-flag (get flags "json"))
                 {})
        given (reduce (fn [acc [k v]]
                        (if-let [spec (get by-flag k)]
                          (assoc acc (keyword k) (coerce-value spec v))
                          acc))
                      base
                      flags)
        missing (->> specs
                     (filter :required?)
                     (map :flag)
                     (remove #(contains? given (keyword %))))]
    (when (seq unknown)
      (output/throw-error (str "Unknown flag" (when (next unknown) "s") ": "
                               (str/join ", " (map #(str "--" %) unknown)))
                          {:exit :usage
                           :hint (str "Run `storrito " (:name procedure) " --help` for the flags.")}))
    (when (and (seq missing)
               (not (get flags "json")))
      (output/throw-error (str "Missing required flag" (when (next missing) "s") ": "
                               (str/join ", " (map #(str "--" %) missing)))
                          {:exit :usage
                           :hint (str "Run `storrito " (:name procedure) " --help` for the flags.")}))
    given))

(defn response-error
  "The CLI error for a non-200 API response."
  [{:keys [status] :as response} procedure-name]
  (let [body (or (http/parse-json-body response)
                 (:body response))
        message (or (:errorMessage body)
                    (str "HTTP " status))]
    (case status
      400 (output/error (str procedure-name ": " message)
                        {:exit :validation
                         :status status
                         :details body
                         :hint "The details carry the JSON schema of the parameters and what was wrong."})
      401 (output/error "Not authorized"
                        {:exit :not-logged-in
                         :status status
                         :hint "Run `storrito login` again, or check STORRITO_TOKEN and the organization."})
      404 (output/error (str "Unknown procedure: " procedure-name)
                        {:exit :usage
                         :status status
                         :hint "Run `storrito commands --refresh` to update the catalog."})
      429 (output/error "Rate limited by the API, even after retries"
                        {:exit :rate-limited
                         :status status
                         :retryable true
                         :details body})
      (output/error (str procedure-name ": " message)
                    {:exit :error
                     :status status
                     :retryable (>= status 500)
                     :details body}))))

(defn invoke
  "Calls the procedure and returns the parsed response, or throws the
   CLI error."
  [{:keys [base-url token]} procedure-name params & [http-opts]]
  (let [response (http/request (http/json-request (str base-url procedure-name)
                                                  token
                                                  params)
                               http-opts)]
    (if (= 200 (:status response))
      (http/parse-json-body response)
      (let [error (response-error response procedure-name)]
        (throw (ex-info (:error error)
                        (assoc error ::output/error true)))))))

(defn strip-code-blocks
  "The doc-string without its fenced code blocks (curl examples)."
  [doc]
  (-> (or doc "")
      (str/replace #"(?s)```.*?```" "")
      (str/replace #"\n[ \t]*\n[ \t\n]*" "\n\n")
      (str/trim)))

(defn first-sentence
  [doc]
  (let [text (str/replace (strip-code-blocks doc) #"\s+" " ")]
    (or (second (re-find #"^(.*?[.!?])(\s|$)" text))
        text)))

(def example-uuid-placeholder
  #"EXAMPLE_[A-Z_]*UUID")

(defn fill-example-uuids
  [s]
  (str/replace s example-uuid-placeholder (str (random-uuid))))

(defn flag-line
  [{:keys [flag type required? description enum format]}]
  (let [type-label (cond
                     enum (str "one of " (str/join ", " enum))
                     format (str type " (" format ")")
                     :else type)]
    (str "  --" flag " <" type-label ">"
         (when required? "  (required)")
         (when description
           (str "\n      " (str/replace description #"\n" "\n      "))))))

(defn help-text
  "The `--help` of a procedure command."
  [procedure]
  (str "storrito " (:name procedure) " [flags]\n\n"
       (strip-code-blocks (:doc procedure))
       "\n\nFlags:\n"
       (str/join "\n" (map flag-line (flag-specs procedure)))
       "\n\nCommon flags:\n"
       "  --org <uuid>       The organization (default: the default login)\n"
       "  --json <json|@file|->  The whole parameter map as JSON, flags override it\n"
       "  --compact          One-line JSON output\n"
       (when-let [example (:exampleInput procedure)]
         (str "\nExample:\n  storrito " (:name procedure) " --json '"
              (fill-example-uuids (json/generate-string example))
              "'\n"))))
