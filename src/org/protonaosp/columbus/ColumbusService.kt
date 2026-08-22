/*
 * SPDX-FileCopyrightText: The Proton AOSP Project
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: GPL-3.0
 */

package org.protonaosp.columbus

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.protonaosp.columbus.actions.*
import org.protonaosp.columbus.gates.*
import org.protonaosp.columbus.sensors.APSensor
import org.protonaosp.columbus.sensors.CHRESensor
import org.protonaosp.columbus.sensors.ColumbusController
import org.protonaosp.columbus.sensors.ColumbusSensor
import org.protonaosp.columbus.sensors.useApSensor

class ColumbusService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    // Services
    private var vibrator: Vibrator? = null
    private var prefs: SharedPreferences? = null
    private var action: Action? = null
    private val vibDoubleTap = EFFECT_HEAVY_CLICK
    private var sensor: ColumbusSensor? = null
    private var controller: ColumbusController? = null
    private var handler: Handler? = null
    private var wakelock: PowerManager.WakeLock? = null
    private var settingsGate: Settings? = null
    private var gates = setOf<Gate>()
    private val binder = Binder()

    // Settings
    private var enabled = true
    private var sensitivity = 0.03f
        set(value) {
            field = value
            if (enabled) {
                sendNewSensitivity()
            }
        }

    inner class Binder : android.os.Binder() {
        fun getService(): ColumbusService = this@ColumbusService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()

        val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibrator = vibratorManager?.defaultVibrator
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakelock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
        handler = Handler.createAsync(Looper.getMainLooper())
        prefs = getDePrefs()

        dlog(TAG, "Initializing Quick Tap gesture")

        val handler = handler ?: return
        if (useApSensor(this)) {
            dlog(TAG, "Initializing AP Sensor")
            sensor = APSensor(this, sensitivity, handler)
        } else {
            dlog(TAG, "Initializing CHRE Sensor")
            sensor = CHRESensor(this, sensitivity, handler)
        }
        val sensor = sensor ?: return

        controller = ColumbusController(this, sensor, handler)
        controller?.setGestureListener(columbusControllerListener)
        settingsGate = Settings(this, handler)
        gates =
            setOf(
                TelephonyActivity(this, handler),
                VrMode(this, handler),
                PocketDetection(this, handler),
                TableDetection(this, handler),
            )

        updateAction()
        updateSensitivity()
        updateEnabled()

        // Disable gesture entirely while the screen is off to save power
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
        dlog(TAG, "Listening to screen on/off events")
        registerReceiver(screenCallback, filter)

        PackageStateManager.onCreate(this)

        // Only register for changes after initial pref updates
        prefs?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        // Cleanup preferences listener
        prefs?.unregisterOnSharedPreferenceChangeListener(this)

        PackageStateManager.onDestroy()

        unregisterReceiver(screenCallback)

        // Cleanup gates
        deactivateGates()
        gates = emptySet()

        // Stop sensor and controller
        controller?.stopListening()
        sensor?.stopListening()

        // Release wakelock if held
        if (wakelock?.isHeld == true) {
            wakelock?.release()
        }

        // Cleanup action
        action?.destroy()
        action = null

        super.onDestroy()
    }

    private fun createAction(key: String): Action {
        return when (key) {
            "screenshot" -> ScreenshotAction(this)
            "assistant" -> AssistantAction(this)
            "media" -> PlayPauseAction(this)
            "notifications" -> NotificationAction(this)
            "overview" -> RecentsAction(this)
            "flashlight" -> FlashlightAction(this)
            "launch" -> LaunchAction(this)

            else -> DummyAction(this)
        }
    }

    private fun updateSensitivity() {
        val prefs = prefs ?: return
        val value = prefs.getSensitivity(this)
        sensitivity =
            if (value <= 5) {
                value.toFloat() / 100f
            } else {
                (value - 5).toFloat() * 0.15f
            }
        dlog(TAG, "Setting sensitivity to $sensitivity")
    }

    private fun updateAction() {
        val prefs = prefs ?: return
        val key = prefs.getAction(this)
        dlog(TAG, "Setting action to $key")
        action?.destroy()
        action = createAction(key)
    }

    private fun updateEnabled() {
        enabled = prefs?.getEnabled(this) ?: return
        if (enabled) {
            dlog(TAG, "Enabling gesture")
            activateGates()
            if (blockingGate()) {
                disableGesture()
            } else {
                enableGesture()
            }
        } else {
            dlog(TAG, "Disabling gesture")
            deactivateGates()
            disableGesture()
        }
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        if (key == null) return
        when (key) {
            getString(R.string.pref_key_enabled) -> updateEnabled()
            getString(R.string.pref_key_sensitivity) -> updateSensitivity()
            getString(R.string.pref_key_action) -> updateAction()
        }
    }

    private fun enableGesture() {
        controller?.startListening()
    }

    private fun disableGesture() {
        controller?.stopListening()
    }

    private fun sendNewSensitivity() {
        controller?.updateSensitivity(sensitivity)
    }

    private fun onGestureDetected(msg: Int) {
        if (msg != 1) return
        val action = action ?: return
        wakelock?.acquire(2000L)
        try {
            val settingsGate = settingsGate
            if (settingsGate != null && settingsGate.isBlocking() && settingsGate.handleGesture()) {
                vibrator?.vibrate(vibDoubleTap, sonicAudioAttr)
                return
            }

            if (!action.canRun()) return

            vibrator?.vibrate(vibDoubleTap, sonicAudioAttr)

            action.run()
        } finally {
            if (wakelock?.isHeld == true) {
                wakelock?.release()
            }
        }
    }

    private val columbusControllerListener =
        object : ColumbusController.GestureListener {
            override fun onGestureDetected(sensor: ColumbusSensor, msg: Int) {
                onGestureDetected(msg)
            }
        }

    private fun activateGates() {
        settingsGate?.registerListener(gateListener)
        gates.forEach { it.registerListener(gateListener) }
    }

    private fun deactivateGates() {
        settingsGate?.unregisterListener(gateListener)
        gates.forEach { it.unregisterListener(gateListener) }
    }

    private fun blockingGate(): Boolean {
        for (it in gates) {
            if (it.isBlocking()) {
                dlog(TAG, "Blocked by ${it::class.simpleName.orEmpty()} gate")
                return true
            }
        }
        return false
    }

    private val gateListener =
        object : Gate.Listener {
            override fun onGateChanged(gate: Gate) {
                if (enabled) {
                    updateEnabled()
                }
            }
        }

    private val screenCallback =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                if (enabled) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_ON -> {
                            dlog(TAG, "Enabling gesture due to screen on")
                            updateEnabled()
                        }
                        // Disable gesture entirely to save power
                        Intent.ACTION_SCREEN_OFF -> {
                            dlog(TAG, "Disabling gesture due to screen off")
                            deactivateGates()
                            disableGesture()
                        }
                    }
                }
            }
        }

    companion object {
        // Matches HapticClick from SystemUI's Columbus implementation
        private val sonicAudioAttr: AudioAttributes =
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .build()

        private val EFFECT_HEAVY_CLICK =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            } else {
                VibrationEffect.createOneShot(75, VibrationEffect.DEFAULT_AMPLITUDE)
            }
    }
}
