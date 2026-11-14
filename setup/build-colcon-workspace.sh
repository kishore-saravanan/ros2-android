#!/usr/bin/env bash
set -euo pipefail
workspace_source="${1:?Workspace source directory}"
workspace_cache="${2:?Workspace output cache}"
workspace_target="${3:?host or android}"
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
colcon_bin="${COLCON_BIN:-$HOME/.local/share/ros2-android-tools/build-venv/bin/colcon}"
set +u
source "${ROBOT_ROS_SETUP:-/opt/ros/jazzy/setup.bash}"
set -u
mkdir -p "$workspace_cache"
cd "$workspace_cache"

if [[ "$workspace_target" == host ]]; then
    exec "$colcon_bin" --log-base log build --merge-install --base-paths "$workspace_source/src" \
        --build-base build --install-base install --event-handlers console_direct+ \
        --cmake-args -DPython3_EXECUTABLE=/usr/bin/python3 -DBUILD_TESTING=OFF
fi

[[ "$workspace_target" == android ]] || {
    printf 'Use host or android\n' >&2
    exit 2
}

workspace_sdk="$(readlink -f "$project_root/native-deps/install")"
export AMENT_PREFIX_PATH="$workspace_sdk:${AMENT_PREFIX_PATH:-}"
export CMAKE_PREFIX_PATH="$workspace_sdk:${CMAKE_PREFIX_PATH:-}"
export RMW_IMPLEMENTATION=rmw_cyclonedds_cpp
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
workspace_python_args=(-DROS2_ANDROID_WITH_PYTHON=OFF)
workspace_prefixes="$workspace_sdk"

if [[ "${4:-false}" == true ]]; then
    workspace_python="$(readlink -f "$project_root/native-deps/python-install")"
    workspace_python_target="$(readlink -f "$project_root/native-deps/python-target")"
    workspace_prefixes="$workspace_python;$workspace_sdk"
    export AMENT_PREFIX_PATH="$workspace_python:$AMENT_PREFIX_PATH"
    workspace_python_args=(-DROS2_ANDROID_WITH_PYTHON=ON
        "-DPython3_INCLUDE_DIR=$workspace_python_target/include/python3.12"
        "-DPython3_LIBRARY=$workspace_python_target/jniLibs/arm64-v8a/libpython3.12.so"
        -DPython3_NumPy_INCLUDE_DIR=/usr/lib/python3/dist-packages/numpy/core/include)
fi

exec "$colcon_bin" --log-base log build --merge-install --base-paths "$workspace_source/src" \
    --build-base build --install-base install --executor sequential \
    --event-handlers console_direct+ \
    --cmake-args -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$sdk_root/ndk/27.2.12479018/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF \
    -DPython3_EXECUTABLE=/usr/bin/python3 -DPYTHON_EXECUTABLE=/usr/bin/python3 \
    -DCMAKE_PREFIX_PATH="$workspace_prefixes" "${workspace_python_args[@]}" \
    -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH \
    -DSTATIC_ROSIDL_TYPESUPPORT_C=rosidl_typesupport_introspection_c \
    -DSTATIC_ROSIDL_TYPESUPPORT_CPP=rosidl_typesupport_introspection_cpp \
    -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384,-z,common-page-size=16384 \
    -DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384,-z,common-page-size=16384
