#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ros_setup="${ROBOT_ROS_SETUP:-/opt/ros/jazzy/setup.bash}"
if [[ ! -f "$ros_setup" ]]; then
    printf 'ROS setup file is missing: %s\n' "$ros_setup" >&2
    printf 'Set ROBOT_ROS_SETUP to your ROS setup.bash.\n' >&2
    exit 1
fi

set +u
source "$ros_setup"
set -u
exec python3 "$project_root/tools/pc-camera-viewer.py" "$@"
