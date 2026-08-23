# RankAuth

Production-ready registration, login, and OP IP-lock security plugin for Paper 1.21.x (and forward-compatible Paper releases using the same 1.21 API surface).

## Features

- **New player registration** — password creation (min length + complexity policy, configurable), password confirmation, e-mail collection, 6-digit e-mail verification code, then automatic release into the server.
- **Returning player login** — `/login <password>`, with rate-limited lockout on repeated failures.
- **Full restriction while unauthenticated** — no movement (soft-clamped, never falls into the void), no damage/death, no chat, no tab/player-list visibility, no commands other than `/login`/`/register`.
- **BossBar countdown** — 3-minute (configurable) timer shown live; expiry kicks the player safely.
- **OP IP security** — the first successful login for an OP account after install (or after an IP-lock reset) binds that account's UUID to the connecting IP. Any other IP is rejected until an admin runs `/opsistemikaldir`.
- **Short-lived trusted session** — after a successful `/login`, the account's UUID + IP + expiry are stored; reconnecting from the *same* IP within `session.duration` (default 60s) skips `/login` entirely. A different IP, or an expired session, always falls back to a normal password prompt. Fully independent from OP IP locking — both are checked when applicable.
- **Duplicate-connection guard** — a second connection under the same username (case-insensitive) while one is already active is rejected at login, before it can touch the existing session; the original connection is never dropped.
- **HubShat integration** — dispatches HubShat's `/hub` command immediately on successful registration/login, with a safe, clearly logged no-op fallback if HubShat isn't installed.
- **Async, portable database layer** — SQLite by default (HikariCP-pooled), schema written to be MySQL/MariaDB-portable for a future connector.
- **Security-first** — BCrypt password hashing (vendored, no plaintext passwords ever stored or logged), SHA-256–hashed verification codes, SMTP credentials read from environment variables first.

## Installation

1. Drop the built `RankAuth-<version>.jar` into your server's `plugins/` folder.
2. Start the server once to generate `plugins/RankAuth/config.yml`.
3. Fill in your SMTP settings (see below) and reload/restart.

## Paper version support

Built against the Paper 1.21.1 API (`api-version: '1.21'`), using only modern Paper API (Adventure `Component`, `AsyncChatEvent`) — no NMS. Because `api-version: '1.21'` is forward-compatible within the 1.21.x line, the same jar runs unmodified on subsequent 1.21.x Paper builds.

## Registration flow

1. Player joins for the first time → suspended above the world at a fixed, safe location (no fall, no void).
2. `Şifre belirle:` — player types a password in chat (or uses `/register <pw> <pw>` directly).
3. Password policy enforced (`auth.minimum-password-length`, uppercase/lowercase/number toggles). Weak passwords are rejected with `Şifreniz çok zayıf.` and re-prompted.
4. `Şifrenizi tekrar girin:` — password confirmed; mismatches restart password entry.
5. `Güvenlik için e-posta adresinizi girin:` — e-mail format validated.
6. A 6-digit code is generated, hashed (SHA-256) before storage, and e-mailed via SMTP.
7. Player enters the code in chat. Correct code → account is persisted (BCrypt hash only) and the player is sent to Hub via HubShat.
8. The whole flow has a 3-minute timer (configurable), shown as a live BossBar countdown. Timeout → safe kick; the player registers from scratch on reconnect.

## Login flow

Returning players are shown the same restrictions and a login-specific BossBar timer, and must run:

```
/login <password>
```

Wrong passwords return only `Şifre yanlış.` (never confirming or denying which part was wrong) and count toward a configurable failed-attempt lockout (`auth.max-failed-login-attempts`, `auth.failed-login-lockout-seconds`).

## OP IP security

- Applies only to accounts with the OP flag — normal players are unaffected.
- Tracked by UUID, not username, so nickname changes don't bypass it.
- First successful login (or the first login after a reset) binds the connecting IP as trusted.
- Any subsequent login from a different IP is rejected with a clear message; the account is not otherwise touched.
- `op-security.behind-proxy` exists as a documented toggle for Velocity/BungeeCord deployments — RankAuth never trusts client-supplied forwarding headers directly, only Paper's own resolved `player.getAddress()`, which is correct once proxy player-info-forwarding is configured at the proxy layer.

### Removing an OP's IP lock

```
/opsistemikaldir <player>
```

Requires `rankauth.opsistemikaldir`. Clears the stored trusted IP; the next successful login from any IP becomes the new trusted IP.

## Short-lived trusted session

Decision order on every join (per spec):

1. **Duplicate nick** — is this UUID or username (case-insensitive) already connected? If so, the new connection is rejected with `duplicate-connection` and the existing player is untouched.
2. **Account exists?** — no → registration flow starts.
3. **Valid session?** — `session.enabled` is true, `session_expires_at` hasn't passed, and the connecting IP matches the account's stored `last_ip`.
4. **IP match** — same IP within the session window → skip `/login`, treat as authenticated.
5. **OP IP security** — checked independently of session trust; an OP account on an untrusted IP is still challenged even with a valid session, because the OP-IP check runs whenever `player.isOp()` and `op-security.enabled` are true, session or not.

Every successful password login (manual `/login`, `/register` flow completion, or a valid auto-login) refreshes `last_ip` and `session_expires_at` to `now + session.duration` (default 60s, `session.duration` in config). A different IP or an expired window always requires the password again — there is no bypass of the password itself, only of re-typing it within a short, same-IP window.

`messages.prefix` (default `Silvera`) is the configurable tag prepended to the duplicate-connection kick message — it is not hardcoded.

## Duplicate connections

Checked in a synchronous `PlayerLoginEvent` handler, before the join event or any world placement happens:

- Same UUID already online → reject.
- Same username, case-insensitive (`Yukile` vs `yukile`) → reject, even if UUIDs differ (defense-in-depth for offline-mode-style setups).
- The already-connected player's session is never touched or dropped.


## Commands

| Command | Description | Permission |
|---|---|---|
| `/register <password> <password>` | Complete password step directly (still requires the e-mail/code steps in chat). | — (available only pre-registration) |
| `/login <password>` | Log in to an existing account. | — (available only pre-login) |
| `/opsistemikaldir <player>` | Remove an OP's trusted IP lock. | `rankauth.opsistemikaldir` |
| `/rankauth reload` | Reload `config.yml`. | `rankauth.reload` |

## Permissions

- `rankauth.admin` (default: op)
- `rankauth.opsistemikaldir` (default: op)
- `rankauth.reload` (default: op)

## Configuration (`config.yml`)

See the shipped `config.yml` for the full, commented set of options: registration/login timers, password policy, verification code length/expiration, database type, SMTP settings, hub command, OP-security toggle, and all player-facing message strings.

### SMTP setup

Set these two environment variables on the host running the server (preferred over editing `config.yml` directly):

```
RANKAUTH_SMTP_USERNAME=your-smtp-username
RANKAUTH_SMTP_PASSWORD=your-smtp-password
```

`smtp.host`, `smtp.port`, `smtp.starttls`, `smtp.from-address`, `smtp.from-name`, and `smtp.subject` are set in `config.yml`. If the environment variables are absent, RankAuth falls back to `smtp.username`/`smtp.password` in `config.yml` — leave these blank in production and use the environment variables instead.

### HubShat integration

If a plugin named `HubShat` is detected and enabled at startup, RankAuth dispatches the configured `hub.command` (default `hub`, i.e. `/hub`) as the player immediately after registration/login. If HubShat isn't present, RankAuth logs a single startup warning and safely no-ops on every subsequent completion — it never errors or blocks the player.

## Database

Default: SQLite (`plugins/RankAuth/rankauth.db`), pooled through HikariCP, all queries off the main thread.

Tables:

- **players** — `uuid`, `username`, `password_hash`, `email`, `email_verified`, `registered_at`, `last_login`
- **verification_codes** — `uuid`, `code_hash`, `pending_email`, `expires_at`
- **op_ip_security** — `uuid`, `username`, `trusted_ip`, `created_at`, `updated_at`

Schema and queries use plain, portable SQL types so switching `database.type` to `mysql`/`mariadb` (host/port/database/username/password in `config.yml`, password overridable via `RANKAUTH_DB_PASSWORD`) requires no schema changes.

## Building

```
./gradlew build
```

or, without a checked-in wrapper jar:

```
gradle build
```

Output: `build/libs/RankAuth-<version>.jar` (shadowed fat jar with relocated SQLite/HikariCP/Jakarta Mail).

GitHub Actions (`.github/workflows/build.yml`) runs the same build on every push/PR to `main` using Gradle 8 + JDK 21 and uploads the built jar as an artifact.

## Security notes

- Passwords are hashed with BCrypt (vendored implementation, cost factor 12) and never stored, logged, or transmitted in plaintext.
- Verification codes are SHA-256–hashed before storage; the plaintext code is never written to disk, only sent once via e-mail.
- `/register` and `/login` password arguments are never logged (Bukkit does not log tab-completed/console-echoed command arguments for these commands beyond the standard command log, and RankAuth itself performs no additional logging of arguments).
- Failed logins are rate-limited per session to blunt brute-force attempts.
