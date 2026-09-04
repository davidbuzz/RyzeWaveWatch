#!/usr/bin/env python3
"""Decode an Android btsnoop_hci.log and print the ATT traffic, labelling the
GloryFit/UTE characteristics by learning handle->UUID from the discovery
responses in the same log.

Usage: decode_btsnoop.py <btsnoop_hci.log> [--all]
  --all   also print ATT traffic on unknown handles
"""
import struct, sys, datetime

OPNAMES = {
    0xA1:"VERSION",0xA2:"BATTERY",0xA3:"SET_TIME",0xA9:"USER_INFO",0xAA:"STEP_SLEEP_STATUS",
    0xAB:"VIBRATE/ALARM",0xAD:"FACTORY_RESET",0xAF:"LANGUAGE",0xB1:"RT_STEPS",0xB2:"STEPS",
    0xB3:"SLEEP_LEGACY",0xB7:"SWIM",0xB9:"SKIP",0xBA:"UV",0xBD:"HRH_SPORT",0xBE:"QUICK_SWITCH",
    0xC1:"CALL_STATUS",0xC4:"CAMERA",0xC5:"NOTIFICATION",0xC6:"NOTIFICATION6",0xC7:"BP_CFG",
    0xC8:"BP_DATA",0xCA:"WEATHER",0xCB:"WEATHER_CITY",0xCD:"UI_RES_VER",0xD1:"ACTION",
    0xD2:"SMS_SWITCH",0xD3:"SEDENTARY",0xD4:"DRINK_WATER",0xD5:"PASSWORD",0xD6:"HR_MODE",
    0xD7:"DND/REJECT_BTN",0xDB:"PUSH_DISPLAY",0xDF:"HV_SCREEN",0xE5:"RT_HR/HR_TEST",0xE6:"HR_DATA",
    0xE9:"BODY_COMP",0xEB:"?EB",0xEC:"SLEEP_SYWEE",0xF3:"RIDE",0xF4:"SPORTS_MODE",0xF7:"HR24",
    0xF9:"UI_PAGES",0xFB:"HR_CALIB",0xFC:"WRIST_CALIB",0xFD:"SPORT_MGMT",
    0x24:"TEMPERATURE",0x26:"DIAL_CFG",0x27:"DIAL_DATA",0x28:"ECG",0x29:"PHYSIO_PERIOD",
    0x31:"SLEEP_INFO",0x32:"SLEEP_STAGES",0x33:"ACCOUNT_ID",0x34:"SPO2",0x37:"CONTACTS/SOS",
    0x38:"BT3/UNPAIR",0x3A:"MUSIC",0x3E:"BP_CALIB",0x3F:"GOALS",0x41:"WASH_HANDS",0x43:"TIMEZONE",
    0x44:"MOOD",0x45:"QUICK_REPLY_TARGET",0x46:"CANNED_MSG",0x47:"GPS",0x4A:"CYWEE_SWIM",
    0x51:"LABEL_ALARM",0x52:"SMS_QUICK_REPLY",0x55:"EL_BP",0x5B:"BLOOD_SUGAR",0x60:"ALIPAY",0x68:"WECHATPAY",
}
KNOWN_UUID16 = {0x33F1:"33F1 cmd>", 0x33F2:"33F2 cmd<", 0x34F1:"34F1 data>", 0x34F2:"34F2 data<",
                0x35F1:"35F1 pay>", 0x35F2:"35F2 pay<", 0x2A19:"battery", 0x2A37:"hr_meas", 0x2902:"CCCD"}
ATT = {0x01:"ERR",0x02:"MTU_REQ",0x03:"MTU_RSP",0x04:"FIND_INFO_REQ",0x05:"FIND_INFO_RSP",
       0x08:"READ_BY_TYPE_REQ",0x09:"READ_BY_TYPE_RSP",0x0A:"READ_REQ",0x0B:"READ_RSP",
       0x10:"READ_GRP_REQ",0x11:"READ_GRP_RSP",0x12:"WRITE_REQ",0x13:"WRITE_RSP",0x52:"WRITE_CMD",
       0x1B:"NOTIFY",0x1D:"INDICATE",0x1E:"CONFIRM"}
BTSNOOP_EPOCH_DELTA = 0x00dcddb30f2f8000  # us between 0000-01-01 and 1970-01-01

def read_records(path):
    with open(path, "rb") as f:
        hdr = f.read(16)
        if hdr[:8] != b"btsnoop\0":
            sys.exit("not a btsnoop file")
        ver, dlt = struct.unpack(">II", hdr[8:16])
        while True:
            rh = f.read(24)
            if len(rh) < 24: return
            olen, ilen, flags, drops, ts = struct.unpack(">IIIIq", rh)
            pkt = f.read(ilen)
            t = datetime.datetime.fromtimestamp((ts - BTSNOOP_EPOCH_DELTA) / 1e6)
            yield t, flags, dlt, pkt

def main():
    path = sys.argv[1]; show_all = "--all" in sys.argv
    handle_names = {}          # att handle -> label
    pending_read = {}          # conn -> (att handle) for READ_REQ
    pending_rbt = {}           # conn -> uuid16 asked for in READ_BY_TYPE_REQ
    acl_buf = {}               # conn -> (bytes, expected_len)
    n_printed = 0
    for t, flags, dlt, pkt in read_records(path):
        if dlt == 1002:                     # H4: first byte is packet type
            if not pkt or pkt[0] != 0x02: continue
            pkt = pkt[1:]
        elif dlt == 1001:                   # HCI UART/H1 style with flags? treat as raw ACL if flags bit1==0
            if flags & 0x02: continue       # command/event
        else:
            continue
        if len(pkt) < 4: continue
        hf, dlen = struct.unpack("<HH", pkt[:4])
        conn, pb = hf & 0x0FFF, (hf >> 12) & 0x3
        body = pkt[4:4+dlen]
        direction = "> " if (flags & 1) == 0 else " <"   # 0 = host->controller (sent)
        # L2CAP reassembly
        if pb in (0x00, 0x02):   # start
            if len(body) < 4: continue
            l2len, cid = struct.unpack("<HH", body[:4])
            acl_buf[conn] = [bytearray(body), 4 + l2len, cid]
        elif pb == 0x01 and conn in acl_buf:
            acl_buf[conn][0] += body
        else:
            continue
        buf, need, cid = acl_buf[conn]
        if len(buf) < need: continue
        del acl_buf[conn]
        if cid != 0x0004: continue          # ATT only
        att = bytes(buf[4:need])
        if not att: continue
        op = att[0]; name = ATT.get(op, f"op{op:02x}")
        label = None; payload = b""
        if op in (0x52, 0x12, 0x1B, 0x1D) and len(att) >= 3:
            h = struct.unpack("<H", att[1:3])[0]; payload = att[3:]
            label = handle_names.get(h, f"h{h:04x}")
        elif op == 0x0A and len(att) >= 3:
            h = struct.unpack("<H", att[1:3])[0]; pending_read[conn] = h
            label = handle_names.get(h, f"h{h:04x}"); payload = b""
        elif op == 0x0B:
            h = pending_read.pop(conn, None); payload = att[1:]
            label = handle_names.get(h, f"h{h:04x}") if h is not None else "?"
        elif op == 0x08 and len(att) >= 7:
            pending_rbt[conn] = struct.unpack("<H", att[5:7])[0] if len(att) == 7 else None
            continue
        elif op == 0x09 and pending_rbt.get(conn) == 0x2803:   # characteristic declarations
            ln = att[1]
            for i in range(2, len(att) - ln + 1, ln):
                rec = att[i:i+ln]
                vh = struct.unpack("<H", rec[3:5])[0]
                if ln == 7:
                    u16 = struct.unpack("<H", rec[5:7])[0]
                    handle_names[vh] = KNOWN_UUID16.get(u16, f"uuid{u16:04x}")
                else:
                    u = rec[5:21][::-1].hex()
                    handle_names[vh] = "uuid" + u[:8]
            continue
        else:
            continue
        if label is None: continue
        known = not label.startswith("h") and not label.startswith("uuid") and not label.startswith("?")
        if not known and not show_all: continue
        opn = OPNAMES.get(payload[0], "") if payload else ""
        print(f"{t.strftime('%H:%M:%S.%f')[:-3]} {direction} {label:11s} {name:9s} {payload.hex():<60s} {opn}")
        n_printed += 1
    print(f"# {n_printed} ATT packets shown; learned handles: " +
          ", ".join(f"0x{h:04x}={n}" for h, n in sorted(handle_names.items()) if not n.startswith('uuid')), file=sys.stderr)

if __name__ == "__main__":
    main()
