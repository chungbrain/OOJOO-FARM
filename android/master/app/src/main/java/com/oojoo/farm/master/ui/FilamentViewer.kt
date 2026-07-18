package com.oojoo.farm.master.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.opengl.Matrix
import android.os.Build
import android.util.Log
import android.view.Choreographer
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.ModelViewer
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "FilamentFarmView"

/**
 * Filament + Android Emulator SwiftShader/ANGLE has caused qemu ACCESS_VIOLATION
 * crashes (host process dies). Skip the native 3D path on emulator images.
 */
private fun isRunningOnEmulator(): Boolean {
    val fingerprint = Build.FINGERPRINT.lowercase()
    val model = Build.MODEL.lowercase()
    val product = Build.PRODUCT.lowercase()
    val hardware = Build.HARDWARE.lowercase()
    val manufacturer = Build.MANUFACTURER.lowercase()
    return fingerprint.contains("generic") ||
        fingerprint.contains("emulator") ||
        fingerprint.contains("vbox") ||
        model.contains("google_sdk") ||
        model.contains("emulator") ||
        model.contains("android sdk") ||
        model.contains("sdk_gphone") ||
        product.contains("sdk") ||
        product.contains("emulator") ||
        product.contains("vbox") ||
        hardware.contains("goldfish") ||
        hardware.contains("ranchu") ||
        manufacturer.contains("genymotion")
}

/**
 * Home 3D farm preview.
 *
 * Crash-prone patterns this avoids:
 * - Compose state writes from AndroidView.factory (lifecycle owned by the View)
 * - Engine.destroy() racing with Choreographer render callbacks
 * - Reusing one ByteBuffer across multiple createAsset() calls
 * - Destroying Engine while gltfio ResourceLoader still has pending async work
 * - Filament GL on emulator SwiftShader (kills qemu-system-x86_64)
 */
@Composable
fun FilamentFarmView(
    modifier: Modifier = Modifier,
    transparentBackground: Boolean = false,
    skyColor: Color = Color(0xFFD6EEFF)
) {
    val skyArgb = remember(skyColor) {
        android.graphics.Color.argb(
            (skyColor.alpha * 255).toInt(),
            (skyColor.red * 255).toInt(),
            (skyColor.green * 255).toInt(),
            (skyColor.blue * 255).toInt()
        )
    }
    AndroidView(
        modifier = modifier.clip(RoundedCornerShape(12.dp)),
        factory = { context ->
            if (isRunningOnEmulator()) {
                Log.i(TAG, "Emulator detected — using 2D farm preview (Filament disabled)")
                FarmFallbackView(context, transparentBackground)
            } else {
                try {
                    FarmFilamentSurface(context, skyArgb)
                } catch (e: Throwable) {
                    Log.e(TAG, "Filament init failed — using 2D fallback", e)
                    FarmFallbackView(context, transparentBackground)
                }
            }
        },
        onRelease = { view ->
            (view as? FarmFilamentSurface)?.releaseFilament()
        }
    )
}

/** Farm placeholder; when transparent, weather backdrop from Compose shows through. */
private class FarmFallbackView(
    context: Context,
    private val transparentBackground: Boolean
) : View(context) {
    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 56f
        isFakeBoldText = true
        setShadowLayer(8f, 0f, 2f, android.graphics.Color.argb(120, 0, 0, 0))
    }
    private val plantPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 42f
    }

    init {
        if (transparentBackground) {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (!transparentBackground) {
            groundPaint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(0xFFA5D6A7.toInt(), 0xFF66BB6A.toInt(), 0xFF2E7D32.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!transparentBackground) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), groundPaint)
        }
        // Decorative plants sitting on the weather ground
        val y = height * 0.62f
        canvas.drawText("🌱", width * 0.22f, y, plantPaint)
        canvas.drawText("🌿", width * 0.50f, y - 8f, plantPaint)
        canvas.drawText("🍅", width * 0.78f, y, plantPaint)
        canvas.drawText("🤖", width * 0.50f, height * 0.42f, plantPaint)
        canvas.drawText("나의 농장", width / 2f, height * 0.88f, textPaint)
    }
}

private class FarmFilamentSurface(
    context: Context,
    skyArgb: Int
) : SurfaceView(context) {
    private val disposed = AtomicBoolean(false)
    private val choreographer = Choreographer.getInstance()

    private var modelViewer: ModelViewer? = null
    private var assetLoader: AssetLoader? = null
    private var resourceLoader: ResourceLoader? = null
    private val assets = mutableListOf<FilamentAsset>()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (disposed.get()) return
            val viewer = modelViewer ?: return
            val loader = resourceLoader
            try {
                loader?.asyncUpdateLoad()
                viewer.render(frameTimeNanos)
            } catch (e: Throwable) {
                Log.e(TAG, "render frame failed", e)
            }
            if (!disposed.get()) {
                choreographer.postFrameCallback(this)
            }
        }
    }

    init {
        val viewer = ModelViewer(this)
        modelViewer = viewer
        val engine = viewer.engine

        val sun = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.SUN)
            .color(1.0f, 1.0f, 0.9f)
            .intensity(100_000.0f)
            .direction(-1.0f, -1.0f, -1.0f)
            .castShadows(true)
            .build(engine, sun)
        viewer.scene.addEntity(sun)

        val fill = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(0.8f, 0.8f, 1.0f)
            .intensity(30_000.0f)
            .direction(1.0f, 1.0f, 1.0f)
            .castShadows(false)
            .build(engine, fill)
        viewer.scene.addEntity(fill)

        val r = android.graphics.Color.red(skyArgb) / 255f
        val g = android.graphics.Color.green(skyArgb) / 255f
        val b = android.graphics.Color.blue(skyArgb) / 255f
        viewer.scene.skybox = Skybox.Builder()
            .color(r, g, b, 1.0f)
            .build(engine)

        viewer.camera.lookAt(0.0, 3.0, 8.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0)

        assetLoader = AssetLoader(engine, UbershaderProvider(engine), EntityManager.get())
        resourceLoader = ResourceLoader(engine)

        loadAsset("models/robot.glb", 0.5f, 0.5f, 0.5f, 0f, 0f, 0f)
        loadAsset("models/plant.glb", 15f, 15f, 15f, -1.5f, 0f, -1f)
        loadAsset("models/plant.glb", 15f, 15f, 15f, 2f, 0f, 1f)

        choreographer.postFrameCallback(frameCallback)
    }

    private fun loadAsset(
        filename: String,
        scaleX: Float, scaleY: Float, scaleZ: Float,
        transX: Float, transY: Float, transZ: Float
    ) {
        val viewer = modelViewer ?: return
        val loader = assetLoader ?: return
        val resources = resourceLoader ?: return
        try {
            context.assets.open(filename).use { stream ->
                val bytes = stream.readBytes()
                // Each createAsset needs its own direct buffer — do not share/rewind one buffer.
                val buffer = ByteBuffer.allocateDirect(bytes.size)
                buffer.put(bytes)
                buffer.rewind()

                val asset = loader.createAsset(buffer) ?: return
                resources.asyncBeginLoad(asset)
                viewer.scene.addEntities(asset.entities)
                assets.add(asset)

                val tm = viewer.engine.transformManager
                val instance = tm.getInstance(asset.root)
                val transform = FloatArray(16)
                Matrix.setIdentityM(transform, 0)
                Matrix.translateM(transform, 0, transX, transY, transZ)
                Matrix.scaleM(transform, 0, scaleX, scaleY, scaleZ)
                tm.setTransform(instance, transform)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "failed to load $filename", e)
        }
    }

    fun releaseFilament() {
        if (!disposed.compareAndSet(false, true)) return
        // Stop rendering first so no frame touches a torn-down Engine.
        choreographer.removeFrameCallback(frameCallback)

        val viewer = modelViewer
        val loader = assetLoader
        val resources = resourceLoader

        try {
            if (viewer != null) {
                for (asset in assets) {
                    try {
                        viewer.scene.removeEntities(asset.entities)
                    } catch (_: Throwable) {}
                    try {
                        loader?.destroyAsset(asset)
                    } catch (_: Throwable) {}
                }
            }
            assets.clear()
        } catch (e: Throwable) {
            Log.w(TAG, "asset destroy failed", e)
        }

        // Avoid asyncCancelLoad() — it has native crash reports on some Filament builds
        // when cancel races with in-flight uploads. Render loop is already stopped.
        try {
            resources?.evictResourceData()
        } catch (_: Throwable) {}
        try {
            resources?.destroy()
        } catch (_: Throwable) {}
        try {
            loader?.destroy()
        } catch (_: Throwable) {}

        try {
            viewer?.engine?.destroy()
        } catch (e: Throwable) {
            Log.w(TAG, "engine destroy failed", e)
        }

        modelViewer = null
        assetLoader = null
        resourceLoader = null
    }
}
