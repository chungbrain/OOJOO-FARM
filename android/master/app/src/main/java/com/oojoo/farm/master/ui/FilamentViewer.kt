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
import android.view.SurfaceHolder
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
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.View as FilamentView
import com.google.android.filament.Viewport
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "FilamentFarmView"

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
 * Process-lifetime singleton for the Filament Engine and all long-lived GPU objects.
 *
 * Filament 1.73.0 throws an uncatchable native PreconditionPanic (SIGABRT) when
 * Engine.destroy() is called while gltfio-created material instances are still
 * alive. destroyAsset() does NOT synchronously release those material instances,
 * so destroying the Engine right after destroyAsset() still panics with:
 *   "destroying material 'base_lit_opaque' but N instances still alive"
 *
 * Additionally, ModelViewer registers an OnAttachStateChangeListener that auto-
 * calls destroy() (which calls engine.destroy()) when the View detaches —
 * impossible to remove since the listener is private. So we bypass ModelViewer
 * entirely and drive Filament directly.
 *
 * Fix: keep Engine, AssetLoader, ResourceLoader, Renderer, Scene, View, Camera
 * and lights alive for the whole process and NEVER call engine.destroy(). The
 * OS reclaims all native memory when the process dies. Each screen entry only
 * loads GLB assets + creates a SwapChain tied to the current Surface, and
 * releaseFilament() tears those down without touching the Engine.
 */
private object FarmEngineHolder {
    @Volatile private var engine: Engine? = null
    @Volatile private var assetLoader: AssetLoader? = null
    @Volatile private var resourceLoader: ResourceLoader? = null
    @Volatile private var renderer: Renderer? = null
    @Volatile private var scene: Scene? = null
    @Volatile private var view: FilamentView? = null
    @Volatile private var camera: Camera? = null
    private val lock = Any()

    fun acquireEngine(): Engine {
        val existing = engine
        if (existing != null) return existing
        return synchronized(lock) {
            engine ?: run {
                val e = Engine.create()
                engine = e
                assetLoader = AssetLoader(e, UbershaderProvider(e), EntityManager.get())
                resourceLoader = ResourceLoader(e)
                renderer = e.createRenderer()
                scene = e.createScene()
                view = e.createView().also { v ->
                    v.scene = scene!!
                }
                val camEntity = EntityManager.get().create()
                camera = e.createCamera(camEntity)
                view!!.camera = camera

                val sun = EntityManager.get().create()
                LightManager.Builder(LightManager.Type.SUN)
                    .color(1.0f, 1.0f, 0.9f)
                    .intensity(100_000.0f)
                    .direction(-1.0f, -1.0f, -1.0f)
                    .castShadows(true)
                    .build(e, sun)
                scene!!.addEntity(sun)

                val fill = EntityManager.get().create()
                LightManager.Builder(LightManager.Type.DIRECTIONAL)
                    .color(0.8f, 0.8f, 1.0f)
                    .intensity(30_000.0f)
                    .direction(1.0f, 1.0f, 1.0f)
                    .castShadows(false)
                    .build(e, fill)
                scene!!.addEntity(fill)
                e
            }
        }
    }

    fun engine(): Engine = engine!!
    fun assetLoader(): AssetLoader = assetLoader!!
    fun resourceLoader(): ResourceLoader = resourceLoader!!
    fun renderer(): Renderer = renderer!!
    fun scene(): Scene = scene!!
    fun view(): FilamentView = view!!
    fun camera(): Camera = camera!!
}

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
        val y = height * 0.62f
        canvas.drawText("🌱", width * 0.22f, y, plantPaint)
        canvas.drawText("🌿", width * 0.50f, y - 8f, plantPaint)
        canvas.drawText("🍅", width * 0.78f, y, plantPaint)
        canvas.drawText("🤖", width * 0.50f, height * 0.42f, plantPaint)
        canvas.drawText("나의 농장", width / 2f, height * 0.88f, textPaint)
    }
}

/**
 * SurfaceView backed directly by Filament (no ModelViewer).
 *
 * We implement SurfaceHolder.Callback ourselves to manage the SwapChain tied to
 * the current Surface, and drive the render loop via Choreographer. There is no
 * OnAttachStateChangeListener here, so no hidden Engine.destroy() on detach.
 */
private class FarmFilamentSurface(
    context: Context,
    skyArgb: Int
) : SurfaceView(context), SurfaceHolder.Callback {

    private val disposed = AtomicBoolean(false)
    private val choreographer = Choreographer.getInstance()

    // Shared, process-lifetime objects.
    private val engine = FarmEngineHolder.acquireEngine()
    private val assetLoader = FarmEngineHolder.assetLoader()
    private val resourceLoader = FarmEngineHolder.resourceLoader()
    private val renderer = FarmEngineHolder.renderer()
    private val scene = FarmEngineHolder.scene()
    private val view = FarmEngineHolder.view()
    private val camera = FarmEngineHolder.camera()
    private var swapChain: SwapChain? = null
    private var skybox: Skybox? = null
    private val assets = mutableListOf<FilamentAsset>()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (disposed.get()) return
            try {
                resourceLoader.asyncUpdateLoad()
                val sc = swapChain
                if (sc != null) {
                    if (renderer.beginFrame(sc, frameTimeNanos)) {
                        renderer.render(view)
                        renderer.endFrame()
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "render frame failed", e)
            }
            if (!disposed.get()) {
                choreographer.postFrameCallback(this)
            }
        }
    }

    init {
        // Skybox color may change per entry (weather scene); replace the Scene's skybox.
        val r = android.graphics.Color.red(skyArgb) / 255f
        val g = android.graphics.Color.green(skyArgb) / 255f
        val b = android.graphics.Color.blue(skyArgb) / 255f
        skybox = Skybox.Builder().color(r, g, b, 1.0f).build(engine)
        scene.skybox = skybox

        camera.lookAt(0.0, 3.0, 8.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0)

        loadAsset("models/robot.glb", 0.5f, 0.5f, 0.5f, 0f, 0f, 0f)
        loadAsset("models/plant.glb", 15f, 15f, 15f, -1.5f, 0f, -1f)
        loadAsset("models/plant.glb", 15f, 15f, 15f, 2f, 0f, 1f)

        holder.addCallback(this)
        choreographer.postFrameCallback(frameCallback)
    }

    private fun loadAsset(
        filename: String,
        scaleX: Float, scaleY: Float, scaleZ: Float,
        transX: Float, transY: Float, transZ: Float
    ) {
        try {
            context.assets.open(filename).use { stream ->
                val bytes = stream.readBytes()
                val buffer = ByteBuffer.allocateDirect(bytes.size)
                buffer.put(bytes)
                buffer.rewind()

                val asset = assetLoader.createAsset(buffer) ?: return
                resourceLoader.asyncBeginLoad(asset)
                scene.addEntities(asset.entities)
                assets.add(asset)

                val tm = engine.transformManager
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

    override fun surfaceCreated(holder: SurfaceHolder) {
        try {
            swapChain = engine.createSwapChain(holder.surface)
        } catch (e: Throwable) {
            Log.e(TAG, "createSwapChain failed", e)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        try {
            view.setViewport(Viewport(0, 0, width, height))
            val aspect = if (height > 0) width.toDouble() / height.toDouble() else 1.0
            camera.setProjection(45.0, aspect, 0.1, 100.0, Camera.Fov.VERTICAL)
        } catch (e: Throwable) {
            Log.e(TAG, "surfaceChanged failed", e)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        try {
            swapChain?.let { engine.destroySwapChain(it) }
        } catch (_: Throwable) {}
        swapChain = null
    }

    fun releaseFilament() {
        if (!disposed.compareAndSet(false, true)) return
        // Stop render loop and detach surface listener first.
        choreographer.removeFrameCallback(frameCallback)
        try { holder.removeCallback(this) } catch (_: Throwable) {}

        // Remove assets from the shared Scene and destroy them.
        for (asset in assets) {
            try { scene.removeEntities(asset.entities) } catch (_: Throwable) {}
            try { assetLoader.destroyAsset(asset) } catch (_: Throwable) {}
        }
        assets.clear()
        try { resourceLoader.evictResourceData() } catch (_: Throwable) {}

        // Tear down this entry's skybox and swapchain. These are not tied to
        // gltfio material instances, so destroying them is safe.
        try {
            scene.skybox = null
            skybox?.let { engine.destroySkybox(it) }
        } catch (_: Throwable) {}
        skybox = null
        try { swapChain?.let { engine.destroySwapChain(it) } } catch (_: Throwable) {}
        swapChain = null

        // IMPORTANT: do NOT call engine.destroy(), renderer/scene/view/camera
        // destroy, assetLoader.destroy(), or resourceLoader.destroy().
        // Filament 1.73.0 panics (uncatchable SIGABRT) when destroying the
        // Engine while gltfio material instances are still alive. All shared
        // objects live for the whole process (FarmEngineHolder) and are
        // reclaimed by the OS on process death.
    }
}
