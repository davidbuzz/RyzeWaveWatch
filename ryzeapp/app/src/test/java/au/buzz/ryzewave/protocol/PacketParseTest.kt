package au.buzz.ryzewave.protocol

import au.buzz.ryzewave.core.HrSample
import au.buzz.ryzewave.core.SampleSource
import au.buzz.ryzewave.core.SleepStage
import au.buzz.ryzewave.core.Spo2Sample
import au.buzz.ryzewave.core.StepsHour
import au.buzz.ryzewave.core.UserProfile
import au.buzz.ryzewave.core.WatchEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** The notification dispatcher, its robustness against foreign traffic, the GATT constants and the time conversions. */
class PacketParseTest {

    private fun hx(s: String): ByteArray = Protocol.fromHex(s)

    @Test
    fun gattUuids() {
        assertEquals(UUID.fromString("000055ff-0000-1000-8000-00805f9b34fb"), Protocol.SVC_CMD_UUID)
        assertEquals(UUID.fromString("000033f1-0000-1000-8000-00805f9b34fb"), Protocol.CH_CMD_WRITE_UUID)
        assertEquals(UUID.fromString("000033f2-0000-1000-8000-00805f9b34fb"), Protocol.CH_CMD_NOTIFY_UUID)
        assertEquals(UUID.fromString("000056ff-0000-1000-8000-00805f9b34fb"), Protocol.SVC_DATA_UUID)
        assertEquals(UUID.fromString("000034f1-0000-1000-8000-00805f9b34fb"), Protocol.CH_DATA_WRITE_UUID)
        assertEquals(UUID.fromString("000034f2-0000-1000-8000-00805f9b34fb"), Protocol.CH_DATA_NOTIFY_UUID)
        assertEquals(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), Protocol.CCCD_UUID)
        assertEquals(247, Protocol.MTU)
        assertEquals(Protocol.Channel.CMD, Protocol.channelOf(Protocol.CH_CMD_NOTIFY_UUID))
        assertEquals(Protocol.Channel.DATA, Protocol.channelOf(Protocol.CH_DATA_WRITE_UUID))
        assertNull(Protocol.channelOf(UUID.fromString("000035f1-0000-1000-8000-00805f9b34fb")))
    }

    @Test
    fun opcodeConstantsMatchThePythonReference() {
        assertEquals(0xA1, Protocol.CMD_VERSION); assertEquals(0xA2, Protocol.CMD_BATTERY); assertEquals(0xA3, Protocol.CMD_TIME)
        assertEquals(0xA9, Protocol.CMD_USER_INFO); assertEquals(0xAA, Protocol.CMD_STEP_STATUS); assertEquals(0xAB, Protocol.CMD_VIBRATE)
        assertEquals(0xAD, Protocol.CMD_FACTORY_RESET); assertEquals(0xAF, Protocol.CMD_LANGUAGE); assertEquals(0xB1, Protocol.CMD_RT_STEPS)
        assertEquals(0xB2, Protocol.CMD_STEPS); assertEquals(0xC1, Protocol.CMD_CALL_STATUS); assertEquals(0xC4, Protocol.CMD_CAMERA)
        assertEquals(0xC5, Protocol.CMD_NOTIFICATION); assertEquals(0xD1, Protocol.CMD_ACTION); assertEquals(0xD3, Protocol.CMD_SEDENTARY)
        assertEquals(0xD5, Protocol.CMD_PASSWORD); assertEquals(0xD6, Protocol.CMD_HR_MODE); assertEquals(0xE5, Protocol.CMD_RT_HR)
        assertEquals(0xE6, Protocol.CMD_HR_SINGLE); assertEquals(0xF7, Protocol.CMD_HR24); assertEquals(0xFD, Protocol.CMD_SPORT)
        assertEquals(0x31, Protocol.CMD_SLEEP_INFO); assertEquals(0x32, Protocol.CMD_SLEEP_STAGES); assertEquals(0x34, Protocol.CMD_SPO2)
        assertEquals(0x3F, Protocol.CMD_GOALS); assertEquals(0x38, Protocol.CMD_BT3)
        assertEquals(0xFA, Protocol.FETCH_START); assertEquals(0x07, Protocol.FETCH_DATA); assertEquals(0xFD, Protocol.FETCH_END)
        assertEquals(0xAA, Protocol.QUERY)
        assertEquals(listOf(0x00, 0x11, 0x22, 0x33, 0x44),
            listOf(Protocol.SPORT_STOP, Protocol.SPORT_START, Protocol.SPORT_PAUSE, Protocol.SPORT_RESUME, Protocol.SPORT_UPDATE))
        assertEquals(14, Protocol.SPORT_RT_LEN)
        assertEquals("UTE8", String(Protocol.PASSWORD_XOR, Charsets.US_ASCII))
        assertEquals("1234", Protocol.DEFAULT_PASSWORD)
    }

    /**
     * The realtime workout push is `FD <sportType> <hr> …`, exactly 14 bytes, for ANY sport type: verified on the
     * wrist 2026-09-05 with an Outdoor Walking workout (`FD 11 23 01` -> pushes `fd235c00…`, HR 92). Only the length
     * identifies it — control echoes are 4 bytes, the pause echo 13 — so `FD 11 23 01` / `FD 00 23 01` must stay echoes.
     */
    @Test
    fun watchOriginatedControlPacketsComeInThe13ByteFormForEveryOp() {
        // Real packets from the 2026-09-05 17:20 run: the watch's own resume (play pressed on its paused screen) and its
        // echo of the app's resume are 13 bytes, like its pause; build 9 accepted 13 B for FD 22 only and dropped these.
        val resume = Packet.parseHex("fd33010100000a000000000000")
        assertEquals(Packet.SportControlEcho(Protocol.SPORT_RESUME, 1, 1, "fd33010100000a000000000000"), resume)
        assertEquals(Packet.SportControlEcho(Protocol.SPORT_RESUME, 1, 1, "fd330101000004000000000000"),
            Packet.parseHex("fd330101000004000000000000"))
        assertEquals(Packet.SportControlEcho(Protocol.SPORT_PAUSE, 1, 1, "fd22010100001000010001153a"),
            Packet.parseHex("fd22010100001000010001153a"))
        assertTrue(Packet.parseHex("fd330101") is Packet.SportControlEcho)              // 4-byte app echo still fine
        // A 13-byte FD 00 has never been seen from the watch (its stop echo is 4 B); Packets.kt parses FD 00 with
        // 5+ bytes as a sport-HISTORY chunk first, so the long stop form is deliberately not asserted here.
        assertTrue(resume !is Packet.SportRt)                                           // 13 B, not the 14-byte realtime
    }

    @Test
    fun sportRtIsIdentifiedByLengthNotBySubCommand() {
        val walk = Packet.parseHex("fd235c0000000000000000000000") as Packet.SportRt
        assertEquals(0x23, walk.sportType)
        assertEquals(92, walk.hr)
        assertEquals(0, walk.calories); assertEquals(0, walk.steps); assertEquals(0.0, walk.distanceMeters, 0.0)
        val run = Packet.parseHex("FD015D0000000000000000000000") as Packet.SportRt     // type 1 still decodes
        assertEquals(1, run.sportType)
        assertEquals(93, run.hr)
        assertTrue(Packet.parseHex("fd112301") is Packet.SportControlEcho)              // start echo
        assertTrue(Packet.parseHex("fd002301") is Packet.SportControlEcho)              // stop echo
        val pause = Packet.parseHex("fd222301000005000000000000")                        // 13-byte pause echo
        assertTrue(pause !is Packet.SportRt)
        assertEquals(Packet.SportControlEcho(Protocol.SPORT_PAUSE, 0x23, 1, "fd222301000005000000000000"), pause)
        // synthetic full packet: FD <type> <hr> cal16 pace_min pace_sec steps24 count16 km km_frac2
        val full = Packet.parseHex("fd0196007b051e0010e100070222") as Packet.SportRt
        assertEquals(1, full.sportType)
        assertEquals(150, full.hr)
        assertEquals(123, full.calories)
        assertEquals(330, full.paceSecPerKm)
        assertEquals(4321, full.steps)
        assertEquals(7, full.count)
        assertEquals(2340.0, full.distanceMeters, 1e-9)
        assertEquals(SportRtData(1, 150, 123, 330, 4321, 7, 2340.0, "fd0196007b051e0010e100070222"), Protocol.decSportRt(hx("fd0196007b051e0010e100070222")))
        assertTrue(Protocol.isSportRt(hx("fd0196007b051e0010e100070222")))
        assertTrue(!Protocol.isSportRt(hx("fd112301")))
        assertTrue(!Protocol.isSportRt(hx("e5015d0000000000000000000000")))
    }

    @Test
    fun foreignTrafficNeverThrows() {
        // Ryze Fit's own traffic when it shares the link (captures/reconnect_20260904_185054.md)
        for (h in listOf(
            "440F0001000456503630", "BB01", "2601FFFFFFFF01D201D200001000000201000000",
            "BE019A6100000000000000000000000000000000", "DBAA000000000000000000000000000001FFFFFF",
            "FD0002666867757777787474766B6B6B6D696C65", "52FE", "C5FD", "A0",
        )) {
            val p = Packet.parseHex(h)
            assertEquals(h.lowercase(), p.raw)
        }
        assertTrue(Packet.parseHex("440F0001000456503630") is Packet.Unknown)
        assertTrue(Packet.parseHex("BB01") is Packet.Unknown)
        assertTrue(Packet.parse(ByteArray(0)) is Packet.Unknown)
        // malformed: impossible date (month 13), truncated records, wrong lengths
        assertTrue(Packet.parseHex("F707EA0D2014FFFFFFFFFFFFFFFFFFFFFF56") is Packet.Unknown)
        assertTrue(Packet.parseHex("B207EA0903") is Packet.Unknown)
        assertTrue(Packet.parseHex("34FA07EA09041400") is Packet.Unknown)
        assertTrue(Packet.parseHex("3203") is Packet.Unknown)
        assertTrue(Packet.parseHex("A2") is Packet.Unknown)
        assertTrue(Packet.parseHex("3101") is Packet.Unknown)
        assertTrue(Packet.parseHex("FD") is Packet.Unknown)
        assertTrue(Packet.parseHex("FD5501") is Packet.Unknown)
        assertTrue(Packet.parseHex("3801") is Packet.Unknown)
        assertTrue(Packet.parseHex("D1FF") is Packet.Unknown)
    }

    @Test
    fun dispatchCoversEveryVerifiedShape() {
        assertTrue(Packet.parseHex("A1524832383052474156303038393439") is Packet.Version)
        assertTrue(Packet.parseHex("A24F") is Packet.Battery)
        assertTrue(Packet.parseHex("A307EA0904123238") is Packet.TimeAck)
        assertTrue(Packet.parseHex("A9") is Packet.UserInfoAck)
        assertTrue(Packet.parseHex("B207EA09031400840000000000062A010084") is Packet.Steps)
        assertTrue(Packet.parseHex("b107ea09041600340000000000060c000034") is Packet.Steps)
        assertTrue(Packet.parseHex("B2FD05") is Packet.StepsEnd)
        assertTrue(Packet.parseHex("F707EA090314FFFFFFFFFFFFFFFFFFFFFF56") is Packet.Hr24)
        assertTrue(Packet.parseHex("F7FDA5") is Packet.Hr24End)
        assertTrue(Packet.parseHex("F70307EA0904130559") is Packet.HrAutoSample)
        assertTrue(Packet.parseHex("F70407EA0904122B693A4B") is Packet.HrSummary)
        assertTrue(Packet.parseHex("F701") is Packet.HrContinuousAck)
        assertTrue(Packet.parseHex("D602") is Packet.HrModeAck)
        assertTrue(Packet.parseHex("34FA07EA0904140060616260FFFFFFFFFFFFFFFF") is Packet.Spo2History)
        assertTrue(Packet.parseHex("34FAFD03") is Packet.Spo2HistoryEnd)
        assertTrue(Packet.parseHex("34000061") is Packet.Spo2Test)
        assertTrue(Packet.parseHex("3400FFFF") is Packet.Spo2Test)
        assertTrue(Packet.parseHex("3411") is Packet.Spo2Test)
        assertTrue(Packet.parseHex("34AAFF") is Packet.Spo2Status)
        assertTrue(Packet.parseHex("340301000A") is Packet.Spo2SettingAck)
        assertTrue(Packet.parseHex("310107EA09041E") is Packet.SleepInfo)
        assertTrue(Packet.parseHex("32173A0101001700150201001E") is Packet.SleepStages)
        assertTrue(Packet.parseHex("3102") is Packet.SleepEnd)
        assertTrue(Packet.parseHex("E511005E") is Packet.LiveHr)
        assertTrue(Packet.parseHex("E511") is Packet.LiveHrAck)
        assertTrue(Packet.parseHex("FD015D0000000000000000000000") is Packet.SportRt)
        assertTrue(Packet.parseHex("FD110101") is Packet.SportControlEcho)
        assertTrue(Packet.parseHex("FD000101") is Packet.SportControlEcho)
        assertTrue(Packet.parseHex("FD44010100001B000000000000") is Packet.SportControlEcho)
        assertTrue(Packet.parseHex("FDAA0001") is Packet.SportQuery)
        assertTrue(Packet.parseHex("FD48AA00010101") is Packet.SportList)
        assertTrue(Packet.parseHex("FDFA07EA090400000003") is Packet.SportHistoryStart)
        assertTrue(Packet.parseHex("FD000001001207EA09040F21146A9A74FD0000BF") is Packet.SportHistory)
        assertTrue(Packet.parseHex("FDFD1E") is Packet.SportHistoryEnd)
        assertTrue(Packet.parseHex("380152797A6520576176652849442D393145352900007802B73791E5010100") is Packet.Bt3)
        assertTrue(Packet.parseHex("380201") is Packet.Bt3Ack)
        assertTrue(Packet.parseHex("D10A00") is Packet.FindPhone)
        assertTrue(Packet.parseHex("D102") is Packet.HangUp)
        assertTrue(Packet.parseHex("D107") is Packet.MusicControl)
        assertTrue(Packet.parseHex("C402") is Packet.CameraShutter)
        assertTrue(Packet.parseHex("D501") is Packet.Password)
    }

    @Test
    fun epochConversionsUseTheGivenZone() {
        val bris = ZoneId.of("Australia/Brisbane")   // UTC+10, no DST
        val local = LocalDateTime.of(2026, 9, 4, 18, 40)
        val ms = local.toEpochMillis(bris)
        assertEquals(LocalDateTime.of(2026, 9, 4, 8, 40), Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDateTime())
        assertEquals(local.toEpochMillis(ZoneOffset.UTC) - 10L * 3600_000L, ms)
        assertEquals(local, localDateTimeOf(ms, bris))
        assertEquals(local, localDateTimeOf(local.toEpochMillis()))   // system zone round-trip
        assertEquals(0L, LocalDateTime.of(1970, 1, 1, 0, 0).toEpochMillis(ZoneOffset.UTC))
    }

    @Test
    fun coreModelConversions() {
        val bris = ZoneId.of("Australia/Brisbane")
        val steps = Protocol.decStepsRecord(hx("B207EA09031400840000000000062A010084"))
        val hour = steps.toStepsHour(bris)
        assertEquals(StepsHour(LocalDateTime.of(2026, 9, 3, 20, 0).toEpochMillis(bris), 132, 132, 0), hour)
        assertEquals(WatchEvent.RealtimeSteps(hour), (Packet.parseHex("B207EA09031400840000000000062A010084") as Packet.Steps).toEvent(bris))

        val hr = HrRecord(LocalDateTime.of(2026, 9, 4, 19, 50), 89)
        assertEquals(HrSample(hr.time.toEpochMillis(bris), 89, SampleSource.HISTORY), hr.toHrSample(zone = bris))
        assertEquals(HrSample(hr.time.toEpochMillis(bris), 89, SampleSource.AUTO),
            (Packet.parseHex("F70307EA0904130559") as Packet.HrAutoSample).toHrSample(bris))
        assertEquals(HrSample(hr.time.toEpochMillis(bris), 89, SampleSource.AUTO), HrPush.Sample(hr.time, 89).toHrSample(bris))

        val spo2 = Spo2Record(LocalDateTime.of(2026, 9, 4, 18, 10), 96)
        assertEquals(Spo2Sample(spo2.time.toEpochMillis(bris), 96, SampleSource.HISTORY), spo2.toSpo2Sample(zone = bris))

        val stage = SleepStageRecord(LocalDateTime.of(2026, 9, 3, 23, 58), 1, 23)
        assertEquals(SleepStage(stage.time.toEpochMillis(bris), 1, 23), stage.toSleepStage(bris))

        val summary = Packet.parseHex("F70407EA0904122B693A4B") as Packet.HrSummary
        assertEquals(WatchEvent.HrSummary(LocalDateTime.of(2026, 9, 4, 18, 43).toEpochMillis(bris), 105, 58, 75), summary.toEvent(bris))
        assertEquals(summary.toEvent(bris), HrPush.Summary(summary.time, 105, 58, 75).toEvent(bris))
    }

    @Test
    fun convenienceEncodersFromCoreTypes() {
        val bris = ZoneId.of("Australia/Brisbane")
        val since = LocalDateTime.of(2026, 9, 4, 18, 38).toEpochMillis(bris)
        assertEquals("f7fa07ea09041226", Protocol.hex(Protocol.encFetchHr24Since(since, zone = bris)))
        assertEquals("f7fa000000000000", Protocol.hex(Protocol.encFetchHr24Since(null)))
        assertEquals("f7fa", Protocol.hex(Protocol.encFetchHr24Since(since, withTs = false, zone = bris)))
        val profile = UserProfile(heightCm = 175, weightKg = 75, age = 40, male = true, stepGoal = 8000)
        assertEquals("a900af004b0500001f4001a500280100020146", Protocol.hex(Protocol.encUserInfo(profile, hrHigh = 165, hrLow = 70)))
        assertEquals("a900af004b0500001f4001000028010002010" + "0", Protocol.hex(Protocol.encUserInfo(profile)))
        assertEquals(19, Protocol.encUserInfo(UserProfile()).size)
    }

    @Test
    fun featuresRejectsWrongLength() {
        try {
            Features.parse(ByteArray(19))
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("20 bytes"))
        }
        assertEquals(0, Features.parse(ByteArray(20)).word(3))
        assertEquals(0, Features.parse(ByteArray(20)).word(9))
    }

    @Test
    fun sleepStagesDecodeRejectsBadLength() {
        try {
            Protocol.decSleepStages(LocalDate.of(2026, 9, 4), hx("321700"))
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("sleep"))
        }
        assertTrue(Protocol.decSleepStages(LocalDate.of(2026, 9, 4), hx("32")).isEmpty())
    }
}
