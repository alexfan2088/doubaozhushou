package com.fwp.doubaonewline

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.fwp.doubaonewline.bridge.BridgeContract
import com.fwp.doubaonewline.bridge.VersionRouter
import com.fwp.doubaonewline.v2.V2Activity
import com.fwp.doubaonewline.v3.V3Activity

class ModeSelectionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mode_selection)

        findViewById<Button>(R.id.openV2Button).setOnClickListener {
            VersionRouter.saveSelectedMode(this, BridgeContract.MODE_V2)
            startActivity(Intent(this, V2Activity::class.java))
        }
        findViewById<Button>(R.id.openV1Button).setOnClickListener {
            VersionRouter.saveSelectedMode(this, BridgeContract.MODE_V1)
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.openV3Button).setOnClickListener {
            VersionRouter.saveSelectedMode(this, BridgeContract.MODE_V3)
            startActivity(Intent(this, V3Activity::class.java))
        }
    }
}
