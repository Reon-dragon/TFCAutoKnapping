# Project Instructions — TFCAutoKnapping

The following rules, conventions, and lessons were accumulated during development of this project. Follow them when working on this codebase.

## Hard Constraints
- Must not use JEI as a dependency for recipe selection
- Recipe selection must be done via a custom visual interface
- All knapping operations must use the 5x5 grid pattern from TFC's recipe manager
- No keybindings for knapping; auto-knapping starts by clicking recipe buttons
- Recipe selection panel must automatically appear when opening the knapping interface
- Recipe selection panel must use a simple uncategorized 6x6 grid layout without category sidebar
- mod.toml must include displayTest="IGNORE_ALL_VERSION" to prevent Forge from checking server-client mod consistency
- Release notes must contain only plain text, no images
- Must use NeoForge 1.21.1 instead of Forge for 1.21 version
- Metadata file must be neoforge.mods.toml instead of mods.toml
- Network packets must use NeoForge PacketDistributor.sendToServer() instead of Forge PacketDistributor
- Client events (ClientTickEvent, MouseScrolled) must use NeoForge event types (e.g., ClientTickEvent.Pre, MouseScrolled.Pre)
- Rendering highlights must use GuiGraphics.fill() for rectangles instead of BufferBuilder
- Gradle must use version 8.9 with moddev plugin 2.0.107 instead of Forge plugin
- Recipe references must use RecipeHolder<KnappingRecipe> instead of KnappingRecipe
- Recipe IDs must be accessed via holder.id() instead of recipe.getId()
- TFC knapping type package must be net.dries007.tfc.util.data instead of net.dries007.tfc.util
- Event bus registration must omit the bus parameter as it is auto-detected in NeoForge
- main branch must only publish 1.20 version; 1.21 content must be isolated in 1.21 branch

## Engineering Conventions
- Recipe data is obtained from TFC's RecipeManager using KnappingType filtering
- Auto-knapping logic compares current pattern with target recipe pattern to determine cells to click
- Auto-knapping uses TFC's ScreenButtonPacket to directly send buttonId (x + 5 * y) to server
- Recipe selection panel uses compact 134x140px icons, automatically positioned at knapping interface right
- Rock variants are merged into a single generic template using extractBaseName() to remove TFC suffixes (_igneous_extrusive, _igneous_intrusive, _metamorphic, _sedimentary)
- GUI position fields (leftPos/topPos) use 5-layer progressive reflection strategy: cached field read → class hierarchy name search (Mojmap/SRG/MCP) → getter methods → value reverse lookup → fallback calculation
- GUI dimensions (imageWidth/imageHeight) are dynamically obtained via reflection with multi-name support
- Recipe pattern value calculation must account for 'default_on' property; out-of-bounds positions return 'defaultOn' value instead of 0

## Lessons Learned
- Direct 5x5 grid reading is insufficient for recipe data; must use TFC's配方管理器
- KeyMapping.consumeClick() fails when GUI is open; use ScreenEvent.KeyPressed.Pre for GUI key handling
- Server response timeout (20 ticks) prevents auto-knapping from getting stuck waiting for pattern updates
- KnappingButton becomes invisible after first click, making mouseClicked() simulation unreliable for auto-knapping
- Gradle incremental build cache can cause class loading failures (NoClassDefFoundError) even when classes exist in JAR; use clean build to resolve
- Reflection for GUI fields (leftPos/topPos) must include failure flags to prevent repeated attempts and log spamming
- Verification timing must use System.currentTimeMillis() instead of frame count to ensure 1000ms server response window
- KnappingPattern index calculation must use x + y * width (actual recipe width) instead of fixed 5 width (y * 5 + x) to avoid incorrect pattern reading
- Sending mod list payload immediately on LoggingIn event blocks server login plugin authentication, causing players to remain in invincible 'logging in' state; must delay by 5 seconds to allow login plugin completion
