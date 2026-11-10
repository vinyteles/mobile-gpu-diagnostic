#!/usr/bin/env bash
set -euo pipefail
# 1. Build sequentially, with bounded Gradle/native workers on the 16 GB workstation.
export JAVA_HOME="${JAVA_HOME:-/home/vinicius/.local/opt/jdk17}"
export CMAKE_BUILD_PARALLEL_LEVEL=2
mkdir -p artifacts/v3
for profile in 16 17 18 19 20 21 22 23; do
    ./gradlew --no-daemon --max-workers=2 -PbenchmarkProfile="$profile" :app:assembleRelease --console=plain
    cp app/build/outputs/apk/release/app-release.apk "artifacts/v3/benchmark-v3-config-$((profile + 1))-arm64.apk"
done
# 2. Pin the delivered bytes after every build succeeds.
(cd artifacts/v3 && sha256sum ./*.apk > SHA256SUMS)
