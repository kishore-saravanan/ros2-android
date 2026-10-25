#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
if [[ ! -d "$ANDROID_HOME/platforms/android-35" ]]; then
    printf 'Android SDK platform 35 is missing. See docs/DEVELOPMENT.md.\n' >&2
    exit 1
fi
cd "$project_root"
if [[ $# == 0 ]]; then
    set -- :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
fi
exec ./gradlew "$@"

