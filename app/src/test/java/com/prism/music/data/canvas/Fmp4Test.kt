package com.prism.music.data.canvas

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The box tools under Canvas's fetch (4.2), on the real first bytes of three YouTube video-only streams
 * (`canvas/<id>-<height>.head.bin`: ftyp + moov + sidx, through the format's indexRange end) and two real moofs.
 * Key-frame times below are ffprobe's (`-skip_frame nokey -show_entries frame=pts_time`) on the whole files.
 */
class Fmp4Test {
    private fun res(name: String): ByteArray = javaClass.classLoader!!.getResourceAsStream("canvas/$name")!!.readBytes()

    /** ffprobe's key frames in the first 40 s of fcnDmrtj6Sk (the same at 720 and 1080: one encode ladder). */
    private val fcnKeys = listOf(0.0, 1.626625, 2.877875, 3.586917, 4.671333, 6.965292, 8.550208, 10.427083, 13.138125,
        20.020000, 20.145125, 22.605917, 23.773750, 25.442083, 28.111417, 29.904875, 31.948583, 35.035000, 38.955583)
    private val fJ9Keys = listOf(0.0, 1.0, 6.0, 10.0, 16.92, 22.0, 28.0, 34.0)

    private fun check(name: String, indexStart: Int, count: Int, fileSize: Long, keys: List<Double>) {
        val head = res(name)
        val sidx = Fmp4.parseSidx(head, indexStart)
        assertEquals("$name fragments", count, sidx.fragments.size)
        val first = sidx.fragments.first()
        assertEquals("$name: the first fragment right after the index", head.size.toLong(), first.offset)
        assertEquals(0L, first.startTicks)
        sidx.fragments.zipWithNext().forEach { (a, b) ->
            assertEquals("$name: contiguous in bytes", a.end, b.offset)
            assertEquals("$name: contiguous in time", a.endTicks, b.startTicks)
            assertTrue("$name: increasing", b.startTicks > a.startTicks)
        }
        assertEquals("$name: the last fragment ends at the file's end", fileSize, sidx.fragments.last().end)
        // Every fragment starting in ffprobe's 40 s is one of its key frames (to the millisecond).
        sidx.fragments.filter { sidx.startMs(it) < 39_000 }.also { assertTrue(it.size >= 4) }.forEach { f ->
            val s = f.startTicks.toDouble() / sidx.timescale
            assertTrue("$name fragment ${f.index} at $s s is a key frame", keys.any { kotlin.math.abs(it - s) < 0.001 })
            assertTrue("starts with a SAP", f.sapWord and 0x80000000L != 0L)
        }
    }

    @Test fun sidxOfRealStreams() {
        check("fcnDmrtj6Sk-720.head.bin", 739, 49, 31_244_232, fcnKeys)
        check("fcnDmrtj6Sk-1080.head.bin", 741, 49, 88_858_852, fcnKeys)
        check("fJ9rUzIMcZQ-1080.head.bin", 742, 64, 88_705_604, fJ9Keys)
    }

    @Test fun initOfARealStream() {
        val head = res("fcnDmrtj6Sk-720.head.bin")
        val init = Fmp4.parseInit(head.copyOfRange(0, 739))
        assertEquals(24_000L, init.mediaTimescale)
        // One frame's delay for B-frames (24000/1001 fps).
        assertEquals(1001L, init.editShift)
        assertEquals(listOf("ftyp", "moov", "sidx"), Fmp4.topLevel(head))
    }

    /** Real moofs of fragments 3 (13.138 s) and 4 (20.145 s) of fcnDmrtj6Sk at 720. */
    private fun moofs(): Pair<ByteArray, ByteArray> {
        val both = res("fcnDmrtj6Sk-720.moof3-4.bin")
        val boxes = Fmp4.boxes(both, 0, both.size)
        assertEquals(listOf("moof", "moof"), boxes.map { it.type })
        return both.copyOfRange(boxes[0].start, boxes[0].end) to both.copyOfRange(boxes[1].start, boxes[1].end)
    }

    @Test fun keyFramesOfRealMoofs() {
        val init = Fmp4.parseInit(res("fcnDmrtj6Sk-720.head.bin").copyOfRange(0, 739))
        val (a, b) = moofs()
        val keysA = Fmp4.keyFrameTicks(a, init).map { it.toDouble() / init.mediaTimescale }
        val keysB = Fmp4.keyFrameTicks(b, init).map { it.toDouble() / init.mediaTimescale }
        println("[fmp4] key frames in fragment 3: $keysA; in fragment 4: $keysB")
        assertEquals(13.138125, keysA.first(), 0.001)
        assertEquals(20.145125, keysB.first(), 0.001)
        // YouTube marks only a fragment's first frame as a key frame: the I-frames ffprobe also lists inside a fragment
        // (20.020 in fragment 3) are not ones a player can start on, so loops start on fragment starts.
        assertEquals(listOf(13.138125), keysA.map { Math.round(it * 1e6) / 1e6 })
        // Whatever the samples mark as key frames, ffprobe agrees (the container's marks are what a player seeks by).
        (keysA + keysB).forEach { k -> assertTrue("$k is a key frame", fcnKeys.any { kotlin.math.abs(it - k) < 0.001 }) }
    }

    @Test fun tfdtRewriteOnRealMoofs() {
        val (a, b) = moofs()
        val ta = Fmp4.decodeTime(a)
        val tb = Fmp4.decodeTime(b)
        assertTrue("fragment 3 is 13 s in", ta > 13 * 24_000)
        val version = a[tfdtOf(a)].toInt()
        println("[fmp4] tfdt version $version, $ta and $tb")
        val before = a.size
        Fmp4.rewriteMoof(a, ta)
        Fmp4.rewriteMoof(b, ta)
        assertEquals(before, a.size)
        assertEquals(0L, Fmp4.decodeTime(a))
        assertEquals("the second keeps its distance", tb - ta, Fmp4.decodeTime(b))
    }

    /** Where the tfdt's version byte sits in a moof. */
    private fun tfdtOf(moof: ByteArray): Int {
        val top = Fmp4.boxes(moof, 0, moof.size).first()
        val traf = Fmp4.boxes(moof, top.body, top.end).first { it.type == "traf" }
        return Fmp4.boxes(moof, traf.body, traf.end).first { it.type == "tfdt" }.body
    }

    @Test fun tfdtRewriteBothVersions() {
        for (version in 0..1) {
            val a = SyntheticStream.moof(seq = 1, decodeTime = 90_000, version = version, samples = 3, sampleTicks = 1000, dataSize = 30)
            val b = SyntheticStream.moof(seq = 2, decodeTime = 93_000, version = version, samples = 3, sampleTicks = 1000, dataSize = 30)
            Fmp4.rewriteMoof(a, 90_000)
            Fmp4.rewriteMoof(b, 90_000)
            assertEquals("v$version", 0L, Fmp4.decodeTime(a))
            assertEquals("v$version", 3_000L, Fmp4.decodeTime(b))
            assertEquals(version, a[tfdtOf(a)].toInt())
        }
    }

    @Test fun absoluteBaseOffsetMovesWithTheMoof() {
        val m = SyntheticStream.moof(seq = 1, decodeTime = 5, version = 1, samples = 1, sampleTicks = 1, dataSize = 4, baseOffset = 10_000)
        Fmp4.rewriteMoof(m, 5, moved = -9_000)
        val top = Fmp4.boxes(m, 0, m.size).first()
        val traf = Fmp4.boxes(m, top.body, top.end).first { it.type == "traf" }
        val tfhd = Fmp4.boxes(m, traf.body, traf.end).first { it.type == "tfhd" }
        assertEquals(1_000L, Fmp4.u64(m, tfhd.body + 8))
    }

    @Test fun writtenSidxReadsBack() {
        val head = res("fcnDmrtj6Sk-720.head.bin")
        val sidx = Fmp4.parseSidx(head, 739)
        val frags = sidx.fragments.subList(3, 6)
        val out = Buffer()
        Fmp4.writeSidx(out, sidx.referenceId, sidx.timescale, 0, frags)
        val bytes = out.readByteArray()
        assertEquals(Fmp4.sidxSize(3), bytes.size.toLong())
        val back = Fmp4.parseSidx(bytes, 0)
        assertEquals(frags.map { it.size }, back.fragments.map { it.size })
        assertEquals(frags.map { it.durationTicks }, back.fragments.map { it.durationTicks })
        assertEquals(0L, back.fragments.first().startTicks)
        assertEquals(bytes.size.toLong(), back.fragments.first().offset)
    }
}
