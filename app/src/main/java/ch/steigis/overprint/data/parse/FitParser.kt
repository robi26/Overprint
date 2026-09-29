package ch.steigis.overprint.data.parse

import ch.steigis.overprint.domain.model.Activity
import ch.steigis.overprint.domain.model.ActivityDetail
import ch.steigis.overprint.domain.model.ActivityDevice
import ch.steigis.overprint.domain.model.ActivityType
import ch.steigis.overprint.domain.model.DataSource
import ch.steigis.overprint.domain.model.DeviceConnection
import ch.steigis.overprint.domain.model.Lap
import ch.steigis.overprint.domain.model.Split
import ch.steigis.overprint.domain.model.SplitKind
import ch.steigis.overprint.domain.model.TrackPoint
import ch.steigis.overprint.domain.stats.StatsEngine
import ch.steigis.overprint.domain.stats.sanitizeActivity
import ch.steigis.overprint.domain.stats.sanitizeFitUnits
import ch.steigis.overprint.domain.stats.sanitizeLap
import ch.steigis.overprint.domain.stats.withDerivedTrackStats
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * Decoder for Garmin FIT activity files. Covers the messages used for summaries:
 * file_id, device_info, session, lap, record, activity, event, split.
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
                stanceTimeBalancePercent = r.stanceTimeBalancePercent,
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

        val lastRecordTs = records.mapNotNull { it.timestamp }.maxOrNull()
        val endMillis = lastRecordTs?.let(::fitTimestampToMillis)
            ?: (startMillis + ((session.elapsed ?: 0.0) * 1000).toLong())
        val splits = (deviceSplits(id, session.splits, track) + riderPositionSplits(id, session.riderPositions, track, endMillis))
            .sortedBy { it.startTimeMillis }

        return ActivityDetail(
            sanitizeActivity(activity.copy(id = id, hasTrack = track.isNotEmpty()))
                .let { it.copy(deviceName = it.deviceName ?: session.device) }
                .withDerivedTrackStats(track),
            track.map { it.copy(activityId = id) },
            parsedLaps.map { sanitizeLap(it.copy(activityId = id)) },
            splits,
            activityDevices(id, session.devices.values),
        )
    }

    /** The recording device and the sensors it logged in device_info, recorder first. */
    private fun activityDevices(id: String, raw: Collection<RawDevice>): List<ActivityDevice> =
        raw.mapNotNull { d ->
            val connection = when {
                d.creator -> DeviceConnection.RECORDER
                d.sourceType == 0 || d.sourceType == 1 -> DeviceConnection.ANT
                d.sourceType == 2 || d.sourceType == 3 -> DeviceConnection.BLUETOOTH
                d.sourceType == 5 -> DeviceConnection.BUILT_IN
                else -> DeviceConnection.OTHER
            }
            val kind = deviceKind(d.sourceType, d.deviceType)
            val manufacturer = d.manufacturer?.let(FitNames::manufacturer)
            val product = d.name
                ?: d.product?.takeIf { d.manufacturer in GARMIN_MANUFACTURERS }?.let(FitNames::garminProduct)
            // Built-in sensors often carry the watch's own product id, so name them by what they measure.
            val name = (if (connection == DeviceConnection.BUILT_IN) kind ?: product else product)
                ?: listOfNotNull(manufacturer, kind?.lowercase()).joinToString(" ").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            ActivityDevice(
                activityId = id,
                name = name,
                connection = connection,
                manufacturer = manufacturer,
                kind = kind,
                serialNumber = d.serial,
                softwareVersion = d.software?.let { String.format(java.util.Locale.US, "%.2f", it) },
                batteryStatus = when (d.batteryStatus) {
                    1 -> "new"
                    2 -> "good"
                    3 -> "ok"
                    4 -> "low"
                    5 -> "critical"
                    6 -> "charging"
                    else -> null
                },
                batteryPercent = d.batteryLevel?.takeIf { it in 0..100 },
                batteryVoltage = d.batteryVoltage,
            )
        }.sortedBy { it.connection.ordinal }

    /** device_type is read through ant+/ble/local device type tables, picked by source_type. */
    private fun deviceKind(sourceType: Int?, deviceType: Int?): String? = when (sourceType) {
        0, 1 -> when (deviceType) {
            11 -> "Power meter"
            12, 25 -> "Environment sensor"
            15 -> "Speed/distance sensor"
            16 -> "Remote control"
            17 -> "Fitness equipment"
            27 -> "Control hub"
            31 -> "Muscle oxygen sensor"
            34 -> "Shifting"
            35, 36 -> "Bike light"
            38 -> "Display"
            40 -> "Bike radar"
            46 -> "Bike aero sensor"
            119 -> "Weight scale"
            120 -> "Heart rate monitor"
            121 -> "Speed/cadence sensor"
            122 -> "Cadence sensor"
            123 -> "Speed sensor"
            124 -> "Footpod"
            else -> null
        }
        2, 3 -> when (deviceType) {
            0 -> "Connected GPS"
            1 -> "Heart rate monitor"
            2 -> "Power meter"
            3 -> "Speed/cadence sensor"
            4 -> "Speed sensor"
            5 -> "Cadence sensor"
            6 -> "Footpod"
            7 -> "Bike trainer"
            else -> null
        }
        5 -> when (deviceType) {
            0 -> "GPS"
            1 -> "GLONASS"
            2 -> "GPS/GLONASS"
            3 -> "Accelerometer"
            4 -> "Barometer"
            5 -> "Temperature sensor"
            10 -> "Wrist heart rate"
            12 -> "Sensor hub"
            else -> null
        }
        else -> null
    }

    /** Run/walk detection and ClimbPro splits the watch wrote as split messages. */
    private fun deviceSplits(id: String, raw: List<RawSplit>, track: List<TrackPoint>): List<Split> =
        raw.mapNotNull { r ->
            val elapsed = r.elapsed?.takeIf { it > 0 } ?: return@mapNotNull null
            val start = fitTimestampToMillis(r.start ?: return@mapNotNull null)
            val window = WindowStats(track, start, start + (elapsed * 1000).toLong())
            val moving = r.moving ?: r.timer
            val distance = r.distance ?: window.distance
            Split(
                activityId = id,
                kind = r.kind,
                startTimeMillis = start,
                durationSeconds = elapsed,
                movingSeconds = moving,
                distanceMeters = distance,
                ascentMeters = r.ascent ?: window.ascent,
                descentMeters = r.descent ?: window.descent,
                avgHeartRate = r.avgHr ?: window.avgHr,
                maxHeartRate = r.maxHr ?: window.maxHr,
                avgSpeedMps = r.avgSpeed ?: distance?.let { it / (moving ?: elapsed) },
                avgCadence = r.avgCadence ?: window.avgCadence,
                avgPower = r.avgPower ?: window.avgPower,
                avgGradePercent = r.avgGrade ?: window.grade,
            )
        }

    /** Seated and standing stretches from rider_position_change events (cycling dynamics pedals). */
    private fun riderPositionSplits(
        id: String,
        events: List<Pair<Long, Int>>,
        track: List<TrackPoint>,
        endMillis: Long,
    ): List<Split> {
        val changes = events.sortedBy { it.first }.mapNotNull { (ts, position) ->
            val kind = when (position) {
                0, 2 -> SplitKind.SEATED
                1, 3 -> SplitKind.STANDING
                else -> null
            }
            kind?.let { fitTimestampToMillis(ts) to it }
        }
        if (changes.none { it.second == SplitKind.STANDING }) return emptyList()
        val segments = mutableListOf<Triple<SplitKind, Long, Long>>()
        changes.forEachIndexed { i, (start, kind) ->
            val end = changes.getOrNull(i + 1)?.first ?: endMillis
            val last = segments.lastOrNull()
            if (last != null && last.first == kind) {
                segments[segments.lastIndex] = Triple(kind, last.second, end)
            } else {
                segments += Triple(kind, start, end)
            }
        }
        return segments.filter { it.third - it.second >= 1000L }.map { (kind, start, end) ->
            val window = WindowStats(track, start, end)
            val seconds = (end - start) / 1000.0
            Split(
                activityId = id,
                kind = kind,
                startTimeMillis = start,
                durationSeconds = seconds,
                distanceMeters = window.distance,
                ascentMeters = window.ascent,
                descentMeters = window.descent,
                avgHeartRate = window.avgHr,
                maxHeartRate = window.maxHr,
                avgSpeedMps = window.distance?.let { it / seconds },
                avgCadence = window.avgCadence,
                avgPower = window.avgPower,
                avgGradePercent = window.grade,
            )
        }
    }

    /** Track statistics between two times, for split fields the file leaves out. */
    private class WindowStats(track: List<TrackPoint>, startMillis: Long, endMillis: Long) {
        private val points = track.filter { it.timestampMillis >= startMillis && it.timestampMillis < endMillis }
        val distance: Double? = points.mapNotNull { it.distanceMeters }.let { d ->
            if (d.size >= 2) (d.last() - d.first()).coerceAtLeast(0.0) else null
        }
        val ascent: Double? = if (points.count { it.altitudeMeters != null } >= 2) StatsEngine.elevationGain(points) else null
        val descent: Double? = if (points.count { it.altitudeMeters != null } >= 2) StatsEngine.elevationLoss(points) else null
        val avgHr: Double? = points.mapNotNull { it.heartRate }.averageOrNull()
        val maxHr: Double? = points.mapNotNull { it.heartRate }.maxOrNull()
        val avgCadence: Double? = points.mapNotNull { it.cadence?.takeIf { c -> c > 0 } }.averageOrNull()
        val avgPower: Double? = points.mapNotNull { it.power }.averageOrNull()
        val grade: Double? = run {
            val alts = points.mapNotNull { it.altitudeMeters }
            val dist = distance
            if (alts.size >= 2 && dist != null && dist > 10.0) (alts.last() - alts.first()) / dist * 100.0 else null
        }

        private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
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
                val name = if (global == 0) fields.strings[8] else fields.strings[27] ?: fields.strings[19]
                val creator = global == 0 || index == 0.0
                val serial = values[3]?.toLong()?.takeIf { it > 0 }
                val raw = RawDevice(
                    creator = creator,
                    sourceType = if (global == 23) values[25]?.toInt() else null,
                    deviceType = if (global == 23) values[1]?.toInt() else null,
                    manufacturer = values[if (global == 0) 1 else 2]?.toInt(),
                    product = values[if (global == 0) 2 else 4]?.toInt(),
                    serial = serial,
                    name = name,
                    software = if (global == 23) values[5]?.div(100.0) else null,
                    batteryStatus = if (global == 23) values[11]?.toInt() else null,
                    batteryLevel = if (global == 23) values[32]?.toInt() else null,
                    batteryVoltage = if (global == 23) values[10]?.div(256.0) else null,
                )
                // Devices are logged again (e.g. at the end with battery state); merge per device.
                val key = when {
                    creator -> "creator"
                    serial != null -> "serial:$serial"
                    values[21] != null -> "ant:${values[21]?.toInt()}:${raw.deviceType}"
                    else -> "device:${index?.toInt()}:${raw.sourceType}:${raw.deviceType}:${raw.manufacturer}:${raw.product}"
                }
                session.devices[key] = session.devices[key]?.merge(raw) ?: raw
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
                    stanceTimeBalancePercent = values[84]?.div(100.0)?.takeIf { it in 30.0..70.0 },
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
            21 -> {
                // event 44 = rider_position_change; data holds the rider_position_type.
                val position = (values[3] ?: values[2])?.toInt()
                if (values[0]?.toInt() == 44 && timestamp != null && position != null) {
                    session.riderPositions += timestamp to position
                }
            }
            312 -> {
                val kind = when (values[0]?.toInt()) {
                    17 -> SplitKind.RUN
                    18 -> SplitKind.WALK
                    22 -> SplitKind.IDLE
                    9 -> SplitKind.CLIMB
                    else -> null
                }
                if (kind != null) {
                    val elapsed = values[1]?.div(1000.0)
                    session.splits += RawSplit(
                        kind = kind,
                        start = values[9]?.toLong() ?: timestamp?.minus((elapsed ?: 0.0).toLong()),
                        elapsed = elapsed,
                        timer = values[2]?.div(1000.0),
                        moving = values[110]?.div(1000.0),
                        distance = values[3]?.div(100.0),
                        avgSpeed = values[4]?.div(1000.0),
                        ascent = values[13],
                        descent = values[14],
                        avgHr = values[15],
                        maxHr = values[16],
                        avgCadence = values[29]?.div(128.0),
                        avgPower = values[40],
                        avgGrade = values[88]?.div(100.0),
                    )
                }
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
            11 -> { // uint16z
                val v = buf.short.toInt() and 0xFFFF
                invalid = 0.0
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
        if (manufacturer in GARMIN_MANUFACTURERS && product != null) FitNames.garminProduct(product)?.let { return it }
        val mfr = manufacturer?.let(FitNames::manufacturer) ?: return null
        return if (product != null) "$mfr $product" else mfr
    }

    /** Garmin, Dynastream and Dynastream OEM share Garmin's product ids. */
    private val GARMIN_MANUFACTURERS = setOf(1, 13, 15)

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
        val stanceTimeBalancePercent: Double? = null,
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
    private data class RawSplit(
        val kind: SplitKind,
        val start: Long?,
        val elapsed: Double?,
        val timer: Double?,
        val moving: Double?,
        val distance: Double?,
        val avgSpeed: Double?,
        val ascent: Double?,
        val descent: Double?,
        val avgHr: Double?,
        val maxHr: Double?,
        val avgCadence: Double?,
        val avgPower: Double?,
        val avgGrade: Double?,
    )
    private data class RawDevice(
        val creator: Boolean,
        val sourceType: Int?,
        val deviceType: Int?,
        val manufacturer: Int?,
        val product: Int?,
        val serial: Long?,
        val name: String?,
        val software: Double?,
        val batteryStatus: Int?,
        val batteryLevel: Int?,
        val batteryVoltage: Double?,
    ) {
        /** Later messages win where they have a value; battery readings are latest-first. */
        fun merge(next: RawDevice) = RawDevice(
            creator = creator || next.creator,
            sourceType = next.sourceType ?: sourceType,
            deviceType = next.deviceType ?: deviceType,
            manufacturer = next.manufacturer ?: manufacturer,
            product = next.product ?: product,
            serial = next.serial ?: serial,
            name = next.name ?: name,
            software = next.software ?: software,
            batteryStatus = next.batteryStatus ?: batteryStatus,
            batteryLevel = next.batteryLevel ?: batteryLevel,
            batteryVoltage = next.batteryVoltage ?: batteryVoltage,
        )
    }
    private class RawSession {
        val devices = LinkedHashMap<String, RawDevice>()
        val splits = mutableListOf<RawSplit>()
        val riderPositions = mutableListOf<Pair<Long, Int>>()
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
