package au.buzz.ryzewave.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * One-to-one replay of tests/test_protocol.py at the repo root (the Python reference tests).
 * Every hex string is copied verbatim; the Kotlin port must produce exactly the same bytes / values.
 */
class ProtocolPyReplayTest {

    private fun hx(s: String): ByteArray = Protocol.fromHex(s)
    private fun ByteArray.hex(): String = Protocol.hex(this)

    @Test
    fun test_version_battery() {
        assertEquals("RH281LWOV008474", Protocol.decVersion(hx("a152483238314c574f56303038343734")))
        assertEquals(BatteryInfo(0x48, false), Protocol.decBattery(hx("a248")))
        assertEquals(BatteryInfo(0x52, true), Protocol.decBattery(hx("a25201")))
    }

    @Test
    fun test_time() {
        assertEquals("a307e90703152600", Protocol.encSetTime(LocalDateTime.of(2025, 7, 3, 21, 38, 0)).hex())
    }

    @Test
    fun test_user_info() {
        val b = Protocol.encUserInfo(heightCm = 170, weightKg = 70, stepGoal = 8000, age = 56, male = false)
        assertEquals("a900aa00460500001f4001000038020002010" + "0", b.hex())
        assertEquals(19, b.size)
    }

    @Test
    fun test_steps_record() {
        val r = Protocol.decStepsRecord(hx("b207e907021700712d31000043313b00002e"))
        assertEquals(LocalDateTime.of(2025, 7, 2, 23, 0), r.time)
        assertEquals(0x71, r.total)
    }

    @Test
    fun test_hr24() {
        val out = Protocol.decHr24Record(hx("f707e90703083030315c2f2f2e2f2f353331"))
        // last value 0x31 = 49 at 08:00; first value 0x30 at 06:10
        assertEquals(HrRecord(LocalDateTime.of(2025, 7, 3, 8, 0), 0x31), out.last())
        assertEquals(HrRecord(LocalDateTime.of(2025, 7, 3, 6, 10), 0x30), out.first())
        assertEquals(12, out.size)
    }

    @Test
    fun test_spo2() {
        val out = Protocol.decSpo2Record(hx("34fa07e907030400ffff60ffff61ffff61ffff60"))
        assertEquals(Spo2Record(LocalDateTime.of(2025, 7, 3, 4, 0), 0x60), out.last())
        assertEquals(4, out.size)
    }

    @Test
    fun test_features() {
        val f = Features.parseHex("080a" + "642a21" + "0c3943" + "756edf" + "fed921" + "005d78" + "000001")
        assertEquals(0x080A, f.word(7))
        assertEquals(0x642A21, f.word(6))
        assertEquals(1, f.word(1))
        assertTrue(f.password)
        assertTrue(f.syncTimestamp)
        assertTrue(f.sleepV2)
        assertTrue(f.sportControlSync)
        assertFalse(f.accountId)
    }

    @Test
    fun test_password() {
        val expected = "1234".toByteArray(Charsets.US_ASCII)
            .zip("UTE8".toByteArray(Charsets.US_ASCII)) { a, b -> (a.toInt() xor b.toInt()).toByte() }
            .toByteArray()
        assertEquals("d501" + expected.hex(), Protocol.encPasswordAuth("1234").hex())
        assertEquals("d5016466760c", Protocol.encPasswordAuth("1234").hex())
        assertEquals("d50235363738", Protocol.encPasswordInput("5678").hex())
        assertEquals("d50335363738", Protocol.encPasswordInput("5678", setNew = true).hex())
    }

    @Test
    fun test_sport() {
        assertEquals("fd110101", Protocol.encSportControl(Protocol.SPORT_START, 1, 1).hex())
        assertEquals("fd440101" + "00001b" + "000000000000", Protocol.encSportUpdate(sportType = 1, durationS = 27).hex())
        assertEquals(93, Protocol.decSportRt(hx("fd015d0000000000000000000000")).hr)
    }

    @Test
    fun test_sleep_stages() {
        val st = Protocol.decSleepStages(LocalDate.of(2025, 7, 3), hx("32" + "1700020100b4" + "0100010100b4"))
        assertEquals(LocalDateTime.of(2025, 7, 2, 23, 0), st[0].time)
        assertEquals(LocalDateTime.of(2025, 7, 3, 1, 0), st[1].time)
        assertEquals(SleepStageRecord(LocalDateTime.of(2025, 7, 2, 23, 0), 2, 180), st[0])
        assertEquals(SleepStageRecord(LocalDateTime.of(2025, 7, 3, 1, 0), 1, 180), st[1])
    }

    @Test
    fun test_hr_push() {
        val r = Protocol.decHrPush(hx("F70307EA0904120462"))
        assertEquals(HrPush.Sample(LocalDateTime.of(2026, 9, 4, 18, 40), 98), r)
        val s = Protocol.decHrPush(hx("F70407EA0904122B693A4B")) as HrPush.Summary
        assertEquals(listOf(105, 58, 75), listOf(s.max, s.min, s.avg))
    }

    @Test
    fun test_sport_list() {
        val lst = Protocol.decSportList(hx("fd48aa00" + "010101" + "020102" + "040103" + "050104"))
        assertEquals(
            listOf(SportListEntry(1, true, 1), SportListEntry(2, true, 2), SportListEntry(4, true, 3), SportListEntry(5, true, 4)),
            lst,
        )
        assertEquals(emptyList<SportListEntry>(), Protocol.decSportList(hx("fd48aafd0900040046")))
    }

    @Test
    fun test_hr_mode() {
        assertEquals("d601", Protocol.encHrMode(false).hex())
        assertEquals("d602", Protocol.encHrMode(true).hex())
        assertEquals("d6100a", Protocol.encHrTimed(10).hex())
    }

    @Test
    fun test_spo2_auto() {
        assertEquals("340301000a", Protocol.encSpo2Auto(true, 10).hex())
        assertEquals("3403010005", Protocol.encSpo2Auto(true, 5).hex())
        assertEquals("340300001e", Protocol.encSpo2Auto(false, 30).hex())
        assertEquals("3404010001173b", Protocol.encSpo2Period(true).hex())
        assertEquals("f701", Protocol.encHrContinuous(true).hex())
    }
}
