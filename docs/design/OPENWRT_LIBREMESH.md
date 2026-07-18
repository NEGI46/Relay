# OpenWrt / LibreMesh fixed-relay design note

This is a hardware and operations workstream, not a direct Relay-core
dependency. Before implementation, validate the target router, radio band,
antenna, power budget, enclosure, and local radio regulations.

## Candidate topologies

- Ethernet fixed uplink: preferred for a predictable Gateway connection.
- Wi-Fi client: useful where wired backhaul is unavailable; validate roaming
  and power-loss recovery.
- Separate VLAN: isolates Relay Gateway traffic from operator/admin traffic.

## Dependencies to validate

- OpenWrt target image and package feed for the exact device.
- `batman-adv` or 802.11s only after a two-node field test.
- Routing feed, firewall policy, Gateway discovery, and offline recovery.
- Installation height, line of sight, weather protection, and UPS runtime.

Supported hardware is intentionally **unspecified / not field-validated**.
The next deliverable is a hardware acceptance test, not APK integration.
