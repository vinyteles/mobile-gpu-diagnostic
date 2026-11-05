#!/usr/bin/env bash
# Stop every relay service. Accepted uploads stay in the spool/database and resume on start.
# Note: restarting the tunnel produces a new public URL.
tmux kill-session -t mobilebench-relay 2>/dev/null && echo "stopped" || echo "not running"
