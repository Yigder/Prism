package com.prism.music.data.canvas

import okio.Buffer

/**
 * A small made-up stream shaped like YouTube's video-only ones: a real ftyp + moov (fcnDmrtj6Sk at 720), a sidx, then
 * [count] fragments of [fragSeconds] each, every one a moof (key frame first, then non-key samples) and an mdat of
 * filler. Small enough to serve from memory in every test run; the boxes are real, the pictures are not.
 */
class SyntheticStream(val count: Int = 10, val fragSeconds: Int = 2, val timescale: Long = 24_000) {
    val init: ByteArray = javaClass.classLoader!!.getResourceAsStream("canvas/fcnDmrtj6Sk-720.head.bin")!!.readBytes().copyOfRange(0, 739)
    val fragments: List<ByteArray>
    val bytes: ByteArray
    val indexStart = init.size.toLong()
    val indexEnd: Long
    /** Each fragment's offset in [bytes]. */
    val offsets: List<Long>

    init {
        val fragTicks = fragSeconds * timescale
        fragments = (0 until count).map { k ->
            val samples = 4
            val dataSize = 1_000 + k * 10
            val moof = moof(seq = k + 1, decodeTime = k * fragTicks, version = 1, samples = samples, sampleTicks = fragTicks / samples, dataSize = dataSize)
            val mdat = Buffer().writeInt(8 + dataSize).writeUtf8("mdat").write(ByteArray(dataSize) { (k + it).toByte() }).readByteArray()
            moof + mdat
        }
        val sidx = Buffer()
        val sidxSize = 32 + 12 * count
        sidx.writeInt(sidxSize).writeUtf8("sidx").writeInt(0).writeInt(1).writeInt(timescale.toInt())
        sidx.writeInt(0).writeInt(0).writeShort(0).writeShort(count)
        fragments.forEach { sidx.writeInt(it.size).writeInt(fragTicks.toInt()).writeInt(0x90000000.toInt()) }
        val all = Buffer().write(init).write(sidx.readByteArray())
        indexEnd = all.size - 1
        val offs = ArrayList<Long>()
        fragments.forEach { offs += all.size; all.write(it) }
        offsets = offs
        bytes = all.readByteArray()
    }

    companion object {
        /**
         * One moof: mfhd, traf (tfhd with the default-base-is-moof flag, or an absolute [baseOffset]), tfdt of
         * [version], trun with a data offset and per-sample durations and flags (first a key frame, the rest not).
         */
        fun moof(seq: Int, decodeTime: Long, version: Int, samples: Int, sampleTicks: Long, dataSize: Int, baseOffset: Long? = null): ByteArray {
            val mfhd = Buffer().writeInt(16).writeUtf8("mfhd").writeInt(0).writeInt(seq)
            val tfhd = Buffer()
            if (baseOffset != null) tfhd.writeInt(24).writeUtf8("tfhd").writeInt(0x000001).writeInt(1).writeLong(baseOffset)
            else tfhd.writeInt(16).writeUtf8("tfhd").writeInt(0x020000).writeInt(1)
            val tfdt = Buffer()
            if (version == 1) tfdt.writeInt(20).writeUtf8("tfdt").writeInt(0x01000000).writeLong(decodeTime)
            else tfdt.writeInt(16).writeUtf8("tfdt").writeInt(0).writeInt(decodeTime.toInt())
            val trunSize = 8 + 4 + 4 + 4 + samples * 12
            val trafSize = 8 + tfhd.size + tfdt.size + trunSize
            val moofSize = 8 + mfhd.size + trafSize
            val trun = Buffer().writeInt(trunSize).writeUtf8("trun").writeInt(0x000D01).writeInt(samples).writeInt((moofSize + 8).toInt())
            // Composition offset one frame (1001 ticks), as the real moov's edit list takes back off.
            repeat(samples) { i -> trun.writeInt(sampleTicks.toInt()).writeInt(if (i == 0) 0x02000000 else 0x01010000).writeInt(1001) }
            return Buffer().writeInt(moofSize.toInt()).writeUtf8("moof").write(mfhd.readByteArray())
                .writeInt(trafSize.toInt()).writeUtf8("traf").write(tfhd.readByteArray()).write(tfdt.readByteArray()).write(trun.readByteArray())
                .readByteArray()
        }
    }
}
