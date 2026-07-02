package com.fwp.doubaonewline.bridge

import android.content.Context
import org.json.JSONObject

class BluetoothSelectionStore(
    private val context: Context,
    private val fileName: String = FILE_NAME
) {

    data class Record(
        val key: String,
        val displayLabel: String
    )

    fun save(record: Record) {
        val payload = JSONObject()
            .put("key", record.key)
            .put("displayLabel", record.displayLabel)
            .toString()

        context.openFileOutput(fileName, Context.MODE_PRIVATE).use { stream ->
            stream.write(payload.toByteArray(Charsets.UTF_8))
        }
    }

    fun load(): Record? {
        val payload = runCatching {
            context.openFileInput(fileName).use { stream ->
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
