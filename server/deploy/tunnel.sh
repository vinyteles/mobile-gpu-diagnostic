#!/usr/bin/env bash
# Cloudflare quick tunnel to the loopback API. Writes the active https URL to the endpoint files
# and exits (so supervise.sh restarts it, with a NEW URL) if the public URL stops answering.
set -u
relay_home=${MOBILEBENCH_HOME:?}
api=http://127.0.0.1:18765
metrics=127.0.0.1:18766
endpoint=$relay_home/run/endpoint.txt
state_copy=$HOME/.local/state/mobile-upload-agent/endpoint.txt

# 1. Start the outbound-only tunnel; no Cloudflare account, DNS or inbound port is involved.
"$relay_home/bin/cloudflared" tunnel --no-autoupdate --metrics "$metrics" --url "$api" &
pid=$!
trap 'kill "$pid" 2>/dev/null; exit 0' TERM INT

# 2. Discover the assigned trycloudflare.com hostname from the local metrics server.
host=""
for _ in $(seq 1 90); do
    host=$(curl -fsS "http://$metrics/quicktunnel" 2>/dev/null | "$relay_home/venv/bin/python" -c 'import json,sys; print(json.load(sys.stdin).get("hostname",""))' 2>/dev/null)
    [ -n "$host" ] && break
    kill -0 "$pid" 2>/dev/null || exit 1
    sleep 2
done
[ -n "$host" ] || { echo "tunnel: no hostname assigned"; kill "$pid"; exit 1; }
url="https://$host"
for file in "$endpoint" "$state_copy"; do
    if [ -d "$(dirname "$file")" ]; then printf '%s\n' "$url" >"$file.tmp" && mv -f "$file.tmp" "$file"; fi
done
echo "$(date -u +%FT%TZ) tunnel: active endpoint $url"

# 3. Watch the public URL; after ~5 minutes of failures, exit so a fresh tunnel is created.
failures=0
while kill -0 "$pid" 2>/dev/null; do
    sleep 60
    if curl -fsS --max-time 20 "$url/v1/health" >/dev/null 2>&1; then
        failures=0
    else
        failures=$((failures + 1))
        echo "$(date -u +%FT%TZ) tunnel: public health check failed ($failures)"
        if [ "$failures" -ge 5 ]; then kill "$pid"; break; fi
    fi
done
wait "$pid"
exit 1
