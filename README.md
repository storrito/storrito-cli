# Storrito CLI

`storrito` is the command-line tool for the [Storrito API](https://storrito.com/documentation/api/v1/):
schedule Instagram Stories, Reels and TikTok posts from a terminal or from
a coding agent such as Claude Code. Every API procedure is a command, the
output is JSON.

```
storrito login                          # sign in with the browser
storrito list-instagram-users
storrito upload story.mp4               # a temp-blob, prints its url
storrito schedule-instagram-story --instagramUsername acme \
  --storyPostUuid $(storrito generate-uuid --compact | jq -r .uuid) \
  --html @story.html
storrito status-instagram-story --storyPostUuid <uuid>
storrito commands                       # every command as JSON, for agents
```

Documentation: https://storrito.com/documentation/cli/

## Install

Linux and macOS, no root needed:

```
curl -fsSL https://storrito.com/install.sh | sh
```

Windows (PowerShell), no administrator rights needed:

```
irm https://storrito.com/install.ps1 | iex
```

`storrito upgrade` updates the installed executable.

## How it works

The CLI is written in Clojure and runs on [babashka](https://babashka.org).
A release is the babashka runtime with this project's jar appended
(`cat bb storrito.jar > storrito`): one self-contained executable per
platform, and the code you run is the code in this repository. The
runtime's ad-hoc code signature stays valid with the jar appended, so
Apple Silicon runs it; CI builds and runs the executable on Linux,
macOS ARM and Windows.

Commands are derived at runtime from the API catalog
(`https://storrito.com/documentation/api/v1/catalog.json`): one command
per procedure, its flags are the top-level properties of the procedure's
input schema, `--json '{...}'` / `--json @file` / `--json -` pass the whole
parameter map. A flag value starting with `@` is read from that file.

Sign-in uses the WorkOS device flow of the Storrito account; for CI and
unattended agents an API credential works via `storrito login --token
<id>:<secret> --org <uuid>` or the environment (`STORRITO_TOKEN`,
`STORRITO_ORG`). Logins are stored per organization in
`~/.config/storrito/credentials.json` (mode 0600, Windows
`%APPDATA%\storrito`).

Output is JSON on stdout, pretty-printed in a terminal (`--compact` for
one line). Errors are JSON on stderr with the details of the API response.
Exit codes: 0 ok, 1 error, 2 usage, 3 not logged in, 4 the API rejected
the parameters, 5 rate limited even after retries, 6 network.

| Variable | Purpose |
|---|---|
| `STORRITO_TOKEN` | An API credential `id:secret`; skips the stored login. |
| `STORRITO_ORG` | The organization UUID, like `--org`. |
| `STORRITO_CONFIG_DIR` | The config directory. |
| `STORRITO_API_BASE` | Base URL template for a dev environment, e.g. `http://ORG_UUID.localhost:8080/api/v1/`. |
| `STORRITO_CATALOG_URL` | Where to download the catalog. |
| `STORRITO_DOWNLOADS_URL` | Where `upgrade` and the installers look for releases. |
| `STORRITO_WORKOS_CLIENT_ID`, `STORRITO_WORKOS_API_BASE` | The WorkOS application (staging) and API. |
| `STORRITO_NO_UPDATE_CHECK` | No daily update hint. |
| `STORRITO_DEBUG` | Print stack traces on stderr. |

## Development

Needs [babashka](https://github.com/babashka/babashka#installation) 1.12.200
or newer.

```
bb cli --help                # run from source
bb test                      # unit tests: fake API, catalog, WorkOS and download servers, no network
bb jar                       # target/storrito.jar
bin/build --platforms linux-amd64 --skip-tests   # a self-contained executable in dist/
```

Namespaces: `storrito.cli.main` (dispatch), `commands` (procedure commands
from the catalog), `catalog`, `auth` + `workos` + `config` (logins),
`http` (retries), `output` (JSON, exit codes), `upload`, `upgrade`.

## Release

```
bin/build --version 0.2.0 --upload
```

Runs the tests, builds the jar, downloads the pinned babashka release for
every platform, assembles the executables, writes the sha256 files and
`latest.json`, and uploads everything plus the install scripts to the
download bucket behind `https://storrito.com/downloads/cli/`. See the
header of `bin/build`.

## License

[MIT](LICENSE)
