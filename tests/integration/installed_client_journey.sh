#!/usr/bin/env bash
# Installed-client journey without a phone: wheel -> managed build tools -> pinned sources
# -> native C++ SDK -> doctor -> external workspace APK. Every step logs to WORK/logs and the
# run stops at the first failure.
#
# Usage: tests/integration/installed_client_journey.sh WHEEL WORK_DIR
# Needs: ROS 2 Jazzy and host build tools, JDK 21 (JAVA_HOME), and ANDROID_HOME containing the
# pinned SDK packages. For an isolated run on a prepared machine, also set a fresh HOME and
# GRADLE_USER_HOME (Gradle otherwise uses the account's home, not $HOME). If the system Python
# lacks ensurepip, set PIP_ZIPAPP to pip's standalone pip.pyz.
set -euo pipefail
[[ $# == 2 ]] || { printf 'Usage: %s WHEEL WORK_DIR\n' "$0" >&2; exit 2; }
wheel="$(realpath "$1")"; work="$(realpath -m "$2")"
repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
[[ "$work" != *" "* ]] || { echo 'WORK_DIR must not contain spaces (upstream generators)' >&2; exit 2; }
mkdir -p "$work/logs"; summary="$work/logs/summary.txt"; : > "$summary"
step() {
  local name=$1; shift; local start; start=$(date +%s)
  if "$@" > "$work/logs/$name.log" 2>&1; then echo "ok     $name $(( $(date +%s) - start ))s" | tee -a "$summary"
  else echo "FAILED $name (log: $work/logs/$name.log)" | tee -a "$summary"; tail -20 "$work/logs/$name.log" >&2; exit 1; fi
}
fails() { ! "$@"; }
venv_with_pip() {  # venv_with_pip DIR [--system-site-packages]
  if [[ -n "${PIP_ZIPAPP:-}" ]]; then
    /usr/bin/python3 -m venv --without-pip "${@:2}" "$1" && "$1/bin/python" "$PIP_ZIPAPP" install -q --disable-pip-version-check pip
  else /usr/bin/python3 -m venv "${@:2}" "$1"; fi
}

step client-venv venv_with_pip "$work/client"
step client-install "$work/client/bin/python" -m pip install --disable-pip-version-check "$wheel"
export PATH="$work/client/bin:$PATH"
step version ros2-android --version
step doctor-host-before fails ros2-android doctor --profile host
managed="${HOME}/.local/share/ros2-android-tools/build-venv"
if [[ -n "${PIP_ZIPAPP:-}" && ! -x "$managed/bin/python" ]]; then step build-venv venv_with_pip "$managed" --system-site-packages; fi
step setup-build-tools ros2-android setup build-tools
step doctor-host ros2-android doctor --profile host
step setup-sources ros2-android setup sources
step setup-native ros2-android setup native --with-cpp
step doctor-android ros2-android doctor --profile android
rm -rf "$work/external_ws"; cp -r "$repo/examples/android_test_ws" "$work/external_ws"
rm -rf "$work/external_ws/build" "$work/external_ws/install" "$work/external_ws/log"
step ws-inspect ros2-android workspace --workspace "$work/external_ws" inspect --without-shell
step ws-host-build ros2-android workspace --workspace "$work/external_ws" host-build
step ws-build ros2-android workspace --workspace "$work/external_ws" build --without-shell
apk="$(find "${XDG_CACHE_HOME:-$HOME/.cache}/ros2-android/tooling" -path '*workspaceRunner/build/outputs/apk/debug/*.apk' -newer "$wheel" | head -1)"
[[ -n "$apk" ]] || { echo 'FAILED apk-locate: no workspace APK' | tee -a "$summary"; exit 1; }
step apk-inspect ros2-android apk inspect "$apk"
cp "$apk" "$work/workspace-runner.apk"
echo "JOURNEY COMPLETE" | tee -a "$summary"
