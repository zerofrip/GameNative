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
            return entries.firstOrNull { it.wireValue.equals(raw.trim(), ignoreCase = true) }
        }
    }
}
