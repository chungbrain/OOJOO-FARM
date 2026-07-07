package com.oojoo.farm.slave.vision

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executors

/**
 * 3초 비디오 캡처 담당.
 * CameraX VideoCapture<Recorder> 를 이용해 MP4 파일로 저장한 후 콜백으로 반환한다.
 * Master 의 "capture_video" 명령을 받으면 capture3s() 를 호출한다.
 */
class VideoRecorder(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    @Volatile var ready = false
        private set

    /** 카메라 프로바이더에 VideoCapture 를 바인딩한다 (Preview/ImageCapture 와 병행 가능). */
    fun bind(lifecycleOwner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.SD))
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                // 기존 바인딩 유지 + VideoCapture 추가
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        capture
                    )
                } catch (e: Exception) {
                    // VideoCapture 단독 바인딩
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        capture
                    )
                }
                videoCapture = capture
                ready = true
                Log.i(TAG, "VideoCapture bound")
            } catch (e: Exception) {
                Log.e(TAG, "bind failed", e)
                ready = false
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** 3초 비디오를 캡처 후 임시 파일을 반환한다. 실패 시 null. */
    fun capture3s(onDone: (File?) -> Unit) {
        val capture = videoCapture
        if (capture == null) { Log.w(TAG, "VideoCapture not bound"); onDone(null); return }
        if (recording != null) { Log.w(TAG, "already recording"); onDone(null); return }

        val outFile = File(context.cacheDir, "capture_${System.currentTimeMillis()}.mp4")
        val output = FileOutputOptions.Builder(outFile).build()

        try {
            recording = capture.output
                .prepareRecording(context, output)
                .start(executor) { event ->
                    when (event) {
                        is VideoRecordEvent.Status -> { /* 진행 중 */ }
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
                Thread.sleep(3000)
                try { recording?.stop() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture3s failed", e)
            recording = null
            onDone(null)
        }
    }

    fun unbind() {
        try { recording?.stop() } catch (_: Exception) {}
        recording = null
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try { future.get().unbindAll() } catch (_: Exception) {}
            }, ContextCompat.getMainExecutor(context))
        } catch (_: Exception) {}
        ready = false
    }

    companion object {
        private const val TAG = "VideoRecorder"
    }
}
