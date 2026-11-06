#!/usr/bin/env bash
set -euo pipefail
# 1. Build six new GPU cases; historical replay answers are not comparable.
mkdir -p artifacts/matrix-2.5
cases=(A B C D E F)
for profile in 10 11 12 13 14 15; do
    ./gradlew -PbenchmarkProfile="$profile" :app:assembleRelease --console=plain
    cp app/build/outputs/apk/release/app-release.apk \
        "artifacts/matrix-2.5/benchmark-2.5-case-${cases[$((profile-10))]}-arm64.apk"
done
# 2. Record exact APK checksums for the release and test reports.
(cd artifacts/matrix-2.5 && sha256sum *.apk > SHA256SUMS)
