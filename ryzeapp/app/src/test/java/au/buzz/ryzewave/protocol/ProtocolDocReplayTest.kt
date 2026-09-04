package au.buzz.ryzewave.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Replays the packets quoted in docs/PROTOCOL.md (sections 2, 5, 6a, 6b, 8) and the raw captures they cite
 * (captures/reconnect_20260904_185054.md, captures/bridge_*.txt). Bytes must match exactly.
 */
class ProtocolDocReplayTest {

    private fun hx(s: String): ByteArray = Protocol.fromHex(s)
    private fun ByteArray.hex(): String = Protocol.hex(this)
    private fun t(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): LocalDateTime = LocalDateTime.of(y, mo, d, h, mi)

    // ------------------------------------------------------------ section 2: connect burst, 2026-09-04 18:50

    @Test
    fun featureBitmapReadOf33F1() {
        val f = Features.parseHex("080A642A210C3943756EDFFED921005D784BA1D4")
        assertEquals(0x4BA1D4, f.word(1))
        assertEquals(0x005D78, f.word(2))
        assertEquals(0xFED921, f.word(3))
        assertEquals(0x756EDF, f.word(4))
        assertEquals(0x0C3943, f.word(5))
        assertEquals(0x642A21, f.word(6))
        assertEquals(0x00080A, f.word(7))
        assertFalse("password bit is clear on the Ryze Wave", f.password)
        assertTrue(f.syncTimestamp)       // FL4 & 0x2000
        assertTrue(f.sleepV2)             // FL4 & 0x40000
        assertTrue(f.sportControlSync)    // FL5 & 2
        assertFalse(f.accountId)          // FL5 & 4
        assertTrue(f.genderStride)        // FL2 & 0x1000 (section 9)
        assertEquals(
            "FL1=0x4BA1D4 FL2=0x005D78 FL3=0xFED921 FL4=0x756EDF FL5=0x0C3943 FL6=0x642A21 FL7=0x00080A" +
                " password=false sync_ts=true sleep_v2=true sport_sync=true",
            f.toString(),
        )
        assertEquals("080a642a210c3943756edffed921005d784ba1d4", f.rawHex)
    }

    @Test
    fun dataChannelReadOf34F1() {
        val info = Protocol.decDataChannelRead(hx("00F4000000000000000000000000000000400000"))
        assertEquals(DataChannelInfo(244, 0x400000), info)
        assertEquals(DataChannelInfo(244, 0), Protocol.decDataChannelRead(hx("00F4")))
    }

    @Test
    fun setTimeAndEcho() {
        assertEquals("a307ea0904123238", Protocol.encSetTime(LocalDateTime.of(2026, 9, 4, 18, 50, 56)).hex())
        assertEquals("a307ea0904123304", Protocol.encSetTime(LocalDateTime.of(2026, 9, 4, 18, 51, 4)).hex())
        assertTrue(Packet.parseHex("A307EA0904123238") is Packet.TimeAck)
    }

    @Test
    fun bt3QueryAndReply() {
        assertEquals("38010200d25dae5d", Protocol.encBt3Query(hx("D25DAE5D")).hex())
        assertEquals("3801020000000000", Protocol.encBt3Query().hex())
        assertEquals("38010200d25d0000", Protocol.encBt3Query(hx("D25D")).hex())
        assertEquals("38010200d25dae5d", Protocol.encBt3Query(hx("D25DAE5D99")).hex())
        // reply on 34F2: 38 01 "Ryze Wave(ID-91E5)" 00 00 <MAC> 01 01 00
        val reply = hx("380152797A6520576176652849442D393145352900007802B73791E5010100")
        assertEquals(31, reply.size)
        assertEquals(Bt3Info("Ryze Wave(ID-91E5)", "78:02:B7:37:91:E5", true, 1, 0), Protocol.decBt3Info(reply))
        // right after 38 02 01 with the phone bonded (captures/bridge_passive_20260904_195347.txt)
        val bonded = Protocol.decBt3Info(hx("380152797a6520576176652849442d393145352900007802b73791e5010101"))
        assertEquals(Bt3Info("Ryze Wave(ID-91E5)", "78:02:B7:37:91:E5", true, 1, 1), bonded)
        assertNull(Protocol.decBt3Info(hx("380201")))
        assertEquals("380201", Protocol.encBt3Enable(true).hex())
        assertEquals("380200", Protocol.encBt3Enable(false).hex())
        val p = Packet.parseHex("380152797A6520576176652849442D393145352900007802B73791E5010100") as Packet.Bt3
        assertEquals("78:02:B7:37:91:E5", p.info.mac)
        assertEquals(Packet.Bt3Ack(true, "380201"), Packet.parseHex("380201"))
    }

    @Test
    fun userInfoAsSentByRyzeFit() {
        // 175 cm, 75 kg, 8000 steps, HR alert 165/70, age 40, male, celsius
        val b = Protocol.encUserInfo(175, 75, 8000, 40, true, raiseWrist = true, hrHigh = 165, hrLow = 70, celsius = true)
        assertEquals("a900af004b0500001f4001a500280100020146", b.hex())
        assertTrue(Packet.parseHex("A9") is Packet.UserInfoAck)
    }

    @Test
    fun versionAndBatteryReplies() {
        assertEquals("a1", Protocol.encVersion().hex())
        assertEquals("a101", Protocol.encDspVersion().hex())
        assertEquals("a2", Protocol.encBattery().hex())
        assertEquals("RH280RGAV008949", Protocol.decVersion(hx("A1524832383052474156303038393439")))
        assertEquals(Packet.Version("RH280RGAV008949", false, "a1524832383052474156303038393439"),
            Packet.parseHex("A1524832383052474156303038393439"))
        assertEquals(Packet.Version("V1.0", true, "a10156312e30"), Packet.parseHex("A10156312E30"))
        assertEquals(BatteryInfo(79, false), Protocol.decBattery(hx("A24F")))
        assertEquals(BatteryInfo(78, false), Protocol.decBattery(hx("a24e")))
        assertEquals(BatteryInfo(76, true), Protocol.decBattery(hx("a24c01")))   // charging push
        assertEquals(Packet.Battery(76, true, "a24c01"), Packet.parseHex("a24c01"))
    }

    @Test
    fun fetchCommandsFromTheConnectBurst() {
        assertEquals("b2fa", Protocol.encFetchSteps().hex())
        assertEquals("3101", Protocol.encFetchSleep().hex())
        assertEquals("f7fa07ea09041226", Protocol.encFetchHr24(LocalDateTime.of(2026, 9, 4, 18, 38)).hex())
        assertEquals("f7fa07ea09041232", Protocol.encFetchHr24(LocalDateTime.of(2026, 9, 4, 18, 50, 59)).hex())
        assertEquals("f7fa000000000000", Protocol.encFetchHr24(null).hex())
        assertEquals("f7fa", Protocol.encFetchHr24(null, withTs = false).hex())
        assertEquals("e6fa07ea09041226", Protocol.encFetchHrSingle(LocalDateTime.of(2026, 9, 4, 18, 38)).hex())
        assertEquals("fdaa", Protocol.encSportQuery().hex())
        assertEquals("fdfa07ea09040000", Protocol.encFetchSportHistory(LocalDateTime.of(2026, 9, 4, 0, 0)).hex())
        assertEquals("34fa", Protocol.encFetchSpo2().hex())
        assertEquals("340301000a", Protocol.encSpo2Auto(true, 10).hex())
        assertEquals("3404010001173b", Protocol.encSpo2Period(true).hex())
        assertEquals("f701", Protocol.encHrContinuous(true).hex())
        assertEquals("f702", Protocol.encHrContinuous(false).hex())
        assertEquals("3f040100001f40", Protocol.encGoal(Protocol.Goal.STEPS, 8000).hex())
        assertEquals("3f040100001f40", Protocol.encStepGoal(8000).hex())
        assertEquals("3f030100000320", Protocol.encGoal(Protocol.Goal.CALORIES, 800).hex())
        assertEquals("3f05010000ea60", Protocol.encGoal(Protocol.Goal.DISTANCE, 60000).hex())
        assertEquals("ab0000000102070" + "1", Protocol.encFindWatch().hex())
        assertEquals("d501", Protocol.encPasswordQuery().hex())
        assertEquals("d502", Protocol.encPasswordRequestCode().hex())
        assertEquals("d61000", Protocol.encHrTimed(0).hex())
        assertEquals("e511", Protocol.encRtHr(true).hex())
        assertEquals("e500", Protocol.encRtHr(false).hex())
        assertEquals("3411", Protocol.encSpo2Test(true).hex())
        assertEquals("3400", Protocol.encSpo2Test(false).hex())
        assertEquals("34aa", Protocol.encSpo2Status().hex())
    }

    // ------------------------------------------------------------ steps (B2 / B1)

    @Test
    fun stepsRecordsFromTheCapture() {
        // 2026-09-03 20h total=132 run=0 walk=132
        val r = Protocol.decStepsRecord(hx("B207EA09031400840000000000062A010084"))
        assertEquals(StepsRecord(t(2026, 9, 3, 20), 132, 0, 0, 0, 0x06, 0x2A, 132), r)
        // 2026-09-04 13h total=556
        val r2 = Protocol.decStepsRecord(hx("B207EA09040D022C00000000000F2E07022C"))
        assertEquals(t(2026, 9, 4, 13), r2.time)
        assertEquals(556, r2.total)
        assertEquals(556, r2.walkSteps)
        assertEquals(0, r2.runSteps)
        // realtime push B1 (same layout): 2026-09-04 22h total=52
        val rt = Packet.parseHex("b107ea09041600340000000000060c000034") as Packet.Steps
        assertTrue(rt.realtime)
        assertEquals(t(2026, 9, 4, 22), rt.record.time)
        assertEquals(52, rt.record.total)
        assertEquals(Packet.StepsEnd(5, "b2fd05"), Packet.parseHex("B2FD05"))
        assertTrue(Protocol.isStepsEnd(hx("B2FD05")))
        assertFalse(Protocol.isStepsEnd(hx("B207EA09031400840000000000062A010084")))
    }

    // ------------------------------------------------------------ 24h HR (F7), section 6a timing

    @Test
    fun hr24FirstRecordAfterPuttingTheWatchOn() {
        // F7 07EA 09 03 14 FF×11 56: a single sample 86 bpm at exactly 20:00
        val out = Protocol.decHr24Record(hx("F707EA090314FFFFFFFFFFFFFFFFFFFFFF56"))
        assertEquals(listOf(HrRecord(t(2026, 9, 3, 20, 0), 86)), out)
    }

    @Test
    fun hr24RecordEndingAtMidnightBelongsToBothDays() {
        // date byte is 09-04, HH = 00: window 2026-09-03 22:10 … 2026-09-04 00:00, first slot empty
        val out = Protocol.decHr24Record(hx("F707EA090400FF5957595751575855504F47"))
        assertEquals(11, out.size)
        assertEquals(HrRecord(t(2026, 9, 3, 22, 20), 89), out.first())
        assertEquals(HrRecord(t(2026, 9, 3, 23, 50), 79), out[out.size - 2])
        assertEquals(HrRecord(t(2026, 9, 4, 0, 0), 71), out.last())
    }

    @Test
    fun hr24PartialRecordAt1935() {
        // section 6a: at 19:35 the 20 h record held 8 values (18:10 … 19:20) followed by FFs
        val out = Protocol.decHr24Record(hx("F707EA090414" + "60625C6260615F5E" + "FFFFFFFF"))
        assertEquals(8, out.size)
        assertEquals(t(2026, 9, 4, 18, 10), out.first().time)
        assertEquals(t(2026, 9, 4, 19, 20), out.last().time)
        assertEquals(listOf(96, 98, 92, 98, 96, 97, 95, 94), out.map { it.bpm })
        for (i in 1 until out.size) assertEquals(10L, java.time.Duration.between(out[i - 1].time, out[i].time).toMinutes())
        // the same slot as captured at 18:50 (4 values)
        val early = Protocol.decHr24Record(hx("F707EA09041460625C62FFFFFFFFFFFFFFFF"))
        assertEquals(listOf(96, 98, 92, 98), early.map { it.bpm })
        assertEquals(t(2026, 9, 4, 18, 40), early.last().time)
    }

    @Test
    fun hr24FullRecordAndEnd() {
        val out = Protocol.decHr24Record(hx("F707EA090412789A68766971736962595C59"))
        assertEquals(12, out.size)
        assertEquals(HrRecord(t(2026, 9, 4, 16, 10), 120), out.first())
        assertEquals(HrRecord(t(2026, 9, 4, 18, 0), 89), out.last())
        assertTrue(Protocol.isHr24End(hx("F7FDA5")))
        assertEquals(Packet.Hr24End(0xA5, "f7fda5"), Packet.parseHex("F7FDA5"))
        val p = Packet.parseHex("F707EA090412789A68766971736962595C59") as Packet.Hr24
        assertEquals(out, p.samples)
    }

    // ------------------------------------------------------------ SpO2 (34)

    @Test
    fun spo2HistoryRecordEndingAt2000() {
        // section 6a: 34 FA … 14 00 = bins ending 20:00, four samples 18:10 … 18:40
        val out = Protocol.decSpo2Record(hx("34FA07EA0904140060616260FFFFFFFFFFFFFFFF"))
        assertEquals(4, out.size)
        assertEquals(Spo2Record(t(2026, 9, 4, 18, 10), 96), out.first())
        assertEquals(Spo2Record(t(2026, 9, 4, 18, 40), 96), out.last())
        assertTrue(Protocol.isSpo2End(hx("34FAFD03")))
        assertEquals(Packet.Spo2HistoryEnd(3, "34fafd03"), Packet.parseHex("34FAFD03"))
        assertTrue(Packet.parseHex("34FA07EA0904140060616260FFFFFFFFFFFFFFFF") is Packet.Spo2History)
    }

    @Test
    fun spo2SpotTestSequence() {
        // 34 11 -> ; 34 11 <- ack ; 34 00 FF FF <- spurious ; ~57 s later 34 00 00 61 <- 97 %
        assertEquals(Spo2Result(Spo2Phase.STARTED, null, null), Protocol.decSpo2Result(hx("3411")))
        assertEquals(Spo2Result(Spo2Phase.FINAL, null, 0xFF), Protocol.decSpo2Result(hx("3400FFFF")))
        assertEquals(Spo2Result(Spo2Phase.FINAL, 97, 0), Protocol.decSpo2Result(hx("34000061")))
        assertEquals(Spo2Result(Spo2Phase.STOPPED, null, null), Protocol.decSpo2Result(hx("3400")))
        assertEquals(Spo2Result(Spo2Phase.MEASURING, 96, 0), Protocol.decSpo2Result(hx("34110060")))
        assertEquals(Packet.Spo2Test(Spo2Result(Spo2Phase.FINAL, 98, 0), "34000062"), Packet.parseHex("34000062"))
        assertEquals(Packet.Spo2Status(0xFF, "34aaff"), Packet.parseHex("34AAFF"))
        assertEquals(Packet.Spo2SettingAck(0x03, "340301000a"), Packet.parseHex("340301000A"))
    }

    // ------------------------------------------------------------ sleep (31 / 32)

    @Test
    fun sleepSessionFromTheCapture() {
        val info = Protocol.decSleepInfo(hx("310107EA09041E"))
        assertEquals(SleepSessionInfo(LocalDate.of(2026, 9, 4), 30), info)
        val stages = hx(
            "32173A0101001700150201001E00330401000400370201001501100301000101110201000301140401000501190201002B" +
                "02080301000102090201000A021303010006021902010003021C0401000502210201001702380401000503010201001103" +
                "120401000203140201002C04040101001E042202010038051E0401000905270201003E062903010001062A02010001062B" +
                "01010002062D04010005063202010030072604010003072901010013080004010008",
        )
        val st = Protocol.decSleepStages(info!!.date, stages)
        assertEquals(30, st.size)
        assertEquals(SleepStageRecord(LocalDateTime.of(2026, 9, 3, 23, 58), 1, 23), st[0])
        assertEquals(SleepStageRecord(LocalDateTime.of(2026, 9, 4, 0, 21), 2, 30), st[1])
        assertEquals(SleepStageRecord(LocalDateTime.of(2026, 9, 4, 0, 51), 4, 4), st[2])
        assertEquals(SleepStageRecord(LocalDateTime.of(2026, 9, 4, 8, 0), 4, 8), st.last())
        for (i in 1 until st.size) assertTrue("stages are chronological", st[i].time.isAfter(st[i - 1].time))
        assertTrue(st.all { it.stage in 1..4 })
        val p = Packet.parseHex(Protocol.hex(stages)) as Packet.SleepStages
        assertEquals(30, p.count)
        assertEquals(st, p.decode(LocalDate.of(2026, 9, 4)))
        assertTrue(Protocol.isSleepEnd(hx("3102")))
        assertEquals(Packet.SleepEnd("3102"), Packet.parseHex("3102"))
        assertEquals(Packet.SleepInfo(LocalDate.of(2026, 9, 4), 30, "310107ea09041e"), Packet.parseHex("310107EA09041E"))
    }

    // ------------------------------------------------------------ HR pushes (F7 03 / F7 04), live HR (E5)

    @Test
    fun hrAutoSamplePushUsesTenMinuteBinIndex() {
        // pushed 19:58 as "13 05" = 19:50 -> 89
        assertEquals(HrPush.Sample(t(2026, 9, 4, 19, 50), 89), Protocol.decHrPush(hx("F70307EA0904130559")))
        // pushed 18:48 as "12 04" = 18:40 -> 98
        assertEquals(HrPush.Sample(t(2026, 9, 4, 18, 40), 98), Protocol.decHrPush(hx("F70307EA0904120462")))
        // more from captures/bridge_passive_20260904_195347.txt
        assertEquals(HrPush.Sample(t(2026, 9, 4, 19, 30), 95), Protocol.decHrPush(hx("f70307ea090413035f")))
        assertEquals(HrPush.Sample(t(2026, 9, 4, 20, 0), 93), Protocol.decHrPush(hx("f70307ea090414005d")))
        assertEquals(HrPush.Sample(t(2026, 9, 4, 22, 0), 81), Protocol.decHrPush(hx("f70307ea0904160051")))
        // a byte-7 value above 5 is taken as plain minutes (SDK reading)
        assertEquals(HrPush.Sample(t(2026, 9, 4, 18, 43), 98), Protocol.decHrPush(hx("F70307EA0904122B62")))
        val p = Packet.parseHex("F70307EA0904130559") as Packet.HrAutoSample
        assertEquals(HrRecord(t(2026, 9, 4, 19, 50), 89), p.sample)
        assertNull(Protocol.decHrPush(hx("F70307EA090413")))
        assertNull(Protocol.decHrPush(hx("F70507EA0904130559")))
    }

    @Test
    fun hrSummaryPush() {
        val s = Protocol.decHrPush(hx("f70407ea09041327693a4c")) as HrPush.Summary
        assertEquals(HrPush.Summary(t(2026, 9, 4, 19, 39), 105, 58, 76), s)
        assertEquals(Packet.HrSummary(t(2026, 9, 4, 18, 43), 105, 58, 75, "f70407ea0904122b693a4b"),
            Packet.parseHex("F70407EA0904122B693A4B"))
        assertNull(Protocol.decHrPush(hx("F70407EA0904122B69")))   // too short for a summary
    }

    @Test
    fun liveHrStream() {
        assertEquals(94, Protocol.decRtHr(hx("e511005e")))
        assertEquals(0, Protocol.decRtHr(hx("e5110000")))            // warm-up
        assertNull(Protocol.decRtHr(hx("e511")))
        assertNull(Protocol.decRtHr(hx("e500")))
        assertEquals(Packet.LiveHr(94, "e511005e"), Packet.parseHex("e511005e"))
        assertEquals(Packet.LiveHrAck(true, "e511"), Packet.parseHex("e511"))
        assertEquals(Packet.LiveHrAck(false, "e500"), Packet.parseHex("e500"))
        assertEquals(Packet.HrModeAck(0x02, "d602"), Packet.parseHex("d602"))
        assertEquals(Packet.HrContinuousAck(true, "f701"), Packet.parseHex("f701"))
    }

    // ------------------------------------------------------------ workout (FD)

    @Test
    fun workoutControlEchoes() {
        assertEquals("fd110101", Protocol.encSportControl(Protocol.SPORT_START, 1).hex())
        assertEquals("fd220101", Protocol.encSportControl(Protocol.SPORT_PAUSE, 1).hex())
        assertEquals("fd330101", Protocol.encSportControl(Protocol.SPORT_RESUME, 1).hex())
        assertEquals("fd000101", Protocol.encSportControl(Protocol.SPORT_STOP, 1).hex())
        assertEquals("fd1102ff", Protocol.encSportControl(Protocol.SPORT_START, 2, 999).hex())
        assertEquals("fd110201", Protocol.encSportControl(Protocol.SPORT_START, 2, 0).hex())
        assertEquals(SportControl(Protocol.SPORT_START, 1, 1), Protocol.decSportControl(hx("FD110101")))
        assertEquals(SportControl(Protocol.SPORT_STOP, 1, 1), Protocol.decSportControl(hx("fd000101")))
        assertEquals(Packet.SportControlEcho(Protocol.SPORT_STOP, 1, 1, "fd000101"), Packet.parseHex("fd000101"))
        assertEquals(SportState(0, 1), Protocol.decSportState(hx("FDAA0001")))
        assertEquals(Packet.SportQuery(0, 1, "fdaa0001"), Packet.parseHex("FDAA0001"))
    }

    @Test
    fun workoutRealtimeHr() {
        // FD <type> <hr> 00×11 (14 B), ~1/s; byte 1 is the sport id (1 = Outdoor Running here)
        val rt = Protocol.decSportRt(hx("fd015d0000000000000000000000"))
        assertEquals(SportRtData(1, 93, 0, 0, 0, 0, 0.0, "fd015d0000000000000000000000"), rt)
        assertEquals(Packet.SportRt(1, 82, 0, 0, 0, 0, 0.0, "fd01520000000000000000000000"), Packet.parseHex("FD01520000000000000000000000"))
        // Outdoor Walking (FD 11 23 01), verified 2026-09-05 08:23: the push is FD 23 5C … = HR 92
        assertEquals(Packet.SportRt(0x23, 92, 0, 0, 0, 0, 0.0, "fd235c0000000000000000000000"), Packet.parseHex("fd235c0000000000000000000000"))
        assertTrue(Protocol.isSportRt(hx("fd235c0000000000000000000000")))
        assertFalse(Protocol.isSportRt(hx("fd112301")))
    }

    @Test
    fun workoutMetricsPushExactLayout() {
        // FD 44 <type> <ivl> hh mm ss cal16 km km_frac2 pace_min pace_sec  (13 bytes)
        val b = Protocol.encSportUpdate(sportType = 1, durationS = 3725, calories = 123, distanceM = 2340.0, paceSPerKm = 330.0)
        assertEquals(13, b.size)
        assertEquals("fd4401" + "01" + "010205" + "007b" + "0222" + "051e", b.hex())
        assertEquals(SportControl(Protocol.SPORT_UPDATE, 1, 1), Protocol.decSportControl(b))   // the watch echoes it
        assertTrue(Packet.parse(b) is Packet.SportControlEcho)
        // zero metrics, 27 s
        assertEquals("fd440101" + "00001b" + "000000000000", Protocol.encSportUpdate(1, 27).hex())
        // no GPS: 7 bytes
        assertEquals("fd440101" + "00001b", Protocol.encSportUpdate(1, 27, gps = false).hex())
        // 12.34 km, pace 4:05, 2000 kcal, 10 h
        assertEquals("fd440101" + "0a0000" + "07d0" + "0c22" + "0405",
            Protocol.encSportUpdate(1, 36000, 2000, 12340.0, 245.0).hex())
        // pace slower than 99:59 min/km is sent as 0:00 (Python reference)
        assertEquals("000000000000", Protocol.encSportUpdate(1, 0, 0, 0.0, 6000.0).hex().substring(14))
        assertEquals("0000000000633b", Protocol.encSportUpdate(1, 0, 0, 0.0, 5999.0).hex().substring(12))
        // 2.9999 km rounds to 3.00, not 2.100 (carry into the whole km)
        assertEquals("0300", Protocol.encSportUpdate(1, 0, 0, 2999.9, 0.0).hex().substring(18, 22))
        // garbage in -> zeros out, never an exception
        assertEquals("fd440101" + "000000" + "000000000000",
            Protocol.encSportUpdate(1, -5, -1, Double.NaN, Double.POSITIVE_INFINITY).hex())
        // calories saturate at u16
        assertEquals("ffff", Protocol.encSportUpdate(1, 0, 70000).hex().substring(14, 18))
        // custom state / interval
        assertEquals("fd110205", Protocol.encSportUpdate(2, 0, state = Protocol.SPORT_START, hrIntervalS = 5, gps = false).hex().substring(0, 8))
    }

    @Test
    fun workoutHistoryRepliesAreNotStopEchoes() {
        // FD FA 07EA 09 04 00 00 -> FD FA 07EA 09 04 00 00 00 03, then FD 00 <part> …, then FD FD 1E
        assertEquals(3, Protocol.decSportHistoryStart(hx("FDFA07EA090400000003")))
        assertEquals(Packet.SportHistoryStart(3, "fdfa07ea090400000003"), Packet.parseHex("FDFA07EA090400000003"))
        val header = hx("FD000001001207EA09040F21146A9A74FD0000BF")
        assertNull("a 20-byte FD 00 packet is not a stop echo", Protocol.decSportControl(header))
        val chunk = Protocol.decSportHistoryChunk(header)!!
        assertEquals(0, chunk.part)
        assertEquals("01001207ea09040f21146a9a74fd0000bf", chunk.payloadHex)
        assertEquals(SportHistoryHeader(1, 0x12, LocalDateTime.of(2026, 9, 4, 15, 33, 20)), Protocol.decSportHistoryHeader(chunk))
        assertEquals(LocalDateTime.of(2026, 9, 4, 16, 17, 6),
            Protocol.decSportHistoryHeader(Protocol.decSportHistoryChunk(hx("fd000001005a07ea09041011066a9a8215000384"))!!)!!.start)
        assertEquals(LocalDateTime.of(2026, 9, 4, 17, 35, 47),
            Protocol.decSportHistoryHeader(Protocol.decSportHistoryChunk(hx("fd000001013a07ea090411232f6a9a9e66000c49"))!!)!!.start)
        val p = Packet.parseHex("FD000001001207EA09040F21146A9A74FD0000BF") as Packet.SportHistory
        assertEquals(0, p.chunk.part)
        val summary = Packet.parseHex("FD00010001500000000D000C6E7865192801") as Packet.SportHistory
        assertEquals(1, summary.chunk.part)
        assertNull(Protocol.decSportHistoryHeader(summary.chunk))
        val hr = Packet.parseHex("FD0002666867757777787474766B6B6B6D696C65") as Packet.SportHistory
        assertEquals(2, hr.chunk.part)
        assertEquals(0x66, hr.chunk.payload[0].toInt() and 0xFF)
        assertTrue(Protocol.isSportHistoryEnd(hx("FDFD1E")))
        assertEquals(Packet.SportHistoryEnd(0x1E, "fdfd1e"), Packet.parseHex("FDFD1E"))
        assertNull(Protocol.decSportHistoryChunk(hx("fd000101")))
        assertNull(Protocol.decSportHistoryChunk(hx("fd01520000")))
    }

    @Test
    fun sportListFromTheDataChannel() {
        val p = Packet.parseHex("fd48aa00" + "010101" + "020102" + "040103" + "050104") as Packet.SportList
        assertEquals(4, p.entries.size)
        assertFalse(p.end)
        val end = Packet.parseHex("fd48aafd0900040046") as Packet.SportList
        assertTrue(end.end)
        assertTrue(end.entries.isEmpty())
        assertTrue(Protocol.isSportListEnd(hx("fd48aafd0900040046")))
    }

    // ------------------------------------------------------------ D1 / D5 / misc

    @Test
    fun actionAndPasswordPackets() {
        assertEquals(Packet.FindPhone(false, "d10a00"), Packet.parseHex("D10A00"))
        assertEquals(Packet.FindPhone(true, "d10a01"), Packet.parseHex("D10A01"))
        assertEquals(Packet.HangUp("d102"), Packet.parseHex("D102"))
        assertEquals(Packet.MusicControl(Protocol.ACTION_MUSIC_NEXT, "d108"), Packet.parseHex("D108"))
        assertEquals(Packet.CameraShutter("c402"), Packet.parseHex("C402"))
        assertEquals(Packet.Password(0x01, "d501"), Packet.parseHex("D501"))
        assertEquals(Packet.Password(0xFF, "d5ff"), Packet.parseHex("D5FF"))
    }

    @Test
    fun sinceStampAndDateHelpers() {
        assertEquals("07ea09041226", Protocol.sinceStamp(LocalDateTime.of(2026, 9, 4, 18, 38, 59)).hex())
        assertEquals("000000000000", Protocol.sinceStamp(null).hex())
        assertEquals(LocalDate.of(2025, 7, 3), Protocol.date(hx("f707e90703"), 1))
        assertEquals("f7", Protocol.hex(Protocol.fromHex("F7")))
        assertEquals("f7fa07ea", Protocol.hex(Protocol.fromHex("F7 FA:07 ea")))
        assertEquals(0xF7, Protocol.opcode(hx("F7FA")))
        assertEquals(0xFA, Protocol.sub(hx("F7FA")))
        assertEquals(-1, Protocol.sub(hx("F7")))
        assertEquals(-1, Protocol.opcode(ByteArray(0)))
        assertEquals("HR24 f7fda5", Protocol.describe(hx("F7FDA5")))
        assertEquals("BT3", Protocol.opName(0x38))
        assertEquals("MOOD", Protocol.opName(0x44))
        assertEquals("99", Protocol.opName(0x99))
        assertEquals("<empty>", Protocol.describe(ByteArray(0)))
    }
}
