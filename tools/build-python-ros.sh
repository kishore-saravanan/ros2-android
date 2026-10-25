#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
python3 "$project_root/tools/prepare-python-target.py"
sdk_root="${ANDROID_HOME:-$HOME/Android/Sdk}"
colcon_bin="${COLCON_BIN:-$HOME/.local/share/ros2-android-tools/build-venv/bin/colcon}"
export PATH="$(dirname -- "$colcon_bin"):$PATH"
set +u
source /opt/ros/jazzy/setup.bash
set -u
export RMW_IMPLEMENTATION=rmw_cyclonedds_cpp RCL_LOGGING_IMPLEMENTATION=rcl_logging_noop
export STATIC_ROSIDL_TYPESUPPORT_C=rosidl_typesupport_introspection_c
export STATIC_ROSIDL_TYPESUPPORT_CPP=rosidl_typesupport_introspection_cpp
cd "$project_root/native-deps"
python_target="$PWD/python-target"
# Copy only header-only pybind11, never the host C library include directory.
mkdir -p "$python_target/cxx-include"
cp -a /usr/include/pybind11 "$python_target/cxx-include/"
export PKG_CONFIG_LIBDIR="$PWD/python-install/lib/pkgconfig"
"$colcon_bin" --log-base python-log build --merge-install --base-paths src "$project_root/examples/python_talker/interfaces" \
    --build-base python-build --install-base python-install \
    --packages-up-to rclpy sensor_msgs std_srvs rmw_cyclonedds_cpp demo_interfaces \
    --packages-skip rcl_logging_spdlog test_msgs \
    --executor sequential --event-handlers console_direct+ \
    --cmake-args -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$sdk_root/ndk/27.2.12479018/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF -DBUILD_SHARED_LIBS=ON \
    -DPython3_EXECUTABLE=/usr/bin/python3 -DPYTHON_EXECUTABLE=/usr/bin/python3 \
    -DPython3_INCLUDE_DIR="$python_target/include/python3.12" \
    -DPython3_LIBRARY="$python_target/jniLibs/arm64-v8a/libpython3.12.so" \
    -DROS2_ANDROID_PYBIND11_INCLUDE="$python_target/cxx-include" \
    -DPython3_NumPy_INCLUDE_DIR=/usr/lib/python3/dist-packages/numpy/core/include \
    -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH -DROS2_ANDROID_WITH_PYTHON=ON \
    -DRMW_IMPLEMENTATION=rmw_cyclonedds_cpp -DRMW_IMPLEMENTATION_DISABLE_RUNTIME_SELECTION=ON \
    -DRCL_LOGGING_IMPLEMENTATION=rcl_logging_noop -DTRACETOOLS_DISABLED=ON \
    -DENABLE_SECURITY=OFF -DENABLE_SSL=OFF -DBUILD_IDLC=OFF -DBUILD_DDSPERF=OFF \
    -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384

python3 "$project_root/tools/stage-python-ros.py"
