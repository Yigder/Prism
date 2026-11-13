package com.prism.music.data.canvas

import okio.BufferedSink
import okio.BufferedSource
import java.io.IOException

/**
 * The few parts of a fragmented MP4 (YouTube's video-only streams: ftyp + moov, a sidx, then moof/mdat pairs) the
 * Canvas fetch needs to cut a few seconds out of a video and keep them as a file that plays by itself. Pure functions
 * over bytes, so each is tested on real YouTube bytes without a phone.
 */
object Fmp4 {

    /** One fragment as the stream's index (sidx) lists it: it starts on a key frame. Times in [Sidx.timescale] ticks. */
    data class Fragment(
        val index: Int,
        /** Byte range in the whole stream, [offset] inclusive to [end] exclusive. */
        val offset: Long,
        val size: Long,
        val startTicks: Long,
        val durationTicks: Long,
        /** The sidx reference's third word (starts-with-SAP, SAP type, delta), copied into the cut file's own index. */
        val sapWord: Long,
    ) {
        val end: Long get() = offset + size
        val endTicks: Long get() = startTicks + durationTicks
    }

    data class Sidx(
        val referenceId: Long,
        val timescale: Long,
        val fragments: List<Fragment>,
    ) {
        fun ms(ticks: Long): Long = ticks * 1000 / timescale
        fun startMs(f: Fragment): Long = ms(f.startTicks)
        fun endMs(f: Fragment): Long = ms(f.endTicks)
    }

    /**
     * Reads the sidx box at [boxStart] of [head] (the stream's first bytes, through the end of its index). [boxStart]
     * is where the box sits in the whole stream too, which is what the fragments' byte offsets are counted from.
     */
    fun parseSidx(head: ByteArray, boxStart: Int): Sidx {
        val size = u32(head, boxStart)
        if (type(head, boxStart) != "sidx") throw IOException("No sidx at $boxStart")
        if (boxStart + size > head.size) throw IOException("sidx cut short: needs ${boxStart + size} bytes, has ${head.size}")
        val version = head[boxStart + 8].toInt()
        var p = boxStart + 12
        val referenceId = u32(head, p); p += 4
        val timescale = u32(head, p); p += 4
        if (timescale <= 0) throw IOException("sidx without a timescale")
        val ept: Long
        val firstOffset: Long
        if (version == 0) {
            ept = u32(head, p); firstOffset = u32(head, p + 4); p += 8
        } else {
            ept = u64(head, p); firstOffset = u64(head, p + 8); p += 16
        }
        p += 2 // reserved
        val count = u16(head, p); p += 2
        var offset = boxStart + size + firstOffset
        var t = ept
        val list = ArrayList<Fragment>(count)
        for (i in 0 until count) {
            val word = u32(head, p)
            // A reference to another index (type 1) would mean a nested sidx; YouTube's never have one.
            if (word and 0x80000000L != 0L) throw IOException("Nested sidx is not supported")
            val refSize = word and 0x7FFFFFFFL
            val dur = u32(head, p + 4)
            val sap = u32(head, p + 8)
            list += Fragment(i, offset, refSize, t, dur, sap)
            offset += refSize
            t += dur
            p += 12
        }
        return Sidx(referenceId, timescale, list)
    }

    /** What the init segment (ftyp + moov) says that the cut needs: timescales, default sample flags, edit shift. */
    data class Init(
        /** The track's own timescale (mdhd): tfdt and sample durations are in it. */
        val mediaTimescale: Long,
        /** trex's default sample duration and flags, used where a fragment names none. */
        val defaultDuration: Long,
        val defaultFlags: Long,
        /** A single edit's media time (elst): what a player subtracts from every sample's time (B-frame delay). */
        val editShift: Long,
    )

    fun parseInit(init: ByteArray): Init {
        var mediaTs = 0L
        var defDur = 0L
        var defFlags = 0L
        var shift = 0L
        val moov = boxes(init, 0, init.size).firstOrNull { it.type == "moov" } ?: throw IOException("No moov")
        for (b in boxes(init, moov.body, moov.end)) {
            when (b.type) {
                "mvex" -> boxes(init, b.body, b.end).firstOrNull { it.type == "trex" }?.let { trex ->
                    // version/flags, track_ID, default_sample_description_index, duration, size, flags
                    defDur = u32(init, trex.body + 12)
                    defFlags = u32(init, trex.body + 20)
                }
                "trak" -> for (t in boxes(init, b.body, b.end)) {
                    if (t.type == "edts") boxes(init, t.body, t.end).firstOrNull { it.type == "elst" }?.let { elst ->
                        val v = init[elst.body].toInt()
                        val count = u32(init, elst.body + 4)
                        if (count == 1L) {
                            shift = if (v == 1) u64(init, elst.body + 16) else u32(init, elst.body + 12).toInt().toLong()
                            if (shift < 0) shift = 0 // -1 is an empty edit, not a shift
                        }
                    }
                    if (t.type == "mdia") boxes(init, t.body, t.end).firstOrNull { it.type == "mdhd" }?.let { mdhd ->
                        val v = init[mdhd.body].toInt()
                        mediaTs = u32(init, mdhd.body + if (v == 1) 20 else 12)
                    }
                }
            }
        }
        if (mediaTs <= 0) throw IOException("No track timescale in moov")
        return Init(mediaTs, defDur, defFlags, shift)
    }

    /** A box inside a byte array: its start, where its body starts (after size and type), and its end. */
    data class Box(val type: String, val start: Int, val body: Int, val end: Int)

    /** The boxes in [from]..[to] of [b], one level deep. Stops at anything malformed rather than reading past it. */
    fun boxes(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = u32(b, p)
            var body = p + 8
            if (size == 1L) {
                if (p + 16 > to) break
                size = u64(b, p + 8); body = p + 16
            } else if (size == 0L) size = (to - p).toLong()
            if (size < body - p || p + size > to) break
            out += Box(type(b, p), p, body, (p + size).toInt())
            p += size.toInt()
        }
        return out
    }

    /** The first moof's tfdt (decode time of its first sample, in the media timescale). */
    fun decodeTime(moof: ByteArray): Long {
        val tfdt = tfdtBox(moof) ?: throw IOException("moof without a tfdt")
        return if (moof[tfdt.body].toInt() == 1) u64(moof, tfdt.body + 4) else u32(moof, tfdt.body + 4)
    }

    private fun tfdtBox(moof: ByteArray): Box? {
        val top = boxes(moof, 0, moof.size).firstOrNull { it.type == "moof" } ?: return null
        val traf = boxes(moof, top.body, top.end).firstOrNull { it.type == "traf" } ?: return null
        return boxes(moof, traf.body, traf.end).firstOrNull { it.type == "tfdt" }
    }

    /**
     * Moves a moof's time back by [shift] (the first kept fragment's decode time), in place, so the cut file's time
     * starts at 0. Without this a player would wait from 0 to the fragment's real time (a minute in) before showing
     * the first frame. Both tfdt versions are kept at their size; [moved] is how far the moof moved in the file, for
     * a tfhd that names an absolute base offset (YouTube's don't; a moved one would point at the wrong bytes).
     */
    fun rewriteMoof(moof: ByteArray, shift: Long, moved: Long = 0) {
        val top = boxes(moof, 0, moof.size).firstOrNull { it.type == "moof" } ?: throw IOException("Not a moof")
        for (traf in boxes(moof, top.body, top.end).filter { it.type == "traf" }) {
            for (b in boxes(moof, traf.body, traf.end)) {
                when (b.type) {
                    "tfdt" -> {
                        if (moof[b.body].toInt() == 1) {
                            val t = u64(moof, b.body + 4) - shift
                            if (t < 0) throw IOException("tfdt before the cut's start")
                            put64(moof, b.body + 4, t)
                        } else {
                            val t = u32(moof, b.body + 4) - shift
                            if (t < 0) throw IOException("tfdt before the cut's start")
                            put32(moof, b.body + 4, t)
                        }
                    }
                    "tfhd" -> if (moved != 0L && u24(moof, b.body + 1) and 0x1L != 0L) {
                        val at = b.body + 8
                        put64(moof, at, u64(moof, at) + moved)
                    }
                }
            }
        }
    }

    /**
     * The decode times of a moof's key frames (samples not marked "non-sync"), shifted by [Init.editShift] to the
     * time a player shows them at: what a seek or a loop can start on without decoding anything before it.
     */
    fun keyFrameTicks(moof: ByteArray, init: Init): List<Long> {
        val top = boxes(moof, 0, moof.size).firstOrNull { it.type == "moof" } ?: return emptyList()
        val out = ArrayList<Long>()
        for (traf in boxes(moof, top.body, top.end).filter { it.type == "traf" }) {
            val inner = boxes(moof, traf.body, traf.end)
            var defDur = init.defaultDuration
            var defFlags = init.defaultFlags
            inner.firstOrNull { it.type == "tfhd" }?.let { h ->
                val flags = u24(moof, h.body + 1)
                var p = h.body + 8
                if (flags and 0x1L != 0L) p += 8
                if (flags and 0x2L != 0L) p += 4
                if (flags and 0x8L != 0L) { defDur = u32(moof, p); p += 4 }
                if (flags and 0x10L != 0L) p += 4
                if (flags and 0x20L != 0L) defFlags = u32(moof, p)
            }
            var t = inner.firstOrNull { it.type == "tfdt" }?.let { d ->
                if (moof[d.body].toInt() == 1) u64(moof, d.body + 4) else u32(moof, d.body + 4)
            } ?: 0L
            for (trun in inner.filter { it.type == "trun" }) {
                val version = moof[trun.body].toInt()
                val flags = u24(moof, trun.body + 1)
                val count = u32(moof, trun.body + 4)
                var p = trun.body + 8
                if (flags and 0x1L != 0L) p += 4
                var firstFlags: Long? = null
                if (flags and 0x4L != 0L) { firstFlags = u32(moof, p); p += 4 }
                for (i in 0 until count) {
                    var dur = defDur
                    var sf = if (i == 0L && firstFlags != null) firstFlags else defFlags
                    var cts = 0L
                    if (flags and 0x100L != 0L) { dur = u32(moof, p); p += 4 }
                    if (flags and 0x200L != 0L) p += 4
                    if (flags and 0x400L != 0L) { sf = u32(moof, p); p += 4 }
                    if (flags and 0x800L != 0L) {
                        cts = if (version == 0) u32(moof, p) else u32(moof, p).toInt().toLong()
                        p += 4
                    }
                    if (p > trun.end) break
                    if (sf and 0x10000L == 0L) out += t + cts - init.editShift
                    t += dur
                }
            }
        }
        return out.sorted()
    }

    /**
     * A fresh index for the cut file: the kept fragments, back to back right after it, the first at [earliest]. It
     * gives a player the file's length and lets it jump to any fragment (each starts on a key frame); without it a
     * fragmented file is "unseekable" and has no known duration. The stream's own sidx can't be kept: its offsets and
     * times are the whole video's.
     */
    fun writeSidx(sink: BufferedSink, referenceId: Long, timescale: Long, earliest: Long, fragments: List<Fragment>) {
        sink.writeInt(sidxSize(fragments.size).toInt()).writeUtf8("sidx").writeInt(0x01000000) // version 1, no flags
        sink.writeInt(referenceId.toInt()).writeInt(timescale.toInt())
        sink.writeLong(earliest).writeLong(0L) // first offset: the first moof follows the index at once
        sink.writeShort(0).writeShort(fragments.size)
        for (f in fragments) {
            sink.writeInt(f.size.toInt()).writeInt(f.durationTicks.toInt()).writeInt(f.sapWord.toInt())
        }
    }

    /** The size [writeSidx] writes for [count] fragments: box and full-box header, ids, version-1 times, references. */
    fun sidxSize(count: Int): Long = 8L + 4 + 4 + 4 + 16 + 4 + 12L * count

    /**
     * Copies fragments from [source] to [sink] box by box, rewriting each moof's times by [shift] (see [rewriteMoof]);
     * mdat bodies stream through without being held in memory. [expected] bytes must arrive, no more and no fewer.
     * Returns each moof's key-frame ticks (in the video's own time, before the shift) as it goes.
     */
    fun copyFragments(source: BufferedSource, sink: BufferedSink, expected: Long, init: Init, shift: Long, moved: Long): List<Long> {
        var read = 0L
        val keys = ArrayList<Long>()
        while (read < expected) {
            val header = source.readByteArray(8)
            var size = u32(header, 0)
            val type = type(header, 0)
            var headerBytes = header
            if (size == 1L) {
                val large = source.readByteArray(8)
                size = u64(large, 0)
                headerBytes = header + large
            }
            if (size < headerBytes.size || read + size > expected) throw IOException("Box $type of $size bytes runs past the fetched range")
            if (type == "moof") {
                if (size > 4L shl 20) throw IOException("moof of $size bytes")
                val moof = headerBytes + source.readByteArray(size - headerBytes.size)
                keys += keyFrameTicks(moof, init)
                rewriteMoof(moof, shift, moved)
                sink.write(moof)
            } else {
                sink.write(headerBytes)
                var left = size - headerBytes.size
                while (left > 0) {
                    val n = source.read(sink.buffer, minOf(left, 64L * 1024))
                    if (n < 0) throw IOException("Stream ended inside $type")
                    left -= n
                    sink.emitCompleteSegments()
                }
            }
            read += size
        }
        return keys
    }

    /** The cut file's top-level box types in order, for checks: "ftyp, moov, sidx, moof, mdat, …". */
    fun topLevel(file: ByteArray): List<String> = boxes(file, 0, file.size).map { it.type }

    internal fun u16(b: ByteArray, p: Int): Int = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
    internal fun u24(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 16) or ((b[p + 1].toLong() and 0xFF) shl 8) or (b[p + 2].toLong() and 0xFF)
    internal fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
            ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)
    internal fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)
    internal fun put32(b: ByteArray, p: Int, v: Long) { for (i in 0..3) b[p + i] = (v ushr (24 - 8 * i)).toByte() }
    internal fun put64(b: ByteArray, p: Int, v: Long) { for (i in 0..7) b[p + i] = (v ushr (56 - 8 * i)).toByte() }
    internal fun type(b: ByteArray, p: Int): String = String(b, p + 4, 4, Charsets.ISO_8859_1)
}
