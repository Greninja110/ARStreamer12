package com.example.myapplication12.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import com.example.myapplication12.ar.ARManager
import com.example.myapplication12.service.ServiceState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val arManager: ARManager?,
    private val serviceScope: CoroutineScope
) : Closeable {

    private val TAG = "CameraManager"

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val _previewSurfaceTexture = MutableSharedFlow<SurfaceTexture>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val previewSurfaceTexture = _previewSurfaceTexture.asSharedFlow()

    private val _imageProxyFlow = MutableSharedFlow<ImageProxy>(replay = 0, extraBufferCapacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val imageProxyFlow = _imageProxyFlow.asSharedFlow()

    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    @Volatile private var surfaceTextureId: Int = -1

    fun startCamera(targetResolution: Size? = null) {
        Log.i(TAG, "Starting camera setup...")
        cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                Log.i(TAG, "CameraProvider obtained.")
                bindCameraUseCases(targetResolution)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get camera provider or bind use cases", e)
                ServiceState.postError("Camera setup failed: ${e.message}")
                close()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("UnsafeOptInUsageError", "NewApi")
    private fun bindCameraUseCases(targetResolution: Size?) {
        val cameraProvider = cameraProvider ?: run {
            Log.e(TAG, "Camera provider not available during binding.")
            ServiceState.postError("Camera provider unavailable.")
            return
        }

        Log.d(TAG, "Binding camera use cases...")

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        val previewBuilder = Preview.Builder()

        if (arManager != null) {
            Log.d(TAG, "ARManager found. Applying Camera2 Interop settings for AR.")
            val camera2Interop = Camera2Interop.Extender(previewBuilder)
            camera2Interop.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                Range(15, 30)
            ).setCaptureRequestOption(
                CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO
            ).setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON
            ).setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO
            ).setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
            Log.d(TAG, "Camera2 Interop settings applied to Preview builder.")
        }

        preview = previewBuilder.build()
        Log.d(TAG, "Preview use case built.")

        imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(targetResolution ?: Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        Log.d(TAG, "ImageAnalysis use case built.")

        imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
            val emitted = _imageProxyFlow.tryEmit(imageProxy)
            if (!emitted) {
                imageProxy.close()
            }
            ServiceState.videoFpsCalculator.frameReceived()
        }

        if (arManager != null) {
            Log.d(TAG, "Setting up manual SurfaceTexture for ARCore...")
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            surfaceTextureId = textures[0]

            if (surfaceTextureId == GLES20.GL_NONE) {
                Log.e(TAG, "Failed to generate valid OpenGL texture ID (returned GL_NONE). Check GL context.")
                ServiceState.postError("OpenGL setup failed for AR.")
                close()
                return
            }
            Log.i(TAG, "Generated OpenGL Texture ID: $surfaceTextureId")

            surfaceTexture = SurfaceTexture(surfaceTextureId)
            targetResolution?.let {
                surfaceTexture?.setDefaultBufferSize(it.width, it.height)
            } ?: surfaceTexture?.setDefaultBufferSize(640, 480)
            surface = Surface(surfaceTexture)

            Log.d(TAG, "Setting SurfaceProvider for Preview use case...")
            preview?.setSurfaceProvider(cameraExecutor) { request ->
                val surfaceToProvide = surface
                if (surfaceToProvide == null) {
                    Log.e(TAG, "AR Surface instance is null when SurfaceRequest received. Cannot provide surface.")
                    return@setSurfaceProvider
                }
                Log.i(TAG, "Providing manually created AR Surface (Texture ID: $surfaceTextureId) to SurfaceRequest.")
                request.provideSurface(surfaceToProvide, cameraExecutor) { result ->
                    Log.i(TAG, "CameraX finished using the provided AR Surface for request. Result: ${result.resultCode}")
                }
            }

            Log.d(TAG,"Informing ARManager of texture ID: $surfaceTextureId")
            arManager.setCameraTextureName(surfaceTextureId)

            surfaceTexture?.setOnFrameAvailableListener({ texture ->
                try {
                    texture.updateTexImage()
                } catch (e: Exception) {
                    Log.e(TAG, "Error calling updateTexImage() for texture ID $surfaceTextureId", e)
                }
            }, Handler(Looper.getMainLooper()))

        } else {
            Log.w(TAG, "ARManager is null. Preview SurfaceProvider not set for AR.")
        }

        try {
            Log.d(TAG, "Unbinding all previous use cases before rebinding...")
            cameraProvider.unbindAll()
            Log.i(TAG, "Previous use cases unbound.")

            val useCasesToBind = mutableListOf<UseCase>()
            preview?.let { useCasesToBind.add(it) }
            imageAnalysis?.let { useCasesToBind.add(it) }

            if (useCasesToBind.isNotEmpty()) {
                Log.i(TAG, "Binding use cases to lifecycle: ${useCasesToBind.joinToString { it::class.java.simpleName }}")
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    *useCasesToBind.toTypedArray()
                )
                Log.i(TAG, "Camera use cases bound successfully.")

                preview?.resolutionInfo?.let { Log.i(TAG, "Actual Preview Resolution: ${it.resolution}") }
                imageAnalysis?.resolutionInfo?.let { Log.i(TAG, "Actual Analysis Resolution: ${it.resolution}") }

                initializeCameraControls()

            } else {
                Log.w(TAG, "No use cases were configured to bind. Camera will not be active.")
            }

        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            ServiceState.postError("Failed to bind camera: ${exc.message}")
            close()
        }
    }

    private fun initializeCameraControls() {
        val camInfo = camera?.cameraInfo ?: return
        camInfo.zoomState.value?.let {
            Log.i(TAG, "Initial Zoom: ratio=${it.zoomRatio}, linear=${it.linearZoom}, range=[${it.minZoomRatio}..${it.maxZoomRatio}]")
        } ?: Log.w(TAG, "Initial ZoomState unavailable.")
        val exposureState = camInfo.exposureState
        Log.i(TAG, "Initial Exposure: index=${exposureState.exposureCompensationIndex}, supported=${exposureState.isExposureCompensationSupported}, range=${exposureState.exposureCompensationRange}, step=${exposureState.exposureCompensationStep}")
    }

    fun stopCamera() {
        Log.i(TAG, "Stopping camera and releasing resources...")
        try {
            cameraProvider?.unbindAll()
            Log.d(TAG,"Use cases unbound.")

            surface?.release()
            surfaceTexture?.release()
            Log.d(TAG,"Manual Surface and SurfaceTexture released.")
            surface = null
            surfaceTexture = null

            val idToDelete = surfaceTextureId
            if (idToDelete != GLES20.GL_NONE && idToDelete != -1) {
                Log.i(TAG, "OpenGL Texture ID $idToDelete marked for deletion (requires GL context on appropriate thread).")
            }
            surfaceTextureId = -1

            camera = null
            Log.i(TAG, "Camera stopped and AR resources potentially released.")
        } catch (e: Exception) {
            Log.e(TAG, "Error occurred during camera stop", e)
        } finally {
            ServiceState.videoFpsCalculator.reset()
        }
    }

    fun setZoomRatio(zoomRatio: Float): ListenableFuture<Void>? {
        val camControl = camera?.cameraControl ?: return null
        Log.d(TAG, "Requesting setZoomRatio: $zoomRatio")
        val future = camControl.setZoomRatio(zoomRatio)
        future.addListener({
            try {
                future.get()
                val currentZoom = camera?.cameraInfo?.zoomState?.value?.zoomRatio
                Log.d(TAG, "setZoomRatio success. Current ratio: $currentZoom")
            } catch (e: CancellationException) {
                Log.i(TAG, "setZoomRatio cancelled.")
            } catch (e: Exception) {
                Log.e(TAG, "setZoomRatio failed.", e)
            }
        }, cameraExecutor)
        return future
    }

    fun setLinearZoom(linearZoom: Float): ListenableFuture<Void>? {
        val camControl = camera?.cameraControl ?: return null
        val clampedZoom = linearZoom.coerceIn(0f, 1f)
        Log.d(TAG, "Requesting setLinearZoom: $clampedZoom (Input: $linearZoom)")
        val future = camControl.setLinearZoom(clampedZoom)
        future.addListener({
            try {
                future.get()
                val currentZoom = camera?.cameraInfo?.zoomState?.value?.linearZoom
                Log.d(TAG, "setLinearZoom success. Current linear: $currentZoom")
            } catch (e: CancellationException) {
                Log.i(TAG, "setLinearZoom cancelled.")
            } catch (e: Exception) {
                Log.e(TAG, "setLinearZoom failed.", e)
            }
        }, cameraExecutor)
        return future
    }

    fun triggerFocusAndMetering(focusPoint: MeteringPoint): ListenableFuture<FocusMeteringResult>? {
        val camControl = camera?.cameraControl ?: return null

        // Fix: Create a proper FocusMeteringAction to check support instead of using raw flags
        val meteringAction = FocusMeteringAction.Builder(focusPoint, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()

        // Now check support using the action object
        if (camera?.cameraInfo?.isFocusMeteringSupported(meteringAction) != true) {
            Log.w(TAG, "Focus/Metering (AF/AE) not supported by current camera.")
            return null
        }

        Log.d(TAG, "Requesting triggerFocusAndMetering.")
        val future = camControl.startFocusAndMetering(meteringAction)
        future.addListener({
            try {
                val result: FocusMeteringResult? = future.get()
                Log.d(TAG, "triggerFocusAndMetering result: success=${result?.isFocusSuccessful}")
            } catch (e: CancellationException) {
                Log.i(TAG, "Focus/Metering cancelled (likely timed out or superseded).")
            } catch (e: Exception) {
                Log.e(TAG, "triggerFocusAndMetering failed.", e)
            }
        }, cameraExecutor)
        return future
    }

    fun setExposureCompensationIndex(index: Int): ListenableFuture<Int>? {
        val camControl = camera?.cameraControl ?: return null
        val exposureState = camera?.cameraInfo?.exposureState ?: return null

        if (!exposureState.isExposureCompensationSupported) {
            Log.w(TAG, "Exposure compensation not supported.")
            return null
        }
        val validRange = exposureState.exposureCompensationRange
        if (!validRange.contains(index)) {
            Log.w(TAG, "Exposure index $index is out of valid range $validRange")
            return null
        }

        Log.d(TAG, "Requesting setExposureCompensationIndex: $index")
        val future = camControl.setExposureCompensationIndex(index)
        future.addListener({
            try {
                val resultIndex: Int = future.get()
                val currentIndex = camera?.cameraInfo?.exposureState?.exposureCompensationIndex
                Log.d(TAG, "setExposureCompensationIndex success. Result: $resultIndex, Current: $currentIndex")
            } catch (e: CancellationException) {
                Log.i(TAG, "setExposureCompensationIndex cancelled.")
            } catch (e: Exception) {
                Log.e(TAG, "setExposureCompensationIndex failed.", e)
            }
        }, cameraExecutor)
        return future
    }

    fun getCameraInfo(): CameraInfo? {
        return camera?.cameraInfo
    }

    override fun close() {
        Log.i(TAG, "Closing CameraManager instance.")
        stopCamera()
        if (!cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
            Log.i(TAG, "CameraExecutor shutdown requested.")
        }
        Log.i(TAG, "CameraManager closed.")
    }
}