#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
adb_bin="$sdk_root/platform-tools/adb"
apk="$project_root/android/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -x "$adb_bin" ]]; then
    printf 'ADB is missing. See docs/DEVELOPMENT.md.\n' >&2
    exit 1
fi
if [[ ! -f "$apk" ]]; then
    printf 'Build the app first with tools/build.sh.\n' >&2
    exit 1
fi
# -d selects the single physical device and refuses ambiguous multiple phones.
"$adb_bin" -d get-state
"$adb_bin" -d install -r "$apk"
"$adb_bin" -d shell am start -W -n dev.robotbrain.android/.MainActivity

