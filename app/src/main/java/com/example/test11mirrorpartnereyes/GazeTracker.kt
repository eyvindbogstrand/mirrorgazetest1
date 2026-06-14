package com.example.test11mirrorpartnereyes

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2

class GazeTracker(private val context: Context) {

    companion object {
        const val TAG = "GazeTracker"
        const val NOSE_TIP = 1
        const val NOSE_BRIDGE = 6
        const val LEFT_EYE_OUTER = 33
        const val RIGHT_EYE_OUTER = 263
        const val CHIN = 152
        const val FOREHEAD = 10
    }

    interface Listener {
        fun onGaze(x: Float, y: Float)
        fun onError(message: String)
    }

    var listener: Listener? = null
    private var faceLandmarker: FaceLandmarker? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    fun initialize() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("face_landmarker.task")
                .build()

            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result: FaceLandmarkerResult, _: MPImage ->
                    processFaceResult(result)
                }
                .setErrorListener { e ->
                    Log.e(TAG, "MediaPipe error: ${e.message}")
                    listener?.onError("MediaPipe: ${e.message}")
                }
                .setNumFaces(1)
                .setOutputFaceBlendshapes(true)
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            Log.d(TAG, "FaceLandmarker initialized!")

        } catch (e: Exception) {
            Log.e(TAG, "Init error: ${e.message}", e)
            listener?.onError("Could not initialize: ${e.message}")
        }
    }

    fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                analysis.setAnalyzer(cameraExecutor) { proxy ->
                    processImageProxy(proxy)
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview, analysis
                )

                Log.d(TAG, "Camera started!")

            } catch (e: Exception) {
                Log.e(TAG, "Camera error: ${e.message}", e)
                listener?.onError("Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun processImageProxy(proxy: ImageProxy) {
        try {
            val bitmap = proxy.toBitmap()
            val mpImage = BitmapImageBuilder(bitmap).build()
            faceLandmarker?.detectAsync(mpImage, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Image processing error: ${e.message}")
        } finally {
            proxy.close()
        }
    }

    fun release() {
        try { faceLandmarker?.close() } catch (_: Exception) {}
        cameraExecutor.shutdown()
    }

    private fun processFaceResult(result: FaceLandmarkerResult) {
        try {
            if (result.faceLandmarks().isEmpty()) return
            val landmarks = result.faceLandmarks()[0]
            if (landmarks.size < 200) return

            // --- Steg 1: Finn ansiktssenter ---
            val leftEye = landmarks[LEFT_EYE_OUTER]
            val rightEye = landmarks[RIGHT_EYE_OUTER]
            val chin = landmarks[CHIN]
            val forehead = landmarks[FOREHEAD]

            val faceCenterX = (leftEye.x() + rightEye.x() + chin.x() + forehead.x()) / 4f
            val faceCenterY = (leftEye.y() + rightEye.y() + chin.y() + forehead.y()) / 4f

            // --- Steg 2: Finn nesens posisjon ---
            val noseTip = landmarks[NOSE_TIP]
            val noseBridge = landmarks[NOSE_BRIDGE]

            val noseX = (noseTip.x() + noseBridge.x()) / 2f
            val noseY = (noseTip.y() + noseBridge.y()) / 2f

            // --- Steg 3: Beregn offset (normalisert med ansiktsstorrelse) ---
            val faceWidth = abs(rightEye.x() - leftEye.x())
            val faceHeight = abs(forehead.y() - chin.y())
            val faceSize = (faceWidth + faceHeight) / 2f

            var offsetX = (noseX - faceCenterX) / faceSize.coerceAtLeast(0.01f)
            val offsetY = (noseY - faceCenterY) / faceSize.coerceAtLeast(0.01f)

            // --- Steg 4: Yaw-kompensasjon ---
            val eyeLineAngle = atan2(
                (rightEye.y() - leftEye.y()).toDouble(),
                (rightEye.x() - leftEye.x()).toDouble()
            ).toFloat()

            offsetX += eyeLineAngle * 0.3f

            // --- Steg 5: Konverter til normaliserte koordinater ---
            val sensitivityX = 3.0f
            val sensitivityY = 3.2f

            var rawX = 0.5f + offsetX * sensitivityX
            var rawY = 0.5f + offsetY * sensitivityY

            // INGEN speilvending her! Speilvending skjer i MainActivity.
            // rawX = 1f - rawX  <-- FJERNET (dobbelt speilvending)

            rawX = rawX.coerceIn(0.0f, 1.0f)
            rawY = rawY.coerceIn(0.0f, 1.0f)

            if (rawX.isNaN() || rawY.isNaN()) return

            listener?.onGaze(rawX, rawY)

        } catch (e: Exception) {
            Log.e(TAG, "Error in processFaceResult: ${e.message}")
        }
    }
}