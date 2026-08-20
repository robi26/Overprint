package net.roz.connectstats.data.parse

import net.roz.connectstats.domain.model.Activity
import net.roz.connectstats.domain.model.ActivityDetail
import net.roz.connectstats.domain.model.ActivityType
import net.roz.connectstats.domain.model.DataSource
import net.roz.connectstats.domain.model.Lap
import net.roz.connectstats.domain.model.TrackPoint
import net.roz.connectstats.domain.stats.StatsEngine
import net.roz.connectstats.domain.stats.sanitizeActivity
import net.roz.connectstats.domain.stats.sanitizeFitUnits
import net.roz.connectstats.domain.stats.sanitizeLap
import net.roz.connectstats.domain.stats.withDerivedTrackStats
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Decoder for Garmin FIT activity files. Covers the messages used for summaries:
 * file_id, session, lap, record, activity, event.
 */
object FitParser {

    fun parse(bytes: ByteArray, id: String = "fit-${System.currentTimeMillis()}", nameHint: String? = null): ActivityDetail {
        val records = mutableListOf<RawRecord>()
        val laps = mutableListOf<RawLap>()
        var session = RawSession()
        val localDefs = arrayOfNulls<Definition>(16)

        val headerSize = bytes[0].toInt() and 0xFF
        val dataSize = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        var offset = headerSize
        val end = (headerSize + dataSize).coerceAtMost(bytes.size)
        var lastTimestamp: Long? = null

        while (offset < end) {
            val header = bytes[offset].toInt() and 0xFF
            offset++
            if (header and 0x80 != 0) {
                val local = (header shr 5) and 0x03
                val timeOffset = header and 0x1F
                val def = localDefs[local] ?: break
                val tsBase = lastTimestamp
                if (tsBase != null) {
                    val baseOff = (tsBase and 0x1F).toInt()
                    lastTimestamp = if (timeOffset >= baseOff) {
                        (tsBase and 0xFFFFFFE0L) + timeOffset
                    } else {
                        (tsBase and 0xFFFFFFE0L) + timeOffset + 0x20
                    }
                }
                val (next, fields) = readFields(bytes, offset, def, omitTimestamp = true)
                offset = next
                ingest(def.global, fields, lastTimestamp, records, laps, session)
                continue
            }
            val local = header and 0x0F
            if (header and 0x40 != 0) {
                val hasDev = header and 0x20 != 0
                offset++ // reserved
                val architecture = bytes[offset].toInt() and 0xFF
                offset++
                val global = u16(bytes, offset, architecture == 1)
                offset += 2
                val fieldCount = bytes[offset].toInt() and 0xFF
                offset++
                val fields = ArrayList<FieldDef>(fieldCount)
                repeat(fieldCount) {
                    val num = bytes[offset].toInt() and 0xFF
                    val size = bytes[offset + 1].toInt() and 0xFF
                    val base = bytes[offset + 2].toInt() and 0xFF
                    offset += 3
                    fields += FieldDef(num, size, base)
                }
                var devDataSize = 0
                if (hasDev) {
                    val devCount = bytes[offset].toInt() and 0xFF
                    offset++
                    repeat(devCount) {
                        if (offset + 2 < bytes.size) {
                            devDataSize += bytes[offset + 1].toInt() and 0xFF
                        }
                        offset += 3
                    }
                }
                localDefs[local] = Definition(global, architecture == 1, fields, devDataSize)
            } else {
                val def = localDefs[local] ?: break
                val (next, fields) = readFields(bytes, offset, def)
                offset = next
                val ts = fields.numbers[253]?.toLong()
                if (ts != null) lastTimestamp = ts
                ingest(def.global, fields, lastTimestamp, records, laps, session)
            }
        }

        val firstRecordTs = records.mapNotNull { it.timestamp }.minOrNull()
        val startFitTs = listOfNotNull(session.startTime, firstRecordTs).minOrNull() ?: 0L
        val startMillis = fitTimestampToMillis(startFitTs)
        val trackZeroMillis = fitTimestampToMillis(firstRecordTs ?: startFitTs)
        val track = records.mapIndexed { i, r ->
            val ts = fitTimestampToMillis(r.timestamp ?: startFitTs + i)
            TrackPoint(
                activityId = id,
                timestampMillis = ts,
                elapsedSeconds = ((ts - trackZeroMillis) / 1000.0).coerceAtLeast(0.0),
                latitude = r.lat,
                longitude = r.lon,
                altitudeMeters = r.alt,
                distanceMeters = r.distance,
                speedMps = r.speed,
                heartRate = r.hr,
                cadence = r.cadence,
                power = r.power,
                gradePercent = r.grade,
                temperatureC = r.temp,
                verticalOscillationMm = r.verticalOscillationMm,
                stanceTimeMs = r.stanceTimeMs,
                verticalRatio = r.verticalRatio,
                stepLengthMm = r.stepLengthMm,
                leftRightBalancePercent = r.leftRightBalancePercent,
                respirationRate = r.respirationRate,
            )
        }.let { StatsEngine.enrichTrack(sanitizeFitUnits(it)) }

        val type = ActivityType.fromKey(session.sport)
        val activity = if (session.distance != null || session.elapsed != null) {
            Activity(
                id = id,
                externalId = id,
                source = DataSource.FILE,
                name = nameHint ?: defaultName(type, startMillis),
                type = type,
                startTimeMillis = startMillis,
                location = null,
                distanceMeters = session.distance ?: track.lastOrNull()?.distanceMeters ?: 0.0,
                durationSeconds = session.elapsed ?: track.lastOrNull()?.elapsedSeconds ?: 0.0,
                movingSeconds = session.timer ?: session.elapsed ?: 0.0,
                elevationGainMeters = session.elev ?: StatsEngine.elevationGain(track),
                calories = session.calories,
                avgHeartRate = session.avgHr ?: track.mapNotNull { it.heartRate }.average().takeIf { !it.isNaN() },
                maxHeartRate = session.maxHr,
                avgSpeedMps = session.avgSpeed,
                maxSpeedMps = session.maxSpeed,
                avgCadence = session.avgCadence,
                avgPower = session.avgPower,
                maxPower = session.maxPower,
                avgGrade = session.avgGrade,
                startLatitude = track.firstOrNull { it.latitude != null }?.latitude,
                startLongitude = track.firstOrNull { it.longitude != null }?.longitude,
                deviceName = session.device,
                hasTrack = track.isNotEmpty(),
                minHeartRate = session.minHr,
                maxCadence = session.maxCadence,
                elevationLossMeters = session.descent,
                normalizedPower = session.normalizedPower,
                trainingStressScore = session.tss,
                intensityFactor = session.intensityFactor,
                avgTemperatureC = session.avgTemp,
                avgVerticalOscillationMm = session.avgVerticalOscillationMm,
                avgStanceTimeMs = session.avgStanceTimeMs,
                avgVerticalRatio = session.avgVerticalRatio,
                avgStepLengthMm = session.avgStepLengthMm,
                avgRespirationRate = session.avgRespirationRate,
                aerobicTrainingEffect = session.aerobicTe,
                anaerobicTrainingEffect = session.anaerobicTe,
            )
        } else {
            StatsEngine.summaryFromTrack(id, nameHint ?: defaultName(type, startMillis), type, DataSource.FILE, track)
        }

        val parsedLaps = laps.mapIndexed { i, l ->
            Lap(
                activityId = id,
                index = i + 1,
                startTimeMillis = fitTimestampToMillis(l.start ?: session.startTime ?: 0),
                durationSeconds = l.elapsed ?: 0.0,
                distanceMeters = l.distance ?: 0.0,
                avgHeartRate = l.avgHr,
                maxHeartRate = l.maxHr,
                avgSpeedMps = l.avgSpeed,
                avgCadence = l.avgCadence,
                avgPower = l.avgPower,
                elevationGainMeters = l.elev,
                label = "Lap ${i + 1}",
            )
        }

        return ActivityDetail(
            sanitizeActivity(activity.copy(id = id, hasTrack = track.isNotEmpty()))
                .let { it.copy(deviceName = it.deviceName ?: session.device) }
                .withDerivedTrackStats(track),
            track.map { it.copy(activityId = id) },
            parsedLaps.map { sanitizeLap(it.copy(activityId = id)) },
        )
    }

    private fun ingest(
        global: Int,
        fields: ParsedFields,
        timestamp: Long?,
        records: MutableList<RawRecord>,
        laps: MutableList<RawLap>,
        session: RawSession,
    ) {
        val values = fields.numbers
        when (global) {
            0, 23 -> {
                val index = values[0]
                val name = fields.strings[27] ?: fields.strings[19]
                val label = deviceLabel(
                    manufacturer = values[if (global == 0) 1 else 2]?.toInt(),
                    product = values[if (global == 0) 2 else 4]?.toInt(),
                    productName = name,
                )
                if (label != null && (session.device == null || index == 0.0)) {
                    session.device = label
                }
            }
            20 -> {
                val cadence = when {
                    values[4] == null && values[53] == null -> null
                    else -> (values[4] ?: 0.0) + (values[53]?.div(128.0) ?: 0.0)
                }
                records += RawRecord(
                    timestamp = timestamp,
                    lat = semicircles(values[0]),
                    lon = semicircles(values[1]),
                    alt = (values[78] ?: values[2])?.let { it / 5.0 - 500 },
                    distance = values[5]?.div(100.0),
                    speed = values[73]?.div(1000.0) ?: values[6]?.div(1000.0),
                    hr = values[3],
                    cadence = cadence,
                    power = values[7],
                    grade = values[9]?.div(100.0),
                    temp = values[13],
                    verticalOscillationMm = values[39]?.div(10.0),
                    stanceTimeMs = values[41]?.div(10.0),
                    verticalRatio = values[83]?.div(100.0),
                    stepLengthMm = values[85]?.div(10.0),
                    leftRightBalancePercent = leftRightBalancePercent(values[30] ?: values[54], scaled100 = false),
                    respirationRate = values[108]?.div(100.0) ?: values[99],
                )
            }
            19 -> laps += RawLap(
                start = values[2]?.toLong() ?: values[253]?.toLong() ?: timestamp,
                elapsed = values[7]?.div(1000.0) ?: values[8]?.div(1000.0),
                distance = values[9]?.div(100.0),
                avgHr = values[15],
                maxHr = values[16],
                avgSpeed = values[14]?.div(1000.0) ?: values[110]?.div(1000.0),
                avgCadence = values[17],
                avgPower = values[19],
                elev = values[21],
            )
            18 -> {
                session.startTime = values[2]?.toLong() ?: session.startTime
                session.sport = sportName(values[5]?.toInt())
                session.elapsed = values[7]?.div(1000.0) ?: values[8]?.div(1000.0) ?: session.elapsed
                session.timer = values[8]?.div(1000.0) ?: session.timer
                session.distance = values[9]?.div(100.0) ?: session.distance
                session.avgSpeed = values[14]?.div(1000.0) ?: values[124]?.div(1000.0) ?: session.avgSpeed
                session.maxSpeed = values[15]?.div(1000.0) ?: values[125]?.div(1000.0) ?: session.maxSpeed
                session.avgHr = values[16] ?: session.avgHr
                session.maxHr = values[17] ?: session.maxHr
                session.minHr = values[64] ?: session.minHr
                session.avgCadence = values[18]?.plus(values[92]?.div(128.0) ?: 0.0) ?: session.avgCadence
                session.maxCadence = values[19]?.plus(values[93]?.div(128.0) ?: 0.0) ?: session.maxCadence
                session.avgPower = values[20] ?: session.avgPower
                session.maxPower = values[21] ?: session.maxPower
                session.calories = values[11] ?: session.calories
                session.elev = values[22] ?: session.elev
                session.descent = values[23] ?: session.descent
                session.avgGrade = values[52]?.div(100.0) ?: session.avgGrade
                session.normalizedPower = values[34] ?: session.normalizedPower
                session.tss = values[35]?.div(10.0) ?: session.tss
                session.intensityFactor = values[36]?.div(1000.0) ?: session.intensityFactor
                session.avgTemp = values[57] ?: session.avgTemp
                session.avgVerticalOscillationMm = values[89]?.div(10.0) ?: session.avgVerticalOscillationMm
                session.avgStanceTimeMs = values[91]?.div(10.0) ?: session.avgStanceTimeMs
                session.avgVerticalRatio = values[132]?.div(100.0) ?: session.avgVerticalRatio
                session.avgStepLengthMm = values[134]?.div(10.0) ?: session.avgStepLengthMm
                session.avgRespirationRate = values[169]?.div(100.0) ?: values[147] ?: session.avgRespirationRate
                session.aerobicTe = values[24]?.div(10.0) ?: session.aerobicTe
                session.anaerobicTe = values[137]?.div(10.0) ?: session.anaerobicTe
            }
        }
    }

    private fun readFields(
        bytes: ByteArray,
        start: Int,
        def: Definition,
        omitTimestamp: Boolean = false,
    ): Pair<Int, ParsedFields> {
        var offset = start
        val numbers = HashMap<Int, Double>()
        val strings = HashMap<Int, String>()
        val order = if (def.bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        for (field in def.fields) {
            if (omitTimestamp && field.num == 253) continue
            if (offset + field.size > bytes.size) break
            val type = field.base and 0x1F
            if (type == 7) {
                decodeString(bytes, offset, field.size)?.let { strings[field.num] = it }
            } else {
                val raw = decodeNumber(bytes, offset, field.size, field.base, order)
                if (raw != null) numbers[field.num] = raw
            }
            offset += field.size
        }
        offset += def.devDataSize
        if (offset > bytes.size) offset = bytes.size
        return offset to ParsedFields(numbers, strings)
    }

    private fun decodeNumber(bytes: ByteArray, offset: Int, size: Int, base: Int, order: ByteOrder): Double? {
        val type = base and 0x1F
        val buf = ByteBuffer.wrap(bytes, offset, size).order(order)
        val invalid: Double?
        val value: Double = when (type) {
            0, 2, 10, 13 -> { // enum / uint8
                val v = bytes[offset].toInt() and 0xFF
                invalid = 0xFF.toDouble()
                v.toDouble()
            }
            1 -> { // sint8
                val v = bytes[offset].toInt()
                invalid = 0x7F.toDouble()
                v.toDouble()
            }
            3 -> { // sint16
                val v = buf.short.toInt()
                invalid = 0x7FFF.toDouble()
                v.toDouble()
            }
            4 -> {
                val v = buf.short.toInt() and 0xFFFF
                invalid = 0xFFFF.toDouble()
                v.toDouble()
            }
            5 -> {
                val v = buf.int
                invalid = 0x7FFFFFFF.toDouble()
                v.toDouble()
            }
            6, 8, 12 -> {
                val v = buf.int.toLong() and 0xFFFFFFFFL
                invalid = 0xFFFFFFFFL.toDouble()
                v.toDouble()
            }
            7 -> return null // string, handled separately
            9 -> {
                invalid = null
                buf.float.toDouble()
            }
            14 -> {
                val v = buf.long
                invalid = 0x7FFFFFFFFFFFFFFFL.toDouble()
                v.toDouble()
            }
            15, 16 -> {
                val v = java.lang.Double.longBitsToDouble(buf.long)
                invalid = null
                v
            }
            else -> return null
        }
        if (invalid != null && value == invalid) return null
        if (value.isNaN()) return null
        return value
    }

    private fun u16(bytes: ByteArray, offset: Int, big: Boolean): Int {
        val a = bytes[offset].toInt() and 0xFF
        val b = bytes[offset + 1].toInt() and 0xFF
        return if (big) (a shl 8) or b else a or (b shl 8)
    }

    private fun decodeString(bytes: ByteArray, offset: Int, size: Int): String? {
        var end = offset
        val limit = (offset + size).coerceAtMost(bytes.size)
        while (end < limit && bytes[end] != 0.toByte()) end++
        if (end <= offset) return null
        return String(bytes, offset, end - offset, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }

    private fun leftRightBalancePercent(raw: Double?, scaled100: Boolean): Double? {
        val value = raw?.toInt() ?: return null
        return if (scaled100) {
            val percent = (value and 0x3FFF) / 100.0
            if (value and 0x8000 != 0) percent else 100.0 - percent
        } else {
            val percent = (value and 0x7F).toDouble()
            if (percent <= 0.0) return null
            if (value and 0x80 != 0) percent else 100.0 - percent
        }
    }

    private fun deviceLabel(manufacturer: Int?, product: Int?, productName: String?): String? {
        productName?.takeIf { it.isNotBlank() }?.let { return it }
        val mfr = when (manufacturer) {
            1 -> "Garmin"
            15 -> "Dynastream"
            32 -> "Wahoo"
            263 -> "Favero"
            else -> null
        } ?: return null
        return if (product != null) "$mfr $product" else mfr
    }

    private fun semicircles(v: Double?): Double? {
        v ?: return null
        val deg = v * (180.0 / 2.0.pow(31))
        if (deg == 180.0 || deg == 0.0 && v == 0x7FFFFFFF.toDouble()) return null
        if (deg < -90 || deg > 90 && kotlin.math.abs(deg) > 180) {
            // longitude can be ±180
        }
        if (deg.isNaN()) return null
        return deg
    }

    private fun sportName(id: Int?): String = when (id) {
        1 -> "running"
        2 -> "cycling"
        5 -> "swimming"
        8 -> "strength_training"
        11 -> "walking"
        13 -> "hiking"
        15 -> "alpine_skiing"
        else -> "other"
    }

    private fun defaultName(type: ActivityType, start: Long): String = "${type.displayName} activity"

    private fun fitTimestampToMillis(ts: Long): Long = (ts + FIT_EPOCH_OFFSET) * 1000

    private const val FIT_EPOCH_OFFSET = 631065600L // seconds between unix epoch and FIT epoch (1989-12-31)

    private data class ParsedFields(val numbers: Map<Int, Double>, val strings: Map<Int, String>)
    private data class FieldDef(val num: Int, val size: Int, val base: Int)
    private data class Definition(val global: Int, val bigEndian: Boolean, val fields: List<FieldDef>, val devDataSize: Int = 0)
    private data class RawRecord(
        val timestamp: Long?,
        val lat: Double?,
        val lon: Double?,
        val alt: Double?,
        val distance: Double?,
        val speed: Double?,
        val hr: Double?,
        val cadence: Double?,
        val power: Double?,
        val grade: Double?,
        val temp: Double?,
        val verticalOscillationMm: Double? = null,
        val stanceTimeMs: Double? = null,
        val verticalRatio: Double? = null,
        val stepLengthMm: Double? = null,
        val leftRightBalancePercent: Double? = null,
        val respirationRate: Double? = null,
    )
    private data class RawLap(
        val start: Long?,
        val elapsed: Double?,
        val distance: Double?,
        val avgHr: Double?,
        val maxHr: Double?,
        val avgSpeed: Double?,
        val avgCadence: Double?,
        val avgPower: Double?,
        val elev: Double?,
    )
    private class RawSession {
        var startTime: Long? = null
        var sport: String? = null
        var elapsed: Double? = null
        var timer: Double? = null
        var distance: Double? = null
        var avgSpeed: Double? = null
        var maxSpeed: Double? = null
        var avgHr: Double? = null
        var maxHr: Double? = null
        var avgCadence: Double? = null
        var avgPower: Double? = null
        var maxPower: Double? = null
        var calories: Double? = null
        var elev: Double? = null
        var descent: Double? = null
        var minHr: Double? = null
        var maxCadence: Double? = null
        var avgGrade: Double? = null
        var normalizedPower: Double? = null
        var tss: Double? = null
        var intensityFactor: Double? = null
        var avgTemp: Double? = null
        var avgVerticalOscillationMm: Double? = null
        var avgStanceTimeMs: Double? = null
        var avgVerticalRatio: Double? = null
        var avgStepLengthMm: Double? = null
        var avgRespirationRate: Double? = null
        var aerobicTe: Double? = null
        var anaerobicTe: Double? = null
        var device: String? = null
    }
}
