/*
 * SPDX-FileCopyrightText: TheParasiteProject
 * SPDX-License-Identifier: GPL-3.0
 */

package org.protonaosp.columbus.gates

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.widget.Toast
import java.lang.ref.WeakReference
import org.protonaosp.columbus.settings.SettingsActivity

class Settings(context: Context, handler: Handler) : Gate(context, handler, 2) {

    private var settingsActivityCount = 0
    private var lastToast: Toast? = null
    private var settingsActivityContext: WeakReference<Context>? = null

    private val activityLifecycleCallbacks =
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

            override fun onActivityStarted(activity: Activity) {
                if (activity is SettingsActivity) {
                    settingsActivityCount++
                    settingsActivityContext = WeakReference(activity)
                    updateBlocking()
                }
            }

            override fun onActivityResumed(activity: Activity) {}

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivityStopped(activity: Activity) {
                if (activity is SettingsActivity) {
                    settingsActivityCount--
                    if (settingsActivityContext?.get() === activity) {
                        clearSettingsActivityContext()
                    }
                    updateBlocking()
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                if (activity is SettingsActivity) {
                    if (settingsActivityContext?.get() === activity) {
                        clearSettingsActivityContext()
                    }
                }
            }
        }

    init {
        // Track activities for the whole lifetime of the gate so start/stop
        // events are not missed while the gate is temporarily deactivated,
        // e.g. by the screen turning off.
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(
            activityLifecycleCallbacks
        )
    }

    private fun updateBlocking() {
        setBlocking(settingsActivityCount > 0)
    }

    override fun onActivate() {
        updateBlocking()
    }

    override fun onDeactivate() {
        lastToast?.cancel()
        lastToast = null
        setBlocking(false)
    }

    fun release() {
        (context.applicationContext as Application).unregisterActivityLifecycleCallbacks(
            activityLifecycleCallbacks
        )
        lastToast?.cancel()
        lastToast = null
    }

    private fun showToast(context: Context) {
        lastToast?.cancel()
        val toast =
            Toast.makeText(
                context.applicationContext,
                org.protonaosp.columbus.R.string.gesture_detected,
                Toast.LENGTH_SHORT,
            )
        toast.show()
        lastToast = toast
    }

    private fun clearSettingsActivityContext() {
        settingsActivityContext = null
        lastToast?.cancel()
        lastToast = null
    }

    fun handleGesture() {
        settingsActivityContext?.get()?.let { showToast(it) }
    }
}
