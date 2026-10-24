#!/usr/bin/env python3
"""Write a PC Fast DDS profile restricted to one IP, e.g. the USB interface."""
import argparse
import ipaddress
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('address')
parser.add_argument('output', type=Path)
args = parser.parse_args()
address = str(ipaddress.IPv4Address(args.address))
args.output.write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<profiles xmlns="http://www.eprosima.com/XMLSchemas/fastRTPS_Profiles">
  <transport_descriptors>
    <transport_descriptor>
      <transport_id>RobotBrainSelectedInterface</transport_id>
      <type>UDPv4</type>
      <interfaceWhiteList><address>{address}</address></interfaceWhiteList>
    </transport_descriptor>
  </transport_descriptors>
  <participant profile_name="RobotBrainPC" is_default_profile="true">
    <rtps>
      <useBuiltinTransports>false</useBuiltinTransports>
      <userTransports><transport_id>RobotBrainSelectedInterface</transport_id></userTransports>
    </rtps>
  </participant>
</profiles>
''')
print(f'Fast DDS profile written for {address}')
