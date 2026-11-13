#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_root="${ANDROID_HOME:-$HOME/Android/Sdk}"
colcon_bin="${COLCON_BIN:-$HOME/.local/share/ros2-android-tools/build-venv/bin/colcon}"
export PATH="$(dirname -- "$colcon_bin"):$PATH"
packages=(rcl sensor_msgs rmw_cyclonedds_cpp)
case "${1:-}" in
    --with-cpp) packages+=(rclcpp) ;;
    "") ;;
    *) printf 'Usage: %s [--with-cpp]\n' "$0" >&2; exit 2 ;;
esac
set +u
source /opt/ros/jazzy/setup.bash
set -u
export RMW_IMPLEMENTATION=rmw_cyclonedds_cpp
export RCL_LOGGING_IMPLEMENTATION=rcl_logging_noop
export STATIC_ROSIDL_TYPESUPPORT_C=rosidl_typesupport_introspection_c
export STATIC_ROSIDL_TYPESUPPORT_CPP=rosidl_typesupport_introspection_cpp
cd "$project_root/native-deps"
export PKG_CONFIG_LIBDIR="$PWD/install/lib/pkgconfig"
"$colcon_bin" build --merge-install --base-paths src \
    --packages-up-to "${packages[@]}" \
    --packages-ignore rcl_logging_spdlog test_msgs \
    --executor sequential --event-handlers console_direct+ --cmake-clean-cache \
    --cmake-args -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$sdk_root/ndk/27.2.12479018/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF -DBUILD_SHARED_LIBS=ON \
    -DPython3_EXECUTABLE=/usr/bin/python3 -DPYTHON_EXECUTABLE=/usr/bin/python3 \
    -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH \
    -DRMW_IMPLEMENTATION=rmw_cyclonedds_cpp \
    -DRMW_IMPLEMENTATION_DISABLE_RUNTIME_SELECTION=ON \
    -DRCL_LOGGING_IMPLEMENTATION=rcl_logging_noop \
    -DTRACETOOLS_DISABLED=ON -DENABLE_SECURITY=OFF -DENABLE_SSL=OFF \
    -DBUILD_IDLC=OFF -DBUILD_DDSPERF=OFF \
    -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384,-z,common-page-size=16384
python3 "$project_root/setup/stage-native-libs.py"
python3 "$project_root/setup/stage-licenses.py"
