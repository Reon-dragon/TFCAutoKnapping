package com.eternal130.tfcak;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;

/**
 * TFCAutoKnapping 主模组类 (NeoForge 1.21)
 *
 * 仿照 TFCAutoForging 的架构，实现 TerraFirmaCraft 打制系统的自动化。
 * 核心原理：在 KnappingScreen 渲染事件中，通过反射获取当前配方图案，
 * 计算需要点击的格子，然后通过 TFC 的 ScreenButtonPacket 完成自动打制。
 *
 * 交互方式（无按键绑定）：
 * 1. 打开打制界面 → 自动弹出配方选择面板（6x6 产物图标网格）
 * 2. 点击配方图标 → 关闭面板，开始自动打制
 * 3. 点击面板外或 X 按钮 → 关闭面板，手动打制
 * 4. 打制完成 → 自动重新弹出面板
 */
@Mod(TFCAutoKnapping.MODID)
public class TFCAutoKnapping
{
    public static final String MODID = "tfcak";
    public static final Logger LOGGER = LogUtils.getLogger();

    // 运行时状态字段
    public static int timer = 0;
    public static int lastPatternData = -1;
    public static boolean isWaitingForServer = false;
    public static int serverWaitTicks = 0;

    // 配方选择状态
    public static int recipeCount = 0;

    public TFCAutoKnapping(IEventBus modEventBus, net.neoforged.fml.ModContainer modContainer)
    {
        modEventBus.addListener(this::setup);
        modContainer.registerConfig(ModConfig.Type.CLIENT, ConfigFile.CONFIG);
    }

    private void setup(final FMLClientSetupEvent event)
    {
        LOGGER.info("TFCAutoKnapping initialized (NeoForge 1.21) - click recipe to start knapping");
    }
}
