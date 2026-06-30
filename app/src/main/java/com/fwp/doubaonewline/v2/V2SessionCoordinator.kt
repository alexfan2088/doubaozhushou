package com.fwp.doubaonewline.v2

/**
 * Small P0 state machine. Audio capture/playback and the provider SDK remain
 * outside this class so they can be tested and replaced independently.
 */
class V2SessionCoordinator(
    private val credentialProvider: CredentialProvider,
    private val client: RealtimeVoiceClient
) {
    var state: RealtimeVoiceState = RealtimeVoiceState.IDLE
        private set

    @Synchronized
    fun start(configFactory: (V2Credentials) -> RealtimeVoiceConfig): Result<Unit> {
        if (state != RealtimeVoiceState.IDLE && state != RealtimeVoiceState.ERROR) {
            return Result.failure(IllegalStateException("A V2 voice session is already active"))
        }

        state = RealtimeVoiceState.CONNECTING
        val credentials = credentialProvider.load()
            .getOrElse {
                state = RealtimeVoiceState.ERROR
                return Result.failure(it)
            }
            .validate()
            .getOrElse {
                state = RealtimeVoiceState.ERROR
                return Result.failure(it)
            }
        val config = runCatching { configFactory(credentials) }
            .getOrElse {
                state = RealtimeVoiceState.ERROR
                return Result.failure(it)
            }

        config.inputFormat.validate().getOrElse {
            state = RealtimeVoiceState.ERROR
            return Result.failure(it)
        }
        config.outputFormat.validate().getOrElse {
            state = RealtimeVoiceState.ERROR
            return Result.failure(it)
        }

        return client.connect(config)
            .onSuccess { state = RealtimeVoiceState.LISTENING }
            .onFailure { state = RealtimeVoiceState.ERROR }
    }

    @Synchronized
    fun stop(reason: DisconnectReason): Result<Unit> {
        if (state == RealtimeVoiceState.IDLE) return Result.success(Unit)
        state = RealtimeVoiceState.DISCONNECTING
        return client.disconnect(reason)
            .onSuccess { state = RealtimeVoiceState.IDLE }
            .onFailure { state = RealtimeVoiceState.ERROR }
    }
}
