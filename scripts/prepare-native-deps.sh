#!/usr/bin/env bash
# Fetch exact native source revisions and prepare the Vulkan header package.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_dir="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
cmake_bin="$sdk_dir/cmake/3.22.1/bin/cmake"

# 1. Fetch pinned sources without changing an existing checkout.
fetch_source() {
    local name="$1" url="$2" revision="$3" destination="$project_dir/third_party/$1"
    if [[ ! -d "$destination" ]]; then
        git init -q "$destination"
        git -C "$destination" remote add origin "$url"
        git -C "$destination" fetch --depth 1 origin "$revision"
        git -C "$destination" checkout --detach FETCH_HEAD
    fi
    [[ "$(git -C "$destination" rev-parse HEAD)" == "$revision" ]] || { echo "Unexpected revision in $destination" >&2; exit 1; }
    [[ -z "$(git -C "$destination" status --porcelain)" ]] || { echo "Modified source in $destination" >&2; exit 1; }
}
mkdir -p "$project_dir/third_party"
fetch_source llama.cpp https://github.com/ggml-org/llama.cpp.git 5851a8e505f4d5fbb183f2847698eb6155d176b7
fetch_source Vulkan-Headers https://github.com/KhronosGroup/Vulkan-Headers.git 9fbc2a782ee3f8a4db64e7d991e23da75e893f44
fetch_source SPIRV-Headers https://github.com/KhronosGroup/SPIRV-Headers.git 6efe5ae6b7c6ad29da3f2cc3f164b9c3d72b78b1

# 2. Install headers locally so the Android cross-build can resolve their CMake package.
"$cmake_bin" -S "$project_dir/third_party/SPIRV-Headers" -B "$project_dir/third_party/spirv-build" \
    -DSPIRV_HEADERS_ENABLE_TESTS=OFF -DCMAKE_INSTALL_PREFIX="$project_dir/third_party/spirv-install"
"$cmake_bin" --install "$project_dir/third_party/spirv-build"
python3 "$project_dir/scripts/prepare-tool-assets.py" --native-only
echo 'Native dependencies ready. Add SDK cmake/3.22.1/bin to PATH when invoking Gradle.'
