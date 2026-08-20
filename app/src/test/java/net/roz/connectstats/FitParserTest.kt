package net.roz.connectstats

import net.roz.connectstats.data.parse.FitParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FitParserTest {

    @Test
    fun elapsedSecondsUsesRecordTimesNotSessionEnd() {
        val start = 1_000_000_000L
        val bytes = fitFile {
            definition(local = 0, global = 20, fields = listOf(Field(253, 4, 6), Field(3, 1, 2)))
            data(0) {
                u32(start)
                u8(140)
            }
            data(0) {
                u32(start + 10)
                u8(150)
            }
            data(0) {
                u32(start + 20)
                u8(160)
            }
            definition(local = 1, global = 18, fields = listOf(Field(253, 4, 6), Field(2, 4, 6), Field(7, 4, 6)))
            data(1) {
                u32(start + 20)
                u32(start)
                u32(20_000)
            }
        }
        val detail = FitParser.parse(bytes, "t")
        assertEquals(3, detail.track.size)
        assertEquals(0.0, detail.track[0].elapsedSeconds, 0.01)
        assertEquals(10.0, detail.track[1].elapsedSeconds, 0.01)
        assertEquals(20.0, detail.track[2].elapsedSeconds, 0.01)
        assertEquals(140.0, detail.track[0].heartRate)
        assertEquals(160.0, detail.track[2].heartRate)
        assertEquals(20.0, detail.activity.durationSeconds, 0.01)
    }

    @Test
    fun compressedTimestampRecordsKeepHeartRateSeries() {
        val start = 1_000_000_000L
        val bytes = fitFile {
            definition(local = 0, global = 20, fields = listOf(Field(253, 4, 6), Field(3, 1, 2)))
            data(0) {
                u32(start)
                u8(120)
            }
            compressed(local = 0, timeOffset = 12) { u8(130) }
            compressed(local = 0, timeOffset = 24) { u8(140) }
        }
        val detail = FitParser.parse(bytes, "t")
        assertEquals(3, detail.track.size)
        assertEquals(listOf(120.0, 130.0, 140.0), detail.track.map { it.heartRate })
        assertTrue(detail.track.last().elapsedSeconds > detail.track.first().elapsedSeconds)
    }

    @Test
    fun developerFieldsDoNotDesyncFollowingRecords() {
        val start = 1_000_000_000L
        val bytes = fitFile {
            definition(
                local = 0,
                global = 20,
                fields = listOf(Field(253, 4, 6), Field(3, 1, 2)),
                developerFields = listOf(Field(0, 4, 0)),
            )
            data(0) {
                u32(start)
                u8(110)
                u32(0)
            }
            data(0) {
                u32(start + 5)
                u8(115)
                u32(0)
            }
        }
        val detail = FitParser.parse(bytes, "t")
        assertEquals(2, detail.track.size)
        assertEquals(110.0, detail.track[0].heartRate)
        assertEquals(115.0, detail.track[1].heartRate)
        assertEquals(5.0, detail.track[1].elapsedSeconds, 0.01)
    }

    @Test
    fun recordExtrasAndSessionTrainingMetricsAreParsed() {
        val start = 1_000_000_000L
        val bytes = fitFile {
            definition(
                local = 0,
                global = 20,
                fields = listOf(
                    Field(253, 4, 6),
                    Field(3, 1, 2),
                    Field(13, 1, 1),
                    Field(39, 2, 4),
                    Field(41, 2, 4),
                    Field(83, 2, 4),
                    Field(85, 2, 4),
                    Field(108, 2, 4),
                ),
            )
            data(0) {
                u32(start)
                u8(150)
                s8(18)
                u16(920)
                u16(2460)
                u16(850)
                u16(11200)
                u16(2450)
            }
            data(0) {
                u32(start + 10)
                u8(155)
                s8(19)
                u16(900)
                u16(2400)
                u16(820)
                u16(11000)
                u16(2500)
            }
            definition(
                local = 1,
                global = 18,
                fields = listOf(
                    Field(253, 4, 6),
                    Field(2, 4, 6),
                    Field(7, 4, 6),
                    Field(19, 1, 2),
                    Field(23, 2, 4),
                    Field(24, 1, 2),
                    Field(34, 2, 4),
                    Field(35, 2, 4),
                    Field(36, 2, 4),
                    Field(57, 1, 1),
                    Field(64, 1, 2),
                    Field(137, 1, 2),
                ),
            )
            data(1) {
                u32(start + 10)
                u32(start)
                u32(10_000)
                u8(185)
                u16(42)
                u8(32)
                u16(248)
                u16(450)
                u16(850)
                s8(18)
                u8(120)
                u8(15)
            }
            definition(local = 2, global = 23, fields = listOf(Field(0, 1, 2), Field(27, 16, 7)))
            data(2) {
                u8(0)
                str("Forerunner 965", 16)
            }
        }
        val detail = FitParser.parse(bytes, "t")
        assertEquals(18.0, detail.track[0].temperatureC)
        assertEquals(92.0, detail.track[0].verticalOscillationMm!!, 0.01)
        assertEquals(246.0, detail.track[0].stanceTimeMs!!, 0.01)
        assertEquals(8.5, detail.track[0].verticalRatio!!, 0.01)
        assertEquals(1120.0, detail.track[0].stepLengthMm!!, 0.01)
        assertEquals(24.5, detail.track[0].respirationRate!!, 0.01)
        assertEquals(185.0, detail.activity.maxCadence)
        assertEquals(42.0, detail.activity.elevationLossMeters)
        assertEquals(3.2, detail.activity.aerobicTrainingEffect!!, 0.01)
        assertEquals(1.5, detail.activity.anaerobicTrainingEffect!!, 0.01)
        assertEquals(248.0, detail.activity.normalizedPower)
        assertEquals(45.0, detail.activity.trainingStressScore!!, 0.01)
        assertEquals(0.85, detail.activity.intensityFactor!!, 0.01)
        assertEquals(18.0, detail.activity.avgTemperatureC)
        assertEquals(120.0, detail.activity.minHeartRate)
        assertEquals("Forerunner 965", detail.activity.deviceName)
    }
}

private data class Field(val num: Int, val size: Int, val base: Int)

private class FitWriter {
    private val data = ByteArrayOutputStream()

    fun definition(local: Int, global: Int, fields: List<Field>, developerFields: List<Field> = emptyList()) {
        val header = 0x40 or local or if (developerFields.isNotEmpty()) 0x20 else 0
        data.write(header)
        data.write(0)
        data.write(0)
        data.write(global and 0xFF)
        data.write((global shr 8) and 0xFF)
        data.write(fields.size)
        fields.forEach { field ->
            data.write(field.num)
            data.write(field.size)
            data.write(field.base)
        }
        if (developerFields.isNotEmpty()) {
            data.write(developerFields.size)
            developerFields.forEach { field ->
                data.write(field.num)
                data.write(field.size)
                data.write(field.base)
            }
        }
    }

    fun data(local: Int, write: FitWriter.() -> Unit) {
        data.write(local and 0x0F)
        write()
    }

    fun compressed(local: Int, timeOffset: Int, write: FitWriter.() -> Unit) {
        data.write(0x80 or ((local and 0x03) shl 5) or (timeOffset and 0x1F))
        write()
    }

    fun u8(value: Int) {
        data.write(value and 0xFF)
    }

    fun u16(value: Int) {
        val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
        data.write(buf.array())
    }

    fun s8(value: Int) {
        data.write(value and 0xFF)
    }

    fun str(value: String, size: Int) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        data.write(bytes, 0, minOf(bytes.size, size))
        repeat((size - bytes.size).coerceAtLeast(0)) { data.write(0) }
    }

    fun u32(value: Long) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt())
        data.write(buf.array())
    }

    fun toDataBytes(): ByteArray = data.toByteArray()
}

private fun fitFile(build: FitWriter.() -> Unit): ByteArray {
    val payload = FitWriter().apply(build).toDataBytes()
    val header = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
    header.put(14)
    header.put(0x10)
    header.putShort(0x082E)
    header.putInt(payload.size)
    header.put(".FIT".toByteArray(Charsets.US_ASCII))
    header.putShort(0)
    return header.array() + payload + byteArrayOf(0, 0)
}
