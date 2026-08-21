package ch.steigis.overprint.data.parse

import ch.steigis.overprint.domain.model.ActivityDetail
import ch.steigis.overprint.domain.model.ActivityType
import ch.steigis.overprint.domain.model.DataSource
import ch.steigis.overprint.domain.model.Lap
import ch.steigis.overprint.domain.model.TrackPoint
import ch.steigis.overprint.domain.stats.StatsEngine
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object GpxParser {
    private val iso = listOf(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss",
    ).map {
        SimpleDateFormat(it, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    fun parse(stream: InputStream, id: String, nameHint: String? = null): ActivityDetail {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(stream, null)
        val points = mutableListOf<TrackPoint>()
        var lat: Double? = null
        var lon: Double? = null
        var ele: Double? = null
        var time: Long? = null
        var hr: Double? = null
        var cad: Double? = null
        var name = nameHint
        var tag = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name.lowercase()
                    if (tag == "trkpt") {
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                        ele = null; time = null; hr = null; cad = null
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isEmpty()) {
                        // skip
                    } else when (tag) {
                        "ele" -> ele = text.toDoubleOrNull()
                        "time" -> time = parseTime(text)
                        "name" -> if (name == null) name = text
                        "hr", "gpxtpx:hr" -> hr = text.toDoubleOrNull()
                        "cad", "gpxtpx:cad" -> cad = text.toDoubleOrNull()
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name.equals("trkpt", true) && lat != null && lon != null) {
                        val ts = time ?: (points.lastOrNull()?.timestampMillis?.plus(1000) ?: System.currentTimeMillis())
                        val start = points.firstOrNull()?.timestampMillis ?: ts
                        points += TrackPoint(
                            activityId = id,
                            timestampMillis = ts,
                            elapsedSeconds = (ts - start) / 1000.0,
                            latitude = lat,
                            longitude = lon,
                            altitudeMeters = ele,
                            distanceMeters = null,
                            speedMps = null,
                            heartRate = hr,
                            cadence = cad,
                            power = null,
                            gradePercent = null,
                            temperatureC = null,
                        )
                    }
                    tag = ""
                }
            }
            event = parser.next()
        }
        val track = StatsEngine.enrichTrack(points)
        val type = guessType(name)
        val activity = StatsEngine.summaryFromTrack(
            id = id,
            name = name ?: "${type.displayName} import",
            type = type,
            source = DataSource.FILE,
            track = track,
        )
        val laps = StatsEngine.computedLaps(track, 1000.0)
        return ActivityDetail(activity, track, laps)
    }

    private fun parseTime(text: String): Long? {
        iso.forEach { fmt ->
            runCatching { return fmt.parse(text)?.time }
        }
        return null
    }

    private fun guessType(name: String?): ActivityType = ActivityType.fromKey(name)
}

object TcxParser {
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val iso2 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun parse(stream: InputStream, id: String, nameHint: String? = null): ActivityDetail {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(stream, null)
        val points = mutableListOf<TrackPoint>()
        val laps = mutableListOf<Lap>()
        var sport = nameHint
        var tag = ""
        var lat: Double? = null
        var lon: Double? = null
        var ele: Double? = null
        var time: Long? = null
        var hr: Double? = null
        var cad: Double? = null
        var speed: Double? = null
        var dist: Double? = null
        var watts: Double? = null
        var lapDist = 0.0
        var lapTime = 0.0
        var lapIndex = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name
                    if (tag == "Activity") sport = parser.getAttributeValue(null, "Sport") ?: sport
                    if (tag == "Trackpoint") {
                        lat = null; lon = null; ele = null; time = null; hr = null; cad = null; speed = null; dist = null; watts = null
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isEmpty()) {
                        // skip
                    } else when (tag) {
                        "LatitudeDegrees" -> lat = text.toDoubleOrNull()
                        "LongitudeDegrees" -> lon = text.toDoubleOrNull()
                        "AltitudeMeters" -> ele = text.toDoubleOrNull()
                        "Time" -> time = runCatching { iso.parse(text)?.time }.getOrNull()
                            ?: runCatching { iso2.parse(text)?.time }.getOrNull()
                        "HeartRateBpm", "Value" -> if (hr == null) hr = text.toDoubleOrNull()
                        "Cadence" -> cad = text.toDoubleOrNull()
                        "DistanceMeters" -> dist = text.toDoubleOrNull()
                        "Speed" -> speed = text.toDoubleOrNull()
                        "Watts" -> watts = text.toDoubleOrNull()
                        "TotalTimeSeconds" -> lapTime = text.toDoubleOrNull() ?: lapTime
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "Trackpoint" -> {
                            val ts = time ?: (points.lastOrNull()?.timestampMillis?.plus(1000) ?: System.currentTimeMillis())
                            val start = points.firstOrNull()?.timestampMillis ?: ts
                            points += TrackPoint(
                                activityId = id,
                                timestampMillis = ts,
                                elapsedSeconds = (ts - start) / 1000.0,
                                latitude = lat,
                                longitude = lon,
                                altitudeMeters = ele,
                                distanceMeters = dist,
                                speedMps = speed,
                                heartRate = hr,
                                cadence = cad,
                                power = watts,
                                gradePercent = null,
                                temperatureC = null,
                            )
                        }
                        "Lap" -> {
                            lapIndex++
                            val slice = points.takeLast(2)
                            laps += Lap(
                                activityId = id,
                                index = lapIndex,
                                startTimeMillis = slice.firstOrNull()?.timestampMillis ?: 0L,
                                durationSeconds = lapTime,
                                distanceMeters = lapDist,
                                avgHeartRate = null,
                                maxHeartRate = null,
                                avgSpeedMps = null,
                                avgCadence = null,
                                avgPower = null,
                                elevationGainMeters = null,
                                label = "Lap $lapIndex",
                            )
                        }
                    }
                    tag = ""
                }
            }
            event = parser.next()
        }
        val track = StatsEngine.enrichTrack(points)
        val type = ActivityType.fromKey(sport)
        val activity = StatsEngine.summaryFromTrack(id, nameHint ?: "${type.displayName} import", type, DataSource.FILE, track, laps)
        return ActivityDetail(activity, track, laps.ifEmpty { StatsEngine.computedLaps(track, 1000.0) })
    }
}

object ActivityFileParser {
    fun parse(bytes: ByteArray, fileName: String): ActivityDetail {
        val id = "file-${fileName.hashCode()}-${bytes.size}"
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".fit") || (bytes.size > 8 && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == ".FIT") ->
                FitParser.parse(bytes, id, fileName.substringBeforeLast('.'))
            lower.endsWith(".gpx") -> GpxParser.parse(bytes.inputStream(), id, fileName.substringBeforeLast('.'))
            lower.endsWith(".tcx") -> TcxParser.parse(bytes.inputStream(), id, fileName.substringBeforeLast('.'))
            bytes.size > 8 && bytes.copyOfRange(8, 12).contentEquals(".FIT".toByteArray()) ->
                FitParser.parse(bytes, id, fileName)
            else -> {
                val text = runCatching { bytes.toString(Charsets.UTF_8).take(80) }.getOrNull().orEmpty()
                when {
                    text.contains("<gpx") -> GpxParser.parse(bytes.inputStream(), id, fileName)
                    text.contains("<TrainingCenterDatabase") -> TcxParser.parse(bytes.inputStream(), id, fileName)
                    else -> FitParser.parse(bytes, id, fileName)
                }
            }
        }
    }
}
