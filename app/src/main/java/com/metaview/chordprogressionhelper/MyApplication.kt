package com.metaview.chordprogressionhelper

import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.os.Bundle
import com.metaview.chordprogressionhelper.data.ProgressionRepository
import com.metaview.chordprogressionhelper.data.SettingsRepository

class MyApplication : Application() {

    val progressionRepository: ProgressionRepository by lazy {
        ProgressionRepository(this)
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Register a global ActivityLifecycleCallbacks that enforces orientation rules:
        // - on phones: force portrait
        // - on tablets: allow rotation (unspecified)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyOrientationPolicy(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                // also ensure orientation applied when activity starts
                applyOrientationPolicy(activity)
            }

            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun applyOrientationPolicy(activity: Activity) {
        try {
            val isTablet = isDeviceTablet()
            if (isTablet) {
                // Allow rotation on tablets
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            } else {
                // Force portrait on phones
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        } catch (_: Exception) {
            // ignore failures; orientation policy is best-effort
        }
    }

    private fun isDeviceTablet(): Boolean {
        return try {
            val sw = resources.configuration.smallestScreenWidthDp
            sw >= 600
        } catch (_: Exception) {
            false
        }
    }
}
