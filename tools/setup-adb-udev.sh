#!/usr/bin/env bash
# Run locally with sudo to authorize the development user for Realme USB modes.
set -euo pipefail
if [[ $EUID -ne 0 ]]; then
    printf 'Run this script with sudo on the development PC.\n' >&2
    exit 1
fi
getent group plugdev >/dev/null
rule_path=/etc/udev/rules.d/71-robotbrain-realme.rules
rule_text='SUBSYSTEM=="usb", ATTR{idVendor}=="22d9", MODE="0660", GROUP="plugdev", TAG+="uaccess"'
if [[ -f "$rule_path" ]] && [[ "$(cat "$rule_path")" != "$rule_text" ]]; then
    cp -a -- "$rule_path" "${rule_path}.backup-$(date +%Y%m%d-%H%M%S)"
fi
printf '%s\n' "$rule_text" > "$rule_path"
chmod 0644 "$rule_path"
udevadm control --reload-rules
udevadm trigger --subsystem-match=usb --attr-match=idVendor=22d9
udevadm settle
printf 'Realme USB development permissions configured. No phone files were changed.\n'
