package com.oojoo.farm.master.ui

import android.view.Choreographer
import android.view.SurfaceView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.gltfio.FilamentAsset
import java.nio.ByteBuffer

@Composable
fun FilamentFarmView(modifier: Modifier = Modifier) {
    // Initialize Filament library
    LaunchedEffect(Unit) {
        try {
            Utils.init()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    var modelViewer by remember { mutableStateOf<ModelViewer?>(null) }
    var choreographer: Choreographer? by remember { mutableStateOf(null) }
    var frameCallback: Choreographer.FrameCallback? by remember { mutableStateOf(null) }

    DisposableEffect(Unit) {
        onDispose {
            frameCallback?.let { choreographer?.removeFrameCallback(it) }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
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
            
            // Adjust Camera
            viewer.camera.lookAt(0.0, 5.0, 8.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)

            val assetLoader = AssetLoader(viewer.engine, UbershaderProvider(viewer.engine), EntityManager.get())
            val resourceLoader = ResourceLoader(viewer.engine)
            val assetsList = mutableListOf<FilamentAsset>()

            fun loadAsset(filename: String, scaleX: Float, scaleY: Float, scaleZ: Float, transX: Float, transY: Float, transZ: Float) {
                try {
                    context.assets.open(filename).use {
                        val bytes = it.readBytes()
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
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // Ground (Box scaled wide and flat)
            loadAsset("models/robot.glb", 10f, 0.1f, 10f, 0f, -0.1f, 0f)

            // Robot (Box)
            loadAsset("models/robot.glb", 0.5f, 0.5f, 0.5f, 0f, 0.5f, 0f)

            // Plants (Avocado)
            loadAsset("models/plant.glb", 15f, 15f, 15f, -2f, 0.2f, -2f)
            loadAsset("models/plant.glb", 15f, 15f, 15f, 2f, 0.2f, 1f)
            loadAsset("models/plant.glb", 15f, 15f, 15f, -1f, 0.2f, 2f)

            choreographer = Choreographer.getInstance()
            frameCallback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    choreographer?.postFrameCallback(this)
                    viewer.animator?.applyAnimation(0, (frameTimeNanos / 1_000_000_000.0).toFloat())
                    viewer.animator?.updateBoneMatrices()
                    viewer.render(frameTimeNanos)
                }
            }
            choreographer?.postFrameCallback(frameCallback!!)

            surfaceView
        }
    )
}
