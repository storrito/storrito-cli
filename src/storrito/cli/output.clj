(ns storrito.cli.output
  "How the CLI talks: JSON on stdout, errors as JSON on stderr, typed
   exit codes. The same shape for humans and agents, pretty-printed when
   stdout is a terminal.

   Exit codes:

   - 0 ok
   - 1 error
   - 2 usage (unknown command, bad flag)
   - 3 not logged in, or the login expired
   - 4 the API rejected the parameters (HTTP 400, the response body is in
       `details`)
   - 5 rate limited, even after retries (HTTP 429)
   - 6 network problem"
  (:require [cheshire.core :as json]))

(def exit-codes
  {:ok 0
   :error 1
   :usage 2
   :not-logged-in 3
   :validation 4
   :rate-limited 5
   :network 6})

(defn tty?
  "Whether stdout is a terminal. `System/console` is nil when stdout or
   stdin is redirected on the JDKs babashka is built with, newer JDKs
   answer `isTerminal`."
  []
  (boolean
    (when-let [console (System/console)]
      (try
        (.isTerminal console)
        (catch Throwable _
          true)))))

(defn json-str
  [data {:keys [pretty]}]
  (json/generate-string data
                        {:pretty pretty}))

(defn print-result
  "Prints `data` as JSON on stdout."
  ([data]
   (print-result data {:pretty (tty?)}))
  ([data opts]
   (println (json-str data opts))
   (flush)))

(defn print-error
  "Prints the error map as JSON on stderr."
  [error]
  (binding [*out* *err*]
    (println (json-str error {:pretty true}))
    (flush)))

(defn exit-code
  "The exit code of an error map, from its `:exit` keyword."
  [{:keys [exit]}]
  (get exit-codes exit 1))

(defn error
  "Builds an error map: `:error` is the message, `:exit` one of the
   `exit-codes` keys, `:hint` says what to do, `:retryable` whether a
   retry can help, `:details` carries the API response body."
  [message {:keys [exit hint retryable details status] :or {exit :error}}]
  (cond-> {:error message
           :exit exit
           :retryable (boolean retryable)}
    hint (assoc :hint hint)
    status (assoc :status status)
    details (assoc :details details)))

(defn throw-error
  "Throws an `ex-info` that `storrito.cli.main` turns into the JSON error
   on stderr and the exit code."
  [message opts]
  (throw (ex-info message
                  (assoc (error message opts)
                         ::error true))))

(defn error?
  [e]
  (boolean (::error (ex-data e))))

(defn error-data
  "The error map of an exception: the data of a `throw-error`, or a
   generic error for any other exception."
  [e]
  (if (error? e)
    (dissoc (ex-data e) ::error)
    (error (or (ex-message e)
               (str (class e)))
           {:exit :error})))
