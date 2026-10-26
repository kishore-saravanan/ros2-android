#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
agent_cache="${ROS2_ANDROID_AGENT_CACHE:-$HOME/.cache/ros2-android-esp32}"
agent_source="$agent_cache/sources/Micro-XRCE-DDS-Agent"
agent_build="$agent_cache/agent-android"
agent_install="$agent_cache/agent-android-install"
ndk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}/ndk/27.2.12479018"
mkdir -p "$agent_cache/sources"
if [[ ! -d "$agent_source/.git" ]]; then
    git clone --depth 1 --branch v2.4.3 https://github.com/eProsima/Micro-XRCE-DDS-Agent.git "$agent_source"
fi
# Force target TinyXML2; host /usr/include is not an Android dependency.
python3 - "$agent_source/cmake/SuperBuild.cmake" <<'PY_PATCH'
from pathlib import Path
import sys
p = Path(sys.argv[1]); text = p.read_text()
if '-DTHIRDPARTY_TinyXML2:STRING=FORCE' not in text:
    text = text.replace('-DTHIRDPARTY:BOOL=ON', '-DTHIRDPARTY:BOOL=ON\n                -DTHIRDPARTY_TinyXML2:STRING=FORCE')
if '-DTINYXML2_INCLUDE_DIR:PATH=<SOURCE_DIR>/thirdparty/tinyxml2' not in text:
    text = text.replace('-DTHIRDPARTY_TinyXML2:STRING=FORCE', '-DTHIRDPARTY_TinyXML2:STRING=FORCE\n                -DTINYXML2_INCLUDE_DIR:PATH=<SOURCE_DIR>/thirdparty/tinyxml2')
if '-DANDROID_ABI:STRING=arm64-v8a' not in text:
    text = text.replace('set(CROSS_CMAKE_ARGS\n', 'set(CROSS_CMAKE_ARGS\n        -DANDROID_ABI:STRING=arm64-v8a\n        -DANDROID_PLATFORM:STRING=android-26\n')
if '-DANDROID_STL:STRING=c++_shared' not in text:
    text = text.replace('-DANDROID_ABI:STRING=arm64-v8a', '-DANDROID_STL:STRING=c++_shared\n        -DANDROID_ABI:STRING=arm64-v8a')
p.write_text(text)
PY_PATCH
# Foonathan caches generated container sizes even after an ABI change.
# Regenerate once for this target/runtime; stale ARM32 pool sizes fail at runtime.
agent_abi="arm64-v8a-api26-c++_shared-v1"
if [[ ! -f "$agent_build/.android-abi" ]] || [[ "$(cat "$agent_build/.android-abi")" != "$agent_abi" ]]; then
    python3 - "$agent_build" <<'PY_ABI'
from pathlib import Path
import sys
base = Path(sys.argv[1])
(base / 'foonathan_memory/src/foonathan_memory-build/src/container_node_sizes_impl.hpp').unlink(missing_ok=True)
for name in ('foonathan_memory', 'fastdds'):
    for step in ('configure', 'build', 'install', 'done', 'test'):
        (base / name / 'src' / (name + '-stamp') / (name + '-' + step)).unlink(missing_ok=True)
for step in ('configure', 'build', 'install', 'done'):
    (base / 'uagent-prefix/src/uagent-stamp' / ('uagent-' + step)).unlink(missing_ok=True)
PY_ABI
    # Existing installed packages can make the superbuild skip these projects.
    # Refresh their generated headers and consumers explicitly on ABI migration.
    if [[ -f "$agent_build/foonathan_memory/src/foonathan_memory/CMakeLists.txt" ]]; then
        cmake -S "$agent_build/foonathan_memory/src/foonathan_memory" \
            -B "$agent_build/foonathan_memory/src/foonathan_memory-build" \
            -DCMAKE_TOOLCHAIN_FILE="$ndk_root/build/cmake/android.toolchain.cmake" \
            -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared \
            -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$agent_build/temp_install/foonathan_memory" \
            -DFOONATHAN_MEMORY_BUILD_EXAMPLES=OFF -DFOONATHAN_MEMORY_BUILD_TESTS=OFF \
            -DFOONATHAN_MEMORY_BUILD_TOOLS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON
        cmake --build "$agent_build/foonathan_memory/src/foonathan_memory-build" --target install -j 8
    fi
    if [[ -f "$agent_build/fastdds/src/fastdds-build/CMakeCache.txt" ]]; then
        cmake --build "$agent_build/fastdds/src/fastdds-build" --target install -j 8
    fi
fi
cmake -S "$agent_source" -B "$agent_build" \
    -DCMAKE_TOOLCHAIN_FILE="$ndk_root/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared \
    -DCMAKE_ANDROID_ARCH_ABI=arm64-v8a -DCMAKE_SYSTEM_VERSION=26 \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$agent_install" \
    -DUAGENT_SUPERBUILD=ON -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY \
    -DCMAKE_FIND_ROOT_PATH="$agent_build/temp_install" \
    -Dfastcdr_DIR="$agent_build/temp_install/fastcdr-2.2.0/lib/cmake/fastcdr" \
    -Dfastrtps_DIR="$agent_build/temp_install/fastrtps-2.14/share/fastrtps/cmake" \
    -Dfoonathan_memory_DIR="$agent_build/temp_install/foonathan_memory/lib/foonathan_memory/cmake" \
    -Dspdlog_DIR="$agent_build/temp_install/spdlog-1.9.2/lib/cmake/spdlog" \
    -DCMAKE_PREFIX_PATH="$agent_build/temp_install/fastcdr-2.2.0;$agent_build/temp_install/fastrtps-2.14;$agent_build/temp_install/foonathan_memory;$agent_build/temp_install/spdlog-1.9.2" \
    -DUAGENT_CED_PROFILE=OFF -DUAGENT_P2P_PROFILE=OFF -DUAGENT_SOCKETCAN_PROFILE=OFF
cmake --build "$agent_build" -j "${CMAKE_BUILD_PARALLEL_LEVEL:-8}"
cmake --install "$agent_build"
python3 "$project_root/setup/stage-micro-ros-agent.py" "$agent_build" "$agent_install"
printf '%s\n' "$agent_abi" > "$agent_build/.android-abi"
