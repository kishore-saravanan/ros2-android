#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
adb_bin="$sdk_root/platform-tools/adb"
apk="$project_root/android/app/build/outputs/apk/debug/app-debug.apk"
app_package="dev.ros2android.android"
activity="dev.ros2android.android.MainActivity"
install_only=false
bundle_manifest=""
custom_apk=false
selection=(-d)
while [[ $# -gt 0 ]]; do
    case "$1" in
        --runner|--python-runner|--workspace-runner)
            app_package="${2:?Missing generated runner application ID}"
            if [[ ! "$app_package" =~ ^dev\.ros2android\.android\.runner\.[a-z][a-z0-9_]*$ ]]; then
                printf 'Refusing application ID outside this project runner namespace.\n' >&2; exit 2
            fi
            if [[ "$1" == --workspace-runner ]]; then
                apk="$project_root/android/workspaceRunner/build/outputs/apk/debug/workspaceRunner-debug.apk"
            elif [[ "$1" == --python-runner ]]; then
                apk="$project_root/android/pythonRunner/build/outputs/apk/debug/pythonRunner-debug.apk"
            else
                apk="$project_root/android/runner/build/outputs/apk/debug/runner-debug.apk"
            fi
            activity="dev.ros2android.runner.MainActivity"
            shift 2 ;;
        --apk) apk="${2:?Missing APK path}"; custom_apk=true; shift 2 ;;
        --bundle-manifest) bundle_manifest="${2:?Missing bundle manifest}"; shift 2 ;;
        --install-only) install_only=true; shift ;;
        --serial) selection=(-s "${2:?Missing ADB serial}"); shift 2 ;;
        *) printf 'Usage: %s [--runner APP_ID | --python-runner APP_ID | --workspace-runner APP_ID] [--install-only] [--serial SERIAL] [--apk APK --bundle-manifest JSON]\n' "$0" >&2; exit 2 ;;
    esac
done
if [[ ! -x "$adb_bin" ]]; then
    adb_bin="$(command -v adb || true)"
fi
if [[ ! -x "$adb_bin" ]]; then
    printf 'ADB is missing. See docs/DEVELOPMENT.md.\n' >&2
    exit 1
fi
if [[ ! -f "$apk" ]]; then
    printf 'Build the app first with setup/build.sh.\n' >&2
    exit 1
fi
# Packaged main APKs carry a build-time identity-checked checksum manifest.
if [[ -n "$bundle_manifest" ]]; then
    if [[ "$app_package" != dev.ros2android.android ]]; then
        printf 'Bundled artifacts are supported only for the main app.\n' >&2; exit 2
    fi
    python3 "$project_root/setup/verify-bundled-apk.py" "$apk" "$bundle_manifest"
else
    if [[ "$custom_apk" == true ]]; then
        printf 'A custom APK requires its bundle manifest.\n' >&2; exit 2
    fi
    aapt_bin="$sdk_root/build-tools/35.0.0/aapt"
    if ! "$aapt_bin" dump badging "$apk" | grep -F "package: name='$app_package'" > /dev/null; then
        printf 'APK identity check failed for %s. Rebuild with setup/build.sh.\n' "$app_package" >&2
        exit 1
    fi
fi
# -d selects the single physical device unless the developer explicitly selects a serial.
"$adb_bin" "${selection[@]}" get-state
if [[ -n "$bundle_manifest" ]]; then
    device_api="$("$adb_bin" "${selection[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
    device_abis="$("$adb_bin" "${selection[@]}" shell getprop ro.product.cpu.abilist | tr -d '\r')"
    if [[ ! "$device_api" =~ ^[0-9]+$ ]] || (( device_api < 26 )) || [[ ",$device_abis," != *,arm64-v8a,* ]]; then
        printf 'This APK requires Android 8.0/API 26 or newer and ARM64 support.\n' >&2; exit 1
    fi
fi
# Scope installation to the active Android user, including phones with protected profiles.
"$adb_bin" "${selection[@]}" install --user current -r "$apk"
if [[ "$install_only" == false ]]; then
    "$adb_bin" "${selection[@]}" shell am start -W -n "$app_package/$activity"
fi

