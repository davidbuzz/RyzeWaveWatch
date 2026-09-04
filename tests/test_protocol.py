"""Offline codec tests using packets from Gadgetbridge's dissector comments and our own captures."""
import datetime as dt
from ryzewave import protocol as P

def test_version_battery():
    assert P.dec_version(bytes.fromhex("a152483238314c574f56303038343734")) == "RH281LWOV008474"
    assert P.dec_version(bytes.fromhex("a10156312e30")) == "V1.0"     # A1 01 <ascii>: DSP version, sub-byte stripped
    assert P.dec_battery(bytes.fromhex("a248")) == (0x48, False)
    assert P.dec_battery(bytes.fromhex("a25201")) == (0x52, True)

def test_time():
    b = P.enc_set_time(dt.datetime(2025, 7, 3, 21, 38, 0))
    assert b.hex() == "a307e90703152600"

def test_user_info():
    b = P.enc_user_info(height_cm=170, weight_kg=70, step_goal=8000, age=56, male=False)
    assert b.hex() == "a900aa00460500001f4001000038020002010" + "0"

def test_steps_record():
    r = P.dec_steps_record(bytes.fromhex("b207e907021700712d31000043313b00002e"))
    assert r.when == dt.datetime(2025, 7, 2, 23) and r.total == 0x71

def test_hr24():
    out = P.dec_hr24_record(bytes.fromhex("f707e90703083030315c2f2f2e2f2f353331"))
    # 07:40 -> 53 (0x35), 07:20 -> 47? per Gadgetbridge comment; last value 0x31=49 at 08:00
    assert out[-1] == (dt.datetime(2025, 7, 3, 8, 0), 0x31)
    assert out[0] == (dt.datetime(2025, 7, 3, 6, 10), 0x30)

def test_spo2():
    out = P.dec_spo2_record(bytes.fromhex("34fa07e907030400ffff60ffff61ffff61ffff60"))
    assert out[-1] == (dt.datetime(2025, 7, 3, 4, 0), 0x60)
    assert len(out) == 4

def test_features():
    f = P.Features.parse(bytes.fromhex("080a" + "642a21" + "0c3943" + "756edf" + "fed921" + "005d78" + "000001"))
    assert f.words[7] == 0x080A and f.words[6] == 0x642A21 and f.words[1] == 1
    assert f.password and f.sync_timestamp and f.sleep_v2 and f.sport_control_sync and not f.account_id

def test_password():
    assert P.enc_password_auth("1234").hex() == "d501" + bytes(a ^ b for a, b in zip(b"1234", b"UTE8")).hex()
    assert P.enc_password_input("5678").hex() == "d50235363738"

def test_sport():
    assert P.enc_sport_control(P.SPORT_START, 1, 1).hex() == "fd110101"
    assert P.enc_sport_update(sport_type=1, duration_s=27).hex() == "fd440101" + "00001b" + "000000000000"
    # km fraction carries into the whole-km byte: 2999.9 m -> 3.00 km, 999.5 m -> 1.00 km
    assert P.enc_sport_update(1, 0, 0, 2999.9, 0.0).hex() == "fd440101" + "000000" + "0000" + "0300" + "0000"
    assert P.enc_sport_update(1, 0, 0, 999.5, 0.0).hex() == "fd440101" + "000000" + "0000" + "0100" + "0000"
    assert P.enc_sport_update(1, 3725, 123, 2340.0, 330.0).hex() == "fd440101" + "010205" + "007b" + "0222" + "051e"
    assert P.dec_sport_rt(bytes.fromhex("fd015d0000000000000000000000"))["hr"] == 93

def test_sleep_stages():
    st = P.dec_sleep_stages(dt.date(2025, 7, 3), bytes.fromhex("32" + "1700020100b4" + "0100010100b4"))
    assert st[0].when == dt.datetime(2025, 7, 2, 23, 0) and st[1].when == dt.datetime(2025, 7, 3, 1, 0)

def test_hr_push():
    r = P.dec_hr_push(bytes.fromhex("F70307EA0904120462"))
    assert r == {"kind": "sample", "when": dt.datetime(2026, 9, 4, 18, 40), "hr": 98}
    r = P.dec_hr_push(bytes.fromhex("F70407EA0904122B693A4B"))
    assert r["kind"] == "summary" and (r["max"], r["min"], r["avg"]) == (105, 58, 75)

def test_sport_list():
    lst = P.dec_sport_list(bytes.fromhex("fd48aa00" + "010101" + "020102" + "040103" + "050104"))
    assert lst == [(1, True, 1), (2, True, 2), (4, True, 3), (5, True, 4)]
    assert P.dec_sport_list(bytes.fromhex("fd48aafd0900040046")) == []

def test_hr_mode():
    assert P.enc_hr_mode(False).hex() == "d601" and P.enc_hr_mode(True).hex() == "d602"
    assert P.enc_hr_timed(10).hex() == "d6100a"

def test_spo2_auto():
    assert P.enc_spo2_auto(True, 10).hex() == "340301000a"
    assert P.enc_spo2_auto(True, 5).hex() == "3403010005"
    assert P.enc_spo2_auto(False, 30).hex() == "340300001e"
    assert P.enc_spo2_period(True).hex() == "3404010001173b"
    assert P.enc_hr_continuous(True).hex() == "f701"
