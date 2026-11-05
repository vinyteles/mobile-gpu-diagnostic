#!/usr/bin/env bash
# Restart selected windows (default: api worker). Restarting "tunnel" changes the public URL.
set -eu
for window in "${@:-api worker}"; do
    for name in $window; do tmux respawn-window -k -t "mobilebench-relay:$name"; echo "restarted $name"; done
done
