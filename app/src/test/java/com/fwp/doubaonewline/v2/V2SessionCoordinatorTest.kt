package com.fwp.doubaonewline.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V2SessionCoordinatorTest {

    @Test
    fun missingCredentialsFailWithoutConnecting() {
        val client = FakeClient()
        val coordinator = V2SessionCoordinator(MissingCredentialProvider, client)

        val result = coordinator.start(::config)

        assertTrue(result.isFailure)
        assertEquals(RealtimeVoiceState.ERROR, coordinator.state)
        assertEquals(0, client.connectCalls)
    }

    @Test
    fun validCredentialsStartAndStopOneSession() {
        val client = FakeClient()
        val coordinator = V2SessionCoordinator(
            CredentialProvider { Result.success(credentials()) },
            client
        )

        assertTrue(coordinator.start(::config).isSuccess)
        assertEquals(RealtimeVoiceState.LISTENING, coordinator.state)
        assertEquals(1, client.connectCalls)
        assertTrue(coordinator.stop(DisconnectReason.USER_REQUEST).isSuccess)
        assertEquals(RealtimeVoiceState.IDLE, coordinator.state)
        assertEquals(1, client.disconnectCalls)
    }

    @Test
    fun duplicateStartIsRejected() {
        val client = FakeClient()
        val coordinator = V2SessionCoordinator(
            CredentialProvider { Result.success(credentials()) },
            client
        )

        assertTrue(coordinator.start(::config).isSuccess)
        assertTrue(coordinator.start(::config).isFailure)
        assertEquals(1, client.connectCalls)
    }

    private fun credentials() = V2Credentials(
        appId = "test-app",
        appKey = "test-key",
        accessToken = "test-token",
        resourceId = V2Credentials.DIALOG_RESOURCE_ID,
        modelId = "test-model",
        voiceId = "test-voice"
    )

    private fun config(credentials: V2Credentials) = RealtimeVoiceConfig(
        credentials = credentials,
        userId = "p0-user",
        systemPrompt = "你是一个友好的语音助手",
        welcomeText = "豆包豆包，你好啊",
        inputFormat = AudioFormatSpec(16000, 1, 16, "pcm"),
        outputFormat = AudioFormatSpec(24000, 1, 16, "pcm")
    )

    private class FakeClient : RealtimeVoiceClient {
        override var state = RealtimeVoiceState.IDLE
        var connectCalls = 0
        var disconnectCalls = 0

        override fun setListener(listener: RealtimeVoiceListener?) = Unit

        override fun connect(config: RealtimeVoiceConfig): Result<Unit> {
            connectCalls++
            state = RealtimeVoiceState.LISTENING
            return Result.success(Unit)
        }

        override fun sendAudio(frame: AudioFrame): Result<Unit> = Result.success(Unit)

        override fun interrupt(): Result<Unit> = Result.success(Unit)

        override fun disconnect(reason: DisconnectReason): Result<Unit> {
            disconnectCalls++
            state = RealtimeVoiceState.IDLE
            return Result.success(Unit)
        }
    }
}
