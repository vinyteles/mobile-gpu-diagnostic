#!/usr/bin/env bash
# Deploy a commit of this repository into $MOBILEBENCH_HOME/src (a detached git worktree),
# install pinned dependencies, run the tests, and restart api+worker (the tunnel keeps its URL).
# Usage: server/deploy/deploy.sh [git-revision]   (default HEAD)
set -eu
home=${MOBILEBENCH_HOME:-$HOME/mobilebench-upload}
repo=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
revision=$(git -C "$repo" rev-parse "${1:-HEAD}")

# 1. Check out the exact commit; the service never runs from an editable working tree.
if [ -d "$home/src" ]; then
    git -C "$home/src" checkout -q --detach "$revision"
else
    git -C "$repo" worktree add -q --detach "$home/src" "$revision"
fi

# 2. Install dependencies and verify before switching traffic.
"$home/venv/bin/pip" install -q -r "$home/src/server/requirements.txt"
(cd "$home/src" && "$home/venv/bin/python" -m pytest -q -p no:cacheprovider server/tests)

# 3. Start or restart services.
if tmux has-session -t mobilebench-relay 2>/dev/null; then
    "$home/src/server/deploy/restart.sh" api worker
else
    "$home/src/server/deploy/start.sh"
fi
echo "deployed $(git -C "$home/src" log -1 --format='%h %s')"
