package com.eternal130.tfcak;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组配置文件 (NeoForge 1.21)
 *
 * 所有配置项均为客户端侧（CLIENT），通过编辑配置文件修改。
 * 自动打制由配方选择面板触发，无需按键开关。
 */
public class ConfigFile
{
    public static final ModConfigSpec.BooleanValue enableKnappingTip;
    public static final ModConfigSpec.IntValue autoKnappingCooldown;
    public static final ModConfigSpec.ConfigValue<Integer> highlightColor;
    public static final ModConfigSpec.BooleanValue debugMode;

    public static ModConfigSpec CONFIG;

    static
    {
        ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
        BUILDER.comment("TFCAutoKnapping General Settings").push("general");

        enableKnappingTip = BUILDER
            .comment("Highlight the next cell to click during auto-knapping")
            .define("enableKnappingTip", true);

        autoKnappingCooldown = BUILDER
            .comment("Cooldown in ticks between each auto-knapping click (1-200)")
            .defineInRange("autoKnappingCooldown", 2, 1, 200);

        highlightColor = BUILDER
            .comment("Highlight box color (ARGB hex, e.g. 0x8000FF00 for semi-transparent green)")
            .define("highlightColor", 0x8000FF00);

        debugMode = BUILDER
            .comment("Enable debug mode - logs detailed knapping info and writes failure reports to tfcak_debug.log")
            .define("debugMode", false);

        BUILDER.pop();
        CONFIG = BUILDER.build();
    }
}
