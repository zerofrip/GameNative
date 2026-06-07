package app.gamenative.rendering

/**
 * High-level Wine / Mesa rendering backend selection for environment injection.
 * Distinct from [com.winlator.container.ContainerData.renderer] (Wine D3D registry string).
 */
enum class RendererMode(val wireValue: String) {
    WINED3D("wined3d"),
    DXVK("dxvk"),
    ZINK("zink"),
    ;

    companion object {
        fun fromStringOrNull(raw: String?): RendererMode? {
            if (raw.isNullOrBlank()) return null
            val token = raw.trim()
            return entries.firstOrNull {
                it.wireValue.equals(token, ignoreCase = true) ||
                    it.name.equals(token, ignoreCase = true)
            }
        }
    }
}
