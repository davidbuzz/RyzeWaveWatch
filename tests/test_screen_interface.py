"""The F9 screen-list codec: the 'Add widget' cards the watch supports and which are enabled."""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from ryzewave import protocol as P


def _reply(supported: set[int], enabled: set[int]) -> bytes:
    """Build an `F9 AA` reply the way the watch does: supported at bytes 2,4,6,8/10,12; enabled at 3,5,7,9/11,13."""
    b = bytearray(20)
    b[0] = P.CMD_INTERFACE
    b[1] = P.QUERY
    sup = sum(1 << i for i in supported)
    en = sum(1 << i for i in enabled)
    for k, off in enumerate((2, 4, 6, 8)):      # supported 0-31
        b[off] = (sup >> (8 * k)) & 0xFF
    for k, off in enumerate((3, 5, 7, 9)):      # enabled 0-31
        b[off] = (en >> (8 * k)) & 0xFF
    b[10] = (sup >> 32) & 0xFF                   # supported 32-39
    b[12] = (sup >> 40) & 0xFF                   # supported 40-47
    b[11] = (en >> 32) & 0xFF
    b[13] = (en >> 40) & 0xFF
    return bytes(b)


def test_query_and_names():
    assert P.enc_interface_query() == bytes([0xF9, 0xAA])
    assert P.screen_name(2) == "blood_oxygen"
    assert P.screen_name(40) == "stopwatch"
    assert P.screen_name(99).startswith("screen_")


def test_show_hide_bytes_match_the_vendor_app():
    # index 2 (blood_oxygen): show -> bit 2 of the first status byte (packet offset 3)
    show = P.enc_interface_show(2)
    assert show[0] == 0xF9 and show[1] == 0x01 and len(show) == 20
    assert show[3] == 0x04 and sum(show[2:]) == 0x04           # only that bit set
    # index 40 (stopwatch): group 5 -> packet offset (40//8)*2+3 = 13
    show40 = P.enc_interface_show(40)
    assert show40[13] == 0x01 and sum(show40[2:]) == 0x01
    # hide: all-ones with the target bit cleared
    hide = P.enc_interface_hide(2)
    assert hide[0] == 0xF9 and hide[1] == 0x02 and len(hide) == 20
    assert hide[3] == 0xFF & ~0x04 and all(x == 0xFF for i, x in enumerate(hide[2:], 2) if i != 3)


def test_decode_lists_supported_screens_with_enabled_flags():
    screens = P.dec_interface(_reply(supported={0, 1, 2, 15, 40, 42}, enabled={1, 15, 40}))
    got = {s.index: s.enabled for s in screens}
    assert set(got) == {0, 1, 2, 15, 40, 42}                  # only supported ones listed
    assert got[1] is True and got[40] is True and got[15] is True
    assert got[0] is False and got[2] is False and got[42] is False
    assert next(s.name for s in screens if s.index == 2) == "blood_oxygen"


def test_decode_round_trips_through_a_toggle():
    # a screen that is supported but off is exactly the "addable" case
    screens = P.dec_interface(_reply(supported={2, 40}, enabled={40}))
    addable = [s for s in screens if not s.enabled]
    assert [s.name for s in addable] == ["blood_oxygen"]


def test_decode_rejects_non_f9():
    try:
        P.dec_interface(bytes([0xA2, 0xAA, 0, 0]))
    except ValueError:
        return
    raise AssertionError("expected ValueError on a non-F9 reply")
