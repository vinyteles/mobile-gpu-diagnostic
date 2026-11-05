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

# Create the session and any missing window; running windows are left untouched.
if ! tmux has-session -t "$session" 2>/dev/null; then
    tmux new-session -d -s "$session" -n api -c "$src" "$environment $supervise api $home/venv/bin/python -m server.api"
    echo "started api"
fi
windows=$(tmux list-windows -t "$session" -F '#{window_name}')
for name in api worker tunnel; do
    echo "$windows" | grep -qx "$name" && continue
    case $name in
        api) command="$supervise api $home/venv/bin/python -m server.api" ;;
        worker) command="$supervise worker $home/venv/bin/python -m server.worker" ;;
        tunnel) command="$supervise tunnel $src/server/deploy/tunnel.sh" ;;
    esac
    tmux new-window -d -t "$session" -n "$name" -c "$src" "$environment $command"
    echo "started $name"
done
