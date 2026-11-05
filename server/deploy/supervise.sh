#!/usr/bin/env bash
# Run one service command forever, restarting it with backoff when it exits.
# Usage: supervise.sh <name> <command> [args...]   (output goes to $MOBILEBENCH_HOME/logs/<name>.out)
set -u
name=$1
shift
log="${MOBILEBENCH_HOME:?}/logs/$name.out"
delay=2
while true; do
    # 1. Rotate the console log at restart boundaries so it stays bounded.
    if [ -f "$log" ] && [ "$(stat -c %s "$log")" -gt 20000000 ]; then
        mv -f "$log" "$log.1"
    fi

    # 2. Run the service and record how it ended.
    started=$(date +%s)
    echo "$(date -u +%FT%TZ) supervise: starting $name" >>"$log"
    "$@" >>"$log" 2>&1
    code=$?
    echo "$(date -u +%FT%TZ) supervise: $name exited with status $code" >>"$log"

    # 3. Back off on crash loops; reset after a healthy minute.
    if [ $(( $(date +%s) - started )) -gt 60 ]; then delay=2; else delay=$(( delay * 2 > 60 ? 60 : delay * 2 )); fi
    sleep "$delay"
done
