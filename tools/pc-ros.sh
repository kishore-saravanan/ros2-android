#!/usr/bin/env bash
set -euo pipefail
project_pc_interface=""

if [[ "${1:-}" == --interface ]]; then
    project_pc_interface="${2:?Missing PC interface IPv4}"
    shift 2
fi

if [[ "${1:-}" == -- ]]; then
    shift
fi

if [[ $# == 0 ]]; then
    printf 'Usage: %s [--interface IPv4] -- COMMAND [ARGS...]\n' "$0" >&2
    exit 2
fi

default_host_prefix="$HOME/.cache/ros2-android-host-runtime/root/opt/ros/jazzy"
project_host_prefix="${ROS2_ANDROID_HOST_PREFIX:-$default_host_prefix}"
ros_setup="${ROBOT_ROS_SETUP:-/opt/ros/jazzy/setup.bash}"
set +u
source "$ros_setup"

if [[ -f "$project_host_prefix/lib/librmw_cyclonedds_cpp.so" ]]; then
    export AMENT_PREFIX_PATH="$project_host_prefix:${AMENT_PREFIX_PATH:-}"
    host_libraries="$project_host_prefix/lib:$project_host_prefix/lib/x86_64-linux-gnu"
    export LD_LIBRARY_PATH="$host_libraries:${LD_LIBRARY_PATH:-}"
elif [[ ! -f /opt/ros/jazzy/lib/librmw_cyclonedds_cpp.so ]]; then
    printf 'Install the Cyclone DDS middleware the phone uses: %s\n' \
        'sudo apt install ros-jazzy-rmw-cyclonedds-cpp' >&2
    exit 1
fi

if [[ -n "${ROS2_ANDROID_PROJECT_OVERLAY:-}" && -f "$ROS2_ANDROID_PROJECT_OVERLAY" ]]; then
    source "$ROS2_ANDROID_PROJECT_OVERLAY"
fi

set -u
export RMW_IMPLEMENTATION=rmw_cyclonedds_cpp

if [[ -n "$project_pc_interface" ]]; then
    check_ipv4='import ipaddress, sys; print(ipaddress.IPv4Address(sys.argv[1]))'
    project_pc_interface="$(python3 -c "$check_ipv4" "$project_pc_interface")"
    interfaces="<Interfaces><NetworkInterface address=\"$project_pc_interface\"/></Interfaces>"
    discovery='<Discovery><ParticipantIndex>auto</ParticipantIndex></Discovery>'
    domain="<General>$interfaces</General>$discovery"
    export CYCLONEDDS_URI="<CycloneDDS><Domain>$domain</Domain></CycloneDDS>"
fi

exec "$@"
