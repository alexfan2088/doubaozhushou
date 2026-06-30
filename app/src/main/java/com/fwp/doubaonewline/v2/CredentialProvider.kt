package com.fwp.doubaonewline.v2

import com.fwp.doubaonewline.BuildConfig

/**
 * Supplies short-lived or P0 test credentials without coupling the voice client
 * to BuildConfig, SharedPreferences, or a future credential service.
 */
fun interface CredentialProvider {
    fun load(): Result<V2Credentials>
}

data class V2Credentials(
    val appId: String,
    val appKey: String,
    val accessToken: String,
    val resourceId: String = DIALOG_RESOURCE_ID,
    val modelId: String? = null,
    val voiceId: String? = null
) {
    fun validate(): Result<V2Credentials> = runCatching {
        require(appId.isNotBlank()) { "AppID is missing" }
        require(appKey.isNotBlank()) { "AppKey/Secret Key is missing" }
        require(accessToken.isNotBlank()) { "Access Token is missing" }
        require(resourceId.isNotBlank()) { "Resource ID is missing" }
        this
    }

    fun safeDescription(): String =
        "appId=${appId.redacted()}, resourceId=$resourceId, modelId=${modelId.orEmpty()}, " +
            "voiceId=${voiceId.orEmpty()}"

    private fun String.redacted(): String = when {
        length <= 4 -> "****"
        else -> take(2) + "****" + takeLast(2)
    }

    companion object {
        const val DIALOG_RESOURCE_ID = "volc.speech.dialog"
    }
}

object MissingCredentialProvider : CredentialProvider {
    override fun load(): Result<V2Credentials> =
        Result.failure(IllegalStateException("P0 realtime voice credentials are not configured"))
}

/**
 * P0-only credentials injected from local.properties or matching environment
 * variables. Production builds must replace this with short-lived credentials.
 */
object LocalTestCredentialProvider : CredentialProvider {
    override fun load(): Result<V2Credentials> =
        V2Credentials(
            appId = BuildConfig.V2_APP_ID,
            appKey = BuildConfig.V2_APP_KEY,
            accessToken = BuildConfig.V2_ACCESS_TOKEN,
            resourceId = BuildConfig.V2_RESOURCE_ID
        ).validate()
}
