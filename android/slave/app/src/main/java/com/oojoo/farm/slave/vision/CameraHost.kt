package com.oojoo.farm.slave.vision

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.oojoo.farm.slave.service.FarmerEngine
import java.util.concurrent.Executors

/**
 * 서비스가 소유한 카메라 라이프사이클.
 *
 * 기존에는 CameraPreview(UI)가 Activity lifecycle에 바인딩했기 때문에
 * 앱이 백그라운드로 내려가면 CameraX가 자동 언바인드되어 촬영이 불가했다.
 * FarmerService(Foreground Service, type=camera)가 살아있는 동안
 * 항상 RESUMED 상태를 유지하는 별도 LifecycleOwner에 바인딩함으로써
 * 화면이 꺼져도/앱이 내려가도 관찰·촬영이 지속된다.
 *
 * UI 프리뷰는 attach()/detach()로 PreviewView만 연결/해제한다.
 * CameraX 규칙상 모든 use case는 단일 bindToLifecycle에 포함되어야 하므로
 * UI에서 별도 바인딩하지 않는다.
 */
object CameraHost {
    private const val TAG = "CameraHost"

    private class HostLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Volatile private var started = false
    @Volatile private var starting = false
    private var host: HostLifecycleOwner? = null
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    @Volatile private var pendingPreviewView: PreviewView? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    val isReady: Boolean get() = started && CameraHolder.ready

    /**
     * 카메라를 서비스 라이프사이클에 바인딩한다.
     * 권한이 없거나(Android 11+ 백그라운드 while-in-use 제한 등) 실패하면
     * started=false로 남아 이후 재호출(앱 화면 진입 시) 시 재시도된다.
     */
    fun start(context: Context): Boolean {
        if (started) return true
        if (starting) return false
        val appCtx = context.applicationContext
        if (ContextCompat.checkSelfPermission(appCtx, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "camera permission not granted — camera host idle")
            return false
        }
        starting = true

        val lifecycleOwner = HostLifecycleOwner().also {
            it.registry.currentState = Lifecycle.State.RESUMED
        }
        host = lifecycleOwner

        val future = ProcessCameraProvider.getInstance(appCtx)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider

                val previewUseCase = Preview.Builder().build()
                preview = previewUseCase

                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { ia ->
                        ia.setAnalyzer(analysisExecutor) { imageProxy ->
                            val result = PlantAnalyzer.analyze(imageProxy)
                            FarmerEngine.onAnalysis(result)
                        }
                    }

                // VideoCapture — 나머지 use case와 반드시 함께 바인딩
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.SD))
                    .build()
                val videoCapture = VideoCapture.withOutput(recorder)

                fun bindAll() {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        previewUseCase, imageCapture, analysis, videoCapture
                    )
                    CameraHolder.setCapture(videoCapture)
                }

                try {
                    CameraHolder.setImageCapture(imageCapture)
                    bindAll()
                    started = true
                    Log.i(TAG, "camera bound to service lifecycle (4 use cases)")
                } catch (e: Exception) {
                    Log.w(TAG, "full bind failed — retry without analysis", e)
                    try {
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            previewUseCase, imageCapture, videoCapture
                        )
                        CameraHolder.setCapture(videoCapture)
                        started = true
                        Log.i(TAG, "camera bound to service lifecycle (no analysis)")
                    } catch (e2: Exception) {
                        Log.w(TAG, "bind failed — retry without video", e2)
                        try {
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                previewUseCase, imageCapture, analysis
                            )
                            CameraHolder.setCapture(null)
                            started = true
                            Log.i(TAG, "camera bound to service lifecycle (no video)")
                        } catch (e3: Exception) {
                            CameraHolder.setImageCapture(null)
                            started = false
                            Log.e(TAG, "camera bind failed", e3)
                        }
                    }
                }

                // 바인딩 완료 후 대기 중이던 UI 프리뷰 연결
                pendingPreviewView?.let { view ->
                    previewUseCase.setSurfaceProvider(view.surfaceProvider)
                }
            } catch (e: Exception) {
                // Android 11+ 백그라운드 while-in-use 제한 등
                started = false
                Log.e(TAG, "camera init failed (while-in-use restriction?)", e)
            } finally {
                starting = false
            }
        }, ContextCompat.getMainExecutor(appCtx))
        return true
    }

    /** UI가 카메라 프리뷰를 표시할 때 호출 — 바인딩은 그대로 두고 surface만 연결. */
    fun attach(context: Context, view: PreviewView) {
        pendingPreviewView = view
        if (!started) start(context)
        try {
            preview?.setSurfaceProvider(view.surfaceProvider)
        } catch (e: Exception) {
            Log.w(TAG, "attach failed", e)
        }
    }

    /** UI가 사라질 때 호출 — 카메라 바인딩은 유지, surface만 해제. */
    fun detach() {
        pendingPreviewView = null
        try { preview?.setSurfaceProvider(null) } catch (_: Exception) {}
    }

    /** 서비스 종료 시 전체 해제. */
    fun stop() {
        started = false
        starting = false
        pendingPreviewView = null
        try { provider?.unbindAll() } catch (_: Exception) {}
        provider = null
        preview = null
        CameraHolder.setImageCapture(null)
        CameraHolder.setCapture(null)
        try {
            host?.registry?.currentState = Lifecycle.State.DESTROYED
        } catch (_: Exception) {}
        host = null
    }
}
