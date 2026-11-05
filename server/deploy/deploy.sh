#!/usr/bin/env bash
# Deploy a commit of this repository into $MOBILEBENCH_HOME/src (a detached git worktree),
# install pinned dependencies, run the tests, and restart api+worker (the tunnel keeps its URL).
# Usage: server/deploy/deploy.sh [git-revision]   (default HEAD)
set -eu
relay_home=${MOBILEBENCH_HOME:-$HOME/mobilebench-upload}
repo=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
revision=$(git -C "$repo" rev-parse "${1:-HEAD}")

# 1. Check out the exact commit; the service never runs from an editable working tree.
if [ -d "$relay_home/src" ]; then
    git -C "$relay_home/src" checkout -q --detach "$revision"
else
    git -C "$repo" worktree add -q --detach "$relay_home/src" "$revision"
fi

# 2. Install dependencies and verify before switching traffic.
"$relay_home/venv/bin/pip" install -q -r "$relay_home/src/server/requirements.txt"
(cd "$relay_home/src" && "$relay_home/venv/bin/python" -m pytest -q -p no:cacheprovider server/tests)

# 3. Start or restart services.
if tmux has-session -t mobilebench-relay 2>/dev/null; then
    "$relay_home/src/server/deploy/restart.sh" api worker
else
    "$relay_home/src/server/deploy/start.sh"
fi
echo "deployed $(git -C "$relay_home/src" log -1 --format='%h %s')"
