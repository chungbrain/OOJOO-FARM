package com.oojoo.farm.slave.vision

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File

/**
 * MP4 영상에서 일정 간격으로 프레임을 추출하는 샘플러.
 *
 * MediaExtractor로 트랙을 읽고 MediaCodec 디코더로 풀어,
 * 지정 간격(intervalMs)마다 최대 maxFrames장의 Bitmap을 뽑는다.
 * 개별 프레임 변환 실패는 건너뛰고 계속 진행하며,
 * 모든 리소스는 finally에서 해제한다.
 */
object VideoFrameSampler {
    private const val TAG = "VideoSampler"
    private const val TIMEOUT_US = 10_000L
    private const val MAX_SIDE = 640 // 업로드용 상한 (CNN 입력은 48x48, 사람 검수는 웹 사이즈면 충분)

    data class SampledFrame(val timestampMs: Long, val bitmap: Bitmap)

    /**
     * file에서 maxFrames장을 intervalMs 간격으로 샘플링한다.
     * 실패해도 예외를 던지지 않고 지금까지 모은 프레임을 반환한다.
     */
    fun sample(file: File, maxFrames: Int = 6, intervalMs: Long = 500): List<SampledFrame> {
        val deadline = System.currentTimeMillis() + 15_000L
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val out = mutableListOf<SampledFrame>()
        try {
            extractor.setDataSource(file.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            var mime: String? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("video/")) {
                    trackIndex = i
                    format = f
                    mime = m
                    break
                }
            }
            if (trackIndex < 0 || format == null || mime == null) {
                Log.w(TAG, "no video track")
                return out
            }
            extractor.selectTrack(trackIndex)

            val dec = MediaCodec.createDecoderByType(mime)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            // 샘플 목표 시각(ms): 0, intervalMs, 2*intervalMs, ...
            val targets = (0 until maxFrames).map { it * intervalMs }
            var nextTarget = 0
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()

            while (!outputDone && out.size < maxFrames && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val inIx = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIx >= 0) {
                        val inBuf = dec.getInputBuffer(inIx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            dec.queueInputBuffer(inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime
                            dec.queueInputBuffer(inIx, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIx = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* 대기 */ }
                    outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* 포맷 변경 무시 */ }
                    outIx >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                        if (nextTarget < targets.size && info.presentationTimeUs / 1000 >= targets[nextTarget]) {
                            try {
                                val image = dec.getOutputImage(outIx)
                                if (image != null) {
                                    val bmp = imageToBitmap(image)
                                    if (bmp != null) {
                                        out += SampledFrame(info.presentationTimeUs / 1000, bmp)
                                        nextTarget++
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "frame convert failed", e)
                            }
                        }
                        dec.releaseOutputBuffer(outIx, false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "sample failed", e)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
        return out
    }

    /**
     * Image(YUV_420_888) → Bitmap(ARGB_8888) 수동 변환.
     * minSdk 24에서는 Image.getBitmap()(API 26)을 쓸 수 없으므로
     * stride(rowStride/pixelStride)를 존중하는 YUV→RGB 변환을 직접 수행한다.
     */
    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        val width = image.width
        val height = image.height
        if (image.format != android.graphics.ImageFormat.YUV_420_888) return null
        val planes = image.planes
        if (planes.size < 3) return null
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yb = yPlane.buffer
        val ub = uPlane.buffer
        val vb = vPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        val uRow = uPlane.rowStride
        val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPix = vPlane.pixelStride

        val px = IntArray(width * height)
        for (row in 0 until height) {
            for (col in 0 until width) {
                val y = yb.get(row * yRow + col * yPix).toInt() and 0xFF
                val uvRow = row / 2
                val uvCol = col / 2
                val u = (ub.get(uvRow * uRow + uvCol * uPix).toInt() and 0xFF) - 128
                val v = (vb.get(uvRow * vRow + uvCol * vPix).toInt() and 0xFF) - 128
                val r = (y + ((v * 359) shr 8)).coerceIn(0, 255)
                val g = (y - ((u * 88) shr 8) - ((v * 183) shr 8)).coerceIn(0, 255)
                val b = (y + ((u * 454) shr 8)).coerceIn(0, 255)
                px[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        var bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, width, 0, 0, width, height)
        // 긴 변 기준 640px 상한으로 축소 (JPEG 업로드 크기 절감)
        val longSide = maxOf(width, height)
        if (longSide > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / longSide
            val scaled = Bitmap.createScaledBitmap(
                bmp,
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        return bmp
    }
}
