#!/usr/bin/env bash
set -euo pipefail
# 1. Build each preset from the same source and signing key; native libraries are unchanged.
labels=(gpu-known gpu-new gemma lfm-cpu energy-cpu)
mkdir -p artifacts/batches-2.1
for batch in 1 2 3 4 5; do
    ./gradlew -PbenchmarkProfile="$batch" :app:assembleRelease --console=plain
    destination="artifacts/batches-2.1/benchmark-2.1-batch-${batch}-${labels[$((batch-1))]}-arm64.apk"
    cp app/build/outputs/apk/release/app-release.apk "$destination"
done
# 2. Record exact deliverable checksums after all five builds succeed.
(cd artifacts/batches-2.1 && sha256sum *.apk > SHA256SUMS)
