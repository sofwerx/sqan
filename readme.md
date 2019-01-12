### Squad Area Network (SqAN)

SqAN is an experimental effort to provide a Mobile Adhoc NETwork (MANET) is support of small, dismounted maneuver units based solely on open source available techniques and pre-existing on-device hardware. This repository includes:

- SqAN itself
- SqAN Test (in the "testing" directory), a tool for conducting load testing on SqAN

SqAN is an evaluation of three possible ways to do a MANET using API level access to Android hardware:

- WiFi Direct®
- WiFi Aware™
- Nearby Connections

Current plans are to include a VPN and intercept all traffic in a specific IP range (a user selectable range within
reserved space for private networks) and forward that traffic over SqAN to other SqAN nodes. SqAN will also likely
include some dynamic IP allocation scheme within that block to avoid the need for any user IP pre-configuration.