package com.oojoo.farm.slave.vision

import android.content.Context
import android.util.Log
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import java.io.File
import java.util.concurrent.Executors

/**
 * CameraPreview 가 바인딩한 VideoCapture<Recorder> 를 FarmerEngine 에서 접근할 수 있게
 * 전달하는 공유 홀더. CameraX 의 모든 use case (Preview, ImageCapture, ImageAnalysis,
 * VideoCapture) 는 **반드시 단일 bindToLifecycle 호출에서 함께 바인딩**되어야 하므로,
 * VideoRecorder 를 분리해서 별도 바인딩하면 안 되고 이 홀더를 경유해야 한다.
 */
object CameraHolder {
    private const val TAG = "CameraHolder"

    @Volatile
    var videoCapture: VideoCapture<Recorder>? = null
        private set

    @Volatile
    var ready: Boolean = false
        private set

    private var recording: Recording? = null
    private val executor = Executors.newSingleThreadExecutor()

    fun setCapture(capture: VideoCapture<Recorder>?) {
        videoCapture = capture
        ready = capture != null
        Log.i(TAG, if (capture != null) "VideoCapture ready" else "VideoCapture cleared")
    }

    /**
     * 3초 비디오를 캡처 후 임시 파일을 반환한다.
     * 실패 시 null. CameraPreview 가 바인딩한 VideoCapture 를 사용한다.
     */
    fun capture3s(context: Context, onDone: (File?) -> Unit) {
        val capture = videoCapture
        if (capture == null || !ready) {
            Log.w(TAG, "VideoCapture not ready")
            onDone(null)
            return
        }
        if (recording != null) {
            Log.w(TAG, "already recording")
            onDone(null)
            return
        }

        val outFile = File(context.cacheDir, "capture_${System.currentTimeMillis()}.mp4")
        val output = FileOutputOptions.Builder(outFile).build()

        try {
            recording = capture.output
                .prepareRecording(context, output)
                .start(executor) { event ->
                    when (event) {
                        is VideoRecordEvent.Finalize -> {
                            recording = null
                            if (event.hasError()) {
                                Log.e(TAG, "record error: ${event.error}")
                                onDone(null)
                            } else {
                                Log.i(TAG, "record done: ${outFile.absolutePath} (${outFile.length()}B)")
                                onDone(outFile)
                            }
                        }
                    }
                }
            // 3초 후 자동 중지
            executor.execute {
                try {
                    Thread.sleep(3000)
                } catch (_: InterruptedException) {
                }
                try {
                    recording?.stop()
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture3s failed", e)
            recording = null
            onDone(null)
        }
    }
}
