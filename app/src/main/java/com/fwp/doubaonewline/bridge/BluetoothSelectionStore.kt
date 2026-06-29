package com.fwp.doubaonewline.bridge

import android.content.Context
import org.json.JSONObject

class BluetoothSelectionStore(private val context: Context) {

    data class Record(
        val key: String,
        val displayLabel: String
    )

    fun save(record: Record) {
        val payload = JSONObject()
            .put("key", record.key)
            .put("displayLabel", record.displayLabel)
            .toString()

        context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).use { stream ->
            stream.write(payload.toByteArray(Charsets.UTF_8))
        }
    }

    fun load(): Record? {
        val payload = runCatching {
            context.openFileInput(FILE_NAME).use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull() ?: return null

        return runCatching {
            val json = JSONObject(payload)
            Record(
                key = json.optString("key").orEmpty(),
                displayLabel = json.optString("displayLabel").orEmpty()
            )
        }.getOrNull()?.takeIf { it.key.isNotBlank() }
    }

    companion object {
        private const val FILE_NAME = "bluetooth_selection.json"
    }
}
