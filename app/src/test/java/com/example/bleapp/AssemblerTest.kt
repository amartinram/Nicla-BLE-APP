package com.example.bleapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the assembler with the notification stream the device actually
 * produces: a header on its own, then 244-byte chunks, for the step log and
 * then the gait log, with a second day following straight after.
 */
class AssemblerTest {

    private val minutes = 1440
    private val mtuChunk = 244

    private fun stepHeader(total: Long) = byteArrayOf(
        0xAA.toByte(), 0xBB.toByte(), (minutes shr 8).toByte(), minutes.toByte(),
        (total shr 24).toByte(), (total shr 16).toByte(),
        (total shr 8).toByte(), total.toByte(), 88.toByte(),
    )

    private fun gaitHeader(cycles: Int = 118) = byteArrayOf(
        0xAA.toByte(), 0xBC.toByte(), (minutes shr 8).toByte(), minutes.toByte(),
        (cycles shr 8).toByte(), cycles.toByte(),
        60, 40, 109, (1100 shr 8).toByte(), 1100.toByte(),
        28, 136.toByte(), 62, (648 shr 8).toByte(), 648.toByte(),
    )

    /** Splits a day into the 244-byte notifications the device sends. */
    private fun chunks(payload: ByteArray) =
        payload.toList().chunked(mtuChunk).map { it.toByteArray() }

    private fun stepPayload(): ByteArray {
        // Deliberately includes 170 then 187 — the byte pair that spells a
        // step header — so a real day exercises the framing guard.
        val p = ByteArray(minutes)
        p[100] = 170.toByte(); p[101] = 187.toByte()
        p[244] = 170.toByte(); p[245] = 188.toByte()   // and a gait magic, chunk-aligned
        p[700] = 55
        return p
    }

    private fun speedPayload(): ByteArray {
        val p = ByteArray(minutes)
        p[700] = 62
        return p
    }

    @Test
    fun `reassembles a full two-block dump`() {
        val a = NiclaProtocol.Assembler()
        val events = mutableListOf<NiclaProtocol.Assembler.Event>()

        events += a.accept(stepHeader(4480))
        for (c in chunks(stepPayload())) events += a.accept(c)
        events += a.accept(gaitHeader())
        for (c in chunks(speedPayload())) events += a.accept(c)

        assertEquals(3, events.size)
        assertTrue(events[0] is NiclaProtocol.Assembler.Event.DayStarted)

        val steps = events[1] as NiclaProtocol.Assembler.Event.StepsComplete
        assertEquals(4480L, steps.header.totalSteps)
        assertEquals(88, steps.header.battery)
        val stepValues = steps.csv.split(",")
        assertEquals(minutes, stepValues.size)
        // The bytes that spell a header survived as data, not as a new block.
        assertEquals("170", stepValues[100])
        assertEquals("187", stepValues[101])
        assertEquals("170", stepValues[244])
        assertEquals("188", stepValues[245])
        assertEquals("55", stepValues[700])

        val gait = events[2] as NiclaProtocol.Assembler.Event.GaitComplete
        assertEquals(118, gait.header.cycles)
        assertEquals(60, gait.header.stancePct)
        val speedValues = gait.csv.split(",")
        assertEquals(minutes, speedValues.size)
        assertEquals("62", speedValues[700])
    }

    @Test
    fun `two days back to back stay separate`() {
        val a = NiclaProtocol.Assembler()
        val events = mutableListOf<NiclaProtocol.Assembler.Event>()
        for (day in 0 until 2) {
            events += a.accept(stepHeader(1000L + day))
            for (c in chunks(stepPayload())) events += a.accept(c)
            events += a.accept(gaitHeader(10 + day))
            for (c in chunks(speedPayload())) events += a.accept(c)
        }
        assertEquals(6, events.size)
        assertEquals(1000L, (events[1] as NiclaProtocol.Assembler.Event.StepsComplete).header.totalSteps)
        assertEquals(10, (events[2] as NiclaProtocol.Assembler.Event.GaitComplete).header.cycles)
        assertEquals(1001L, (events[4] as NiclaProtocol.Assembler.Event.StepsComplete).header.totalSteps)
        assertEquals(11, (events[5] as NiclaProtocol.Assembler.Event.GaitComplete).header.cycles)
    }

    @Test
    fun `a dump with no gait block still yields the day`() {
        // Step-only firmware: the next thing on the wire is the following day.
        val a = NiclaProtocol.Assembler()
        val events = mutableListOf<NiclaProtocol.Assembler.Event>()
        events += a.accept(stepHeader(4210))
        for (c in chunks(stepPayload())) events += a.accept(c)
        events += a.accept(stepHeader(3900))
        for (c in chunks(stepPayload())) events += a.accept(c)

        assertEquals(4, events.size)
        assertEquals(4210L, (events[1] as NiclaProtocol.Assembler.Event.StepsComplete).header.totalSteps)
        assertTrue(events[2] is NiclaProtocol.Assembler.Event.DayStarted)
        assertEquals(3900L, (events[3] as NiclaProtocol.Assembler.Event.StepsComplete).header.totalSteps)
    }

    @Test
    fun `payload arriving before any header is ignored`() {
        val a = NiclaProtocol.Assembler()
        assertTrue(a.accept(ByteArray(244) { 7 }).isEmpty())
        // ...and the assembler still picks up the next real day.
        val events = mutableListOf<NiclaProtocol.Assembler.Event>()
        events += a.accept(stepHeader(1234))
        for (c in chunks(stepPayload())) events += a.accept(c)
        assertEquals(1234L, (events[1] as NiclaProtocol.Assembler.Event.StepsComplete).header.totalSteps)
    }

    private fun dayInfo(ageMinutes: Int, seq: Int) = byteArrayOf(
        0xAA.toByte(), 0xBD.toByte(), (ageMinutes shr 8).toByte(), ageMinutes.toByte(),
        (seq shr 8).toByte(), seq.toByte(), 2, 1,
    )

    private fun steps(events: List<NiclaProtocol.Assembler.Event>) =
        events.filterIsInstance<NiclaProtocol.Assembler.Event.StepsComplete>()

    @Test
    fun `day info rides with the step log that follows it`() {
        val a = NiclaProtocol.Assembler()
        val events = mutableListOf<NiclaProtocol.Assembler.Event>()

        events += a.accept(dayInfo(ageMinutes = 300, seq = 7))
        events += a.accept(stepHeader(4480))
        for (c in chunks(stepPayload())) events += a.accept(c)

        val day = steps(events).single()
        assertEquals(NiclaProtocol.DayInfo(ageMinutes = 300, daySeq = 7, protocol = 2, flags = 1),
                     day.info)
        assertEquals(minutes, day.csv.split(",").size)   // info bytes are not payload
    }

    @Test
    fun `day info does not leak into the next day`() {
        val a = NiclaProtocol.Assembler()
        val events = mutableListOf<NiclaProtocol.Assembler.Event>()

        events += a.accept(dayInfo(ageMinutes = 300, seq = 7))
        events += a.accept(stepHeader(100))
        for (c in chunks(stepPayload())) events += a.accept(c)
        // Second day from firmware that sends no day info.
        events += a.accept(stepHeader(200))
        for (c in chunks(stepPayload())) events += a.accept(c)

        val days = steps(events)
        assertEquals(2, days.size)
        assertEquals(7, days[0].info?.daySeq)
        assertEquals(null, days[1].info)
    }

    @Test
    fun `a day cut short by a disconnect does not corrupt the retry`() {
        val a = NiclaProtocol.Assembler()
        val payload = stepPayload()

        // First connection: the link drops after two of the six chunks.
        a.accept(stepHeader(4480))
        chunks(payload).take(2).forEach { a.accept(it) }
        a.reset()

        // Second connection: the device starts the same day again from the top.
        val events = mutableListOf<NiclaProtocol.Assembler.Event>()
        events += a.accept(stepHeader(4480))
        for (c in chunks(payload)) events += a.accept(c)

        val day = steps(events).single()
        assertEquals(payload.joinToString(",") { (it.toInt() and 0xFF).toString() }, day.csv)
    }

    @Test
    fun `without a reset a cut-short day swallows the retry`() {
        // Documents why BleBackgroundService must call reset() on disconnect:
        // mid-block the assembler does not look for headers, so the retry's
        // header and data are appended to the broken day.
        val a = NiclaProtocol.Assembler()
        val payload = stepPayload()

        a.accept(stepHeader(4480))
        chunks(payload).take(2).forEach { a.accept(it) }

        val events = mutableListOf<NiclaProtocol.Assembler.Event>()
        events += a.accept(stepHeader(4480))
        for (c in chunks(payload)) events += a.accept(c)

        val day = steps(events).first()
        assertTrue(day.csv != payload.joinToString(",") { (it.toInt() and 0xFF).toString() })
    }

    @Test
    fun `reassembles a day sent in small notifications`() {
        // A link that never negotiated a large MTU carries 20 bytes at a time.
        val a = NiclaProtocol.Assembler()
        val events = mutableListOf<NiclaProtocol.Assembler.Event>()
        val payload = stepPayload()

        events += a.accept(stepHeader(4480))
        payload.toList().chunked(20).forEach { events += a.accept(it.toByteArray()) }

        assertEquals(payload.joinToString(",") { (it.toInt() and 0xFF).toString() },
                     steps(events).single().csv)
    }
}
