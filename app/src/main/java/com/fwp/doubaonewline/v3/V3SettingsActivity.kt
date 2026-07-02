package com.fwp.doubaonewline.v3

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fwp.doubaonewline.R
import java.util.Locale

class V3SettingsActivity : AppCompatActivity() {
    private lateinit var settings: V3Settings
    private lateinit var modelManager: V3ModelManager
    private lateinit var modelGroup: RadioGroup
    private lateinit var model15: RadioButton
    private lateinit var model7: RadioButton
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var downloadButton: Button
    private lateinit var deleteButton: Button
    private lateinit var bargeIn: CheckBox
    private lateinit var speaker: Spinner
    private lateinit var gainLabel: TextView
    private lateinit var gain: SeekBar
    private lateinit var sentences: Spinner
    private lateinit var contextRounds: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_v3_settings)
        settings = V3Settings(this)
        modelManager = V3ModelManager(this)
        modelGroup = findViewById(R.id.v3ModelRadioGroup)
        model15 = findViewById(R.id.v3Model15Radio)
        model7 = findViewById(R.id.v3Model7Radio)
        status = findViewById(R.id.v3ModelInstallStatus)
        progress = findViewById(R.id.v3ModelProgress)
        downloadButton = findViewById(R.id.v3DownloadModelButton)
        deleteButton = findViewById(R.id.v3DeleteModelButton)
        bargeIn = findViewById(R.id.v3BargeInCheck)
        speaker = findViewById(R.id.v3SpeakerSpinner)
        gainLabel = findViewById(R.id.v3GainLabel)
        gain = findViewById(R.id.v3GainSeek)
        sentences = findViewById(R.id.v3SentenceSpinner)
        contextRounds = findViewById(R.id.v3ContextSpinner)

        setupSpinner(speaker, (1..5).map { "音色 $it" })
        setupSpinner(sentences, listOf("1 句", "2 句", "3 句"))
        setupSpinner(contextRounds, (1..8).map { "$it 轮" })
        bind(settings.load())
        renderRecommendation()
        renderInstallState()

        modelGroup.setOnCheckedChangeListener { _, _ -> renderInstallState() }
        gain.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    renderGain(value + 1)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }
        )
        downloadButton.setOnClickListener { confirmAndDownload(selectedModel()) }
        deleteButton.setOnClickListener {
            val model = selectedModel()
            AlertDialog.Builder(this)
                .setTitle("删除 ${model.parameters} 模型？")
                .setMessage("另一种已下载模型和公共语音识别模型会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ ->
                    modelManager.delete(model)
                    renderInstallState()
                }
                .show()
        }
        findViewById<Button>(R.id.v3SaveSettingsButton).setOnClickListener {
            settings.save(readConfig())
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun bind(config: V3Config) {
        if (config.selectedModel == V3Model.DEEPSEEK_7B) model7.isChecked = true
        else model15.isChecked = true
        bargeIn.isChecked = config.allowBargeIn
        speaker.setSelection(config.speakerId)
        gain.progress = config.ttsGain - 1
        renderGain(config.ttsGain)
        sentences.setSelection(config.maxResponseSentences - 1)
        contextRounds.setSelection(config.contextRounds - 1)
    }

    private fun readConfig() = V3Config(
        selectedModel = selectedModel(),
        allowBargeIn = bargeIn.isChecked,
        speakerId = speaker.selectedItemPosition.coerceIn(0, 4),
        ttsGain = (gain.progress + 1).coerceIn(1, 30),
        maxResponseSentences = sentences.selectedItemPosition + 1,
        contextRounds = contextRounds.selectedItemPosition + 1
    )

    private fun selectedModel() =
        if (model7.isChecked) V3Model.DEEPSEEK_7B else V3Model.DEEPSEEK_1_5B

    private fun renderRecommendation() {
        val recommendation = settings.recommendation()
        findViewById<TextView>(R.id.v3RecommendationText).text =
            "系统推荐：${recommendation.recommendedModel.parameters}\n" +
            "总内存 ${gb(recommendation.totalMemoryBytes)}GB，当前可用 " +
            "${gb(recommendation.availableMemoryBytes)}GB，可用存储 " +
            "${gb(recommendation.freeStorageBytes)}GB。\n${recommendation.reason}"
    }

    private fun renderInstallState() {
        val selected = selectedModel()
        val selectedReady = modelManager.isInstalled(selected)
        val other = if (selected == V3Model.DEEPSEEK_1_5B) {
            V3Model.DEEPSEEK_7B
        } else {
            V3Model.DEEPSEEK_1_5B
        }
        val selectedStatus = if (selectedReady) "已安装" else "未安装"
        val otherStatus = if (modelManager.isInstalled(other)) "已安装" else "未安装"
        status.text =
            "${selected.parameters}：$selectedStatus；${other.parameters}：$otherStatus"
        progress.visibility = View.GONE
        downloadButton.isEnabled = !selectedReady
        deleteButton.isEnabled = selectedReady
    }

    private fun confirmAndDownload(model: V3Model) {
        val recommendation = settings.recommendation()
        if (model == V3Model.DEEPSEEK_7B &&
            recommendation.recommendedModel != V3Model.DEEPSEEK_7B
        ) {
            AlertDialog.Builder(this)
                .setTitle("当前设备推荐 1.5B")
                .setMessage("仍可下载 7B，但可能卡顿、发热或被系统终止。最终由你决定。")
                .setNegativeButton("取消", null)
                .setPositiveButton("仍然下载") { _, _ -> startDownload(model) }
                .show()
        } else {
            startDownload(model)
        }
    }

    private fun startDownload(model: V3Model) {
        downloadButton.isEnabled = false
        deleteButton.isEnabled = false
        modelManager.download(model) { state ->
            runOnUiThread {
                when (state) {
                    V3ModelManager.State.Idle -> Unit
                    is V3ModelManager.State.Downloading -> {
                        status.text = "正在下载${state.label}：${state.percent}%"
                        progress.visibility = View.VISIBLE
                        progress.progress = state.percent
                    }
                    is V3ModelManager.State.Verifying -> {
                        status.text = "正在校验${state.label}"
                        progress.visibility = View.VISIBLE
                    }
                    is V3ModelManager.State.Ready -> {
                        Toast.makeText(this, "${state.model.parameters} 模型已就绪", Toast.LENGTH_LONG)
                            .show()
                        renderInstallState()
                    }
                    is V3ModelManager.State.Failed -> {
                        status.text = state.message
                        Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                        renderInstallState()
                    }
                }
            }
        }
    }

    private fun renderGain(value: Int) {
        gainLabel.text = "TTS 总增益：$value 倍（范围 1–30 倍）"
    }

    private fun setupSpinner(spinner: Spinner, labels: List<String>) {
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun gb(bytes: Long) = String.format(Locale.US, "%.1f", bytes / 1024.0 / 1024 / 1024)

    override fun onDestroy() {
        modelManager.close()
        super.onDestroy()
    }
}
