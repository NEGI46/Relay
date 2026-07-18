# Fixed Relay site runbook

This is a design and commissioning note, not an APK implementation.

Before installation, record the router model/target image, radio band and
antenna gain, enclosure/IP rating, mounting height and line of sight, power
budget, UPS runtime, and local radio rules. Supported hardware remains
**unspecified / pre-validation** until a two-node field test passes.

Gateway connectivity has three supported patterns:

1. Ethernet fixed uplink (preferred).
2. Wi-Fi client uplink (validate reconnect and power-loss recovery).
3. Separate VLAN (isolate Gateway traffic from administration).

The image must document its OpenWrt package feed and whether batman-adv,
802.11s, or another routing feed is enabled. Commissioning must validate
offline queueing, firewall rules, clock recovery, and Gateway restore before
the site is accepted.
