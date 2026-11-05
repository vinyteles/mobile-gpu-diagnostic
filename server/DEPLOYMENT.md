# Upload relay deployment

This guide describes how to deploy and operate the upload relay on a Linux host (a server or a
workstation). API contract: [API.md](API.md).

## Architecture

```
phone ──HTTPS──▶ Cloudflare quick tunnel ──▶ cloudflared (outbound only) ──▶ 127.0.0.1:18765 API
                                                                              │ SQLite + fsynced spool
                                                                              ▼
                                         worker (only process holding the HF credential) ──▶ huggingface.co
```

- **api** (`python -m server.api`) validates and persists uploads. It decides from each
  report whether the session's assigned configurations are complete (120/120 fixture questions
  each): partial reports become `stored`, complete ones `accepted`. It never reads the HF
  credential.
- **worker** (`python -m server.worker`) coalesces stored partials (keeping the most complete
  one per run) and publishes **only complete** reports to HF, with ordering, backoff and restart
  recovery. It also maintains the public index dataset (`vinyteles/mobilebench-index-v2`).
- **tunnel** (`server/deploy/tunnel.sh`) runs cloudflared and records the active URL. The
  script exits when the public URL fails health checks for about 5 minutes, and its supervisor
  then starts a fresh tunnel, which gets a **new URL**.

Each process runs under its own restart loop (`server/deploy/supervise.sh`, exponential
backoff up to 60 s) in one window of the tmux session **`mobilebench-relay`**. The tmux
server is independent of the login shell, so closing a terminal does not stop it.

## Layout (outside Git)

| Path | Contents |
|---|---|
| `~/mobilebench-upload/src` | Detached git worktree at the deployed commit (`git -C … log -1`) |
| `~/mobilebench-upload/venv` | Python 3.12 venv with the pinned `server/requirements.txt` |
| `~/mobilebench-upload/bin/cloudflared` | Official cloudflared linux-amd64 installed by `install-cloudflared.sh` (SHA-256 checked against the GitHub release API digest) |
| `~/mobilebench-upload/config.toml` | Optional overrides (see `server/deploy/config.example.toml`) |
| `~/mobilebench-upload/var/relay.sqlite3` | Sessions, uploads, publication slots (WAL, `synchronous=FULL`) |
| `~/mobilebench-upload/var/spool/` | Retained ZIPs: stored partials and unpublished complete reports (`<session>/<upload>.zip`), `incoming/` temp parts |
| `~/mobilebench-upload/var/service-instance-id` | Public `serviceInstanceId` (**back it up; do not delete**; clients pin it) |
| `~/mobilebench-upload/var/address-salt` | Salt for hashed client addresses |
| `~/mobilebench-upload/logs/` | `api.log`, `worker.log` (rotating 10 MB × 5), `*.out` console logs (rotated at 20 MB on restart) |
| `~/mobilebench-upload/run/endpoint.txt` | Active public URL (optionally copied to `$MOBILEBENCH_ENDPOINT_COPY`) |
| `~/mobilebench-upload/hf-home` | `HF_HOME` for the worker (isolated cache) |
| `~/.config/mobile-benchmark-upload/hf-token` | HF credential (0600, directory 0700) |

Nothing here is in Git. Logs never contain session secrets, the HF token, or signed URLs. Hub
errors are reduced to `Hub HTTP <status>` or the exception type.

## First deployment

```sh
repo=<path of a checkout of this repository>
mkdir -p -m 700 ~/mobilebench-upload && python3.12 -m venv ~/mobilebench-upload/venv
~/mobilebench-upload/venv/bin/pip install -r "$repo/server/requirements.txt"
"$repo/server/deploy/install-cloudflared.sh"          # optional tag argument, default latest release
# Write the HF token to ~/.config/mobile-benchmark-upload/hf-token (0600) without printing it,
# e.g. from huggingface_hub.get_token() in a Python process that writes the file itself.
cp "$repo/server/deploy/config.example.toml" ~/mobilebench-upload/config.toml   # optional
"$repo/server/deploy/deploy.sh" <revision>            # worktree checkout → pip → pytest → start
```

A fresh `var/` directory creates a fresh `serviceInstanceId` and an empty queue. To run a
second, independent relay next to an existing one, use separate state, a separate
`index_repo_name` and a separate bootstrap file, so clients of one never upload to the other.

## Operating

```sh
cd ~/mobilebench-upload/src
server/deploy/status.sh            # windows, deployed commit, endpoint, local+public health, readiness, disk, log tails
server/deploy/start.sh             # idempotent; (re)creates any missing window
server/deploy/restart.sh           # restart api+worker; the tunnel and its URL are untouched
server/deploy/restart.sh tunnel    # NEW public URL; update the bootstrap JSON afterwards
server/deploy/stop.sh              # stop everything; stored and pending uploads stay on disk
tmux attach -t mobilebench-relay   # watch live (detach with Ctrl-b d; do not Ctrl-c)
tail -f ~/mobilebench-upload/logs/worker.log
```

Deploy a new commit after it is committed in the repository checkout:

```sh
<repo>/server/deploy/deploy.sh <revision>   # checkout → pip install → pytest → restart api+worker
```

Live completion-gate verification (creates a public **synthetic-test** dataset once the
synthetic report reaches 120/120; partial snapshots stay on the relay):

```sh
cd ~/mobilebench-upload/src && ../venv/bin/python -m server.e2e_check \
  --base-url "$(cat ~/mobilebench-upload/run/endpoint.txt)" \
  --restart-command "server/deploy/restart.sh api worker"
```

## Reboot persistence

Nothing is installed as a system service. After a reboot, run `server/deploy/start.sh`. The
tunnel then gets a new URL. The worker republishes `endpoint.json` in the index once that URL
passes a health check, but the GitHub bootstrap JSON must be updated by hand. A user crontab
`@reboot` entry running `start.sh` is an optional convenience.

## URL stability

The current URL is a **Cloudflare quick tunnel** (`*.trycloudflare.com`). It needs no
account. It changes whenever cloudflared restarts (manual restart, crash, health-watchdog
restart, reboot). It carries no uptime guarantee, and Cloudflare documents quick tunnels as
intended for testing. Mitigations: the APK resolves the URL from a bootstrap file and pins
`serviceInstanceId`, and the relay keeps `endpoint.json` in
`huggingface.co/datasets/vinyteles/mobilebench-index-v2` current.

For a **stable hostname**, one of the following is needed:

1. A Cloudflare account with a zone (domain). Create a named tunnel
   (`cloudflared tunnel login`, `cloudflared tunnel create mobilebench-relay`,
   `cloudflared tunnel route dns mobilebench-relay relay.<domain>`), then replace the
   `--url` quick tunnel in `tunnel.sh` with `cloudflared tunnel run mobilebench-relay`.
   Keep `--url http://127.0.0.1:18765`, or put the same ingress in its config. No inbound
   port is needed.
2. Or an inbound HTTPS port with a DNS name forwarded to the host. In that case add a TLS
   terminator (e.g. Caddy) on that port in front of 127.0.0.1:18765, and set
   `trusted_proxies`/`client_ip_header` accordingly.

## HF credential: rotation and expiry

- The file `~/.config/mobile-benchmark-upload/hf-token` holds only the token (`hf_…`) for
  account `vinyteles`. OAuth-issued credentials expire; check the expiry when installing one
  and renew before it.
- Replace it without printing it. Write the new value to a temp file in the same directory,
  `chmod 600` it, and `mv` it over the old file (atomic). The worker re-reads the file whenever
  its mtime/size/inode changes, so no restart is needed. It verifies `whoami == vinyteles`
  before use. A fine-grained token with write access to `vinyteles/mobilebench-*` datasets and
  repo creation is sufficient.
- If the credential is missing, expired or for the wrong account, `GET /v1/ready` shows
  `hubCredential: false` (503), and the worker logs `credential …` or `Hub HTTP 401`.
  Uploads are **still accepted** and wait in the spool, within the spool quotas, until a valid
  token is installed. Then complete reports publish automatically.

## Disk and retention

- The spool holds stored partials and unpublished complete reports. A blob is deleted only
  after its upload is `published`, or `superseded` by a newer retained snapshot of the same run
  that holds every one of its completed questions. The janitor also removes orphans (temp parts
  or unreferenced blobs) older than 2 h. **Stored and pending blobs are never deleted
  automatically**, so a run's most complete partial survives restarts and maintenance.
- Bounds: 1 GiB retained per session, 8 GiB retained in total, and at least 5 GiB free on the
  volume. Beyond these, uploads get `507` with `Retry-After`, and phones keep their outbox.
  The relay needs no GPU and does not load models.
- Database rows (metadata, ~2 KB each) are kept for audit. Older rows of finished sessions
  can be deleted manually later if needed.
- Limits are configurable in `~/mobilebench-upload/config.toml` (see
  `server/deploy/config.example.toml`), then `restart.sh`.

## Tests

```sh
cd <repo> && ~/mobilebench-upload/venv/bin/python -m pytest -q server/tests
```

The suite covers validation (paths, duplicates, bombs, CRC, protocol, fixture, catalog,
distinct completed fixture questions), API behavior (idempotent registration, session
isolation, duplicates, saved-progress fields, size/type/rate/quota errors, per-question upload
rates with status polling, trusted proxy, feed privacy, concurrency in one event loop), worker
behavior (no HF commit for 1/42/119-answer, stopped, errored or configuration partials; durable
partials across API/worker restart and janitor runs; coalescing to the most complete partial;
out-of-order and duplicate snapshots; all nine single-configuration profiles and legacy groups;
atomic hash-matched pairs; HF failure backoff; credential errors; pacing; separate repos; index
privacy), and `test_catalog_sync.py`, which parses `ModelCatalog.kt` so server constants and
profile mappings cannot drift from the APK.
