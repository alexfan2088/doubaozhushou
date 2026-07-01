package com.fwp.doubaonewline.v2

/**
 * Provider-neutral boundary for the official Android SDK or a WebSocket fallback.
 * No V1 class depends on this interface.
 */
interface RealtimeVoiceClient {
    val state: RealtimeVoiceState

    fun setListener(listener: RealtimeVoiceListener?)
    fun connect(config: RealtimeVoiceConfig): Result<Unit>
    fun sendAudio(frame: AudioFrame): Result<Unit>
    fun interrupt(): Result<Unit>
    fun disconnect(reason: DisconnectReason): Result<Unit>
}

data class RealtimeVoiceConfig(
    val credentials: V2Credentials,
    val userId: String,
    val systemPrompt: String,
    val welcomeText: String,
    val inputFormat: AudioFormatSpec,
    val outputFormat: AudioFormatSpec
)

data class AudioFormatSpec(
    val sampleRateHz: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val encoding: String
) {
    fun validate(): Result<AudioFormatSpec> = runCatching {
        require(sampleRateHz > 0) { "Sample rate must be positive" }
        require(channelCount in 1..2) { "Only mono or stereo audio is supported" }
        require(bitsPerSample in setOf(16, 24, 32)) { "Unsupported PCM bit depth" }
        require(encoding.isNotBlank()) { "Audio encoding is missing" }
        this
    }
}

data class AudioFrame(
    val data: ByteArray,
    val sequence: Long,
    val capturedAtNanos: Long
)

enum class RealtimeVoiceState {
    IDLE,
    CONNECTING,
    LISTENING,
    USER_SPEAKING,
    WAITING_RESPONSE,
    MODEL_SPEAKING,
    DISCONNECTING,
    ERROR
}

enum class DisconnectReason {
    USER_REQUEST,
    AUDIO_DEVICE_LOST,
    NETWORK_ERROR,
    CREDENTIAL_ERROR,
    SERVICE_ERROR,
    APP_SHUTDOWN
}

sealed interface RealtimeVoiceEvent {
    data object Connected : RealtimeVoiceEvent
    data object UserSpeechStarted : RealtimeVoiceEvent
    data object UserSpeechEnded : RealtimeVoiceEvent
    data object ModelResponseStarted : RealtimeVoiceEvent
    data class ModelAudio(val frame: AudioFrame) : RealtimeVoiceEvent
    data object ModelResponseEnded : RealtimeVoiceEvent
    data class Usage(
        val inputUnits: Long,
        val outputUnits: Long,
        val inputTextUnits: Long = 0,
        val inputAudioUnits: Long = 0,
        val outputTextUnits: Long = 0,
        val outputAudioUnits: Long = 0
    ) : RealtimeVoiceEvent
    data class Failure(val code: String, val message: String, val retryable: Boolean) :
        RealtimeVoiceEvent
}

fun interface RealtimeVoiceListener {
    fun onEvent(event: RealtimeVoiceEvent)
}
