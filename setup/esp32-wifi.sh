#!/usr/bin/env bash
set -euo pipefail
umask 077
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
esp_cache="${ROS2_ANDROID_ESP32_CACHE:-$HOME/.cache/ros2-android-esp32}"
esp_transport="${ROS2_ANDROID_ESP32_TRANSPORT:-wifi}"
case "$esp_transport" in wifi|serial) ;; *) printf 'Unsupported transport\n' >&2; exit 2 ;; esac
esp_project="$esp_cache/firmware-project"
if [[ "$esp_transport" == serial ]]; then esp_project="$esp_cache/firmware-serial-project"; fi
esp_venv="$esp_cache/toolchain-venv"
if [[ ! -x "$esp_venv/bin/pio" ]]; then
    python3 -m venv "$esp_venv"
    "$esp_venv/bin/pip" install platformio esptool patchelf
fi
if [[ "$esp_transport" == wifi && ! -f "$project_root/device-logs/esp32-private/wifi_config.h" ]]; then
    printf 'Run setup/configure-esp32-wifi.py --agent-ip PHONE_IP first.\n' >&2
    exit 1
fi
mkdir -p "$esp_project/src" "$esp_project/include"
chmod 700 "$esp_project"
cp "$project_root/examples/esp32_wifi/platformio.ini" "$esp_project/platformio.ini"
cp "$project_root/examples/esp32_wifi/src/main.cpp" "$esp_project/src/main.cpp"
if [[ "$esp_transport" == wifi ]]; then
    cp "$project_root/device-logs/esp32-private/wifi_config.h" "$esp_project/include/wifi_config.h"
else
    esp_domain="${ROS2_ANDROID_ESP32_DOMAIN:-0}"
    if [[ ! "$esp_domain" =~ ^[0-9]{1,3}$ ]] || ((10#$esp_domain > 101)); then
        printf 'ROS2_ANDROID_ESP32_DOMAIN must be 0 to 101\n' >&2; exit 2
    fi
    sed -i 's/board_microros_transport = wifi/board_microros_transport = serial/' "$esp_project/platformio.ini"
    printf '\nbuild_flags = -DROS2_ANDROID_USB -DROS_DOMAIN=%d\n' "$((10#$esp_domain))" >> "$esp_project/platformio.ini"
fi
esp_core="$esp_cache/platformio"
mkdir -p "$esp_core"
if [[ ! -e "$esp_core/penv" ]]; then ln -s "$esp_venv" "$esp_core/penv"; fi
for shared in packages platforms; do
    if [[ ! -e "$esp_core/$shared" && -d "$HOME/.platformio/$shared" ]]; then
        ln -s "$HOME/.platformio/$shared" "$esp_core/$shared"
    fi
done
export PLATFORMIO_CORE_DIR="$esp_core"
# CMake also searches PATH parents; remove Linux ROS prefixes from MCU builds.
clean_path="$(python3 -c 'import os; print(":".join(p for p in os.environ["PATH"].split(":") if not p.startswith("/opt/ros/")))')"
export PATH="$esp_venv/bin:$clean_path"
unset PYTHONPATH AMENT_PREFIX_PATH CMAKE_PREFIX_PATH COLCON_PREFIX_PATH ROS_DISTRO RMW_IMPLEMENTATION
# Upstream micro-ROS builder assembles shell commands without quoting source paths.
# Work in a private cache path with no spaces; keep Wi-Fi credentials out of Git.
case "${1:-build}" in
    build) exec "$esp_venv/bin/pio" run -d "$esp_project" ;;
    upload) exec "$esp_venv/bin/pio" run -d "$esp_project" -t upload --upload-port "${2:-/dev/ttyUSB0}" ;;
    monitor) exec "$esp_venv/bin/pio" device monitor --port "${2:-/dev/ttyUSB0}" --baud 115200 ;;
    *) printf 'Usage: %s {build|upload|monitor} [serial-port]\n' "$0" >&2; exit 2 ;;
esac
