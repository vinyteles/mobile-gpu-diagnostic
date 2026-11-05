#!/usr/bin/env bash
# Start the relay services in the tmux session "mobilebench-relay" (idempotent).
# Windows: api (loopback HTTP), worker (HF publisher), tunnel (public HTTPS). Each has its own restart loop.
set -eu
home=${MOBILEBENCH_HOME:-$HOME/mobilebench-upload}
src=$home/src
session=mobilebench-relay
environment="env MOBILEBENCH_HOME=$home HF_HOME=$home/hf-home HF_HUB_DISABLE_TELEMETRY=1 PYTHONUNBUFFERED=1"
if [ -f "$home/config.toml" ]; then environment="$environment MOBILEBENCH_CONFIG=$home/config.toml"; fi
supervise="$src/server/deploy/supervise.sh"

if tmux has-session -t "$session" 2>/dev/null; then
    echo "$session already running"
    exit 0
fi
tmux new-session -d -s "$session" -n api -c "$src" "$environment $supervise api $home/venv/bin/python -m server.api"
tmux new-window -t "$session" -n worker -c "$src" "$environment $supervise worker $home/venv/bin/python -m server.worker"
tmux new-window -t "$session" -n tunnel -c "$src" "$environment $supervise tunnel $src/server/deploy/tunnel.sh"
echo "started $session"
