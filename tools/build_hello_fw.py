#!/usr/bin/env python3
"""Build a Realtek OTA image ("hello.bin") for the Ryze Wave (RTL8763EW), repeatably.

READ THIS FIRST. This produces a *format-structured* Realtek OTA pack: the container and the
per-image header are reconstructed from the vendor app's own DFU parser, and it wraps a minimal
ARM Cortex-M payload (a valid vector table plus a reset handler that just loops). It is a
scaffold for iteration, NOT working firmware, and NOT something to flash as-is:

  * It cannot boot. A running image must link against Realtek's NDA "Bee" SDK (ROM/patch/stack
    entry points, the real memory map). None of that is public, so the payload here is a stub.
  * Several values it needs are UNKNOWN for the 8763EW and are PLACEHOLDERS below: the flash load
    address, the SRAM base/size (so the stack pointer and vector-table addresses), the exact
    ic_type byte, and the header CRC polynomial. They are guesses, clearly marked.
  * Flashing an image with wrong addresses either gets rejected by the DFU/bootloader (best case,
    dual-bank protects the active image) or bricks a sealed, unrecoverable watch (worst case).

So: the tool is real and repeatable; the *correct* values are the missing input. When we ever
obtain the 8763EW memory map (datasheet/SDK, or a flash dump if the case is opened), fill in the
KNOWN section and this emits a genuine candidate. Until then, `hello.bin` is a shape, not firmware.

Format sources: pack header + directory from com/realsil/sdk/dfu/image/pack/a.java (magic 0x4D47,
40-byte header, image bitmap, 12-byte records); image header from com/realsil/sdk/dfu/h/*.java.

Usage:
    tools/build_hello_fw.py                 # writes dist/hello.bin + prints the field manifest
    tools/build_hello_fw.py -o out.bin
"""
import argparse
import struct
import sys

# ---------------------------------------------------------------------------
# KNOWN (verified this session)
# ---------------------------------------------------------------------------
PACK_MAGIC = 0x4D47          # "GM", checked as 19783 in a.java h() [KNOWN: from the SDK parser]
APP_BIT = 5                  # App image lives at bit 5 of the pack image-bitmap [KNOWN: BinIndicator table]
TARGET = "RTL8763EW"         # [KNOWN: from the USB mass-storage descriptor]

# ---------------------------------------------------------------------------
# PLACEHOLDER (NOT verified for the 8763EW - guesses, do not trust)
# ---------------------------------------------------------------------------
IC_TYPE = 0x0A               # [PLACEHOLDER] SDK branches on icType==10 for the 8763 family; exact 8763EW byte unconfirmed
FLASH_ADDR = 0x00800000      # [PLACEHOLDER] App flash load address - real 8763EW value unknown (needs memory map)
SRAM_TOP = 0x00280000        # [PLACEHOLDER] initial stack pointer = top of SRAM - real 8763EW size/base unknown
IMAGE_ID = 0x0009            # [PLACEHOLDER] App image id (App=bit5); exact id per this build unconfirmed
IMAGE_VERSION = 0x00000001   # [PLACEHOLDER] arbitrary version for iteration
OTA_FLAG = 0x00              # [PLACEHOLDER] ctrl/ota flag byte; secure-boot bit meaning unconfirmed

HEADER_LEN = 0x20            # Realtek image control header size [KNOWN: layout; some fields placeholder]


def crc16_ccitt(data: bytes) -> int:
    """CRC16-CCITT (0x1021, init 0xFFFF). [PLACEHOLDER: the exact Realtek header polynomial is unconfirmed.]"""
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if (crc & 0x8000) else (crc << 1) & 0xFFFF
    return crc


def build_cortex_m_payload() -> bytes:
    """A minimal, valid ARM Cortex-M image body: vector table + a reset handler that loops forever.

    word0 = initial SP, word1 = reset vector (Thumb, low bit set). The reset handler is `b .`
    (0xE7FE), i.e. spin in place - the smallest well-formed 'hello, I booted' with no peripherals.
    Addresses are PLACEHOLDERS (see the module header)."""
    reset_handler_addr = FLASH_ADDR + HEADER_LEN + 0x40      # code sits after a 0x40-entry vector table
    vectors = bytearray(0x40)
    struct.pack_into("<I", vectors, 0x00, SRAM_TOP)          # initial stack pointer
    struct.pack_into("<I", vectors, 0x04, reset_handler_addr | 1)  # reset handler (Thumb)
    # remaining exception vectors left zero (a stub; a real image points them at handlers)
    code = struct.pack("<H", 0xE7FE)                          # Thumb: b .   (infinite loop)
    body = bytes(vectors) + code
    if len(body) % 4:
        body += b"\x00" * (4 - len(body) % 4)
    return body


def build_image_header(payload: bytes) -> bytes:
    """The Realtek per-image control header (h/*.java layout): crc16, ic_type, ota_flag, ctrl/id,
    image_id, image_version, payload_length. CRC is computed over the rest of the header + payload."""
    h = bytearray(HEADER_LEN)
    h[2] = IC_TYPE & 0xFF
    h[3] = OTA_FLAG & 0xFF
    struct.pack_into("<H", h, 4, 0x0000)                      # ctrl_flag [PLACEHOLDER]
    struct.pack_into("<H", h, 6, IMAGE_ID & 0xFFFF)
    struct.pack_into("<I", h, 8, IMAGE_VERSION & 0xFFFFFFFF)
    struct.pack_into("<I", h, 12, len(payload))              # payload length
    crc = crc16_ccitt(bytes(h[2:]) + payload)
    struct.pack_into("<H", h, 0, crc)
    return bytes(h)


def build_app_subimage() -> bytes:
    payload = build_cortex_m_payload()
    return build_image_header(payload) + payload


def build_pack(subimage: bytes) -> bytes:
    """Assemble the GM pack: 40-byte header, 4-byte image bitmap (App bit set), one 12-byte record,
    then the sub-image bytes (a.java h() + the sub-file loop)."""
    bitmap = bytearray(4)
    bitmap[APP_BIT // 8] |= 1 << (APP_BIT % 8)               # set the App bit
    record = struct.pack("<III", IMAGE_ID, len(subimage), 0)[:12]  # (id/version, size, flag) [layout KNOWN; fields best-effort]
    body = bytes(bitmap) + record + subimage
    total = 40 + len(body)
    header = bytearray(40)
    struct.pack_into("<H", header, 0, PACK_MAGIC)            # "GM"
    struct.pack_into("<I", header, 2, total)                 # total pack size
    # header[6:38] reserved/version (zeros); header[38:40] version-flags -> directory bitmap size 4
    struct.pack_into("<H", header, 38, 0x0001)
    return bytes(header) + body


def manifest() -> list[tuple[str, str, str]]:
    return [
        ("target chip", TARGET, "KNOWN (USB descriptor)"),
        ("pack magic", f"0x{PACK_MAGIC:04X} 'GM'", "KNOWN (SDK parser)"),
        ("image format", "GM pack + Realtek image header", "KNOWN layout (reconstructed)"),
        ("banking", "dual-bank silent (work mode 16)", "KNOWN scheme / addresses UNKNOWN"),
        ("ic_type byte", f"0x{IC_TYPE:02X}", "PLACEHOLDER"),
        ("flash load addr", f"0x{FLASH_ADDR:08X}", "PLACEHOLDER (memory map unknown)"),
        ("stack pointer / SRAM top", f"0x{SRAM_TOP:08X}", "PLACEHOLDER (RAM size/base unknown)"),
        ("vector table", "SP + reset vector, Thumb", "FORMAT correct / addresses PLACEHOLDER"),
        ("peripheral registers", "none used (stub loops)", "UNKNOWN (NDA) - not driveable"),
        ("header CRC16", "CCITT 0x1021", "PLACEHOLDER (poly unconfirmed)"),
    ]


def main():
    ap = argparse.ArgumentParser(description="Build hello.bin (a Realtek OTA scaffold; not flashable).")
    ap.add_argument("-o", "--out", default="dist/hello.bin")
    args = ap.parse_args()
    pack = build_pack(build_app_subimage())
    import os
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "wb") as f:
        f.write(pack)
    print(f"wrote {args.out}  ({len(pack)} bytes)\n")
    print(f"{'field':28} {'value':34} {'status'}")
    print("-" * 90)
    for name, val, status in manifest():
        print(f"{name:28} {val:34} {status}")
    print("\n*** NOT FLASHABLE ***  addresses are placeholders; this is a format scaffold, not firmware.")
    print("Flashing it risks bricking a sealed, unrecoverable watch. Do not send it over OTA.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
