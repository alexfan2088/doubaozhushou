package com.fwp.doubaonewline

import android.app.Activity
import android.os.Bundle
import com.fwp.doubaonewline.bridge.VersionRouter

class AppLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VersionRouter.launchSelectedMode(this)
        finish()
    }
}
