#!/usr/bin/env bash
# Format the code with the project's pinned formatters:
#   Python  ruff format (settings in pyproject.toml)
#   C/C++   clang-format (.clang-format, ROS 2 style)
#   Kotlin  ktfmt --kotlinlang-style (Kotlin coding conventions)
#   Shell   shfmt -i 4 -ci
# Usage: setup/format.sh [--check]
#   --check changes nothing and fails when a file needs formatting (CI runs this).
# Native SDK build inputs (INPUTS in python/ros2android/native_sdk.py) are skipped: the published
# SDK only fits byte-identical copies, so they are formatted together with the next SDK build.
set -euo pipefail
project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cache="${XDG_CACHE_HOME:-$HOME/.cache}/ros2-android-format"
python_tools="ruff==0.17.5 clang-format==23.1.5 shfmt-py==4.2.0" # shfmt-py ships shfmt 3.14.1
ktfmt_version=0.64
ktfmt_sha256=5b3d5286fd2defcc7dc8e28c21ddf156cc6b2d8682bdcd929ce4333e7a6201f2
check=false

case "${1:-}" in
    --check) check=true ;;
    "") ;;
    *)
        printf 'Usage: %s [--check]\n' "$0" >&2
        exit 2
        ;;
esac

tools="$cache/venv/bin"

if [[ "$(cat "$cache/venv/versions" 2>/dev/null)" != "$python_tools" ]]; then
    rm -rf "$cache/venv"
    mkdir -p "$cache"

    if ! python3 -m venv "$cache/venv"; then
        printf 'Install python3-venv first: sudo apt install python3-venv\n' >&2
        exit 1
    fi

    # shellcheck disable=SC2086 # One argument per pinned package.
    "$tools/pip" install --quiet --disable-pip-version-check $python_tools
    printf '%s' "$python_tools" >"$cache/venv/versions"
fi

ktfmt="$cache/ktfmt-$ktfmt_version-with-dependencies.jar"

if ! printf '%s  %s\n' "$ktfmt_sha256" "$ktfmt" | sha256sum --check --status 2>/dev/null; then
    maven=https://repo1.maven.org/maven2/com/facebook/ktfmt
    curl -fsSL -o "$ktfmt.partial" "$maven/$ktfmt_version/$(basename "$ktfmt")"

    if ! printf '%s  %s\n' "$ktfmt_sha256" "$ktfmt.partial" | sha256sum --check --status; then
        rm -f "$ktfmt.partial"
        printf 'The ktfmt download does not match its pinned checksum; run this again.\n' >&2
        exit 1
    fi

    mv "$ktfmt.partial" "$ktfmt"
fi

cd "$project_root"
sdk_inputs='^(setup/build-(native-ros|python-ros|micro-ros-agent)\.sh'
sdk_inputs+='|setup/(stage-native-libs|stage-python-ros|stage-micro-ros-agent|stage-licenses)\.py'
sdk_inputs+='|setup/prepare-python-target\.py|native/patches/|examples/python_talker/interfaces/)'

tracked() {
    git ls-files -- "$@" | grep -v -E "$sdk_inputs" || true
}

mapfile -t python < <(tracked '*.py' tools/check-shell-cli tools/connect-shell \
    tools/esp32-via-phone tools/ros2android tools/ros2android-workspace)
mapfile -t cpp < <(tracked '*.c' '*.cpp' '*.h' '*.hpp')
mapfile -t kotlin < <(tracked '*.kt' '*.kts')
mapfile -t shell < <(tracked '*.sh' tools/turtlebot4-demo tools/view-camera)

if $check; then
    status=0
    "$tools/ruff" format --check "${python[@]}" || status=1
    "$tools/clang-format" --dry-run --Werror "${cpp[@]}" || status=1
    java -jar "$ktfmt" --kotlinlang-style --dry-run --set-exit-if-changed "${kotlin[@]}" || status=1
    "$tools/shfmt" -i 4 -ci -d "${shell[@]}" || status=1

    if ((status)); then
        printf '\nRun setup/format.sh to format the files listed above.\n' >&2
    fi

    exit "$status"
fi

"$tools/ruff" format "${python[@]}"
"$tools/clang-format" -i "${cpp[@]}"
java -jar "$ktfmt" --kotlinlang-style "${kotlin[@]}"
"$tools/shfmt" -i 4 -ci -w "${shell[@]}"
