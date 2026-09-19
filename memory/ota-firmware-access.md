---
name: ota-firmware-access
description: How (and whether) the Ryze Wave firmware can be obtained/updated - cloud, BLE OTA, USB, hardware
metadata:
  type: project
---

Findings from the 2026-09-19/20 firmware-access investigation. Full detail in `docs/PROTOCOL.md` §10.

**SoC is Realtek RTL8763EW** (confirmed from the USB descriptor `Realtek RTL8763EW Disk`), not the "8763-family"
guess and not the `8762C` the vendor app's log tags say.

**Our firmware is not obtainable non-invasively.** No cloud OTA (`getBtVersionUpdate` on `apsouth.uteasy.com` returns
`flag:-1 no new version` for every version of btname `RH280RGA` - firmware is gated per brand by `appkey`, ours only
serves our model and it's already latest at `RH280RGAV008949`). The USB port is the music drive only. BLE OTA is
write-only (can't read the running image back). Reading our bytes would need a captured vendor OTA (none offered) or a
hardware readout via debug pads - but the case is sealed/waterproof and must stay so, so **hardware teardown is off
the table** (Buzz, 2026-09-20).

**The USB port is a 480 MiB FAT16 music volume** (`8888:1234`, one MSC interface, `rtk_music.bin` + `audio/`). Not a
firmware channel; dropping a file there only adds MP3s. No bootloader/DFU interface appears on USB - the ROM only
does USB download via a boot-strap pin held at cold-boot reset (an internal pad, not the 4 external pads). Cradle
data contact is marginal (enumeration flaps; kernel says "Maybe the USB cable is bad?").

**A real sibling firmware is bundled in the APK**: `res/raw/rh266fp.bin` (RH266FPV000682) + `rh266fp_ui.bin`, for the
RH266FP model. It targets **RTL8762C** (different chip - study only, NOT flashable to our watch). It is **plaintext,
not encrypted** (the app's AES key is OTA transport only) and integrity is a **checksum, not a signature** - so at the
image level this class is moddable; SoC secure-boot fuse state unknown. Extracted copy in scratch, not committed.

**White-label lineage**: ODM is YouChuangyi (Shenzhen) / UTE / GloryFit (`com.yc.gloryfit`). Same board ships as
Oukitel BT103, DM58, UAUE T60, HT36, Imilab KW66. Sibling RE (tcsenpai/ht36) hit the same walls: no cloud OTA,
firmware behind a debug port, never write the OTA characteristic.

Runtime update path (if an image existed): BLE dual-bank silent OTA - `GattDfuAdapter`, `setOtaWorkMode(16)`,
write inactive bank then `CMD_OTA_ACTIVE_RESET`. Never send to the OTA/DFU UUIDs without Buzz's go-ahead (see CLAUDE.md).
