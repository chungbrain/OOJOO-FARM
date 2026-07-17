package com.oojoo.farm.master.ui

import android.view.Choreographer
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable
fun FilamentFarmView(modifier: Modifier = Modifier) {
    var initError by remember { mutableStateOf(false) }
    var modelViewer by remember { mutableStateOf<ModelViewer?>(null) }
    var choreographer: Choreographer? by remember { mutableStateOf(null) }
    var frameCallback: Choreographer.FrameCallback? by remember { mutableStateOf(null) }
    val disposed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            frameCallback?.let { choreographer?.removeFrameCallback(it) }
            try {
                modelViewer?.engine?.destroy()
            } catch (_: Throwable) {}
        }
    }

    if (initError) {
        Box(
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFFA5D6A7), Color(0xFF66BB6A), Color(0xFF2E7D32))
                    )
                )
        ) {
            Text(
                "\uD83C\uDF31 \uB18D\uC7A5",
                Modifier.align(Alignment.Center),
                fontSize = 48.sp,
                fontWeight = FontWeight.Black,
                color = Color.White
            )
        }
        return
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            try {
                val surfaceView = SurfaceView(context)
                val viewer = ModelViewer(surfaceView)
                modelViewer = viewer

                // Create Sun light
                val sun = EntityManager.get().create()
                LightManager.Builder(LightManager.Type.SUN)
                    .color(1.0f, 1.0f, 0.9f)
                    .intensity(100_000.0f)
                    .direction(-1.0f, -1.0f, -1.0f)
                    .castShadows(true)
                    .build(viewer.engine, sun)
                viewer.scene.addEntity(sun)

                // Create Fill light (ambient simulation)
                val fill = EntityManager.get().create()
                LightManager.Builder(LightManager.Type.DIRECTIONAL)
                    .color(0.8f, 0.8f, 1.0f)
                    .intensity(30_000.0f)
                    .direction(1.0f, 1.0f, 1.0f)
                    .castShadows(false)
                    .build(viewer.engine, fill)
                viewer.scene.addEntity(fill)

                // Set Skybox (light blue background)
                val skybox = Skybox.Builder().color(0.85f, 0.93f, 1.0f, 1.0f).build(viewer.engine)
                viewer.scene.skybox = skybox

                // Adjust Camera
                viewer.camera.lookAt(0.0, 3.0, 8.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0)

                val assetLoader = AssetLoader(viewer.engine, UbershaderProvider(viewer.engine), EntityManager.get())
                val resourceLoader = ResourceLoader(viewer.engine)
                val assetsList = mutableListOf<FilamentAsset>()

                fun loadAsset(
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

                            val asset = assetLoader.createAsset(buffer)
                            if (asset != null) {
                                resourceLoader.asyncBeginLoad(asset)
                                viewer.scene.addEntities(asset.entities)
                                assetsList.add(asset)

                                val tm = viewer.engine.transformManager
                                val instance = tm.getInstance(asset.root)
                                val transform = FloatArray(16)
                                android.opengl.Matrix.setIdentityM(transform, 0)
                                android.opengl.Matrix.translateM(transform, 0, transX, transY, transZ)
                                android.opengl.Matrix.scaleM(transform, 0, scaleX, scaleY, scaleZ)
                                tm.setTransform(instance, transform)
                            }
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }

                // Robot (Farmer)
                loadAsset("models/robot.glb", 0.5f, 0.5f, 0.5f, 0f, 0f, 0f)

                // Plants — read GLB once, create multiple assets from shared buffer
                try {
                    val plantBytes = context.assets.open("models/plant.glb").use { it.readBytes() }
                    val plantBuffer = ByteBuffer.allocateDirect(plantBytes.size)
                    plantBuffer.put(plantBytes)

                    val plantPositions = listOf(
                        Triple(-2f, 0f, -2f),
                        Triple(2f, 0f, 1f),
                        Triple(-1f, 0f, 2f)
                    )
                    for ((px, py, pz) in plantPositions) {
                        plantBuffer.rewind()
                        val asset = assetLoader.createAsset(plantBuffer)
                        if (asset != null) {
                            resourceLoader.asyncBeginLoad(asset)
                            viewer.scene.addEntities(asset.entities)
                            assetsList.add(asset)

                            val tm = viewer.engine.transformManager
                            val instance = tm.getInstance(asset.root)
                            val transform = FloatArray(16)
                            android.opengl.Matrix.setIdentityM(transform, 0)
                            android.opengl.Matrix.translateM(transform, 0, px, py, pz)
                            android.opengl.Matrix.scaleM(transform, 0, 15f, 15f, 15f)
                            tm.setTransform(instance, transform)
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                }

                choreographer = Choreographer.getInstance()
                frameCallback = object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (disposed.get()) return
                        choreographer?.postFrameCallback(this)
                        try {
                            // Process async resource loading each frame
                            resourceLoader.asyncUpdateLoad()

                            val animator = viewer.animator
                            if (animator != null && animator.animationCount > 0) {
                                animator.applyAnimation(0, (frameTimeNanos / 1_000_000_000.0).toFloat())
                                animator.updateBoneMatrices()
                            }
                            viewer.render(frameTimeNanos)
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        }
                    }
                }
                choreographer?.postFrameCallback(frameCallback!!)

                surfaceView
            } catch (e: Throwable) {
                e.printStackTrace()
                initError = true
                SurfaceView(context)
            }
        }
    )
}
