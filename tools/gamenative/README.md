# GameNative tooling

Small helpers and docs for packaging Mesa/content-related artifacts used by the GameNative app.

- Example per-game JSON: `app/src/main/assets/examples/game_launch_config.custom_mesa.example.json`
- Kotlin entry points: `app/src/main/java/app/gamenative/rendering/MesaDeployment.kt` (tier1 content vs tier2 `container/lib` custom Mesa)

For building Mesa Android drivers and zipping bundles, see mesa-mirror `scripts/gamenative/`. Manifest driver entries may set `"driverStack": "panvk"` so installs use `PanVkDriverManager` (VK_ICD/LD_LIBRARY_PATH) instead of Adrenotools.
