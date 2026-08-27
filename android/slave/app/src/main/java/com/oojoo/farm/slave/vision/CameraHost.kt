package com.oojoo.farm.slave.vision

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.view.Surface
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

    // 가로/세로 회전 지원 — use case 들의 targetRotation 을 디스플레이 회전에 맞춘다.
    private var appCtx: Context? = null
    private var imageCaptureUseCase: ImageCapture? = null
    private var videoCaptureUseCase: VideoCapture<Recorder>? = null
    private var analysisUseCase: ImageAnalysis? = null
    private var displayListener: DisplayManager.DisplayListener? = null

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
        this.appCtx = appCtx

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
                imageCaptureUseCase = imageCapture

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { ia ->
                        ia.setAnalyzer(analysisExecutor) { imageProxy ->
                            val result = PlantAnalyzer.analyze(imageProxy)
                            FarmerEngine.onAnalysis(result)
                        }
                    }
                analysisUseCase = analysis

                // VideoCapture — 나머지 use case와 반드시 함께 바인딩
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.SD))
                    .build()
                val videoCapture = VideoCapture.withOutput(recorder)
                videoCaptureUseCase = videoCapture

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

                // 가로/세로 회전 추적 — 디스플레이 회전이 바뀌면 targetRotation 갱신
                if (started) registerRotationListener()
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
        // UI 부착 시점에도 현재 회전 동기화 (리스너 등록이 늦었을 수 있음)
        applyTargetRotation()
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
        unregisterRotationListener()
        try { provider?.unbindAll() } catch (_: Exception) {}
        provider = null
        preview = null
        imageCaptureUseCase = null
        videoCaptureUseCase = null
        analysisUseCase = null
        CameraHolder.setImageCapture(null)
        CameraHolder.setCapture(null)
        try {
            host?.registry?.currentState = Lifecycle.State.DESTROYED
        } catch (_: Exception) {}
        host = null
    }

    // ---------- 가로/세로 회전 지원 ----------

    /** 디스플레이 회전 변경을 감지해 use case 들의 targetRotation 을 갱신한다. */
    private fun registerRotationListener() {
        val ctx = appCtx ?: return
        unregisterRotationListener()
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) applyTargetRotation()
            }
        }
        try {
            dm.registerDisplayListener(listener, null)
            displayListener = listener
        } catch (e: Exception) {
            Log.w(TAG, "display listener registration failed", e)
        }
        applyTargetRotation()
    }

    private fun unregisterRotationListener() {
        val ctx = appCtx
        val listener = displayListener
        if (ctx != null && listener != null) {
            try {
                val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                dm.unregisterDisplayListener(listener)
            } catch (_: Exception) {}
        }
        displayListener = null
    }

    private fun currentRotation(): Int {
        val ctx = appCtx ?: return Surface.ROTATION_0
        return try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        } catch (_: Exception) {
            Surface.ROTATION_0
        }
    }

    /** 현재 디스플레이 회전(가로/세로)을 촬영 use case 에 반영 — Farmer의 촬영 방향을 따른다. */
    fun applyTargetRotation() {
        val rot = currentRotation()
        try { preview?.targetRotation = rot } catch (_: Exception) {}
        try { imageCaptureUseCase?.targetRotation = rot } catch (_: Exception) {}
        try { analysisUseCase?.targetRotation = rot } catch (_: Exception) {}
        try { videoCaptureUseCase?.targetRotation = rot } catch (_: Exception) {}
    }
}
