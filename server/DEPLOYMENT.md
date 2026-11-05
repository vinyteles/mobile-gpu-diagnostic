# Upload relay deployment (h100-eliomar)

This is the operator handoff for the relay running on `gpu-h100-3` (user `user_viniciusteles`).
API contract: [API.md](API.md).

## Architecture

```
phone ──HTTPS──▶ Cloudflare quick tunnel ──▶ cloudflared (outbound only) ──▶ 127.0.0.1:18765 API
                                                                              │ SQLite + fsynced spool
                                                                              ▼
                                         worker (only process holding the HF credential) ──▶ huggingface.co
```

- **api** (`python -m server.api`) validates and persists uploads. It never reads the HF
  credential.
- **worker** (`python -m server.worker`) publishes to HF with coalescing, ordering, backoff and
  restart recovery. It also maintains the public index dataset.
- **tunnel** (`server/deploy/tunnel.sh`) runs cloudflared and records the active URL. The
  script exits when the public URL fails health checks for about 5 minutes, and its supervisor
  then starts a fresh tunnel, which gets a **new URL**.

Each process runs under its own restart loop (`server/deploy/supervise.sh`, exponential
backoff up to 60 s) in one window of the tmux session **`mobilebench-relay`**. The tmux
server is independent of SSH and of the Claude session, so disconnects do not stop it.

## Layout (outside Git)

| Path | Contents |
|---|---|
| `~/mobilebench-upload/src` | Detached git worktree at the deployed commit (`git -C … log -1`) |
| `~/mobilebench-upload/venv` | Python 3.12 venv (uv-managed interpreter), pinned `server/requirements.txt` |
| `~/mobilebench-upload/bin/cloudflared` | cloudflared 2026.10.7 linux-amd64, SHA-256 `1f99fb518b83dec2ba8d870a7cc991d82a77e6196295a3d2754ba17ab9f91872` (checked against the GitHub release asset digest and release notes) |
| `~/mobilebench-upload/var/relay.sqlite3` | Sessions, uploads, publication slots (WAL, `synchronous=FULL`) |
| `~/mobilebench-upload/var/spool/` | Accepted ZIPs not yet published (`<session>/<upload>.zip`), `incoming/` temp parts |
| `~/mobilebench-upload/var/service-instance-id` | Public `serviceInstanceId` (**back it up; do not delete**; clients pin it) |
| `~/mobilebench-upload/var/address-salt` | Salt for hashed client addresses |
| `~/mobilebench-upload/logs/` | `api.log`, `worker.log` (rotating 10 MB × 5), `*.out` console logs (rotated at 20 MB on restart), `boot.log` |
| `~/mobilebench-upload/run/endpoint.txt` | Active public URL (also copied to `~/.local/state/mobile-upload-agent/endpoint.txt`) |
| `~/mobilebench-upload/hf-home` | `HF_HOME` for the worker (isolated cache) |
| `~/.config/mobile-benchmark-upload/hf-token` | HF credential (0600, directory 0700) |

Nothing here contains secrets in Git. Logs never contain session secrets, the HF token, or
signed URLs. Hub errors are reduced to `Hub HTTP <status>` or the exception type.

## Operating

```sh
cd ~/mobilebench-upload/src
server/deploy/status.sh            # windows, deployed commit, endpoint, local+public health, readiness, disk, log tails
server/deploy/start.sh             # idempotent; (re)creates any missing window
server/deploy/restart.sh           # restart api+worker; the tunnel and its URL are untouched
server/deploy/restart.sh tunnel    # NEW public URL; update the bootstrap JSON afterwards
server/deploy/stop.sh              # stop everything; pending uploads stay queued on disk
tmux attach -t mobilebench-relay   # watch live (detach with Ctrl-b d; do not Ctrl-c)
tail -f ~/mobilebench-upload/logs/worker.log
```

Deploy a new commit after it is committed in `~/mobile-gpu-diagnostic`:

```sh
~/mobile-gpu-diagnostic/server/deploy/deploy.sh <revision>   # checkout → pip install → pytest → restart api+worker
```

Quick live verification (creates a public **synthetic-test** dataset):

```sh
cd ~/mobilebench-upload/src && ../venv/bin/python -m server.e2e_check \
  --base-url "$(cat ~/mobilebench-upload/run/endpoint.txt)" --zip ~/.local/state/mobile-upload-agent/examples/report.zip
```

## Reboot persistence

A user crontab entry (`crontab -l`) runs `start.sh` 30 s after boot. It is **configured but not
reboot-tested**: the VM was not rebooted. After a reboot the tunnel gets a new URL. The worker
republishes `endpoint.json` in the index once that URL passes a health check, but the GitHub
bootstrap JSON must be updated by hand. systemd user units were not used because lingering
is disabled for this account (`loginctl show-user` → `Linger=no`), and enabling it needs admin
rights.

## URL stability

The current URL is a **Cloudflare quick tunnel** (`*.trycloudflare.com`). It needs no
account. It changes whenever cloudflared restarts (manual restart, crash, health-watchdog
restart, reboot). It carries no uptime guarantee, and Cloudflare documents quick tunnels as
intended for testing. Mitigations: the APK resolves the URL from a bootstrap file and pins
`serviceInstanceId`, and the relay keeps `endpoint.json` in
`huggingface.co/datasets/vinyteles/mobilebench-index` current.

For a **stable hostname**, one of the following is needed (none is available on this VM
today):

1. A Cloudflare account with a zone (domain) Vinicius controls. Create a named tunnel
   (`cloudflared tunnel login` in a browser, `cloudflared tunnel create mobilebench-relay`,
   `cloudflared tunnel route dns mobilebench-relay relay.<domain>`), then replace the
   `--url` quick tunnel in `tunnel.sh` with `cloudflared tunnel run mobilebench-relay`.
   Keep `--url http://127.0.0.1:18765`, or put the same ingress in its config. No inbound
   port is needed.
2. Or an inbound HTTPS port with a DNS name forwarded to this VM (NAT admin). In that case
   add a TLS terminator (e.g. Caddy) on that port in front of 127.0.0.1:18765, and set
   `trusted_proxies`/`client_ip_header` accordingly.

## HF credential: rotation and expiry

- The file `~/.config/mobile-benchmark-upload/hf-token` holds only the token (`hf_…`) for
  account `vinyteles`. It is an OAuth credential that **expires 2026-11-26T20:25:49Z**.
- Replace it before then without printing it. Write the new value to a temp file in the
  same directory, `chmod 600` it, and `mv` it over the old file (atomic). The worker re-reads
  the file whenever its mtime/size/inode changes, so no restart is needed. It verifies
  `whoami == vinyteles` before use. A fine-grained token with write access to
  `vinyteles/mobilebench-*` datasets and repo creation is sufficient.
- If the credential is missing, expired or for the wrong account, `GET /v1/ready` shows
  `hubCredential: false` (503), and the worker logs `credential …` or `Hub HTTP 401`.
  Uploads are **still accepted** and wait in the spool, within the spool quotas, until a valid
  token is installed. Then they publish automatically.
- Reminder: check `GET /v1/ready` and renew by **2026-11-24**.

## Disk and retention

- The spool holds only unpublished ZIPs. A blob is deleted right after its upload is marked
  `published` or `superseded` in the database. The janitor also removes orphans (temp parts or
  unreferenced blobs) older than 2 h. **Pending blobs are never deleted automatically.**
- Bounds: 384 MiB pending per session, 3 GiB pending in total, and at least 5 GiB free on `/`.
  Beyond these, uploads get `507` with `Retry-After`, and phones keep their outbox. The VM
  had about 24 GB free at deployment. The relay needs no GPU and does not touch other jobs.
- Database rows (metadata, ~2 KB each) are kept for audit. Older rows of finished sessions
  can be deleted manually later if needed.
- Limits are configurable in `~/mobilebench-upload/config.toml` (see
  `server/deploy/config.example.toml`), then `restart.sh`.

## Tests

```sh
cd ~/mobile-gpu-diagnostic && ~/mobilebench-upload/venv/bin/python -m pytest -q server/tests
```

The suite covers validation (paths, duplicates, bombs, CRC, protocol, fixture, catalog),
API behavior (idempotent registration, session isolation, duplicates, size/type/rate/quota
errors, trusted proxy, feed privacy, concurrency in one event loop), worker behavior (atomic
pairs, coalescing, out-of-order snapshots, configuration snapshots, multi-run latest, HF
failure backoff, credential errors, restart recovery, terminal pacing, separate repos, index
privacy), and `test_catalog_sync.py`, which parses `ModelCatalog.kt` so server constants
cannot drift from the APK.
