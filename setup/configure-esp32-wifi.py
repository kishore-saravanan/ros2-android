#!/usr/bin/env python3
"""Generate ignored ESP32 Wi-Fi configuration without printing the password."""

import argparse
import getpass
import ipaddress
import json
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--agent-ip', required=True, type=ipaddress.IPv4Address)
parser.add_argument('--domain', default=0, type=int)
parser.add_argument('--interface', help='Connected PC Wi-Fi interface; auto-detected if omitted')
args = parser.parse_args()
if not 0 <= args.domain <= 101:
    parser.error('Use ROS domain 0–101')


def output(*command):
    return subprocess.check_output(command, text=True).strip()


interface = args.interface
if not interface:
    for line in output('nmcli', '-t', '-f', 'DEVICE,TYPE,STATE', 'device', 'status').splitlines():
        fields = line.split(':')
        if len(fields) == 3 and fields[1:] == ['wifi', 'connected']:
            interface = fields[0]
            break

if not interface:
    parser.error('No connected Wi-Fi interface; use --interface')

profile = output('nmcli', '-g', 'GENERAL.CONNECTION', 'device', 'show', interface)
ssid = output('nmcli', '-g', '802-11-wireless.ssid', 'connection', 'show', profile)
secret = subprocess.run(
    [
        'nmcli',
        '--show-secrets',
        '-g',
        '802-11-wireless-security.psk',
        'connection',
        'show',
        profile,
    ],
    capture_output=True,
    text=True,
)
password = secret.stdout.strip() if secret.returncode == 0 else ''
if not password:
    password = getpass.getpass('Wi-Fi password (local input, not echoed): ')

root = Path(__file__).resolve().parents[1]
private = root / 'device-logs/esp32-private'
private.mkdir(parents=True, exist_ok=True, mode=0o700)
value = '#pragma once\nstatic char WIFI_SSID[] = ' + json.dumps(ssid) + ';\n'
value += 'static char WIFI_PASSWORD[] = ' + json.dumps(password) + ';\n'
value += (
    ', '.join(
        f'constexpr int AGENT_IP_{i}={int(part)}'
        for i, part in enumerate(str(args.agent_ip).split('.'))
    ).replace(', constexpr int', ',')
    + ';\n'
)
value += f'constexpr unsigned AGENT_PORT=8888;\nconstexpr unsigned ROS_DOMAIN={args.domain};\n'
path = private / 'wifi_config.h'
path.write_text(value)
path.chmod(0o600)
print(
    'ESP32 Wi-Fi configuration generated in ignored device-logs/esp32-private; password withheld.'
)
