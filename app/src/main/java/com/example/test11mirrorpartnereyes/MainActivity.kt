package com.example.test11mirrorpartnereyes

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.*

/**
 * MirrorGazeTest1 -- Staveapp med oeystyring via head pose estimation.
 *
 * Basert paa Test11MirrorPartnerEyes med:
 * - EKSISTERENDE stavelogikk (firstClick, letterMap, handleLongPress)
 * - MediaPipe Face Landmarker for head pose estimation
 * - Dwell-time: 1000ms blikk paa knapp = automatisk "klikk"
 * - 3-stegs kalibrering (rett frem -> venstre -> opp)
 */
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener, GazeTracker.Listener {

    companion object {
        const val TAG = "MirrorGazeTest1"
        const val CAM_PERM = 1001
        const val DWELL_MS = 1000L
    }

    // ============================================================
    // EKSISTERENDE stavevariabler (uendret fra Test11MirrorPartnerEyes)
    // ============================================================
    private lateinit var outputText: TextView
    private var firstClick: Int? = null
    private lateinit var tts: TextToSpeech

    private val letterMap = mapOf(
        "11" to "a", "12" to "b", "13" to "c", "14" to "d", "15" to "e",
        "21" to "f", "22" to "g", "23" to "h", "24" to "i", "25" to "j",
        "31" to "k", "32" to "l", "33" to "m", "34" to "n", "35" to "o",
        "41" to "p", "42" to "r", "43" to "s", "44" to "t", "45" to "u",
        "51" to "v", "52" to "y", "53" to "\u00e6", "54" to "\u00f8", "55" to "\u00e5"
    )

    // ============================================================
    // NYE gaze-variabler
    // ============================================================
    private lateinit var gazeTracker: GazeTracker
    private lateinit var previewView: PreviewView
    private var gazeActive = false

    // Kalibrering
    private var calibrating = true
    private var calStep = 0
    private var calStartTime = 0L
    private var centerX = 0.5f
    private var centerY = 0.5f
    private var sensitivityX = 0.8f
    private var sensitivityY = 0.6f

    // Glatting - 0.35 gir mer responsivitet enn 0.15
    private var smoothX = 0.5f
    private var smoothY = 0.5f

    // Dwell
    private var currentButton: Int? = null
    private var dwellStart = 0L
    private val dwellHandler = Handler(Looper.getMainLooper())

    // UI
    private lateinit var tvGazeStatus: TextView
    private lateinit var tvCalibrating: TextView
    private lateinit var gazeDot: View

    // Knapp-referanser
    private lateinit var button1: Button
    private lateinit var button2: Button
    private lateinit var button3: Button
    private lateinit var button4: Button
    private lateinit var button5: Button

    private lateinit var spaceBar: Button
    private lateinit var deleteBar: Button

    // ============================================================
    // LIVSSYKLUS
    // ============================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        // --- EKSISTERENDE UI (uendret) ---
        outputText = findViewById(R.id.outputText)
        tts = TextToSpeech(this, this)

        button1 = findViewById(R.id.button1)
        button2 = findViewById(R.id.button2)
        button3 = findViewById(R.id.button3)
        button4 = findViewById(R.id.button4)
        button5 = findViewById(R.id.button5)
        spaceBar = findViewById(R.id.spaceBar)
        deleteBar = findViewById(R.id.deleteBar)

        // Klikk-lyttere
        button1.setOnClickListener { handleButtonClick(1) }
        button2.setOnClickListener { handleButtonClick(2) }
        button3.setOnClickListener { handleButtonClick(3) }
        button4.setOnClickListener { handleButtonClick(4) }
        button5.setOnClickListener { handleButtonClick(5) }

        // Langt-trykk-lyttere
        button1.setOnLongClickListener { handleLongPress(1); true }
        button2.setOnLongClickListener { handleLongPress(2); true }
        button3.setOnLongClickListener { handleLongPress(3); true }
        button4.setOnLongClickListener { handleLongPress(4); true }
        button5.setOnLongClickListener { handleLongPress(5); true }

        // Space-bar (NYTT ORD)
        findViewById<Button>(R.id.spaceBar).setOnClickListener {
            val currentText = outputText.text.toString()
            if (currentText.isNotEmpty()) {
                tts.speak(currentText, TextToSpeech.QUEUE_FLUSH, null, null)
                outputText.append(" ")
            }
        }

        // Slett-tekst-bar (SLETT TEKST)
        findViewById<Button>(R.id.deleteBar).setOnClickListener {
            outputText.text = ""
            tts.stop()
        }

        // --- NYE Gaze UI ---
        previewView = findViewById(R.id.previewView)
        tvGazeStatus = findViewById(R.id.tvGazeStatus)
        tvCalibrating = findViewById(R.id.tvCalibrating)
        gazeDot = findViewById(R.id.gazeDot)

        // Start gaze
        calStartTime = System.currentTimeMillis()
        if (hasCam()) {
            startGaze()
            tvCalibrating.visibility = View.VISIBLE
        } else {
            requestCam()
        }
    }

    // ============================================================
    // EKSISTERENDE STAVELOGIKK (uendret fra Test11MirrorPartnerEyes)
    // ============================================================
    private fun handleButtonClick(buttonNumber: Int) {
        if (firstClick == null) {
            firstClick = buttonNumber
        } else {
            val combination = "$firstClick$buttonNumber"
            val letter = letterMap[combination]
            if (letter != null) {
                outputText.append(letter)
                tts.speak(letter, TextToSpeech.QUEUE_FLUSH, null, null)
            }
            firstClick = null
        }
    }

    private fun handleLongPress(buttonNumber: Int) {
        val letter = when (buttonNumber) {
            1 -> "a"
            2 -> "g"
            3 -> "m"
            4 -> "t"
            5 -> "\u00e5"
            else -> null
        }
        if (letter != null) {
            outputText.append(letter)
            tts.speak(letter, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("nb", "NO"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault())
            }
        }
    }

    // ============================================================
    // GAZE -- Hovedloopen
    // ============================================================
    private fun startGaze() {
        gazeTracker = GazeTracker(this)
        gazeTracker.listener = this
        gazeTracker.initialize()
        gazeTracker.start(this, previewView)
        gazeActive = true
    }

    override fun onGaze(x: Float, y: Float) {
        if (!gazeActive) return

        // EMA-glatting: 0.35 gir mer responsivitet enn 0.15
        smoothX = 0.45f * x + 0.55f * smoothX  // var 0.5f * x + 0.5f
        smoothY = 0.45f * y + 0.55f * smoothY  // var 0.5f * y + 0.5f

        if (calibrating) {
            handleCalibration(smoothX, smoothY)
        } else {
            handleNormalGaze(smoothX, smoothY)
        }
    }

    override fun onError(message: String) {
        runOnUiThread { tvGazeStatus.text = message }
    }

    // ============================================================
    // KALIBRERING (3 steg)
    // ============================================================
    private fun handleCalibration(rawX: Float, rawY: Float) {
        when (calStep) {
            0 -> {
                centerX = centerX * 0.9f + rawX * 0.1f
                centerY = centerY * 0.9f + rawY * 0.1f
                val elapsed = System.currentTimeMillis() - calStartTime
                runOnUiThread {
                    tvCalibrating.text = "Se rett frem! (${(elapsed / 1000) + 1}/3s)"
                }
                if (elapsed > 3000) {
                    calStep = 1
                    calStartTime = System.currentTimeMillis()
                }
            }
            1 -> {
                val elapsed = System.currentTimeMillis() - calStartTime
                val leftExtent = kotlin.math.abs(rawX - centerX)
                sensitivityX = sensitivityX * 0.7f +
                        (0.5f / leftExtent.coerceAtLeast(0.1f)) * 0.3f
                runOnUiThread {
                    tvCalibrating.text = "Se til venstre! (${(elapsed / 1000) + 1}/2s)"
                }
                if (elapsed > 2000) {
                    calStep = 2
                    calStartTime = System.currentTimeMillis()
                }
            }
            2 -> {
                val elapsed = System.currentTimeMillis() - calStartTime
                val upExtent = kotlin.math.abs(rawY - centerY)
                sensitivityY = sensitivityY * 0.7f +
                        (0.5f / upExtent.coerceAtLeast(0.1f)) * 0.3f
                runOnUiThread {
                    tvCalibrating.text = "Se opp! (${(elapsed / 1000) + 1}/2s)"
                }
                if (elapsed > 2000) {
                    finishCalibration()
                }
            }
        }
    }

    private fun finishCalibration() {
        calibrating = false
        runOnUiThread {
            tvCalibrating.visibility = View.GONE
            tvGazeStatus.text = "Beveg hodet for aa velge"
            Toast.makeText(this, "Kalibrering ferdig!", Toast.LENGTH_SHORT).show()
        }
        Log.d(TAG, "Cal: center=($centerX,$centerY) sens=($sensitivityX,$sensitivityY)")
    }

    // ============================================================
    // NORMAL GAZE -- knapp-valg med dwell-time
    // ============================================================
    private fun handleNormalGaze(rawX: Float, rawY: Float) {
        // Konverter gaze til skjerm-koordinater (0-1)
        val screenX = 0.5f + (rawX - centerX) * sensitivityX
        val screenY = 0.5f + (rawY - centerY) * sensitivityY

        // SPEILVEND: frontkamera speiler bildet, saa vi snur X
        val gazeX = 1f - screenX

        runOnUiThread { updateGazeDot(gazeX, screenY) }

        val buttonId = findButtonAt(gazeX, screenY)
        val now = System.currentTimeMillis()

        if (buttonId != null) {
            if (currentButton != buttonId) {
                currentButton = buttonId
                dwellStart = now
                runOnUiThread { highlightButton(buttonId, 0f) }
            } else {
                val progress = ((now - dwellStart).toFloat() / DWELL_MS).coerceIn(0f, 1f)
                runOnUiThread {
                    highlightButton(buttonId, progress)
                    tvGazeStatus.text = "Velger... ${(progress * 100).toInt()}%"
                }
                if (now - dwellStart >= DWELL_MS) {
                    runOnUiThread {
                        triggerButtonClick(buttonId)
                        resetHighlights()
                    }
                    currentButton = null
                }
            }
        } else {
            if (currentButton != null) {
                runOnUiThread { resetHighlights() }
                currentButton = null
            }
        }
    }

    /** Trigger knapp-klikk via gaze (samme logikk som touch) */
    private fun triggerButtonClick(buttonId: Int) {
        when (buttonId) {
            1 -> handleButtonClick(1)
            2 -> handleButtonClick(2)
            3 -> handleButtonClick(3)
            4 -> handleButtonClick(4)
            5 -> handleButtonClick(5)
            6 -> handleButtonClick(6)
            7 -> handleButtonClick(7)
        }
    }

    // ============================================================
    // HJELPEMETODER -- Gaze UI
    // ============================================================
    private fun findButtonAt(x: Float, y: Float): Int? {
        val loc = IntArray(2)
        val metrics = resources.displayMetrics
        val buttons = listOf(
            1 to button1, 2 to button2, 3 to button3,
            4 to button4, 5 to button5,  6 to spaceBar,
            7 to deleteBar
        )
        for ((id, btn) in buttons) {
            btn.getLocationOnScreen(loc)
            val l = loc[0].toFloat() / metrics.widthPixels
            val t = loc[1].toFloat() / metrics.heightPixels
            val r = l + btn.width.toFloat() / metrics.widthPixels
            val b = t + btn.height.toFloat() / metrics.heightPixels
            val margin = 0.05f
            if (x >= l - margin && x <= r + margin && y >= t - margin && y <= b + margin) {
                return id
            }
        }
        return null
    }

    private fun highlightButton(id: Int, progress: Float) {
        val btn = when (id) {
            1 -> button1; 2 -> button2; 3 -> button3
            4 -> button4; 5 -> button5  6 -> spaceBar
            7 -> deleteBar
            else -> return
        }
        btn.alpha = 0.4f + (progress * 0.6f)
    }

    private fun resetHighlights() {
        listOf(button1, button2, button3, button4, button5,
            spaceBar, deleteBar)
            .forEach { it.alpha = 1.0f }
    }

    private fun updateGazeDot(x: Float, y: Float) {
        // Bruk displayMetrics for aa matche findButtonAt()
        val dm = resources.displayMetrics
        val px = x * dm.widthPixels - gazeDot.width / 2f
        val py = y * dm.heightPixels - gazeDot.height / 2f
        gazeDot.x = px.coerceIn(0f, (dm.widthPixels - gazeDot.width).toFloat())
        gazeDot.y = py.coerceIn(0f, (dm.heightPixels - gazeDot.height).toFloat())
    }

    // ============================================================
    // KAMERA-TILLATELSER
    // ============================================================
    private fun hasCam() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun requestCam() {
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.CAMERA), CAM_PERM
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAM_PERM &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startGaze()
            calStartTime = System.currentTimeMillis()
            tvCalibrating.visibility = View.VISIBLE
        } else {
            tvGazeStatus.text = "Kamera-tillatelse nektet. Bruk beroring."
        }
    }

    // ============================================================
    // OPPRYDDING
    // ============================================================
    override fun onDestroy() {
        gazeActive = false
        gazeTracker.release()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}