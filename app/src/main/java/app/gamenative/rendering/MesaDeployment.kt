package app.gamenative.rendering

import android.content.Context
import com.winlator.contents.ContentProfile
import java.io.File

/**
 * Mesa is resolved in two tiers for Zink and related GL/Vulkan paths:
 *
 * **Tier 1 — [TIER_CONTENT] (ContentsManager / content packs):** Drivers such as PanVK shipped as
 * installable [ContentProfile] archives. After install, [ContentsManager] records trusted paths
 * (e.g. `PANVK_TRUST_FILES`) under the imagefs layout.
 *
 * **Tier 2 — [TIER_CUSTOM] ([GameLaunchConfig]):** A local tree under
 * [GameLaunchConfig.customMesaLibRoot] (same path as [customMesaRoot]). Per-game JSON may set
 * [GameLaunchConfig.requireCustomMesa] so the launcher prefers this bundle over relying on
 * content packs alone.
 */
object MesaDeployment {
    const val TIER_CONTENT = "tier1_content"
    const val TIER_CUSTOM = "tier2_custom"

    fun customMesaRoot(context: Context): File = GameLaunchConfig.customMesaLibRoot(context)

    fun panvkContentType(): ContentProfile.ContentType = ContentProfile.ContentType.CONTENT_TYPE_PANVK
}
