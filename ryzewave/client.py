"""Async BLE client for the Ryze Wave. Standalone: no vendor app involved.

    async with RyzeWave() as w:          # scans for 'Ryze Wave*', connects, authenticates
        print(await w.version(), await w.battery())
        for rec in await w.fetch_steps(): ...
"""
from __future__ import annotations
import asyncio, datetime as dt, json, logging, os, sys
from typing import Callable, Awaitable

from bleak import BleakClient, BleakScanner

from . import protocol as P

log = logging.getLogger("ryzewave")

STORE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "captures", "devices.json")  # inside the repo


class NeedsPairingCode(Exception):
    """The watch is showing a 4-digit code; call `pair(code)`."""


class RyzeWave:
    def __init__(self, address: str | None = None, name_prefix: str = "Ryze Wave", password: str | None = None):
        self.address = address
        self.name_prefix = name_prefix
        self._password = password
        self._client: BleakClient | None = None
        self.features: P.Features | None = None
        self.max_len: int | None = None
        self._waiters: list[tuple[str, Callable[[bytes], bool], asyncio.Future | None]] = []
        self.on_event: Callable[[str, bytes], None] | None = None   # (channel, packet) for unsolicited packets
        self._write_props: dict[str, bool] = {}

    # ---- lifecycle -------------------------------------------------------
    async def __aenter__(self):
        await self.connect()
        return self

    async def __aexit__(self, *exc):
        await self.disconnect()

    async def connect(self, timeout: float = 60.0, authenticate: bool = True):
        # the watch advertises only every ~15 s when idle, so give the scan a full minute
        dev = await self._find(timeout)
        # first-ever service discovery on this watch is slow (~0.4 s per ATT round trip); BlueZ caches it once the
        # device is trusted. The link also drops with "Connection Timeout" while the watch pages for a phone over
        # classic BT, so retry a few times.
        last: Exception | None = None
        for attempt in range(1, 4):
            log.info("connecting to %s (%s), attempt %d", dev.address, dev.name, attempt)
            self._client = BleakClient(dev, timeout=90.0,
                                       disconnected_callback=lambda c: log.warning("disconnected from %s", c.address))
            try:
                await self._client.connect()
                break
            except Exception as e:                  # noqa: BLE001
                last = e
                log.warning("connect attempt %d failed: %s", attempt, e)
                await asyncio.sleep(2)
        else:
            raise RuntimeError(f"could not connect after 3 attempts: {last}")
        self.address = dev.address
        log.info("connected, mtu=%s", self._client.mtu_size)
        for ch in (P.CH_CMD_WRITE, P.CH_DATA_WRITE):
            c = self._client.services.get_characteristic(ch)
            self._write_props[ch] = c is not None and "write-without-response" in c.properties
        await self._client.start_notify(P.CH_CMD_NOTIFY, lambda _, d: self._on_packet("cmd", bytes(d)))
        await self._client.start_notify(P.CH_DATA_NOTIFY, lambda _, d: self._on_packet("data", bytes(d)))
        try:
            self.features = P.Features.parse(bytes(await self._client.read_gatt_char(P.CH_CMD_WRITE)))
            log.info("features: %s", self.features)
        except Exception as e:                       # noqa: BLE001
            log.warning("could not read feature bitmap: %s", e)
        try:
            ml = bytes(await self._client.read_gatt_char(P.CH_DATA_WRITE))
            if len(ml) >= 2:
                self.max_len = (ml[0] << 8) | ml[1]
                log.info("max data packet length: %d", self.max_len)
        except Exception as e:                       # noqa: BLE001
            log.debug("no max-length read: %s", e)
        if authenticate:
            await self.authenticate()

    async def disconnect(self):
        if self._client and self._client.is_connected:
            await self._client.disconnect()
        self._client = None

    async def _find(self, timeout: float):
        """Locate the watch. On BlueZ, prefer the cached device object (no scan needed: the idle watch
        advertises only every ~15 s and BlueZ suppresses RSSI updates within 8 dB, so scans often miss it)."""
        if self.address and sys.platform.startswith("linux"):
            from bleak.backends.bluezdbus.manager import get_global_bluez_manager
            from bleak.backends.device import BLEDevice
            path = f"/org/bluez/hci0/dev_{self.address.upper().replace(':', '_')}"
            try:
                m = await get_global_bluez_manager()
                props = m._properties.get(path, {}).get("org.bluez.Device1")  # noqa: SLF001
            except Exception:                    # noqa: BLE001
                props = None
            if props:
                log.info("using BlueZ device object %s (no scan)", path)
                return BLEDevice(self.address, props.get("Name"), {"path": path, "props": props})
        # scan; the RSSI filter makes BlueZ report every advertisement, not only RSSI changes
        bluez = {"filters": {"Transport": "le", "RSSI": -120}}
        if self.address:
            d = await BleakScanner.find_device_by_address(self.address, timeout=timeout, bluez=bluez)
            if d:
                return d
        d = await BleakScanner.find_device_by_filter(
            lambda dev, adv: (adv.local_name or dev.name or "").startswith(self.name_prefix), timeout=timeout, bluez=bluez)
        if not d:
            raise RuntimeError("watch not found. Is it connected to the phone? Turn the phone's Bluetooth off, "
                               "tap the watch screen, and retry.")
        return d

    # ---- auth --------------------------------------------------------------
    def _load_password(self) -> str | None:
        if self._password:
            return self._password
        try:
            with open(STORE) as f:
                return json.load(f).get(self.address, {}).get("password")
        except (OSError, ValueError):
            return None

    def _save_password(self, pw: str):
        os.makedirs(os.path.dirname(STORE), exist_ok=True)
        try:
            with open(STORE) as f:
                d = json.load(f)
        except (OSError, ValueError):
            d = {}
        d.setdefault(self.address, {})["password"] = pw
        with open(STORE, "w") as f:
            json.dump(d, f, indent=1)
        self._password = pw

    async def authenticate(self):
        """Run the D5 handshake if the watch advertises password support. Raises NeedsPairingCode."""
        if self.features and not self.features.password:
            log.info("watch does not require a password")
            return
        pw = self._load_password()
        if pw and pw != P.DEFAULT_PASSWORD:
            rsp = await self.request(P.enc_password_auth(pw), P.CMD_PASSWORD, timeout=5)
        else:
            rsp = await self.request(P.enc_password_request_code(), P.CMD_PASSWORD, timeout=10)
        code = rsp[1] if len(rsp) > 1 else None
        if code == 0x01:
            log.info("password accepted")
            return
        if code == 0x02:
            raise NeedsPairingCode("watch is displaying a pairing code")
        raise RuntimeError(f"password handshake failed: {rsp.hex()}")

    async def pair(self, code: str):
        rsp = await self.request(P.enc_password_input(code), P.CMD_PASSWORD, timeout=10)
        if len(rsp) > 1 and rsp[1] == 0x01:
            self._save_password(code)
            log.info("paired, password stored in %s", STORE)
            return True
        raise RuntimeError(f"pairing rejected: {rsp.hex()}")

    # ---- transport ---------------------------------------------------------
    def _on_packet(self, channel: str, data: bytes):
        log.debug("%s <- %s  %s", channel, data.hex(), P.OPNAME.get(data[0], "") if data else "")
        for entry in list(self._waiters):
            ch, pred, fut = entry
            if ch != channel or (fut is not None and fut.done()):
                continue
            if pred(data):
                if fut is not None:
                    fut.set_result(data)
                return
        if self.on_event:
            self.on_event(channel, data)

    async def write(self, data: bytes, channel: str = "cmd"):
        ch = P.CH_CMD_WRITE if channel == "cmd" else P.CH_DATA_WRITE
        log.debug("%s -> %s  %s", channel, data.hex(), P.OPNAME.get(data[0], ""))
        await self._client.write_gatt_char(ch, data, response=not self._write_props.get(ch, True))

    async def wait_for(self, pred: Callable[[bytes], bool], timeout: float = 5.0, channel: str = "cmd") -> bytes:
        fut = asyncio.get_running_loop().create_future()
        entry = (channel, pred, fut)
        self._waiters.append(entry)
        try:
            return await asyncio.wait_for(fut, timeout)
        finally:
            self._waiters.remove(entry)

    async def request(self, cmd: bytes, first: int, timeout: float = 5.0, channel: str = "cmd",
                      reply_channel: str | None = None) -> bytes:
        """Write `cmd` and return the first packet whose opcode is `first`."""
        fut = asyncio.get_running_loop().create_future()
        entry = (reply_channel or channel, lambda d: d and d[0] == first, fut)
        self._waiters.append(entry)
        try:
            await self.write(cmd, channel)
            return await asyncio.wait_for(fut, timeout)
        finally:
            self._waiters.remove(entry)

    async def collect(self, cmd: bytes, first: int, is_end: Callable[[bytes], bool], timeout: float = 30.0,
                      channel: str = "cmd", extra_channel: str | None = None, extra_first: int | None = None) -> list[bytes]:
        """Write a fetch command and gather every packet with opcode `first` until is_end(pkt)."""
        pkts: list[bytes] = []
        done = asyncio.get_running_loop().create_future()

        def pred(d: bytes) -> bool:
            if not d or d[0] not in (first, extra_first):
                return False
            pkts.append(d)
            if is_end(d) and not done.done():
                done.set_result(True)
            return True
        entries = [(channel, pred, None)]          # fut=None: keep swallowing packets until `done`
        if extra_channel:
            entries.append((extra_channel, pred, None))
        self._waiters.extend(entries)
        try:
            await self.write(cmd, channel)
            await asyncio.wait_for(done, timeout)
        finally:
            for e in entries:
                self._waiters.remove(e)
        return pkts

    # ---- simple queries ---------------------------------------------------
    async def version(self) -> str:
        return P.dec_version(await self.request(P.enc_version(), P.CMD_VERSION))

    async def battery(self) -> tuple[int, bool]:
        return P.dec_battery(await self.request(P.enc_battery(), P.CMD_BATTERY))

    async def set_time(self, t: dt.datetime | None = None) -> bytes:
        return await self.request(P.enc_set_time(t), P.CMD_TIME)

    async def set_user_info(self, **kw) -> bytes:
        return await self.request(P.enc_user_info(**kw), P.CMD_USER_INFO)

    async def find_watch(self):
        await self.write(P.enc_find_watch())

    # ---- history fetches ----------------------------------------------------
    async def fetch_steps(self) -> list[P.StepsRecord]:
        pkts = await self.collect(P.enc_fetch_steps(), P.CMD_STEPS, lambda d: len(d) == 3 and d[1] == P.FETCH_END)
        return [P.dec_steps_record(p) for p in pkts if len(p) == 18]

    async def fetch_heart_rate(self, since: dt.datetime | None = None) -> list[tuple[dt.datetime, int]]:
        with_ts = self.features.sync_timestamp if self.features else True
        pkts = await self.collect(P.enc_fetch_hr24(since, with_ts), P.CMD_HR24,
                                  lambda d: len(d) == 3 and d[1] == P.FETCH_END)
        out = []
        for p in pkts:
            if len(p) == 18:
                out += P.dec_hr24_record(p)
        return out

    async def fetch_spo2(self) -> list[tuple[dt.datetime, int]]:
        pkts = await self.collect(P.enc_fetch_spo2(), P.CMD_SPO2,
                                  lambda d: len(d) >= 3 and d[1] == P.FETCH_START and d[2] == P.FETCH_END)
        out = []
        for p in pkts:
            if len(p) == 20:
                out += P.dec_spo2_record(p)
        return out

    async def fetch_sleep(self) -> list[P.SleepStage]:
        """31 01 -> (33F2) 31 01 yyyy MM dd n ; (34F2) 32 <stages>… ; (33F2) 31 02."""
        pkts = await self.collect(P.enc_fetch_sleep(), P.CMD_SLEEP_INFO, lambda d: len(d) >= 2 and d[1] == 0x02,
                                  extra_channel="data", extra_first=P.CMD_SLEEP_STAGES)
        out, session = [], None
        for p in pkts:
            if p[0] == P.CMD_SLEEP_INFO and p[1] == 0x01 and len(p) >= 7:
                session = P._date(p, 2)
            elif p[0] == P.CMD_SLEEP_STAGES and session:
                out += P.dec_sleep_stages(session, p)
        return out

    # ---- live measurements ---------------------------------------------------
    async def realtime_hr(self, seconds: float, cb: Callable[[int], None]):
        """Turn on realtime HR (E5 11) for `seconds`, calling cb(hr) for each sample."""
        prev = self.on_event

        def handler(ch, d):
            if d and d[0] == P.CMD_RT_HR:
                hr = P.dec_rt_hr(d)
                if hr:
                    cb(hr)
                    return
            if d and d[0] == P.CMD_SPORT and len(d) > 2 and d[1] == P.SPORT_RT_DATA:
                cb(d[2]); return
            if prev:
                prev(ch, d)
        self.on_event = handler
        try:
            await self.write(P.enc_rt_hr(True))
            await asyncio.sleep(seconds)
        finally:
            self.on_event = prev
            if self._client and self._client.is_connected:
                try:
                    await self.write(P.enc_rt_hr(False))
                except Exception as e:           # noqa: BLE001
                    log.warning("could not stop realtime HR: %s", e)

    async def spo2_test(self, timeout: float = 90.0) -> int | None:
        """Start a spot SpO2 test and wait for the final value (34 00 00 <spo2>, ~60 s).
        The watch emits a spurious `34 00 FF FF` within ~1 s of the ack; ignore it and keep waiting."""
        t0 = asyncio.get_running_loop().time()

        def is_end(d: bytes) -> bool:
            if len(d) < 4 or d[1] != 0x00:
                return False
            if d[2] == 0xFF and asyncio.get_running_loop().time() - t0 < 3.0:
                return False                     # the early bogus failure
            return True
        pkts = await self.collect(P.enc_spo2_test(True), P.CMD_SPO2, is_end, timeout=timeout)
        for p in reversed(pkts):
            r = P.dec_spo2_result(p)
            if r.get("spo2"):
                return r["spo2"]
        return None

    # ---- classic bluetooth side ---------------------------------------------------
    async def bt3_enable(self, on: bool) -> bytes:
        """Switch the watch's classic Bluetooth (calls/audio) on or off. Off keeps the BLE link stable when no phone is paired."""
        return await self.request(P.enc_bt3_enable(on), P.CMD_BT3, timeout=8, channel="data")

    async def bt3_query(self) -> bytes:
        return await self.request(P.enc_bt3_query(os.urandom(4)), P.CMD_BT3, timeout=8, channel="data")

    # ---- workouts --------------------------------------------------------------
    async def sport_start(self, sport_type: int = 1, hr_interval_s: int = 1) -> bytes:
        return await self.request(P.enc_sport_control(P.SPORT_START, sport_type, hr_interval_s), P.CMD_SPORT, timeout=8)

    async def sport_stop(self, sport_type: int = 1) -> bytes:
        return await self.request(P.enc_sport_control(P.SPORT_STOP, sport_type), P.CMD_SPORT, timeout=8)

    async def sport_update(self, **kw):
        await self.write(P.enc_sport_update(**kw))
