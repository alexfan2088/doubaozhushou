package com.fwp.doubaonewline.v2

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fwp.doubaonewline.R

class V2TokenSavingSettingsActivity : AppCompatActivity() {
    private lateinit var store: V2TokenSavingSettings
    private lateinit var localWelcomeCheck: CheckBox
    private lateinit var welcomeText: EditText
    private lateinit var speakerSpinner: Spinner
    private lateinit var gainLabel: TextView
    private lateinit var gainSeek: SeekBar
    private lateinit var previewButton: Button
    private lateinit var sentenceSpinner: Spinner
    private lateinit var idleSpinner: Spinner
    private lateinit var cooldownSpinner: Spinner
    private lateinit var contextModeSpinner: Spinner
    private lateinit var contextRoundsSpinner: Spinner
    private lateinit var responseTimeoutSpinner: Spinner
    private lateinit var welcomeSpeaker: V2LocalWelcomeSpeaker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_v2_token_saving_settings)
        store = V2TokenSavingSettings(this)
        welcomeSpeaker = V2LocalWelcomeSpeaker(this)
        localWelcomeCheck = findViewById(R.id.localWelcomeCheck)
        welcomeText = findViewById(R.id.localWelcomeText)
        speakerSpinner = findViewById(R.id.offlineTtsSpeakerSpinner)
        gainLabel = findViewById(R.id.offlineTtsGainLabel)
        gainSeek = findViewById(R.id.offlineTtsGainSeek)
        previewButton = findViewById(R.id.previewOfflineTtsButton)
        sentenceSpinner = findViewById(R.id.responseSentenceSpinner)
        idleSpinner = findViewById(R.id.idleTimeoutSpinner)
        cooldownSpinner = findViewById(R.id.wakeCooldownSpinner)
        contextModeSpinner = findViewById(R.id.contextModeSpinner)
        contextRoundsSpinner = findViewById(R.id.contextRoundsSpinner)
        responseTimeoutSpinner = findViewById(R.id.responseTimeoutSpinner)
        setupSpinner(speakerSpinner, listOf("音色 1", "音色 2", "音色 3", "音色 4", "音色 5"))
        setupSpinner(sentenceSpinner, listOf("1 句", "2 句", "3 句", "不限制"))
        setupSpinner(idleSpinner, listOf("10 秒", "15 秒", "30 秒", "60 秒"))
        setupSpinner(cooldownSpinner, listOf("1 秒", "3 秒", "5 秒", "10 秒"))
        setupSpinner(contextModeSpinner, listOf("达到轮数后清空", "继续保留上下文"))
        setupSpinner(contextRoundsSpinner, listOf("5 轮", "8 轮", "10 轮", "15 轮", "20 轮"))
        setupSpinner(
            responseTimeoutSpinner,
            listOf("10 秒", "20 秒", "30 秒", "60 秒", "不限制")
        )

        localWelcomeCheck.setOnCheckedChangeListener { _, checked ->
            welcomeText.isEnabled = checked
        }
        gainSeek.max =
            V2TokenSavingConfig.MAX_TTS_GAIN - V2TokenSavingConfig.MIN_TTS_GAIN
        gainSeek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    renderGainLabel(selectedGain())
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )
        previewButton.setOnClickListener {
            val text = welcomeText.text.toString().trim()
                .ifBlank { V2TokenSavingConfig.DEFAULT_WELCOME_TEXT }
            previewButton.isEnabled = false
            previewButton.text = "正在试听…"
            welcomeSpeaker.speak(
                text,
                speakerSpinner.selectedItemPosition.coerceIn(0, 4),
                selectedGain().toFloat(),
                store.load().ttsEngineMode
            ) {
                previewButton.isEnabled = true
                previewButton.text = "试听当前音色"
            }
        }
        contextModeSpinner.onItemSelectedListener = SimpleItemSelectedListener {
            contextRoundsSpinner.isEnabled = it == 0
        }
        bind(store.load())
        findViewById<Button>(R.id.disableTokenOptimizationButton).setOnClickListener {
            val current = store.load()
            val preset = V2TokenSavingConfig.unoptimized().copy(
                localWelcomeText = current.localWelcomeText,
                offlineTtsSpeakerId = current.offlineTtsSpeakerId,
                offlineTtsGain = current.offlineTtsGain,
                ttsEngineMode = current.ttsEngineMode
            )
            store.save(preset)
            bind(preset)
            Toast.makeText(this, "已切换为不限制回答和上下文的配置", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.saveTokenSettingsButton).setOnClickListener {
            save()
            finish()
        }
        findViewById<Button>(R.id.cancelTokenSettingsButton).setOnClickListener {
            finish()
        }
    }

    private fun bind(config: V2TokenSavingConfig) {
        localWelcomeCheck.isChecked = config.localWelcomeEnabled
        welcomeText.setText(config.localWelcomeText)
        welcomeText.isEnabled = config.localWelcomeEnabled
        speakerSpinner.setSelection(config.offlineTtsSpeakerId)
        gainSeek.progress = config.offlineTtsGain - V2TokenSavingConfig.MIN_TTS_GAIN
        renderGainLabel(config.offlineTtsGain)
        sentenceSpinner.setSelection(
            V2TokenSavingConfig.SENTENCE_OPTIONS.indexOf(config.maxResponseSentences)
        )
        idleSpinner.setSelection(
            V2TokenSavingConfig.IDLE_TIMEOUT_OPTIONS.indexOf(config.idleTimeoutSeconds)
        )
        cooldownSpinner.setSelection(
            V2TokenSavingConfig.WAKE_COOLDOWN_OPTIONS.indexOf(config.wakeCooldownSeconds)
        )
        contextModeSpinner.setSelection(
            if (config.contextRetentionMode == ContextRetentionMode.RESET_AFTER_LIMIT) 0 else 1
        )
        contextRoundsSpinner.setSelection(
            V2TokenSavingConfig.CONTEXT_ROUND_OPTIONS.indexOf(config.maxContextRounds)
        )
        contextRoundsSpinner.isEnabled =
            config.contextRetentionMode == ContextRetentionMode.RESET_AFTER_LIMIT
        responseTimeoutSpinner.setSelection(
            V2TokenSavingConfig.RESPONSE_TIMEOUT_OPTIONS.indexOf(config.maxResponseSeconds)
        )
    }

    private fun save() {
        store.save(
            V2TokenSavingConfig(
                localWelcomeEnabled = localWelcomeCheck.isChecked,
                localWelcomeText = welcomeText.text.toString().trim()
                    .ifBlank { V2TokenSavingConfig.DEFAULT_WELCOME_TEXT },
                offlineTtsSpeakerId = speakerSpinner.selectedItemPosition.coerceIn(0, 4),
                offlineTtsGain = selectedGain(),
                ttsEngineMode = store.load().ttsEngineMode,
                maxResponseSentences =
                    V2TokenSavingConfig.SENTENCE_OPTIONS[sentenceSpinner.selectedItemPosition],
                idleTimeoutSeconds =
                    V2TokenSavingConfig.IDLE_TIMEOUT_OPTIONS[idleSpinner.selectedItemPosition],
                wakeCooldownSeconds =
                    V2TokenSavingConfig.WAKE_COOLDOWN_OPTIONS[cooldownSpinner.selectedItemPosition],
                contextRetentionMode = if (contextModeSpinner.selectedItemPosition == 0) {
                    ContextRetentionMode.RESET_AFTER_LIMIT
                } else {
                    ContextRetentionMode.KEEP
                },
                maxContextRounds =
                    V2TokenSavingConfig.CONTEXT_ROUND_OPTIONS[contextRoundsSpinner.selectedItemPosition],
                maxResponseSeconds =
                    V2TokenSavingConfig.RESPONSE_TIMEOUT_OPTIONS[
                        responseTimeoutSpinner.selectedItemPosition
                    ]
            )
        )
    }

    private fun setupSpinner(spinner: Spinner, labels: List<String>) {
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun selectedGain(): Int =
        (gainSeek.progress + V2TokenSavingConfig.MIN_TTS_GAIN)
            .coerceIn(V2TokenSavingConfig.MIN_TTS_GAIN, V2TokenSavingConfig.MAX_TTS_GAIN)

    private fun renderGainLabel(gain: Int) {
        gainLabel.text =
            "离线 TTS 总增益：${gain} 倍（范围 " +
            "${V2TokenSavingConfig.MIN_TTS_GAIN}–${V2TokenSavingConfig.MAX_TTS_GAIN} 倍）"
    }

    override fun onDestroy() {
        welcomeSpeaker.shutdown()
        super.onDestroy()
    }
}
