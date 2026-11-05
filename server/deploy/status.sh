#!/usr/bin/env bash
# Print service, endpoint and recent log status (no secrets are ever logged).
home=${MOBILEBENCH_HOME:-$HOME/mobilebench-upload}
echo "== tmux"; tmux list-windows -t mobilebench-relay -F '#{window_name} pid=#{pane_pid} dead=#{pane_dead}' 2>/dev/null || echo "not running"
echo "== deployed commit"; git -C "$home/src" log -1 --format='%h %s' 2>/dev/null
echo "== endpoint"; cat "$home/run/endpoint.txt" 2>/dev/null || echo "none"
echo "== local health"; curl -fsS --max-time 5 http://127.0.0.1:18765/v1/health; echo
echo "== readiness"; curl -sS --max-time 5 http://127.0.0.1:18765/v1/ready; echo
url=$(cat "$home/run/endpoint.txt" 2>/dev/null)
if [ -n "$url" ]; then echo "== public health"; curl -fsS --max-time 15 "$url/v1/health" || echo "UNREACHABLE"; echo; fi
echo "== disk"; df -h "$home" | tail -1; du -sh "$home/var/spool" 2>/dev/null
for name in api worker; do echo "== $name.log (tail)"; tail -n 5 "$home/logs/$name.log" 2>/dev/null; done
echo "== tunnel.out (tail)"; grep -E 'tunnel:|ERR' "$home/logs/tunnel.out" 2>/dev/null | tail -n 5
