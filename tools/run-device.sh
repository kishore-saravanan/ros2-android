#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
adb_bin="$sdk_root/platform-tools/adb"
apk="$project_root/android/app/build/outputs/apk/debug/app-debug.apk"
app_package="dev.robotbrain.android"
activity="dev.robotbrain.android.MainActivity"
install_only=false
selection=(-d)
while [[ $# -gt 0 ]]; do
    case "$1" in
        --runner|--python-runner|--workspace-runner)
            app_package="${2:?Missing generated runner application ID}"
            if [[ ! "$app_package" =~ ^dev\.robotbrain\.android\.runner\.[a-z][a-z0-9_]*$ ]]; then
                printf 'Refusing application ID outside this project runner namespace.\n' >&2; exit 2
            fi
            if [[ "$1" == --workspace-runner ]]; then
                apk="$project_root/android/workspaceRunner/build/outputs/apk/debug/workspaceRunner-debug.apk"
            elif [[ "$1" == --python-runner ]]; then
                apk="$project_root/android/pythonRunner/build/outputs/apk/debug/pythonRunner-debug.apk"
            else
                apk="$project_root/android/runner/build/outputs/apk/debug/runner-debug.apk"
            fi
            activity="dev.robotbrain.runner.MainActivity"
            shift 2 ;;
        --install-only) install_only=true; shift ;;
        --serial) selection=(-s "${2:?Missing ADB serial}"); shift 2 ;;
        *) printf 'Usage: %s [--runner APP_ID | --python-runner APP_ID | --workspace-runner APP_ID] [--install-only] [--serial SERIAL]\n' "$0" >&2; exit 2 ;;
    esac
done
if [[ ! -x "$adb_bin" ]]; then
    printf 'ADB is missing. See docs/DEVELOPMENT.md.\n' >&2
    exit 1
fi
if [[ ! -f "$apk" ]]; then
    printf 'Build the app first with tools/build.sh.\n' >&2
    exit 1
fi
# Verify the APK identity before touching the selected project application.
aapt_bin="$sdk_root/build-tools/35.0.0/aapt"
"$aapt_bin" dump badging "$apk" | rg --fixed-strings "package: name='$app_package'" > /dev/null
# -d selects the single physical device unless the developer explicitly selects a serial.
"$adb_bin" "${selection[@]}" get-state
"$adb_bin" "${selection[@]}" install -r "$apk"
if [[ "$install_only" == false ]]; then
    "$adb_bin" "${selection[@]}" shell am start -W -n "$app_package/$activity"
fi

