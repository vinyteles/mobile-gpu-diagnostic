#!/usr/bin/env bash
set -euo pipefail
# 1. Build one configuration per APK from the same source and signing key.
labels=(energy-mixed48-gpu lfm-vulkan-gpu lfm-litert-gpu gemma-mobile-gpu gemma-q8-gpu lfm-cpu energy-int8-cpu energy-mixed48-cpu gemma-mobile-cpu)
mkdir -p artifacts/configs-2.4
for configuration in 1 2 3 4 5 6 7 8 9; do
    ./gradlew -PbenchmarkProfile="$configuration" :app:assembleRelease --console=plain
    destination="artifacts/configs-2.4/benchmark-2.4-config-${configuration}-${labels[$((configuration-1))]}-arm64.apk"
    cp app/build/outputs/apk/release/app-release.apk "$destination"
done
# 2. Record exact deliverable checksums after all nine builds succeed.
(cd artifacts/configs-2.4 && sha256sum *.apk > SHA256SUMS)
