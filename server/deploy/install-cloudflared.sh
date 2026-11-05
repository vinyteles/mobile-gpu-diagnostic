#!/usr/bin/env bash
# Install the official cloudflared linux-amd64 binary into $MOBILEBENCH_HOME/bin, verified against the
# SHA-256 digest that the GitHub release API publishes for that asset (no mirrors).
# Usage: server/deploy/install-cloudflared.sh [release-tag]   (default: latest release)
set -eu
relay_home=${MOBILEBENCH_HOME:-$HOME/mobilebench-upload}
tag=${1:-latest}
asset=cloudflared-linux-amd64
api=https://api.github.com/repos/cloudflare/cloudflared/releases
if [ "$tag" = latest ]; then url=$api/latest; else url=$api/tags/$tag; fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# 1. Read the release metadata: exact tag, download URL and the published digest of the asset.
curl -fsSL "$url" -o "$work/release.json"
read -r tag download expected < <(python3 - "$work/release.json" "$asset" <<'PY'
import json, sys
release = json.load(open(sys.argv[1]))
item = next(a for a in release["assets"] if a["name"] == sys.argv[2])
digest = item.get("digest") or ""
if not digest.startswith("sha256:"):
    sys.exit("release API did not publish a sha256 digest for the asset")
print(release["tag_name"], item["browser_download_url"], digest.removeprefix("sha256:"))
PY
)
case $download in https://github.com/cloudflare/cloudflared/releases/download/*) ;; *) echo "unexpected URL $download"; exit 1 ;; esac

# 2. Download, verify and install atomically.
curl -fsSL "$download" -o "$work/$asset"
actual=$(sha256sum "$work/$asset" | cut -d' ' -f1)
[ "$actual" = "$expected" ] || { echo "digest mismatch: $actual != $expected"; exit 1; }
mkdir -p -m 700 "$relay_home/bin"
install -m 0755 "$work/$asset" "$relay_home/bin/cloudflared.new"
mv -f "$relay_home/bin/cloudflared.new" "$relay_home/bin/cloudflared"
echo "installed cloudflared $tag sha256 $actual"
"$relay_home/bin/cloudflared" --version
