package com.example.barichords

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Декодує будь-який аудіофайл у моно PCM float з частотою TARGET_SR (повністю офлайн). */
object AudioDecoder {
    const val TARGET_SR = 22050

    private class FloatList {
        var data = FloatArray(1 shl 20)
        var size = 0
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray() = data.copyOf(size)
    }

    fun decode(context: Context, uri: Uri, onProgress: (Float) -> Unit = {}): Pair<FloatArray, Int> {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var trackIdx = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIdx = i; format = f; break
            }
        }
        require(trackIdx >= 0 && format != null) { "Аудіодоріжку не знайдено" }
        extractor.selectTrack(trackIdx)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var sr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ratio = sr.toDouble() / TARGET_SR

        val out = FloatList()
        var acc = 0.0; var cnt = 0; var pos = 0.0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val nf = codec.outputFormat
                    channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    sr = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ratio = sr.toDouble() / TARGET_SR
                }
                outIdx >= 0 -> {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val sb = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = sb.remaining() / channels
                    for (f in 0 until frames) {
                        var s = 0
                        for (c in 0 until channels) s += sb.get().toInt()
                        acc += s / (32768.0 * channels); cnt++; pos += 1.0
                        if (pos >= ratio) { out.add((acc / cnt).toFloat()); acc = 0.0; cnt = 0; pos -= ratio }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        codec.stop(); codec.release(); extractor.release()
        return out.toArray() to TARGET_SR
    }
}
