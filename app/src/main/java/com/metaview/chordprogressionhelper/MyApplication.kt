package com.metaview.chordprogressionhelper

import android.app.Application
import com.metaview.chordprogressionhelper.data.ProgressionRepository
import com.metaview.chordprogressionhelper.data.SettingsRepository

class MyApplication : Application() {

    val progressionRepository: ProgressionRepository by lazy {
        ProgressionRepository(this)
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(this)
    }
}
