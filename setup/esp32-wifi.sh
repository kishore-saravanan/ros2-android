#!/usr/bin/env bash
set -euo pipefail
umask 077
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
esp_cache="${ROS2_ANDROID_ESP32_CACHE:-$HOME/.cache/ros2-android-esp32}"
esp_transport="${ROS2_ANDROID_ESP32_TRANSPORT:-wifi}"
case "$esp_transport" in
    wifi | serial) ;;
    *)
        printf 'Unsupported transport\n' >&2
        exit 2
        ;;
esac

esp_project="$esp_cache/firmware-project"

if [[ "$esp_transport" == serial ]]; then
    esp_project="$esp_cache/firmware-serial-project"
fi

platformio_ini="$esp_project/platformio.ini"
wifi_config="$project_root/device-logs/esp32-private/wifi_config.h"

esp_venv="$esp_cache/toolchain-venv"
if [[ ! -x "$esp_venv/bin/pio" ]]; then
    python3 -m venv "$esp_venv"
    "$esp_venv/bin/pip" install platformio esptool patchelf
fi

if [[ "$esp_transport" == wifi && ! -f "$wifi_config" ]]; then
    printf 'Run setup/configure-esp32-wifi.py --agent-ip PHONE_IP first.\n' >&2
    exit 1
fi

mkdir -p "$esp_project/src" "$esp_project/include"
chmod 700 "$esp_project"
cp "$project_root/examples/esp32_wifi/platformio.ini" "$platformio_ini"
cp "$project_root/examples/esp32_wifi/src/main.cpp" "$esp_project/src/main.cpp"
cp "$project_root/examples/esp32_wifi/include/"* "$esp_project/include/"

if [[ "${ROS2_ANDROID_ESP32_ROBOT:-0}" != 0 && "${ROS2_ANDROID_ESP32_ROBOT:-0}" != 1 ]]; then
    printf 'ROS2_ANDROID_ESP32_ROBOT must be 0 or 1\n' >&2
    exit 2
fi

if [[ "$esp_transport" == wifi ]]; then
    cp "$wifi_config" "$esp_project/include/wifi_config.h"
else
    esp_domain="${ROS2_ANDROID_ESP32_DOMAIN:-0}"
    if [[ ! "$esp_domain" =~ ^[0-9]{1,3}$ ]] || ((10#$esp_domain > 101)); then
        printf 'ROS2_ANDROID_ESP32_DOMAIN must be 0 to 101\n' >&2
        exit 2
    fi

    sed -i 's/board_microros_transport = wifi/board_microros_transport = serial/' "$platformio_ini"
    domain_flag="-DROS_DOMAIN=$((10#$esp_domain))"
    printf '\nbuild_flags = -DROS2_ANDROID_USB %s\n' "$domain_flag" >>"$platformio_ini"
fi

if [[ "${ROS2_ANDROID_ESP32_ROBOT:-0}" == 1 ]]; then
    if [[ "$esp_transport" == serial ]]; then
        sed -i 's/^build_flags = /build_flags = -DROS2_ANDROID_ROBOT /' "$platformio_ini"
    else
        printf '\nbuild_flags = -DROS2_ANDROID_ROBOT\n' >>"$platformio_ini"
    fi

    case "${ROS2_ANDROID_ESP32_FULL_PWM:-0}" in
        0) ;;
        1) sed -i 's/^build_flags = /build_flags = -DROS2_ANDROID_FULL_PWM=1 /' "$platformio_ini" ;;
        *)
            printf 'ROS2_ANDROID_ESP32_FULL_PWM must be 0 or 1\n' >&2
            exit 2
            ;;
    esac
fi

esp_core="$esp_cache/platformio"
mkdir -p "$esp_core"

if [[ ! -e "$esp_core/penv" ]]; then
    ln -s "$esp_venv" "$esp_core/penv"
fi

for shared in packages platforms; do
    if [[ ! -e "$esp_core/$shared" && -d "$HOME/.platformio/$shared" ]]; then
        ln -s "$HOME/.platformio/$shared" "$esp_core/$shared"
    fi
done

export PLATFORMIO_CORE_DIR="$esp_core"
# CMake also searches PATH parents; remove Linux ROS prefixes from MCU builds.
clean_path="$(python3 -c 'import os; print(":".join(p for p in os.environ["PATH"].split(":")
    if not p.startswith("/opt/ros/")))')"
export PATH="$esp_venv/bin:$clean_path"
unset PYTHONPATH AMENT_PREFIX_PATH CMAKE_PREFIX_PATH COLCON_PREFIX_PATH
unset ROS_DISTRO RMW_IMPLEMENTATION

# Upstream micro-ROS builder assembles shell commands without quoting source paths.
# Work in a private cache path with no spaces; keep Wi-Fi credentials out of Git.
case "${1:-build}" in
    build) exec "$esp_venv/bin/pio" run -d "$esp_project" ;;
    upload)
        exec "$esp_venv/bin/pio" run -d "$esp_project" -t upload --upload-port "${2:-/dev/ttyUSB0}"
        ;;
    monitor) exec "$esp_venv/bin/pio" device monitor --port "${2:-/dev/ttyUSB0}" --baud 115200 ;;
    *)
        printf 'Usage: %s {build|upload|monitor} [serial-port]\n' "$0" >&2
        exit 2
        ;;
esac
