package com.prism.music.data.canvas

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads a kept Canvas loop's frames small, once, for [ClipRefine] (1.8.6): the phone's own video decoder, no picture
 * shown, each frame reduced to a brightness grid, and a handful looked at by Android's own face finder
 * (`android.media.FaceDetector`: part of the system, nothing leaves the phone). A few seconds of work per song, off the
 * main thread, with a time limit; anything this phone can't do here (a decoder that won't hand frames out, a file it
 * can't read) gives null and the loop plays as the finder guessed it.
 */
object ClipDecode : ClipRefiner {
    /** At most this long on one loop. */
    private const val BUDGET_MS = 12_000L
    /** Faces are looked for about this often, in a picture this wide. */
    private const val FACE_EVERY_MS = 900L
    private const val FACE_W = 480
    private const val MAX_FACES = 3

    /** How long the last loops took here and what they said, for Share diagnostics and the tests. */
    @Volatile var last: String? = null; private set

    override suspend fun refine(file: File, loop: VideoClip): ClipRefine.Refined? = withContext(Dispatchers.Default) {
        val began = System.currentTimeMillis()
        // The file's own clock starts at 0 where the kept part starts.
        val start = (loop.startMs - loop.fileStartMs).coerceAtLeast(0)
        val read = runCatching { read(file, start, start + ClipRepository.MAX_LOOP_MS + 200, began + BUDGET_MS) }
            .onFailure { last = "couldn't read ${file.name}: $it" }.getOrNull() ?: return@withContext null
        if (read.frames.size < 30) { last = "only ${read.frames.size} frames from ${file.name}"; return@withContext null }
        val r = ClipRefine.judge(read.frames, read.faces, start, loop.bars)
        last = "${read.frames.size} frames, ${read.faces.size} faces, ${r.cutsMs.size} cuts, ${if (r.flashing) "flashing" else "calm"}, " +
            "ends ${r.endMs - start} ms in, crop ${r.cropX ?: "kept"}, ${System.currentTimeMillis() - began} ms" +
            read.faces.joinToString(prefix = " [", postfix = "]") { "${it.timeMs - start}:${(it.x * 100).toInt()}/${(it.size * 100).toInt()}/${(it.confidence * 100).toInt()}" }
        // Back on the video's clock.
        r.copy(endMs = r.endMs + loop.fileStartMs, cutsMs = r.cutsMs.map { it + loop.fileStartMs })
    }

    private class Read(val frames: List<ClipRefine.Frame>, val faces: List<ClipRefine.Face>)

    private fun read(file: File, fromMs: Long, toMs: Long, deadline: Long): Read {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val frames = ArrayList<ClipRefine.Frame>(300); val faces = ArrayList<ClipRefine.Face>()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: return Read(frames, faces)
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            extractor.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var fed = false; var done = false
            var nextFace = fromMs
            while (!done && System.currentTimeMillis() < deadline) {
                if (!fed) {
                    val i = codec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (size < 0 || extractor.sampleTime > toMs * 1000) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); fed = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                if (o >= 0) {
                    val ms = info.presentationTimeUs / 1000
                    if (info.size > 0 && ms in fromMs..toMs) codec.getOutputImage(o)?.use { image ->
                        frames += ClipRefine.Frame(ms, grid(image))
                        if (ms >= nextFace) { nextFace = ms + FACE_EVERY_MS; faces += facesIn(image, ms) }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { extractor.release() }
        }
        frames.sortBy { it.timeMs }
        return Read(frames, faces)
    }

    /** The picture's brightness on the grid: four points to a cell. */
    private fun grid(image: Image): ByteArray {
        val plane = image.planes[0]; val buf = plane.buffer
        val crop = image.cropRect; val w = crop.width(); val h = crop.height()
        val out = ByteArray(ClipRefine.GRID_W * ClipRefine.GRID_H)
        for (gy in 0 until ClipRefine.GRID_H) for (gx in 0 until ClipRefine.GRID_W) {
            var sum = 0
            for (sy in 0..1) for (sx in 0..1) {
                val x = crop.left + ((gx + 0.25f + 0.5f * sx) * w / ClipRefine.GRID_W).toInt().coerceIn(0, w - 1)
                val y = crop.top + ((gy + 0.25f + 0.5f * sy) * h / ClipRefine.GRID_H).toInt().coerceIn(0, h - 1)
                sum += buf.get(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xff
            }
            out[gy * ClipRefine.GRID_W + gx] = (sum / 4).toByte()
        }
        return out
    }

    /** The faces in a frame, found on a small grey copy of it. */
    private fun facesIn(image: Image, ms: Long): List<ClipRefine.Face> {
        val plane = image.planes[0]; val buf = plane.buffer
        val crop = image.cropRect; val w = crop.width(); val h = crop.height()
        if (w < 64 || h < 64) return emptyList()
        // The finder wants an even width and RGB 565.
        val bw = FACE_W; val bh = (FACE_W.toLong() * h / w).toInt().coerceAtLeast(2) and 1.inv()
        val pixels = IntArray(bw * bh)
        for (y in 0 until bh) {
            val row = (crop.top + y.toLong() * h / bh).toInt() * plane.rowStride
            for (x in 0 until bw) {
                val v = buf.get(row + (crop.left + (x.toLong() * w / bw).toInt()) * plane.pixelStride).toInt() and 0xff
                pixels[y * bw + x] = (0xff shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.RGB_565)
        bitmap.setPixels(pixels, 0, bw, 0, 0, bw, bh)
        val found = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
        val n = FaceDetector(bw, bh, MAX_FACES).findFaces(bitmap, found)
        bitmap.recycle()
        val mid = PointF()
        return (0 until n).mapNotNull { found[it] }.filter { it.confidence() >= 0.4f }.map {
            it.getMidPoint(mid)
            ClipRefine.Face(ms, mid.x / bw, it.eyesDistance() / bw, it.confidence())
        }
    }
}
