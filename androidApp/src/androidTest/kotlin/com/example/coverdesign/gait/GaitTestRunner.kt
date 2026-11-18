package com.example.coverdesign.gait

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import com.example.coverdesign.MainApplication

/** Repo-only emulator runs must not start the default Firebase uploader from MainApplication. */
class GaitTestRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        val repoEmulatorRun = InstrumentationRegistry.getArguments().getString("gaitCloudEmulator") == "true"
        if (repoEmulatorRun && app is MainApplication) return
        super.callApplicationOnCreate(app)
    }
}
