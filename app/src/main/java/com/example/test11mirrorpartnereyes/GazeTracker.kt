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

/**
 * GazeTracker -- Head Pose Estimation med MediaPipe Face Landmarker.
 *
 * Bruker nesens offset fra ansiktssenter for aa estimere
 * hvor paa skjermen brukeren ser. Dette er mer robust enn
 * iris-tracking paa vanlige mobil-kameraer.
 *
 * PRINSIPP:
 * - Naar du ser til venstre, vender hodet litt mot venstre
 * - Naar du ser opp, lofter du hodet litt (haken ned)
 * - Nesens offset fra ansiktssenter = blikk-retning
 */
class GazeTracker(private val context: Context) {

    companion object {
        const val TAG = "GazeTracker"
        // Viktige landmarks i MediaPipe Face Mesh
        const val NOSE_TIP = 1           // Nespissen
        const val NOSE_BRIDGE = 6        // Neseryggen
        const val LEFT_EYE_OUTER = 33    // Venstre oeye ytterkant
        const val RIGHT_EYE_OUTER = 263  // Hoeire oeye ytterkant
        const val CHIN = 152             // Hake
        const val FOREHEAD = 10          // Pannen (toppen)
    }

    interface Listener {
        /** Kalles med normaliserte gaze-koordinater (0-1) for hvert frame */
        fun onGaze(x: Float, y: Float)
        /** Kalles ved feil */
        fun onError(message: String)
    }

    var listener: Listener? = null
    private var faceLandmarker: FaceLandmarker? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    /**
     * Initialiser MediaPipe Face Landmarker.
     * Maa kalles foer start().
     *
     * Krever: face_landmarker.task i app/src/main/assets/
     * Last ned fra: https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task
     */
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
                    Log.e(TAG, "MediaPipe feil: ${e.message}")
                    listener?.onError("MediaPipe: ${e.message}")
                }
                .setNumFaces(1)
                // Aktiver face blendshapes for bedre ansiktsanalyse
                .setOutputFaceBlendshapes(true)
                .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            Log.d(TAG, "FaceLandmarker initialisert!")

        } catch (e: Exception) {
            Log.e(TAG, "Init-feil: ${e.message}", e)
            listener?.onError("Kunne ikke initialisere: ${e.message}")
        }
    }

    /**
     * Start kamera og blikk-sporing.
     */
    fun start(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
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

                Log.d(TAG, "Kamera startet (frontkamera)!")

            } catch (e: Exception) {
                Log.e(TAG, "Kamera-feil: ${e.message}", e)
                listener?.onError("Kamera-feil: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Prosesser kamerabilde og send til MediaPipe.
     */
    private fun processImageProxy(proxy: ImageProxy) {
        try {
            val bitmap = proxy.toBitmap()
            val mpImage = BitmapImageBuilder(bitmap).build()
            faceLandmarker?.detectAsync(mpImage, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Bilde-prosessering feil: ${e.message}")
        } finally {
            proxy.close()
        }
    }

    /**
     * Frigjoer ressurser.
     */
    fun release() {
        try {
            faceLandmarker?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Feil ved opprydding: ${e.message}")
        }
        cameraExecutor.shutdown()
    }

    // ============================================================
    // HEAD POSE ESTIMATION
    // ============================================================

    /**
     * Hovedalgoritme: Bruk nesens offset fra ansiktssenter
     * for aa estimere blikk-retning.
     */
    private fun processFaceResult(result: FaceLandmarkerResult) {
        if (result.faceLandmarks().isEmpty()) return

        val landmarks = result.faceLandmarks()[0]
        if (landmarks.size < 200) return  // Sikre at vi har nok landmarks

        try {
            // --- Steg 1: Finn ansiktssenter ---
            val leftEye = landmarks[LEFT_EYE_OUTER]
            val rightEye = landmarks[RIGHT_EYE_OUTER]
            val chin = landmarks[CHIN]
            val forehead = landmarks[FOREHEAD]

            // Ansiktssenter = gjennomsnitt av oeyne + hake + panne
            val faceCenterX = (leftEye.x() + rightEye.x() + chin.x() + forehead.x()) / 4f
            val faceCenterY = (leftEye.y() + rightEye.y() + chin.y() + forehead.y()) / 4f

            // --- Steg 2: Finn nesens posisjon ---
            val noseTip = landmarks[NOSE_TIP]
            val noseBridge = landmarks[NOSE_BRIDGE]

            // Nese-gjennomsnitt for stabilisering
            val noseX = (noseTip.x() + noseBridge.x()) / 2f
            val noseY = (noseTip.y() + noseBridge.y()) / 2f

            // --- Steg 3: Beregn offset (nesen i forhold til ansiktssenter) ---
            // Naar du ser til venstre, vender hodet mot venstre,
            // saa nesen beveger seg mot venstre (lavere X)
            var offsetX = noseX - faceCenterX
            var offsetY = noseY - faceCenterY

            // --- Steg 4: Beregn ansiktsrotasjon (yaw) for ekstra presisjon ---
            // Bruk oeyne for aa finne ansiktsvinkelen
            val eyeLineAngle = atan2(
                (rightEye.y() - leftEye.y()).toDouble(),
                (rightEye.x() - leftEye.x()).toDouble()
            ).toFloat()

            // Kompenser for ansiktsrotasjon i X-retning
            // Naar hodet roterer (yaw), bidrar dette til nesens offset
            val yawCompensation = eyeLineAngle * 0.3f
            offsetX += yawCompensation

            // --- Steg 5: Konverter til normaliserte koordinater (0-1) ---
            // OffsetX er typisk i omraadet [-0.15, +0.15]
            // OffsetY er typisk i omraadet [-0.10, +0.10]
            val sensitivityX = 3.0f  // Justerbar: hoyere = mer sensitiv
            val sensitivityY = 4.0f

            var rawX = 0.5f + offsetX * sensitivityX
            var rawY = 0.5f + offsetY * sensitivityY

            // --- Steg 6: Speilvend X (frontkamera speiler bildet) ---
            rawX = 1f - rawX

            // --- Steg 7: Begrens til gyldig omraade ---
            rawX = rawX.coerceIn(0.0f, 1.0f)
            rawY = rawY.coerceIn(0.0f, 1.0f)

            // Sjekk for ugyldige verdier
            if (rawX.isNaN() || rawY.isNaN()) return

            listener?.onGaze(rawX, rawY)

        } catch (e: Exception) {
            Log.e(TAG, "Feil i processFaceResult: ${e.message}")
        }
    }
}
