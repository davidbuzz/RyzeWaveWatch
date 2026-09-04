# Laptop (BlueZ 5.72 / Intel AX201) BLE link drops — investigation notes, 2026-09-04

Symptom: every LE connection from the laptop to the Ryze Wave ends after 5-20 s with
`HCI Event: Disconnect Complete, Reason: Connection Timeout (0x08)` (supervision timeout, 5 s as requested by the watch).
Before the drop ATT round trips take 0.3-0.5 s and a full GATT discovery 30-40 s; connect attempts often fail first
with 0x3e (Connection Failed to be Established) and succeed on retry.

Ruled out:
- distance (laptop, phone, watch within 50 cm)
- the watch's classic radio paging for a phone (turned off with `38 02 00`; still drops)
- 100 % duty-cycle discovery scans from gnome-control-center (lowered to ~19 % via main.conf; still drops)
- Wi-Fi coexistence in the obvious form (laptop Wi-Fi was disconnected during the tests)
- a watch-side "app watchdog" (drops also happen mid-discovery before any command; the phone's Android stack keeps
  the link for hours with the same 15-45 ms interval / 5 s timeout parameters)

Facts still on the table:
- `ControllerMode = le` in main.conf is required for BlueZ to pick the LE bearer at all (dual-mode watch, public address).
- dmesg shows `Bluetooth: hci0: Opcode 0x2037 failed: -38` (LE Set Extended Advertising Data) and
  `Command not allowed when scan/LE connect` on kernel 7.0 with this controller.
- Negotiated params after the watch's update request: interval 15-45 ms, latency 0, timeout 5 s; no 2M PHY update
  (Android gets 2M).
- BlueZ background/autoconnect passive scanning (Window 60 ms) appears during connections after `trust`.

Captures: scratchpad btmon4..btmon10 (session temp dir), `captures/linktest_20260904_192345.txt`.
Decision: continue protocol work through the phone (android/ RyzeBridge + tools/bridge.py); revisit the laptop stack
later with the research-workflow results (usb dongle, kernel/firmware, PHY/DLE, supervision-timeout tricks).

## Research workflow conclusions (30 agents, 2026-09-04 late evening)
Rule-outs that survived adversarial review: client init order / MTU / write type (the link dies with zero ATT traffic,
e.g. btmon7 shows only the MTU exchange before the 0x08), app-level keepalives (neither Android client sends any),
and `ControllerMode = le` itself (kernel mgmt only turns BR/EDR scan off; the two dmesg lines are cosmetic).
Best-supported causes, by confidence after review:
1. **Peripheral-initiated switch to LE 2M PHY on the Intel AX201** (0.8) — btmon8/btmon10 show PHY Update Complete to
   2M shortly before the drops; the AX201's 2M receive path with this link budget looks marginal.
2. **Initial kernel LE parameters** (0.85): the first connection runs with a 420 ms supervision timeout until the watch's
   own update (5 s) lands, which explains the 0x3e/0x08 failures during service discovery.
3. **Marginal RF at the laptop**: the watch's advertisements arrive at -67..-83 dBm at 50 cm (expect -45..-60), and ATT
   round trips of 0.3-0.5 s at a 45 ms interval mean most connection events are missed even with no scanning.
   Antenna/coex path of the CNVi AX201, not host configuration.
Cheap next experiments if the laptop path is ever needed again (runtime commands, no file edits):
- force 1M PHY on the host before connecting: `sudo btmgmt phy LE1MTX LE1MRX` (and restore with `btmgmt phy` defaults);
- raise the initial supervision timeout: main.conf `[LE] ConnectionSupervisionTimeout = 2000` (20 s) — file edit, approved;
- compare with a cheap USB BLE dongle (nRF52840 or CSR8510) which sidesteps the AX201 entirely.
Decision stands: develop on the phone (RyzeBridge / the app); the laptop stack is for reference only.
