#!/usr/bin/env bash
set -euo pipefail
project_root="${1:?Pass an absolute ROS2 Android project directory}"
project_id="${2:?Pass the validated project ID}"
if [[ ! "$project_id" =~ ^[a-z][a-z0-9_]{0,63}$ ]]; then
    printf 'Invalid project ID\n' >&2
    exit 2
fi

if [[ ! -d "$project_root/interfaces" ]]; then
    printf 'Project has no interfaces directory\n' >&2
    exit 1
fi

host_workspace="$HOME/.cache/ros2-android-projects/$project_id/host"
colcon_bin="${COLCON_BIN:-$HOME/.local/share/ros2-android-tools/build-venv/bin/colcon}"
ros_setup="${ROBOT_ROS_SETUP:-/opt/ros/jazzy/setup.bash}"
set +u
source "$ros_setup"
set -u
mkdir -p "$host_workspace"
"$colcon_bin" --log-base "$host_workspace/log" build --base-paths "$project_root/interfaces" \
    --merge-install --build-base "$host_workspace/build" --install-base "$host_workspace/install" \
    --cmake-args -DBUILD_TESTING=OFF \
    -DPython3_EXECUTABLE=/usr/bin/python3 -DPYTHON_EXECUTABLE=/usr/bin/python3
printf 'PC interface overlay: %s/install/local_setup.bash\n' "$host_workspace"
