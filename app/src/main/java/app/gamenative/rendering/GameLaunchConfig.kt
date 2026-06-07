package app.gamenative.rendering

import android.content.Context
import com.winlator.container.Container
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Optional per-game launch overrides read from the app sandbox:
 * `files/container/game_configs/<sanitized_app_id>.json`
 */
data class GameLaunchConfig(
    val gameId: String? = null,
    val rendererMode: RendererMode? = null,
    val fallbackRenderer: RendererMode? = null,
    /** e.g. "900p", "1080p", or explicit "1600x900" */
    val resolution: String? = null,
    val fsr: Boolean? = null,
    /** When true with renderer [RendererMode.ZINK], missing custom Mesa under [customMesaLibRoot] triggers WineD3D fallback. */
    val requireCustomMesa: Boolean = false,
) {
    companion object {
        private const val TAG = "GameLaunchConfig"

        fun customMesaLibRoot(context: Context): File =
            File(context.filesDir, "container/lib")

        fun configFileForApp(context: Context, appId: String): File {
            val safe = appId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            return File(File(context.filesDir, "container/game_configs"), "$safe.json")
        }

        fun load(context: Context, appId: String): GameLaunchConfig? {
            val f = configFileForApp(context, appId)
            if (!f.isFile) return null
            return try {
                parse(JSONObject(f.readText()))
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to read game launch config: ${f.path}")
                null
            }
        }

        fun parse(json: JSONObject): GameLaunchConfig {
            val rendererRaw = json.optString("renderer", "").trim()
            val fallbackRaw = when {
                json.has("fallbackRenderer") -> json.optString("fallbackRenderer", "").trim()
                else -> json.optString("fallback_renderer", "").trim()
            }
            val gameIdRaw = when {
                json.has("gameId") -> json.optString("gameId", "").trim()
                else -> json.optString("game_id", "").trim()
            }
            return GameLaunchConfig(
                gameId = gameIdRaw.takeIf { it.isNotBlank() },
                rendererMode = RendererMode.fromStringOrNull(rendererRaw),
                fallbackRenderer = RendererMode.fromStringOrNull(fallbackRaw),
                resolution = json.optString("resolution", "").trim().takeIf { it.isNotEmpty() },
                fsr = if (json.has("fsr")) json.optBoolean("fsr") else null,
                requireCustomMesa = json.optBoolean("require_custom_mesa", false),
            )
        }

        /**
         * Maps common presets to WIDTHxHEIGHT for [Container.setScreenSize].
         */
        fun resolveResolutionToken(token: String): String? {
            val t = token.trim().lowercase()
            if (t.matches(Regex("\\d+x\\d+"))) return t
            return when (t) {
                "720p" -> "1280x720"
                "900p" -> "1600x900"
                "1080p" -> "1920x1080"
                "1440p" -> "2560x1440"
                "2160p", "4k" -> "3840x2160"
                else -> null
            }
        }
    }
}
