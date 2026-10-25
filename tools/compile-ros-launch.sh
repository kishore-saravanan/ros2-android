#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
set +u
source "${ROBOT_ROS_SETUP:-/opt/ros/jazzy/setup.bash}"
set -u
exec /usr/bin/python3 "$project_root/tools/compile_ros_launch.py" "$@"
