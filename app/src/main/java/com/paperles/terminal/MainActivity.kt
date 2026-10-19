package com.paperles.terminal

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tvClock: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvDeviceInfo: TextView
    private lateinit var tvHeartbeatStatus: TextView
    private lateinit var tvStatusFeedback: TextView
    private lateinit var tvPinDisplay: TextView
    private lateinit var tvLogConsole: TextView
    private lateinit var tvConsoleEndpoint: TextView
    private lateinit var tvLogCount: TextView
    private lateinit var nsvLogConsole: NestedScrollView
    private lateinit var btnLogClear: MaterialButton
    private lateinit var flSensorContainer: FrameLayout
    private lateinit var ivFingerprintIcon: ImageView
    private lateinit var vSensorRingOuter: View
    private lateinit var btnThemeToggle: MaterialButton
    private lateinit var btnSettings: MaterialButton
    private lateinit var btnQuickPunch: MaterialButton

    private var pinBuffer = "101"
    private var logEventCount = 0
    private var sensorBreathingAnim: ObjectAnimator? = null
    private var sensorRadarAnim: ObjectAnimator? = null

    // 12-hour format with AM/PM (e.g. 02:30:15 PM)
    private val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.US)
    private val dateFormat = SimpleDateFormat("EEE, MMM dd, yyyy", Locale.US)
    private val punchDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // Tick the terminal clock display every second
    private val clockRunnable = object : Runnable {
        override fun run() {
            val now = Date()
            tvClock.text = timeFormat.format(now)
            tvDate.text = dateFormat.format(now)
            handler.postDelayed(this, 1000)
        }
    }

    // Ping the backend every 30 seconds to update online status
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendHeartbeat(silent = true)
            handler.postDelayed(this, 30000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
        val isDarkMode = prefs.getBoolean("dark_mode", true)
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        btnThemeToggle.setIconResource(if (isDarkMode) R.drawable.ic_light_mode else R.drawable.ic_theme_mode)
        setupNumpad()
        setupSensorAnimations()
        setupClickListeners()
        updateDeviceInfoHeader()
        updateEndpointDisplay()
        initConsoleSession()
        playEntranceAnimations()

        handler.post(clockRunnable)
        handler.postDelayed(heartbeatRunnable, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorBreathingAnim?.cancel()
        sensorRadarAnim?.cancel()
        handler.removeCallbacks(clockRunnable)
        handler.removeCallbacks(heartbeatRunnable)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (currentFocus != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(currentFocus?.windowToken, 0)
            currentFocus?.clearFocus()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun bindViews() {
        tvClock = findViewById(R.id.tvClock)
        tvDate = findViewById(R.id.tvDate)
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo)
        tvHeartbeatStatus = findViewById(R.id.tvHeartbeatStatus)
        tvStatusFeedback = findViewById(R.id.tvStatusFeedback)
        tvPinDisplay = findViewById(R.id.tvPinDisplay)
        tvLogConsole = findViewById(R.id.tvLogConsole)
        tvConsoleEndpoint = findViewById(R.id.tvConsoleEndpoint)
        tvLogCount = findViewById(R.id.tvLogCount)
        nsvLogConsole = findViewById(R.id.nsvLogConsole)
        btnLogClear = findViewById(R.id.btnLogClear)
        flSensorContainer = findViewById(R.id.flSensorContainer)
        ivFingerprintIcon = findViewById(R.id.ivFingerprintIcon)
        vSensorRingOuter = findViewById(R.id.vSensorRingOuter)
        btnThemeToggle = findViewById(R.id.btnThemeToggle)
        btnSettings = findViewById(R.id.btnSettings)
        btnQuickPunch = findViewById(R.id.btnQuickPunch)

        // Cache & restore last used PIN
        pinBuffer = prefs.getString("last_pin", "101") ?: "101"
        tvPinDisplay.text = pinBuffer
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("saved_pin", pinBuffer)
        outState.putString("saved_log", tvLogConsole.text.toString())
        outState.putInt("saved_log_count", logEventCount)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        savedInstanceState.getString("saved_pin")?.let {
            pinBuffer = it
            tvPinDisplay.text = pinBuffer
        }
        savedInstanceState.getString("saved_log")?.let {
            tvLogConsole.text = it
        }
        logEventCount = savedInstanceState.getInt("saved_log_count", 0)
        tvLogCount.text = "$logEventCount events"
    }

    private fun playEntranceAnimations() {
        val views = listOf(
            findViewById<LinearLayout>(R.id.headerBar),
            findViewById<MaterialCardView>(R.id.clockCard),
            findViewById<MaterialCardView>(R.id.statusCard),
            findViewById<LinearLayout>(R.id.sensorSection),
            findViewById<LinearLayout>(R.id.pinSection)
        )

        // Stagger card appearances sequentially for smooth launch effect
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 40f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(450)
                .setStartDelay((index * 80).toLong())
                .setInterpolator(OvershootInterpolator(0.8f))
                .start()
        }
    }

    private fun setupNumpad() {
        val numpadIds = listOf(
            R.id.btnNum0, R.id.btnNum1, R.id.btnNum2, R.id.btnNum3,
            R.id.btnNum4, R.id.btnNum5, R.id.btnNum6, R.id.btnNum7,
            R.id.btnNum8, R.id.btnNum9
        )

        for (id in numpadIds) {
            val btn = findViewById<MaterialButton>(id)
            val digit = btn.text.toString()
            btn.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                animateButtonPress(it)
                // Cap PIN length to 8 digits
                if (pinBuffer.length < 8) {
                    pinBuffer += digit
                    updatePinDisplay()
                }
            }
        }

        findViewById<MaterialButton>(R.id.btnNumClear).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            animateButtonPress(it)
            pinBuffer = ""
            updatePinDisplay()
        }

        findViewById<MaterialButton>(R.id.btnNumBackspace).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            animateButtonPress(it)
            if (pinBuffer.isNotEmpty()) {
                pinBuffer = pinBuffer.dropLast(1)
                updatePinDisplay()
            }
        }
    }

    private fun updatePinDisplay() {
        prefs.edit().putString("last_pin", pinBuffer).apply()
        tvPinDisplay.text = pinBuffer
        tvPinDisplay.animate()
            .scaleX(1.08f)
            .scaleY(1.08f)
            .setDuration(80)
            .withEndAction {
                tvPinDisplay.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120)
                    .setInterpolator(OvershootInterpolator(2f))
                    .start()
            }
            .start()
    }

    private fun animateButtonPress(view: View) {
        view.animate()
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(60)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .setInterpolator(OvershootInterpolator(3f))
                    .start()
            }
            .start()
    }

    private fun setupSensorAnimations() {
        // Continuous outward radar pulse ring expanding smoothly from the sensor boundary
        val ringScaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.35f)
        val ringScaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.35f)
        val ringAlpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.55f, 0.0f)
        sensorRadarAnim = ObjectAnimator.ofPropertyValuesHolder(
            vSensorRingOuter, ringScaleX, ringScaleY, ringAlpha
        ).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Gentle breathing pulse on the sensor pad
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.04f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.04f)
        sensorBreathingAnim = ObjectAnimator.ofPropertyValuesHolder(
            flSensorContainer, scaleX, scaleY
        ).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun setupClickListeners() {
        flSensorContainer.setOnClickListener {
            // Animate fingerprint icon with crisp spring bounce on touch
            ivFingerprintIcon.animate()
                .scaleX(0.82f)
                .scaleY(0.82f)
                .setDuration(90)
                .withEndAction {
                    ivFingerprintIcon.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(240)
                        .setInterpolator(OvershootInterpolator(2.5f))
                        .start()
                }
                .start()

            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            promptBiometricSensor()
        }

        btnQuickPunch.setOnClickListener {
            animateButtonPress(it)
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            if (pinBuffer.isBlank()) {
                showStatusFeedback("Enter employee PIN first", isError = true)
                return@setOnClickListener
            }
            executePunch(rawStatus = 0, label = "PIN KEYPAD PUNCH")
        }

        btnThemeToggle.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            animateButtonPress(it)
            val currentMode = prefs.getBoolean("dark_mode", true)
            val newMode = !currentMode
            prefs.edit().putBoolean("dark_mode", newMode).apply()
            AppCompatDelegate.setDefaultNightMode(
                if (newMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        btnSettings.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            animateButtonPress(it)
            showSettingsDialog()
        }

        btnLogClear.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            logEventCount = 0
            tvLogConsole.text = ""
            tvLogCount.text = "0 events"
            appendLog("Protocol log buffer cleared.")
        }
    }

    private fun updateDeviceInfoHeader() {
        val branch = prefs.getString("branch_name", "Mohakhali Branch") ?: "Mohakhali Branch"
        val sn = prefs.getString("device_sn", "ZK-ANDROID-001") ?: "ZK-ANDROID-001"
        tvDeviceInfo.text = "Branch: $branch  \u2022  SN: $sn"
    }

    private fun updateEndpointDisplay() {
        val serverBase = prefs.getString("server_url", "http://127.0.0.1:3001") ?: "http://127.0.0.1:3001"
        val deviceSn = prefs.getString("device_sn", "ZK-ANDROID-001") ?: "ZK-ANDROID-001"
        tvConsoleEndpoint.text = "Endpoint: $serverBase/iclock/cdata • SN: $deviceSn"
    }

    private fun initConsoleSession() {
        val serverBase = prefs.getString("server_url", "http://127.0.0.1:3001") ?: "http://127.0.0.1:3001"
        val deviceSn = prefs.getString("device_sn", "ZK-ANDROID-001") ?: "ZK-ANDROID-001"
        val branch = prefs.getString("branch_name", "Mohakhali Branch") ?: "Mohakhali Branch"

        tvLogConsole.text = ""
        appendLog("Terminal initialized [ZK ADMS Protocol]")
        appendLog("Server: $serverBase • SN: $deviceSn")
        appendLog("Branch: $branch")
        appendLog("Subsystems: Biometrics [Active] | Keypad [Ready]")
        appendLog("Awaiting employee attendance events...")
    }

    private fun showStatusFeedback(message: String, isError: Boolean = false) {
        tvStatusFeedback.text = message
        val primaryColor = MaterialColors.getColor(tvStatusFeedback, com.google.android.material.R.attr.colorPrimary)
        tvStatusFeedback.setTextColor(
            if (isError) ContextCompat.getColor(this, android.R.color.holo_red_light) else primaryColor
        )

        tvStatusFeedback.alpha = 0f
        tvStatusFeedback.translationX = -20f
        tvStatusFeedback.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(1.5f))
            .start()
    }

    private fun promptBiometricSensor() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                appendLog("[Biometric] Fingerprint sample matched & verified.")
                animateSensorSuccess()
                executePunch(rawStatus = 0, label = "FINGERPRINT SCAN")
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                appendLog("[Biometric] Fingerprint scan rejected: Unregistered finger.")
                animateSensorError()
                showStatusFeedback("Fingerprint not recognized", isError = true)
            }

            // Fall back to punch if biometric hardware is unavailable or in error state
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                appendLog("[Biometric] Hardware fallback: $errString (Code $errorCode)")
                executePunch(rawStatus = 0, label = "HARDWARE SENSOR TOUCH")
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Paperles Biometric Verification")
            .setSubtitle("Place registered finger on sensor to record attendance")
            .setNegativeButtonText("Cancel")
            .build()

        prompt.authenticate(promptInfo)
    }

    private fun animateSensorSuccess() {
        ivFingerprintIcon.animate()
            .scaleX(1.3f)
            .scaleY(1.3f)
            .setDuration(150)
            .withEndAction {
                ivFingerprintIcon.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(250)
                    .setInterpolator(OvershootInterpolator(3f))
                    .start()
            }
            .start()
    }

    private fun animateSensorError() {
        val shake = ObjectAnimator.ofFloat(
            flSensorContainer, View.TRANSLATION_X,
            0f, -12f, 12f, -10f, 10f, -6f, 6f, 0f
        ).apply {
            duration = 400
        }
        shake.start()
    }

    private fun executePunch(rawStatus: Int, label: String) {
        val serverBase = prefs.getString("server_url", "http://127.0.0.1:3001") ?: "http://127.0.0.1:3001"
        val deviceSn = prefs.getString("device_sn", "ZK-ANDROID-001") ?: "ZK-ANDROID-001"
        val pin = if (pinBuffer.isNotBlank()) pinBuffer else "101"
        prefs.edit().putString("last_pin", pin).apply()
        val timeStamp = punchDateFormat.format(Date())

        // Standard ADMS ATTLOG record: PIN \t Time \t Status \t Verified \t WorkCode \t Reserved
        val attLogPayload = "$pin\t$timeStamp\t$rawStatus\t1\t0\t0\n"
        val endpoint = "$serverBase/iclock/cdata?SN=$deviceSn&table=ATTLOG"

        appendLog(">>> [$label] PIN: $pin | Timestamp: $timeStamp")
        appendLog(">>> POST $endpoint")
        showStatusFeedback("Transmitting punch to server...")

        val request = Request.Builder()
            .url(endpoint)
            .post(attLogPayload.toRequestBody("text/plain".toMediaType()))
            .build()

        Thread {
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()?.trim() ?: ""
                    val code = response.code
                    runOnUiThread {
                        if (code == 200) {
                            showStatusFeedback("\u2713 Attendance recorded: PIN $pin")
                            appendLog("<<< [HTTP 200 OK] Server Response: $body")
                            animateSensorSuccess()
                        } else {
                            showStatusFeedback("Server Rejected (HTTP $code)", isError = true)
                            appendLog("<<< [HTTP $code Error] Server Response: $body")
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showStatusFeedback("Connection Failed", isError = true)
                    appendLog("<<< [Connection Failed] ${e.message}")
                    animateSensorError()
                }
            }
        }.start()
    }

    private fun sendHeartbeat(silent: Boolean) {
        val serverBase = prefs.getString("server_url", "http://127.0.0.1:3001") ?: "http://127.0.0.1:3001"
        val deviceSn = prefs.getString("device_sn", "ZK-ANDROID-001") ?: "ZK-ANDROID-001"
        val endpoint = "$serverBase/iclock/cdata?SN=$deviceSn&options=all"

        val request = Request.Builder()
            .url(endpoint)
            .get()
            .build()

        Thread {
            try {
                client.newCall(request).execute().use { response ->
                    val code = response.code
                    val body = response.body?.string()?.trim() ?: ""
                    runOnUiThread {
                        if (code == 200) {
                            updateHeartbeatUI(online = true)
                            if (!silent) appendLog("<-> [HEARTBEAT OK] HTTP 200: $body")
                        } else {
                            updateHeartbeatUI(online = false)
                            if (!silent) appendLog("<!> [HEARTBEAT FAIL] HTTP $code")
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    updateHeartbeatUI(online = false)
                    if (!silent) appendLog("<!> [HEARTBEAT ERR] ${e.message}")
                }
            }
        }.start()
    }

    private fun updateHeartbeatUI(online: Boolean) {
        if (online) {
            tvHeartbeatStatus.text = "\u2022 ONLINE"
            tvHeartbeatStatus.setTextColor(0xFF22C55E.toInt())
            // Tint background drawable without replacing the rounded pill shape
            tvHeartbeatStatus.backgroundTintList = ColorStateList.valueOf(0x2622C55E)
        } else {
            tvHeartbeatStatus.text = "\u2022 OFFLINE"
            tvHeartbeatStatus.setTextColor(0xFFEF4444.toInt())
            tvHeartbeatStatus.backgroundTintList = ColorStateList.valueOf(0x26EF4444)
        }

        tvHeartbeatStatus.alpha = 0.5f
        tvHeartbeatStatus.animate()
            .alpha(1f)
            .setDuration(300)
            .start()
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etUrl = dialogView.findViewById<TextInputEditText>(R.id.etDialogServerUrl)
        val etSn = dialogView.findViewById<TextInputEditText>(R.id.etDialogDeviceSn)
        val etBranch = dialogView.findViewById<TextInputEditText>(R.id.etDialogBranchName)

        val currentUrl = prefs.getString("server_url", "http://127.0.0.1:3001") ?: "http://127.0.0.1:3001"
        val currentSn = prefs.getString("device_sn", "ZK-ANDROID-001") ?: "ZK-ANDROID-001"
        val currentBranch = prefs.getString("branch_name", "Mohakhali Branch") ?: "Mohakhali Branch"

        etUrl.setText(currentUrl)
        etSn.setText(currentSn)
        etBranch.setText(currentBranch)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val rawUrl = etUrl.text?.toString()?.trim()?.trimEnd('/') ?: currentUrl
                val newUrl = when {
                    rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
                    rawUrl.isNotEmpty() -> "http://$rawUrl"
                    else -> currentUrl
                }
                val newSn = etSn.text?.toString()?.trim() ?: currentSn
                val newBranch = etBranch.text?.toString()?.trim() ?: currentBranch

                // Cache last URL, SN, and branch in persistent SharedPreferences
                prefs.edit()
                    .putString("server_url", newUrl)
                    .putString("device_sn", newSn)
                    .putString("branch_name", newBranch)
                    .apply()

                updateDeviceInfoHeader()
                updateEndpointDisplay()
                appendLog("[CONFIG UPDATE] Server: $newUrl | SN: $newSn | Branch: $newBranch")
                sendHeartbeat(silent = false)
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnDismissListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(dialogView.windowToken, 0)
        }

        dialog.show()
    }

    private fun appendLog(msg: String) {
        logEventCount++
        tvLogCount.text = "$logEventCount events"

        val nowTime = timeFormat.format(Date())
        val entry = "[$nowTime] $msg"

        val current = tvLogConsole.text.toString().trim()
        // Retain last 60 log lines to allow ample scrolling history
        val lines = if (current.isEmpty()) entry else (current + "\n" + entry).split("\n").takeLast(60).joinToString("\n")
        tvLogConsole.text = lines

        // Auto-scroll seamlessly to bottom on new log entries
        nsvLogConsole.post {
            nsvLogConsole.fullScroll(View.FOCUS_DOWN)
        }

        tvLogConsole.animate()
            .alpha(0.75f)
            .setDuration(40)
            .withEndAction {
                tvLogConsole.animate()
                    .alpha(1f)
                    .setDuration(160)
                    .start()
            }
            .start()
    }
}
