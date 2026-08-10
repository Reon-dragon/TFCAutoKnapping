package com.eternal130.tfcak;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.ModLoadingContext;
import org.slf4j.Logger;

/**
 * TFCAutoKnapping 主模组类
 *
 * 仿照 TFCAutoForging 的架构，实现 TerraFirmaCraft 打制系统的自动化。
 * 核心原理：在 KnappingScreen 渲染事件中，通过反射获取当前配方图案，
 * 计算需要点击的格子，然后模拟鼠标点击完成自动打制。
 *
 * 交互方式（无按键绑定）：
 * 1. 打开打制界面 → 自动弹出配方选择面板（分类侧边栏 + 产物图标网格）
 * 2. 点击分类 → 切换到该分类的配方列表
 * 3. 点击配方图标 → 关闭面板，开始自动打制
 * 4. 点击面板外或 X 按钮 → 关闭面板，手动打制
 * 5. 打制完成 → 自动重新弹出面板
 */
@Mod(TFCAutoKnapping.MODID)
public class TFCAutoKnapping
{
    public static final String MODID = "tfcak";
    public static final Logger LOGGER = LogUtils.getLogger();

    // 运行时状态字段
    public static int timer = 0;                           // 冷却计时器
    public static int lastPatternData = -1;                 // 上次记录的 pattern 位域值
    public static boolean isWaitingForServer = false;       // 是否正在等待服务器响应
    public static int serverWaitTicks = 0;                  // 等待服务器的 tick 计数（超时检测）

    // 配方选择状态
    public static int recipeCount = 0;                      // 可用配方数量

    public TFCAutoKnapping()
    {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 注册客户端设置事件
        modEventBus.addListener(this::setup);

        // 注册 Forge 事件总线
        MinecraftForge.EVENT_BUS.register(this);

        // 注册配置文件
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ConfigFile.CONFIG);
    }

    private void setup(final FMLClientSetupEvent event)
    {
        LOGGER.info("TFCAutoKnapping initialized - click recipe to start knapping");
    }
}
