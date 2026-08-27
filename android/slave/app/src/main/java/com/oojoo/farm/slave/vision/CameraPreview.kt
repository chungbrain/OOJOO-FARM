package com.oojoo.farm.slave.vision

import android.view.ViewGroup
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.Executors

/**
 * 카메라 프리뷰 UI.
 *
 * 카메라 바인딩은 더 이상 여기서 하지 않는다 — FarmerService 가 구동하는
 * CameraHost(서비스 라이프사이클)가 상시 바인딩을 소유하며, 앱이 내려가도
 * 관찰·촬영이 유지된다. 이 Composable 은 화면이 떠 있을 때만
 * PreviewView surface 를 연결(attach)하고 사라질 때 해제(detach)한다.
 */
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

    DisposableEffect(Unit) {
        CameraHost.attach(context, previewView)
        onDispose {
            // surface만 해제 — 서비스 소유 카메라 바인딩은 백그라운드에서 유지된다.
            CameraHost.detach()
            executor.shutdown()
        }
    }

    // 수동 촬영(대시보드 버튼) — CameraHost 가 등록한 ImageCapture 사용
    LaunchedEffect(captureRequested) {
        if (captureRequested) {
            val capture = CameraHolder.imageCapture
            if (capture == null) {
                onCaptureDone()
            } else {
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
        val raw = image.toBitmap()
        OrientationUtil.rotateBitmap(raw, image.imageInfo.rotationDegrees)
    } catch (e: Exception) {
        null
    }
}
