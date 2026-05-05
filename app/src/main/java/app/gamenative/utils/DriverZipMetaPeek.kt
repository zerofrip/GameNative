package app.gamenative.utils

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.util.zip.ZipInputStream

/**
 * Reads root [meta.json] from a driver ZIP without fully extracting it.
 */
object DriverZipMetaPeek {

    fun peekDriverStack(context: Context, uri: Uri): String? {
        return context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && (name == "meta.json" || name.endsWith("/meta.json"))) {
                        val text = zis.readBytes().decodeToString()
                        val jo = JSONObject(text)
                        return jo.optString("driverStack", "").trim().takeIf { it.isNotEmpty() }
                    }
                    entry = zis.nextEntry
                }
                null
            }
        }
    }
}
