package com.oojoo.farm.slave.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.oojoo.farm.slave.album.AlbumEntry
import java.io.File
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 누적 사진 + 촬영 시각/위치로 아주 짧은 성장 스토리 MP4를 만들고,
 * 부드러운 펜타토닉 BGM(AAC)을 섞는다.
 */
object GrowthClipRenderer {
    private const val TAG = "GrowthClip"
    private const val W = 720
    private const val H = 1280
    private const val FPS = 24
    private const val FRAMES_PER_PHOTO = 12
    private const val TITLE_FRAMES = 20
    private const val MAX_PHOTOS = 16
    private const val SAMPLE_RATE = 44100
    private const val AAC_FRAME = 1024

    data class Result(val file: File, val photoCount: Int)

    fun render(photos: List<AlbumEntry>, plantName: String, outFile: File): Result {
        val picked = sample(photos, MAX_PHOTOS)
        require(picked.isNotEmpty()) { "no photos" }

        val frames = mutableListOf<Bitmap>()
        frames += titleFrame(plantName, picked.size)
        for (p in picked) {
            // EXIF 회전 적용 — 가로/세로 어느 방향으로 찍힌 사진도 upright로
            val src = OrientationUtil.decodeUpright(p.file.absolutePath)
                ?: continue
            frames += composeFrame(src, caption(p))
            if (src !== frames.last()) src.recycle()
        }
        require(frames.size >= 2) { "decode failed" }

        val durations = IntArray(frames.size) { i -> if (i == 0) TITLE_FRAMES else FRAMES_PER_PHOTO }
        val totalVideoFrames = durations.sum()
        val durationUs = totalVideoFrames.toLong() * 1_000_000L / FPS

        if (outFile.exists()) outFile.delete()
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var videoTrack = -1
        var audioTrack = -1

        val video = createVideoEncoder()
        val audio = createAudioEncoder()
        video.start()
        audio?.start()

        val yuv = ByteArray(W * H * 3 / 2)
        val info = MediaCodec.BufferInfo()
        var videoPts = 0L
        var audioPts = 0L
        val pcm = generateBgmPcm(durationUs)
        var pcmOffset = 0
        var videoDone = false
        var audioDone = audio == null
        var frameCursor = 0
        var holdLeft = 0
        var currentYuv: ByteArray? = null

        fun drainVideo() {
            while (true) {
                val i = video.dequeueOutputBuffer(info, 10_000)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (videoTrack < 0) videoTrack = muxer.addTrack(video.outputFormat)
                        if (!muxerStarted) maybeStart(muxer, videoTrack, audioTrack, audio != null) { muxerStarted = true }
                    }
                    i >= 0 -> {
                        val buf = video.getOutputBuffer(i)
                        if (buf != null && info.size > 0 && muxerStarted && videoTrack >= 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(videoTrack, buf, info)
                        }
                        video.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            videoDone = true
                            return
                        }
                    }
                }
            }
        }

        fun drainAudio() {
            val a = audio ?: return
            while (true) {
                val i = a.dequeueOutputBuffer(info, 2_000)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (audioTrack < 0) audioTrack = muxer.addTrack(a.outputFormat)
                        if (!muxerStarted) maybeStart(muxer, videoTrack, audioTrack, true) { muxerStarted = true }
                    }
                    i >= 0 -> {
                        val buf = a.getOutputBuffer(i)
                        if (buf != null && info.size > 0 && muxerStarted && audioTrack >= 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(audioTrack, buf, info)
                        }
                        a.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            audioDone = true
                            return
                        }
                    }
                }
            }
        }

        try {
            while (!videoDone || !audioDone) {
                if (!videoDone) {
                    val inIx = video.dequeueInputBuffer(20_000)
                    if (inIx >= 0) {
                        val inBuf = video.getInputBuffer(inIx)!!
                        if (frameCursor >= frames.size && holdLeft <= 0) {
                            video.queueInputBuffer(inIx, 0, 0, videoPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            if (holdLeft <= 0) {
                                fillYuv(frames[frameCursor], yuv)
                                currentYuv = yuv
                                holdLeft = durations[frameCursor]
                                frameCursor++
                            }
                            inBuf.clear()
                            inBuf.put(currentYuv)
                            video.queueInputBuffer(inIx, 0, yuv.size, videoPts, 0)
                            videoPts += 1_000_000L / FPS
                            holdLeft--
                        }
                    }
                    drainVideo()
                }

                if (audio != null && !audioDone) {
                    val inIx = audio.dequeueInputBuffer(8_000)
                    if (inIx >= 0) {
                        val inBuf = audio.getInputBuffer(inIx)!!
                        if (pcmOffset >= pcm.size) {
                            audio.queueInputBuffer(inIx, 0, 0, audioPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            val bytes = min(AAC_FRAME * 2, pcm.size - pcmOffset)
                            inBuf.clear()
                            inBuf.put(pcm, pcmOffset, bytes)
                            audio.queueInputBuffer(inIx, 0, bytes, audioPts, 0)
                            val samples = bytes / 2
                            audioPts += samples * 1_000_000L / SAMPLE_RATE
                            pcmOffset += bytes
                        }
                    }
                    drainAudio()
                }
            }
        } finally {
            try { video.stop() } catch (_: Exception) {}
            try { video.release() } catch (_: Exception) {}
            try { audio?.stop() } catch (_: Exception) {}
            try { audio?.release() } catch (_: Exception) {}
            if (muxerStarted) {
                try { muxer.stop() } catch (_: Exception) {}
            }
            try { muxer.release() } catch (_: Exception) {}
            frames.forEach { it.recycle() }
        }

        if (!outFile.exists() || outFile.length() < 1024) {
            throw IllegalStateException("clip file empty")
        }
        Log.i(TAG, "clip ready ${outFile.length()}B photos=${picked.size}")
        return Result(outFile, picked.size)
    }

    private fun maybeStart(muxer: MediaMuxer, v: Int, a: Int, needAudio: Boolean, onStart: () -> Unit) {
        if (v >= 0 && (!needAudio || a >= 0)) {
            try {
                muxer.start()
                onStart()
            } catch (_: Exception) {}
        }
    }

    private fun createVideoEncoder(): MediaCodec {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val color = pickColor(codec.codecInfo)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_400_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        return codec
    }

    private fun createAudioEncoder(): MediaCodec? = try {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AAC_FRAME * 4)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec
    } catch (e: Exception) {
        Log.w(TAG, "AAC encoder unavailable, video only", e)
        null
    }

    private fun pickColor(info: MediaCodecInfo): Int {
        val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val prefer = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        for (c in prefer) if (caps.colorFormats.contains(c)) return c
        return caps.colorFormats.firstOrNull() ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    }

    private fun fillYuv(bmp: Bitmap, yuv: ByteArray) {
        val scaled = if (bmp.width == W && bmp.height == H) bmp else Bitmap.createScaledBitmap(bmp, W, H, true)
        val argb = IntArray(W * H)
        scaled.getPixels(argb, 0, W, 0, 0, W, H)
        if (scaled !== bmp) scaled.recycle()
        encodeNv12(yuv, argb, W, H)
    }

    private fun encodeNv12(yuv: ByteArray, argb: IntArray, width: Int, height: Int) {
        var yIndex = 0
        var uvIndex = width * height
        for (j in 0 until height) {
            for (i in 0 until width) {
                val c = argb[j * width + i]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff
                val y = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(16, 235)
                yuv[yIndex++] = y.toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    val u = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val v = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    yuv[uvIndex++] = u.toByte()
                    yuv[uvIndex++] = v.toByte()
                }
            }
        }
    }

    private fun titleFrame(name: String, count: Int): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF1F3D2B.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 56f
        c.drawText("성장 스토리", W / 2f, H / 2f - 40f, paint)
        paint.textSize = 36f
        paint.color = 0xFFE8F5E9.toInt()
        c.drawText(name, W / 2f, H / 2f + 30f, paint)
        paint.textSize = 24f
        paint.color = 0xFFB2DFDB.toInt()
        c.drawText("사진 ${count}장 · 숏클립", W / 2f, H / 2f + 80f, paint)
        return out
    }

    private fun composeFrame(src: Bitmap, caption: String): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF102018.toInt())
        val scale = max(W / src.width.toFloat(), H / src.height.toFloat())
        val dw = src.width * scale
        val dh = src.height * scale
        val dx = (W - dw) / 2f
        val dy = (H - dh) / 2f
        c.drawBitmap(src, null, RectF(dx, dy, dx + dw, dy + dh), null)
        val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        bar.shader = LinearGradient(0f, H - 200f, 0f, H.toFloat(), 0x00000000, 0xCC000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, H - 200f, W.toFloat(), H.toFloat(), bar)
        val text = Paint(Paint.ANTI_ALIAS_FLAG)
        text.color = Color.WHITE
        text.textSize = 32f
        text.typeface = Typeface.DEFAULT_BOLD
        c.drawText(caption, 28f, H - 56f, text)
        return out
    }

    private fun caption(p: AlbumEntry): String {
        val time = p.takenAt.ifBlank { "" }
        val loc = p.location.ifBlank { "" }
        return listOf(time, loc).filter { it.isNotBlank() }.joinToString("  ·  ").ifBlank { "성장 기록" }
    }

    private fun <T> sample(list: List<T>, max: Int): List<T> {
        if (list.size <= max) return list
        return List(max) { i -> list[i * (list.size - 1) / (max - 1)] }
    }

    /** 짧은 펜타토닉 아르페지오 + 패드. 저작권 없는 생성 음원. */
    private fun generateBgmPcm(durationUs: Long): ByteArray {
        val samples = max(SAMPLE_RATE, ((durationUs / 1_000_000.0) * SAMPLE_RATE).toInt())
        val notes = doubleArrayOf(261.63, 329.63, 392.00, 523.25, 392.00, 329.63)
        val out = ByteArray(samples * 2)
        val step = (SAMPLE_RATE * 0.42).toInt()
        for (i in 0 until samples) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = min(1.0, t * 4) * min(1.0, (samples - i).toDouble() / SAMPLE_RATE * 3)
            val n = notes[(i / step) % notes.size]
            val notePos = (i % step).toDouble() / SAMPLE_RATE
            val pluck = sin(2 * PI * n * t) * Math.E.pow(-3.2 * notePos)
            val pad = 0.22 * sin(2 * PI * 130.81 * t) + 0.12 * sin(2 * PI * 196.00 * t)
            val sample = ((pluck * 0.55 + pad) * env * 0.55 * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i * 2] = (sample and 0xff).toByte()
            out[i * 2 + 1] = ((sample shr 8) and 0xff).toByte()
        }
        return out
    }
}
