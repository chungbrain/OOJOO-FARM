package com.oojoo.farm.slave.vision

import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onAnalysisResult: (AnalysisResult) -> Unit = {},
    captureRequested: Boolean = false,
    onCaptureDone: () -> Unit = {}
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            CameraHolder.setCapture(null)
            val providerFuture = ProcessCameraProvider.getInstance(context)
            providerFuture.addListener({
                try { providerFuture.get().unbindAll() } catch (_: Exception) {}
            }, ContextCompat.getMainExecutor(context))
        }
    }

    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { ia ->
                    ia.setAnalyzer(executor) { imageProxy ->
                        val result = PlantAnalyzer.analyze(imageProxy)
                        onAnalysisResult(result)
                    }
                }

            // VideoCapture — Preview/ImageCapture/ImageAnalysis 와 **함께** 바인딩
            // (CameraX 는 모든 use case 를 단일 bindToLifecycle 에 포함해야 함)
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.SD))
                .build()
            val videoCapture = VideoCapture.withOutput(recorder)

            imageCapture = capture
            // use case 바인딩 우선순위:
            // 1) Preview + ImageCapture + ImageAnalysis + VideoCapture (전부)
            // 2) Preview + ImageCapture + VideoCapture (ImageAnalysis 제외 — 영상 캡처 우선)
            // 3) Preview + ImageCapture + ImageAnalysis (VideoCapture 미지원 기기)
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    context as androidx.lifecycle.LifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, capture, analysis, videoCapture
                )
                CameraHolder.setCapture(videoCapture)
            } catch (e: Exception) {
                try {
                    cameraProvider.bindToLifecycle(
                        context as androidx.lifecycle.LifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, capture, videoCapture
                    )
                    CameraHolder.setCapture(videoCapture)
                } catch (e2: Exception) {
                    try {
                        cameraProvider.bindToLifecycle(
                            context as androidx.lifecycle.LifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview, capture, analysis
                        )
                    } catch (e3: Exception) {
                        onAnalysisResult(AnalysisResult(0.0, 0.0, "카메라 바인딩 실패: ${e3.message}", false, 0.0))
                    }
                    CameraHolder.setCapture(null)
                }
            }
        }, ContextCompat.getMainExecutor(context))
    }

    LaunchedEffect(captureRequested) {
        if (captureRequested && imageCapture != null) {
            val capture = imageCapture!!
            capture.takePicture(
                executor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                        val bitmap = imageProxyToBitmap(image)
                        if (bitmap != null) {
                            val result = PlantAnalyzer.analyzeBitmap(bitmap)
                            onAnalysisResult(result)
                        }
                        image.close()
                        onCaptureDone()
                    }
                    override fun onError(exc: ImageCaptureException) {
                        onCaptureDone()
                    }
                }
            )
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        )
    }
}

private fun imageProxyToBitmap(image: androidx.camera.core.ImageProxy): android.graphics.Bitmap? {
    return try {
        image.toBitmap()
    } catch (e: Exception) {
        null
    }
}
