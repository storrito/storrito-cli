---
name: storrito
description: Schedule Instagram Stories, Instagram Reels and TikTok posts with the storrito command-line tool. Use when the user wants to post or schedule social media content through Storrito, upload media for a story, check the status of a scheduled post, or create a draft in the Storrito gallery. Every Storrito API procedure is a storrito command with JSON output.
---

# Storrito CLI

`storrito` is installed on this machine. It talks to the Storrito API
(https://storrito.com/documentation/api/v1/), which auto-posts Instagram
Stories with native interactive stickers, and schedules Instagram Reels
and TikTok posts. Docs: https://storrito.com/documentation/cli/

## Discover the commands

- `storrito commands` prints every command with its description and the
  JSON schema of its parameters. Read it before guessing flags.
- `storrito <command> --help` explains one command with its flags and an
  example.
- The flags of an API procedure are the top-level properties of its
  input schema. `--json '{...}'`, `--json @file.json` or `--json -`
  (stdin) pass the whole parameter map instead. A flag value starting
  with `@` is read from that file: `--html @story.html`.

## Output and exit codes

- Every command prints JSON on stdout. Use `--compact` for one line,
  `jq` to pick fields.
- Errors are JSON on stderr with `error`, `hint` and, for API errors,
  `details` (on a validation error the parameter schema and what was
  wrong).
- Exit codes: 0 ok, 1 error, 2 usage (unknown or missing flag), 3 not
  logged in, 4 the API rejected the parameters, 5 rate limited after
  retries, 6 network. The CLI never prompts; on exit code 3 tell the
  user to run `storrito login` (or to set `STORRITO_TOKEN` and
  `STORRITO_ORG`), do not retry.
- Requests are retried automatically on 429, 502, 503 and 504.

## Typical flows

Post or schedule an Instagram Story from HTML with sticker components:

```
storrito list-instagram-users
UUID=$(storrito generate-uuid --compact | jq -r .uuid)
storrito schedule-instagram-story --instagramUsername <username> \
  --storyPostUuid $UUID --html @story.html [--date 2026-10-01T09:00:00Z]
storrito status-instagram-story --storyPostUuid $UUID
```

The HTML uses `<insta-story>` and sticker elements such as
`<insta-link>`, `<insta-hashtag>`, `<insta-mention>`, `<insta-poll>`;
the reference is https://storrito.com/documentation/api/v1/story-components.md.
Without `--date` the story is posted right away.

Local media: `storrito upload photo.jpg` uploads it as a temp-blob and
prints a `url` to use as `<insta-story src="...">` or `<img src>` in the
HTML. Media with a public URL needs no upload.

Draft only (lands in the Storrito gallery, posts nothing):

```
storrito create-draft --draftUuid $UUID --name "Draft name" --html @story.html
storrito status-draft --draftUuid $UUID
```

Reels and TikTok: `schedule-instagram-reel`, `schedule-tiktok-post`
(accounts from `list-tiktok-accounts`), each with a matching `status-*`
and `cancel-*` command.

Generate the UUIDs with `storrito generate-uuid`; passing the same UUID
again is safe (idempotent), it never creates a duplicate post.

## Accounts and login

- `storrito status` shows the stored logins and the default
  organization; `--org <uuid>` picks another one.
- `storrito login` signs in through the browser (the user has to do
  this). For unattended runs an API credential works via
  `STORRITO_TOKEN=<id>:<secret>` and `STORRITO_ORG=<organization uuid>`.
- `storrito doctor` checks the installation, the login and the API
  connection.
