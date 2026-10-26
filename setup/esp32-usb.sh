#!/usr/bin/env bash
set -euo pipefail
# Same example and pinned SDK, isolated serial build cache, no Wi-Fi credentials.
export ROS2_ANDROID_ESP32_TRANSPORT=serial
exec "$(dirname -- "${BASH_SOURCE[0]}")/esp32-wifi.sh" "$@"
