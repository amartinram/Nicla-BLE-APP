package com.example.bleapp

/**
 * The wire format the Nicla uses, and the shape of one upload to the server.
 *
 * Kept free of Android types on purpose: everything here is pure, so the
 * framing rules that decide whether a patient's day is parsed or dropped can
 * be unit-tested on the JVM instead of only on a device.
 *
 * A dump is two blocks, each followed by a 0xCC acknowledgement:
 *
 *   0xAA 0xBB  step log   — 9-byte header,  then one byte per minute (steps)
 *   0xAA 0xBC  gait log   — 16-byte header, then one byte per minute (speed)
 *
 * The gait block is optional: a device on step-only firmware never sends one,
 * and the app must still upload the day.
 *
 * Newer firmware sends one more record just before the step log, with no
 * acknowledgement of its own:
 *
 *   0xAA 0xBD  day info   — 8 bytes: how long ago the day closed, and which
 *                           day since boot it is
 *
 * It is optional too. Without it a day is dated by the moment it arrived.
 */
object NiclaProtocol {

    const val MAGIC = 0xAA
    const val STEP_BLOCK = 0xBB
    const val GAIT_BLOCK = 0xBC
    const val INFO_RECORD = 0xBD

    const val STEP_HEADER_LEN = 9
    const val GAIT_HEADER_LEN = 16
    const val INFO_LEN = 8

    /** Walking speed is one byte per minute; this is the m/s each unit is worth. */
    const val SPEED_LSB = 0.02

    data class StepHeader(val length: Int, val totalSteps: Long, val battery: Int)

    /**
     * What the device knows about the day it is about to send. It has no
     * clock, so [ageMinutes] — how long the day sat in its buffer waiting for
     * a connection — is the only way to date a day that did not arrive the
     * moment it closed. [daySeq] counts days since the device booted and is
     * the same on every retry of a day.
     */
    data class DayInfo(val ageMinutes: Int, val daySeq: Int, val protocol: Int, val flags: Int)

    data class GaitHeader(
        val length: Int,
        val cycles: Int,
        val stancePct: Int,
        val swingPct: Int,
        val cadence: Int,
        val strideMs: Int,
        val strideCv: Int,
        val strideCm: Int,
        val meanSpeed: Int,
        val excursion01: Int,
    )

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)

    private fun hasMagic(b: ByteArray, kind: Int, minLen: Int) =
        b.size >= minLen && u8(b, 0) == MAGIC && u8(b, 1) == kind

    /**
     * Parses a step header, or returns null if this notification is not one.
     *
     * [midTransfer] must be true while the bytes of a block are still arriving.
     * Payload is raw step counts, so a minute of 170 steps followed by one of
     * 187 is byte-for-byte a 0xAA 0xBB header; the device only ever sends a
     * header between blocks, so refusing to look for one mid-transfer is what
     * keeps that coincidence from truncating a day.
     */
    fun stepHeader(b: ByteArray, midTransfer: Boolean): StepHeader? {
        if (midTransfer || !hasMagic(b, STEP_BLOCK, STEP_HEADER_LEN)) return null
        val total = (u8(b, 4).toLong() shl 24) or (u8(b, 5).toLong() shl 16) or
                (u8(b, 6).toLong() shl 8) or u8(b, 7).toLong()
        return StepHeader(length = u16(b, 2), totalSteps = total, battery = u8(b, 8))
    }

    /** Parses a day-info record, or returns null if this notification is not one. */
    fun dayInfo(b: ByteArray, midTransfer: Boolean): DayInfo? {
        if (midTransfer || !hasMagic(b, INFO_RECORD, INFO_LEN)) return null
        return DayInfo(ageMinutes = u16(b, 2), daySeq = u16(b, 4),
                       protocol = u8(b, 6), flags = u8(b, 7))
    }

    /**
     * When the day ended, as best the phone can tell: now, less the time the
     * device says the day spent waiting. A device that sends no day info gets
     * the arrival time, which is right whenever the phone was in range as the
     * day closed and late by the wait otherwise.
     */
    fun captureTime(nowMs: Long, info: DayInfo?): Long =
        nowMs - (info?.ageMinutes ?: 0) * 60_000L

    /**
     * Identifies a day across retries. The device resends a day whose
     * acknowledgement it never saw, and without this the same day would be
     * uploaded twice under two timestamps. [DayInfo.daySeq] alone restarts
     * from zero at every reboot, so the content is part of the key.
     */
    fun dayKey(info: DayInfo, header: StepHeader, csv: String): String =
        "${info.daySeq}|${header.totalSteps}|${csv.hashCode()}"

    /** Minutes to wait before each retry of a failed upload; the last repeats. */
    private val RETRY_MINUTES = longArrayOf(1, 2, 5, 15, 30, 60)

    fun retryDelayMs(attempt: Int): Long =
        RETRY_MINUTES[attempt.coerceIn(0, RETRY_MINUTES.size - 1)] * 60_000L

    /**
     * Cache keys are `<captureTime>_<sheetName>`. Oldest first, so days reach
     * the server in the order they happened and eviction drops the oldest.
     */
    fun oldestFirst(keys: Collection<String>): List<String> =
        keys.sortedWith(compareBy({ it.substringBefore('_').toLongOrNull() ?: Long.MAX_VALUE }, { it }))

    /** Parses a gait header, or returns null if this notification is not one. */
    fun gaitHeader(b: ByteArray, midTransfer: Boolean): GaitHeader? {
        if (midTransfer || !hasMagic(b, GAIT_BLOCK, GAIT_HEADER_LEN)) return null
        return GaitHeader(
            length = u16(b, 2),
            cycles = u16(b, 4),
            stancePct = u8(b, 6),
            swingPct = u8(b, 7),
            cadence = u8(b, 8),
            strideMs = u16(b, 9),
            strideCv = u8(b, 11),
            strideCm = u8(b, 12),
            meanSpeed = u8(b, 13),
            excursion01 = u16(b, 14),
        )
    }

    /**
     * One day on its way to the server: the step log, and the gait log when the
     * device measured one.
     */
    data class Upload(
        val captureTime: Long,
        val sheetName: String,
        val steps: String,
        val logData: String,
        val speedLog: String = "",
        val gait: GaitHeader? = null,
    ) {
        /** The POST body. Gait fields are omitted entirely when there is no gait. */
        fun formFields(): Map<String, String> {
            val fields = linkedMapOf(
                "captureTime" to captureTime.toString(),
                "sheetName" to sheetName,
                "steps" to steps,
                "logData" to logData,
            )
            val g = gait
            if (g != null && g.cycles > 0) {
                fields["speedLog"] = speedLog
                fields["gaitCycles"] = g.cycles.toString()
                fields["meanSpeed"] = g.meanSpeed.toString()
                fields["stancePct"] = g.stancePct.toString()
                fields["strideMs"] = g.strideMs.toString()
                fields["strideCv"] = g.strideCv.toString()
                fields["strideCm"] = g.strideCm.toString()
                fields["excursion01"] = g.excursion01.toString()
            }
            return fields
        }

        /**
         * The offline-cache representation. Pipe-separated because neither log
         * is anything but digits and commas, and versioned because entries
         * written by an older install have to survive the upgrade — see [decode].
         */
        fun encode(): String {
            val g = gait
            return listOf(
                CACHE_VERSION, captureTime, sheetName, steps, logData, speedLog,
                g?.cycles ?: 0, g?.stancePct ?: 0, g?.cadence ?: 0, g?.strideMs ?: 0,
                g?.strideCv ?: 0, g?.strideCm ?: 0, g?.meanSpeed ?: 0, g?.excursion01 ?: 0,
            ).joinToString("|")
        }
    }

    const val CACHE_VERSION = "v2"
    private const val V2_FIELDS = 14
    private const val V1_FIELDS = 4

    /**
     * Reads a cached entry back. Entries written before gait existed have the
     * four-field shape `captureTime|sheetName|steps|logData`; dropping those on
     * upgrade would silently discard days the server never received, so both
     * are accepted. Returns null only for an entry that is genuinely unusable.
     */
    fun decode(payload: String): Upload? {
        val parts = payload.split("|")
        if (parts.isEmpty()) return null

        if (parts[0] != CACHE_VERSION) {
            if (parts.size != V1_FIELDS) return null
            val time = parts[0].toLongOrNull() ?: return null
            return Upload(time, parts[1], parts[2], parts[3])
        }
        if (parts.size != V2_FIELDS) return null
        val time = parts[1].toLongOrNull() ?: return null
        fun num(i: Int) = parts[i].toIntOrNull() ?: 0

        val cycles = num(6)
        val gait = if (cycles > 0) GaitHeader(
            length = parts[5].count { it == ',' } + 1,
            cycles = cycles, stancePct = num(7), swingPct = (100 - num(7)).coerceAtLeast(0),
            cadence = num(8), strideMs = num(9), strideCv = num(10), strideCm = num(11),
            meanSpeed = num(12), excursion01 = num(13),
        ) else null

        return Upload(time, parts[2], parts[3], parts[4], parts[5], gait)
    }

    /**
     * Reassembles a dump from the stream of BLE notifications.
     *
     * Held here, free of Android, because this is where a day gets silently
     * truncated or dropped if the framing is wrong, and that is not something
     * to find out from a patient's missing week.
     */
    class Assembler {

        sealed interface Event {
            /** A step header arrived: a new day has started on the wire. */
            object DayStarted : Event
            data class StepsComplete(
                val header: StepHeader, val csv: String, val info: DayInfo? = null,
            ) : Event
            data class GaitComplete(val header: GaitHeader, val csv: String) : Event
        }

        private enum class Block { NONE, STEPS, GAIT }

        private val buffer = mutableListOf<Int>()
        private var block = Block.NONE
        private var expected = 0
        private var stepHeader: StepHeader? = null
        private var gaitHeader: GaitHeader? = null
        private var dayInfo: DayInfo? = null

        private val midTransfer get() = expected > 0 && buffer.size < expected

        fun accept(bytes: ByteArray): List<Event> {
            val events = mutableListOf<Event>()

            // Day info travels ahead of the step header and belongs to the
            // step log that follows it.
            dayInfo(bytes, midTransfer)?.let { dayInfo = it; return events }

            val step = stepHeader(bytes, midTransfer)
            val gait = gaitHeader(bytes, midTransfer)
            when {
                step != null -> {
                    block = Block.STEPS
                    stepHeader = step
                    gaitHeader = null
                    start(step.length, bytes, STEP_HEADER_LEN)
                    events += Event.DayStarted
                }
                gait != null -> {
                    block = Block.GAIT
                    gaitHeader = gait
                    start(gait.length, bytes, GAIT_HEADER_LEN)
                }
                else -> for (b in bytes) buffer.add(b.toInt() and 0xFF)
            }

            if (expected > 0 && buffer.size >= expected) {
                val csv = buffer.joinToString(",")
                when (block) {
                    Block.STEPS -> {
                        stepHeader?.let { events += Event.StepsComplete(it, csv, dayInfo) }
                        dayInfo = null      // never let it attach to a later day
                    }
                    Block.GAIT -> gaitHeader?.let { events += Event.GaitComplete(it, csv) }
                    Block.NONE -> Unit
                }
                endBlock()
            }
            return events
        }

        /**
         * Forgets everything in progress. Call when the link drops.
         *
         * The device restarts a day from its header on every connection, so a
         * block never continues across one. Without this, a block cut short
         * left the assembler waiting for the rest: it then refused to see the
         * next connection's header (headers are not looked for mid-block) and
         * glued the new day onto the tail of the broken one.
         */
        fun reset() {
            endBlock()
            stepHeader = null
            gaitHeader = null
            dayInfo = null
        }

        private fun start(length: Int, bytes: ByteArray, headerLength: Int) {
            expected = length
            buffer.clear()
            for (i in headerLength until bytes.size) buffer.add(bytes[i].toInt() and 0xFF)
        }

        private fun endBlock() {
            expected = 0
            buffer.clear()
            block = Block.NONE
        }
    }
}
