package com.example.bleapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the framing and cache rules that stand between a patient's day and
 * the doctor seeing it. Every case here is one the device can actually produce.
 */
class NiclaProtocolTest {

    private fun stepHeaderBytes(length: Int, total: Long, battery: Int) = byteArrayOf(
        0xAA.toByte(), 0xBB.toByte(),
        (length shr 8).toByte(), length.toByte(),
        (total shr 24).toByte(), (total shr 16).toByte(),
        (total shr 8).toByte(), total.toByte(),
        battery.toByte(),
    )

    private fun gaitHeaderBytes(
        length: Int = 1440, cycles: Int = 118, stance: Int = 60, cadence: Int = 109,
        strideMs: Int = 1100, cv: Int = 28, strideCm: Int = 136, speed: Int = 62,
        excursion: Int = 648,
    ) = byteArrayOf(
        0xAA.toByte(), 0xBC.toByte(),
        (length shr 8).toByte(), length.toByte(),
        (cycles shr 8).toByte(), cycles.toByte(),
        stance.toByte(), (100 - stance).toByte(), cadence.toByte(),
        (strideMs shr 8).toByte(), strideMs.toByte(),
        cv.toByte(), strideCm.toByte(), speed.toByte(),
        (excursion shr 8).toByte(), excursion.toByte(),
    )

    @Test
    fun `parses a step header`() {
        val h = NiclaProtocol.stepHeader(stepHeaderBytes(1440, 4480, 88), midTransfer = false)
        assertNotNull(h)
        assertEquals(1440, h!!.length)
        assertEquals(4480L, h.totalSteps)
        assertEquals(88, h.battery)
    }

    @Test
    fun `parses a step total above the signed byte range`() {
        // Every byte of the 32-bit total set high: naive sign extension breaks here.
        val h = NiclaProtocol.stepHeader(stepHeaderBytes(1440, 0xFEDCBA98L, 200),
                                         midTransfer = false)
        assertEquals(0xFEDCBA98L, h!!.totalSteps)
        assertEquals(200, h.battery)
    }

    @Test
    fun `parses a gait header`() {
        val g = NiclaProtocol.gaitHeader(gaitHeaderBytes(), midTransfer = false)
        assertNotNull(g)
        assertEquals(1440, g!!.length)
        assertEquals(118, g.cycles)
        assertEquals(60, g.stancePct)
        assertEquals(40, g.swingPct)
        assertEquals(109, g.cadence)
        assertEquals(1100, g.strideMs)
        assertEquals(28, g.strideCv)
        assertEquals(136, g.strideCm)
        assertEquals(62, g.meanSpeed)
        assertEquals(648, g.excursion01)
        assertEquals(1.24, g.meanSpeed * NiclaProtocol.SPEED_LSB, 0.001)
    }

    @Test
    fun `step payload that looks like a header is not read as one`() {
        // 170 steps in one minute then 187 in the next is byte-for-byte 0xAA 0xBB.
        // Mid-transfer that must stay payload, or the day is truncated here.
        val payload = ByteArray(244) { 0 }.also {
            it[0] = 0xAA.toByte(); it[1] = 0xBB.toByte()
        }
        assertNull(NiclaProtocol.stepHeader(payload, midTransfer = true))
        assertNull(NiclaProtocol.gaitHeader(payload, midTransfer = true))
        // Between blocks the same bytes are a header, which is why the flag matters.
        assertNotNull(NiclaProtocol.stepHeader(payload, midTransfer = false))
    }

    @Test
    fun `a gait magic in step payload is not read as a header`() {
        val payload = ByteArray(244) { 0 }.also {
            it[0] = 0xAA.toByte(); it[1] = 0xBC.toByte()
        }
        assertNull(NiclaProtocol.gaitHeader(payload, midTransfer = true))
    }

    @Test
    fun `short or foreign notifications are not headers`() {
        assertNull(NiclaProtocol.stepHeader(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0, 5),
                                            midTransfer = false))
        assertNull(NiclaProtocol.gaitHeader(gaitHeaderBytes().copyOf(10), midTransfer = false))
        assertNull(NiclaProtocol.stepHeader(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9),
                                            midTransfer = false))
    }

    @Test
    fun `a day without gait posts no gait fields`() {
        val day = NiclaProtocol.Upload(1757086400000L, "AA_BB", "4210", "1,2,3")
        val fields = day.formFields()
        assertEquals(setOf("captureTime", "sheetName", "steps", "logData"), fields.keys)
        assertEquals("4210", fields["steps"])
    }

    @Test
    fun `a day with gait posts the gait fields`() {
        val gait = NiclaProtocol.gaitHeader(gaitHeaderBytes(), midTransfer = false)!!
        val day = NiclaProtocol.Upload(1757086400000L, "AA_BB", "4480", "1,2,3",
                                       "0,62,0", gait)
        val fields = day.formFields()
        assertEquals("118", fields["gaitCycles"])
        assertEquals("62", fields["meanSpeed"])
        assertEquals("60", fields["stancePct"])
        assertEquals("1100", fields["strideMs"])
        assertEquals("648", fields["excursion01"])
        assertEquals("0,62,0", fields["speedLog"])
    }

    @Test
    fun `a gait block the device could not fill posts no gait fields`() {
        // The device always sends the gait block; on a day with no analysable
        // walking every field in it is zero. That is absence, not a measurement.
        val empty = NiclaProtocol.gaitHeader(
            gaitHeaderBytes(cycles = 0, stance = 0, cadence = 0, strideMs = 0,
                            cv = 0, strideCm = 0, speed = 0, excursion = 0),
            midTransfer = false)!!
        val day = NiclaProtocol.Upload(1L, "AA_BB", "300", "1,2", "0,0", empty)
        assertTrue("gaitCycles" !in day.formFields())
    }

    @Test
    fun `a cached day survives a round trip with its gait`() {
        val gait = NiclaProtocol.gaitHeader(gaitHeaderBytes(), midTransfer = false)!!
        val day = NiclaProtocol.Upload(1757086400000L, "AA_BB", "4480",
                                       "0,11,22", "0,62,0", gait)
        val back = NiclaProtocol.decode(day.encode())
        assertNotNull(back)
        assertEquals(day.captureTime, back!!.captureTime)
        assertEquals(day.sheetName, back.sheetName)
        assertEquals(day.steps, back.steps)
        assertEquals(day.logData, back.logData)
        assertEquals(day.speedLog, back.speedLog)
        assertEquals(day.formFields(), back.formFields())
    }

    @Test
    fun `a cached day without gait survives a round trip`() {
        val day = NiclaProtocol.Upload(1757086400000L, "AA_BB_Battery", "88", "")
        val back = NiclaProtocol.decode(day.encode())
        assertNotNull(back)
        assertNull(back!!.gait)
        assertEquals(day.formFields(), back.formFields())
    }

    @Test
    fun `entries cached by the previous version are still uploaded`() {
        // Upgrading must not throw away days the server never received.
        val legacy = "1757086400000|AA_BB|4210|0,11,22"
        val back = NiclaProtocol.decode(legacy)
        assertNotNull(back)
        assertEquals(1757086400000L, back!!.captureTime)
        assertEquals("AA_BB", back.sheetName)
        assertEquals("4210", back.steps)
        assertEquals("0,11,22", back.logData)
        assertNull(back.gait)
    }

    /**
     * The bytes below were produced by the firmware itself — NiclaComm::sendHeader
     * and ::sendGaitHeader compiled and run — not written from the spec by hand.
     * If the device's packing ever changes, this is what catches it, instead of a
     * patient's day arriving as nonsense.
     */
    private fun hex(s: String) =
        s.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `parses the bytes the firmware actually emits`() {
        val step = NiclaProtocol.stepHeader(hex("AA BB 05 A0 00 00 11 80 58"),
                                            midTransfer = false)
        assertNotNull(step)
        assertEquals(1440, step!!.length)
        assertEquals(4480L, step.totalSteps)
        assertEquals(88, step.battery)

        val gait = NiclaProtocol.gaitHeader(
            hex("AA BC 05 A0 00 76 3C 28 6D 04 4C 1C 88 3E 02 88"), midTransfer = false)
        assertNotNull(gait)
        assertEquals(1440, gait!!.length)
        assertEquals(118, gait.cycles)
        assertEquals(60, gait.stancePct)
        assertEquals(40, gait.swingPct)
        assertEquals(109, gait.cadence)
        assertEquals(1100, gait.strideMs)
        assertEquals(28, gait.strideCv)
        assertEquals(136, gait.strideCm)
        assertEquals(62, gait.meanSpeed)
        assertEquals(648, gait.excursion01)
        assertEquals(1.24, gait.meanSpeed * NiclaProtocol.SPEED_LSB, 0.001)

        // ...and these are the fields the server is then asked to store.
        val fields = NiclaProtocol.Upload(1L, "AA_BB", "4480", "1,2", "0,62", gait).formFields()
        assertEquals("118", fields["gaitCycles"])
        assertEquals("62", fields["meanSpeed"])
        assertEquals("60", fields["stancePct"])
        assertEquals("1100", fields["strideMs"])
        assertEquals("28", fields["strideCv"])
        assertEquals("136", fields["strideCm"])
        assertEquals("648", fields["excursion01"])
    }

    @Test
    fun `an unreadable cache entry is rejected rather than posted as junk`() {
        assertNull(NiclaProtocol.decode(""))
        assertNull(NiclaProtocol.decode("garbage"))
        assertNull(NiclaProtocol.decode("v2|only|three"))
        assertNull(NiclaProtocol.decode("notanumber|AA_BB|1|2"))
    }

    @Test
    fun `parses a day info record`() {
        val b = byteArrayOf(0xAA.toByte(), 0xBD.toByte(), 0x01, 0x2C, 0x00, 0x07, 2, 1)
        assertEquals(NiclaProtocol.DayInfo(ageMinutes = 300, daySeq = 7, protocol = 2, flags = 1),
                     NiclaProtocol.dayInfo(b, midTransfer = false))
        // Payload bytes that happen to spell the magic are still payload.
        assertNull(NiclaProtocol.dayInfo(b, midTransfer = true))
        assertNull(NiclaProtocol.dayInfo(b.copyOf(7), midTransfer = false))
    }

    @Test
    fun `a day that waited is dated when it ended, not when it arrived`() {
        val now = 1_800_000_000_000L
        val waitedFiveHours = NiclaProtocol.DayInfo(ageMinutes = 300, daySeq = 0, protocol = 2, flags = 0)
        assertEquals(now - 5 * 3_600_000L, NiclaProtocol.captureTime(now, waitedFiveHours))
        // Firmware without day info: the arrival time is all there is.
        assertEquals(now, NiclaProtocol.captureTime(now, null))
    }

    @Test
    fun `a resent day has the same key and a different day does not`() {
        val header = NiclaProtocol.StepHeader(length = 3, totalSteps = 30, battery = 96)
        val first = NiclaProtocol.DayInfo(ageMinutes = 0, daySeq = 4, protocol = 2, flags = 1)
        // The retry comes an hour later, so only the age differs.
        val retry = first.copy(ageMinutes = 58)
        assertEquals(NiclaProtocol.dayKey(first, header, "10,10,10"),
                     NiclaProtocol.dayKey(retry, header, "10,10,10"))
        // Same sequence number after a reboot, different day.
        assertTrue(NiclaProtocol.dayKey(first, header, "10,10,10") !=
                   NiclaProtocol.dayKey(first, header, "0,20,10"))
        assertTrue(NiclaProtocol.dayKey(first, header, "10,10,10") !=
                   NiclaProtocol.dayKey(first.copy(daySeq = 5), header, "10,10,10"))
    }

    @Test
    fun `upload retries back off and then hold at an hour`() {
        assertEquals(60_000L, NiclaProtocol.retryDelayMs(0))
        assertTrue(NiclaProtocol.retryDelayMs(1) > NiclaProtocol.retryDelayMs(0))
        assertEquals(3_600_000L, NiclaProtocol.retryDelayMs(5))
        assertEquals(3_600_000L, NiclaProtocol.retryDelayMs(500))
    }

    @Test
    fun `the cache is sent oldest day first`() {
        val keys = listOf("1700000200000_AA_BB", "1700000100000_AA_BB_Battery",
                          "1700000100000_AA_BB", "junk")
        assertEquals(listOf("1700000100000_AA_BB", "1700000100000_AA_BB_Battery",
                            "1700000200000_AA_BB", "junk"),
                     NiclaProtocol.oldestFirst(keys))
    }
}
