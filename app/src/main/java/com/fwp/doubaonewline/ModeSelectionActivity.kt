package com.fwp.doubaonewline

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.fwp.doubaonewline.v2.V2Activity
import com.fwp.doubaonewline.v3.V3Activity

class ModeSelectionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mode_selection)

        findViewById<Button>(R.id.openV2Button).setOnClickListener {
            startActivity(Intent(this, V2Activity::class.java))
        }
        findViewById<Button>(R.id.openV1Button).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.openV3Button).setOnClickListener {
            startActivity(Intent(this, V3Activity::class.java))
        }
    }
}
