package com.eternal130.tfcak;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.dries007.tfc.client.screen.KnappingScreen;
import net.dries007.tfc.common.container.KnappingContainer;
import net.dries007.tfc.common.recipes.KnappingRecipe;
import net.dries007.tfc.util.KnappingPattern;
import net.dries007.tfc.util.KnappingType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLPaths;
import org.joml.Matrix4f;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.eternal130.tfcak.ConfigFile.*;

/**
 * 打制事件处理器 — 模组核心逻辑
 *
 * 事件清单：
 * 1. ScreenEvent.Render.Post           — 渲染：面板 + 高亮提示 + 自动点击
 * 2. TickEvent.ClientTickEvent         — 计时器递减 + 服务器等待超时
 * 3. ScreenEvent.MouseButtonPressed.Pre — 面板点击拦截（分类切换、配方选择、关闭）
 * 4. ScreenEvent.MouseScrolled         — 面板滚轮（分类列表 + 配方网格）
 * 5. ScreenEvent.Closing               — 状态重置
 *
 * 交互流程：
 *   打开界面 → 自动弹出配方选择面板 → 点击配方图标 → 开始自动打制 → 完成后重新弹出面板
 */
@Mod.EventBusSubscriber(modid = TFCAutoKnapping.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class KnappingEvent
{
    /** 缓存的配方列表（避免每帧查询） */
    private static List<KnappingRecipe> cachedRecipes = null;
    /** 缓存的打制类型（变化时重新查询） */
    private static KnappingType cachedKnappingType = null;
    /** 面板是否已初始化（每次打开界面时重置） */
    private static boolean panelInitialized = false;
    /** 上次计算的需要点击格子数（用于检测打制完成） */
    private static int lastCellsToClickCount = 0;
    /** 调试计数器（限制日志频率） */
    private static int debugCounter = 0;

    // ===== 调试状态 =====
    /** 本次自动打制的总点击次数 */
    private static int totalClicksThisSession = 0;
    /** 本次自动打制的失败次数 */
    private static int failureCountThisSession = 0;
    /** 上次点击的格子（用于调试日志） */
    private static int lastClickedCell = -1;
    /** 上次点击时间戳（用于调试日志） */
    private static String lastClickTime = null;
    /** 调试用：上一次的目标配方（用于超时时的日志记录） */
    private static KnappingRecipe targetRecipeForDebug = null;

    // ==================== 渲染事件：核心入口 ====================

    @SubscribeEvent
    public static void onKnappingRender(ScreenEvent.Render.Post event)
    {
        if (!(event.getScreen() instanceof KnappingScreen screen))
        {
            return;
        }

        // 面板初始化后，若面板和自动打制都未激活，不做处理（手动模式）
        // 面板未初始化时（首次打开），需要继续执行以自动弹出面板
        if (panelInitialized && !RecipeSelector.selectionMode && !RecipeSelector.autoKnappingActive)
        {
            return;
        }

        try
        {
            // 1. 获取容器和当前状态
            KnappingContainer container = screen.getMenu();
            KnappingPattern currentPattern = container.getPattern();
            KnappingType knappingType = container.getKnappingType();
            Level level = Minecraft.getInstance().level;

            if (currentPattern == null || knappingType == null || level == null)
            {
                return;
            }

            // 2. 服务器响应检测（带超时）
            int currentData = KnappingUtil.getPatternData(currentPattern);
            if (TFCAutoKnapping.isWaitingForServer)
            {
                if (KnappingUtil.hasPatternChanged(currentPattern, TFCAutoKnapping.lastPatternData))
                {
                    TFCAutoKnapping.isWaitingForServer = false;
                    TFCAutoKnapping.serverWaitTicks = 0;
                }
                else
                {
                    TFCAutoKnapping.serverWaitTicks++;
                    if (TFCAutoKnapping.serverWaitTicks > 20)
                    {
                        TFCAutoKnapping.LOGGER.warn("Server response timeout, forcing continue (waited {} ticks)",
                            TFCAutoKnapping.serverWaitTicks);
                        if (debugMode.get())
                        {
                            writeFailureLog("SERVER_TIMEOUT", targetRecipeForDebug,
                                currentPattern, null, null,
                                "Server did not respond within 20 ticks after clicking cell " + lastClickedCell);
                        }
                        TFCAutoKnapping.isWaitingForServer = false;
                        TFCAutoKnapping.serverWaitTicks = 0;
                    }
                }
            }

            // 3. 查找可用配方（带缓存）
            boolean typeChanged = (cachedKnappingType != knappingType);
            if (cachedRecipes == null || typeChanged)
            {
                cachedRecipes = KnappingUtil.findRecipes(level, knappingType);
                cachedKnappingType = knappingType;
                TFCAutoKnapping.recipeCount = cachedRecipes.size();
                RecipeSelector.categorizeRecipes(cachedRecipes);
                panelInitialized = false; // 新配方集，重置面板状态
                TFCAutoKnapping.LOGGER.info("Loaded {} recipes for knapping type: {}",
                    cachedRecipes.size(), knappingType);
            }

            if (cachedRecipes.isEmpty())
            {
                if (debugCounter++ % 100 == 0)
                {
                    TFCAutoKnapping.LOGGER.debug("No recipes found for current knapping type");
                }
                return;
            }

            // 4. 自动弹出配方选择面板（每次打开界面时）
            if (!panelInitialized)
            {
                RecipeSelector.selectionMode = true;
                RecipeSelector.autoKnappingActive = false;
                panelInitialized = true;
            }

            // 5. 面板开启时渲染面板，暂停自动打制
            if (RecipeSelector.selectionMode)
            {
                RecipeSelector.render(
                    event.getGuiGraphics(), screen, cachedRecipes,
                    event.getMouseX(), event.getMouseY()
                );
                return;
            }

            // 6. 自动打制逻辑
            if (!RecipeSelector.autoKnappingActive)
            {
                return;
            }

            // 7. 获取选中的目标配方
            KnappingRecipe targetRecipe = RecipeSelector.getSelectedRecipe(cachedRecipes);
            targetRecipeForDebug = targetRecipe; // 保存供超时日志使用
            if (targetRecipe == null)
            {
                TFCAutoKnapping.LOGGER.warn("No recipe selected, stopping auto-knapping");
                if (debugMode.get())
                {
                    writeFailureLog("NO_RECIPE_SELECTED", null,
                        currentPattern, null, null,
                        "RecipeSelector.selectedRecipeId = " + RecipeSelector.selectedRecipeId);
                }
                RecipeSelector.autoKnappingActive = false;
                RecipeSelector.selectionMode = true;
                lastCellsToClickCount = 0;
                return;
            }

            KnappingPattern recipePattern = KnappingUtil.getRecipePattern(targetRecipe);
            if (recipePattern == null)
            {
                TFCAutoKnapping.LOGGER.warn("Failed to get pattern for recipe: {}", targetRecipe.getId());
                if (debugMode.get())
                {
                    writeFailureLog("PATTERN_NULL", targetRecipe,
                        currentPattern, null, null,
                        "getRecipePattern() returned null");
                }
                return;
            }

            // 8. 检查配方是否仍可完成（防止手动误触破坏配方）
            if (!KnappingUtil.isAchievable(recipePattern, currentPattern))
            {
                TFCAutoKnapping.LOGGER.info("Recipe no longer achievable, stopping: {}", targetRecipe.getId());
                if (debugMode.get())
                {
                    writeFailureLog("RECIPE_NOT_ACHIEVABLE", targetRecipe,
                        currentPattern, recipePattern, null,
                        "Pattern was ruined - recipe requires cells that have already been clicked");
                }
                RecipeSelector.autoKnappingActive = false;
                RecipeSelector.selectedRecipeId = null;
                RecipeSelector.selectionMode = true;
                lastCellsToClickCount = 0;
                Player player = Minecraft.getInstance().player;
                if (player != null)
                {
                    player.sendSystemMessage(Component.translatable("tfcak.recipe.failed"));
                }
                return;
            }

            // 9. 计算需要点击的格子
            List<Integer> cellsToClick = KnappingUtil.computeCellsToClick(recipePattern, currentPattern);

            // 调试日志：每帧输出当前状态
            if (debugMode.get() && debugCounter++ % 20 == 0)
            {
                debugLog("State: timer=%d, waiting=%b, waitTicks=%d, cellsRemaining=%d, totalClicks=%d",
                    TFCAutoKnapping.timer, TFCAutoKnapping.isWaitingForServer,
                    TFCAutoKnapping.serverWaitTicks, cellsToClick.size(), totalClicksThisSession);
                debugLog("Current pattern:\n%s", KnappingUtil.patternToString(currentPattern));
                debugLog("Recipe pattern:\n%s", KnappingUtil.patternToString(recipePattern));
                debugLog("Cells to click: %s", KnappingUtil.cellsToString(cellsToClick));
            }

            // 10. 检测打制完成
            //    cellsToClick 为空 = 所有需要移除的格子都已点击
            //    cellsToClick 数量增加 = 网格被重置（TFC 完成配方后自动重置）
            if (cellsToClick.isEmpty()
                || (lastCellsToClickCount > 0 && cellsToClick.size() > lastCellsToClickCount))
            {
                TFCAutoKnapping.LOGGER.info("Knapping complete: {} (total clicks: {})",
                    targetRecipe.getId(), totalClicksThisSession);
                if (debugMode.get())
                {
                    debugLog("=== KNAPPING COMPLETE ===");
                    debugLog("Recipe: %s", targetRecipe.getId());
                    debugLog("Total clicks: %d, Failures: %d", totalClicksThisSession, failureCountThisSession);
                }
                RecipeSelector.autoKnappingActive = false;
                RecipeSelector.selectedRecipeId = null;
                RecipeSelector.selectionMode = true; // 重新弹出面板
                lastCellsToClickCount = 0;
                totalClicksThisSession = 0;
                failureCountThisSession = 0;
                targetRecipeForDebug = null;
                Player player = Minecraft.getInstance().player;
                if (player != null)
                {
                    ItemStack output = targetRecipe.getResultItem(level.registryAccess());
                    player.sendSystemMessage(Component.translatable(
                        "tfcak.recipe.complete", output.getHoverName()));
                }
                return;
            }
            lastCellsToClickCount = cellsToClick.size();

            // 11. 找到下一个需要点击的格子
            int nextCell = cellsToClick.get(0);

            // 12. 计算按钮坐标（用于高亮渲染）
            int guiLeft = KnappingUtil.getGuiLeft(screen);
            int guiTop = KnappingUtil.getGuiTop(screen);

            // 13. 渲染高亮提示
            if (enableKnappingTip.get())
            {
                int cellX = nextCell % KnappingPattern.MAX_WIDTH;
                int cellY = nextCell / KnappingPattern.MAX_WIDTH;
                int buttonX = guiLeft + 12 + 16 * cellX;
                int buttonY = guiTop + 12 + 16 * cellY;
                drawHighlight(event.getGuiGraphics(), buttonX, buttonY);
            }

            // 14. 自动点击 — 使用直接 API（绕过按钮系统）
            if (TFCAutoKnapping.timer == 0 && !TFCAutoKnapping.isWaitingForServer)
            {
                int cellX = nextCell % KnappingPattern.MAX_WIDTH;
                int cellY = nextCell / KnappingPattern.MAX_WIDTH;
                TFCAutoKnapping.LOGGER.info("Auto-clicking cell {} ({},{}) via direct API, recipe={}, remaining={}",
                    nextCell, cellX, cellY, targetRecipe.getId(), cellsToClick.size());

                // 记录点击前的 pattern 数据（用于服务器响应检测）
                TFCAutoKnapping.lastPatternData = currentData;
                TFCAutoKnapping.isWaitingForServer = true;
                TFCAutoKnapping.serverWaitTicks = 0;
                TFCAutoKnapping.timer = autoKnappingCooldown.get();

                // 调试记录
                totalClicksThisSession++;
                lastClickedCell = nextCell;
                lastClickTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));

                // 使用直接 API 发送打制点击（绕过按钮系统）
                boolean success = KnappingUtil.clickCellDirect(container, nextCell);
                if (!success)
                {
                    TFCAutoKnapping.LOGGER.warn("Direct API failed, trying fallback for cell {}", nextCell);
                    success = KnappingUtil.clickCellDirectFallback(container, nextCell);
                }

                if (!success)
                {
                    failureCountThisSession++;
                    if (debugMode.get())
                    {
                        writeFailureLog("CLICK_FAILED", targetRecipe,
                            currentPattern, recipePattern, cellsToClick,
                            "Both direct API and fallback failed for cell " + nextCell);
                    }
                    // 重置等待状态，允许下一次尝试
                    TFCAutoKnapping.isWaitingForServer = false;
                    TFCAutoKnapping.timer = autoKnappingCooldown.get();
                }

                if (debugMode.get())
                {
                    debugLog("Clicked cell %d (%d,%d) at %s, success=%b, remaining=%d",
                        nextCell, cellX, cellY, lastClickTime, success, cellsToClick.size() - 1);
                }
            }
        }
        catch (Throwable e)
        {
            TFCAutoKnapping.LOGGER.error("Error in knapping event handler", e);
            if (debugMode.get())
            {
                writeFailureLog("EXCEPTION", targetRecipeForDebug,
                    null, null, null,
                    "Exception: " + e.getClass().getName() + " - " + e.getMessage());
            }
        }
    }

    // ==================== 鼠标点击拦截（选择面板） ====================

    @SubscribeEvent
    public static void onMouseClick(ScreenEvent.MouseButtonPressed.Pre event)
    {
        if (!(event.getScreen() instanceof KnappingScreen screen))
        {
            return;
        }

        try
        {
            if (!RecipeSelector.selectionMode)
            {
                return;
            }

            if (cachedRecipes == null || cachedRecipes.isEmpty())
            {
                return;
            }

            // 左键点击
            if (event.getButton() == 0)
            {
                boolean handled = RecipeSelector.handleClick(
                    event.getMouseX(), event.getMouseY(), screen, cachedRecipes
                );
                if (handled)
                {
                    event.setCanceled(true);
                }
            }
        }
        catch (Throwable e)
        {
            TFCAutoKnapping.LOGGER.error("Error in mouse click handler", e);
        }
    }

    // ==================== 滚轮事件（选择面板） ====================

    @SubscribeEvent
    public static void onMouseScroll(ScreenEvent.MouseScrolled event)
    {
        if (!(event.getScreen() instanceof KnappingScreen screen))
        {
            return;
        }

        try
        {
            if (!RecipeSelector.selectionMode || cachedRecipes == null)
            {
                return;
            }

            boolean handled = RecipeSelector.handleScroll(
                event.getMouseX(), event.getMouseY(), screen,
                event.getScrollDelta(), cachedRecipes
            );
            if (handled)
            {
                event.setCanceled(true);
            }
        }
        catch (Throwable e)
        {
            TFCAutoKnapping.LOGGER.error("Error in scroll handler", e);
        }
    }

    // ==================== 计时器事件 ====================

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event)
    {
        if (TFCAutoKnapping.timer > 0)
        {
            TFCAutoKnapping.timer--;
        }
    }

    // ==================== 界面关闭事件 ====================

    @SubscribeEvent
    public static void onScreenClosed(ScreenEvent.Closing event)
    {
        if (event.getScreen() instanceof KnappingScreen)
        {
            TFCAutoKnapping.isWaitingForServer = false;
            TFCAutoKnapping.serverWaitTicks = 0;
            TFCAutoKnapping.lastPatternData = -1;
            TFCAutoKnapping.timer = 0;
            RecipeSelector.reset();
            cachedRecipes = null;
            cachedKnappingType = null;
            panelInitialized = false;
            lastCellsToClickCount = 0;
            debugCounter = 0;
            totalClicksThisSession = 0;
            failureCountThisSession = 0;
            lastClickedCell = -1;
            lastClickTime = null;
            targetRecipeForDebug = null;
        }
    }

    // ==================== 调试日志工具 ====================

    /**
     * 调试日志输出（仅在 debugMode 开启时生效）
     * 同时输出到 LOGGER 和 tfcak_debug.log 文件
     */
    private static void debugLog(String format, Object... args)
    {
        String msg = String.format(format, args);
        TFCAutoKnapping.LOGGER.info("[TFCAK-DEBUG] {}", msg);
        writeDebugLogLine(msg);
    }

    /**
     * 写入失败日志（结构化报告）
     * 当打制失败时自动调用，记录详细状态信息
     */
    private static void writeFailureLog(String failureType, KnappingRecipe recipe,
                                         KnappingPattern currentPattern,
                                         KnappingPattern recipePattern,
                                         List<Integer> cellsToClick,
                                         String errorMessage)
    {
        failureCountThisSession++;
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));

        StringBuilder sb = new StringBuilder();
        sb.append("========================================\n");
        sb.append("TFCAK FAILURE REPORT #").append(failureCountThisSession).append("\n");
        sb.append("========================================\n");
        sb.append("Timestamp: ").append(timestamp).append("\n");
        sb.append("Failure Type: ").append(failureType).append("\n");
        sb.append("Recipe: ").append(recipe != null ? recipe.getId() : "null").append("\n");
        sb.append("Error: ").append(errorMessage).append("\n");
        sb.append("Session Stats: clicks=").append(totalClicksThisSession)
          .append(", failures=").append(failureCountThisSession).append("\n");
        sb.append("Last Click: cell=").append(lastClickedCell)
          .append(", time=").append(lastClickTime != null ? lastClickTime : "none").append("\n");
        sb.append("Timer: ").append(TFCAutoKnapping.timer).append("\n");
        sb.append("Waiting for server: ").append(TFCAutoKnapping.isWaitingForServer).append("\n");
        sb.append("Server wait ticks: ").append(TFCAutoKnapping.serverWaitTicks).append("\n");
        sb.append("Last pattern data: ").append(TFCAutoKnapping.lastPatternData).append("\n");

        if (currentPattern != null)
        {
            sb.append("Current Pattern (1=present, 0=removed):\n");
            sb.append(KnappingUtil.patternToString(currentPattern)).append("\n");
        }
        if (recipePattern != null)
        {
            sb.append("Recipe Pattern (1=keep, 0=remove):\n");
            sb.append(KnappingUtil.patternToString(recipePattern)).append("\n");
        }
        if (cellsToClick != null)
        {
            sb.append("Cells to click: ").append(KnappingUtil.cellsToString(cellsToClick)).append("\n");
        }
        sb.append("========================================\n\n");

        String report = sb.toString();
        TFCAutoKnapping.LOGGER.warn("TFCAK Failure Report:\n{}", report);
        writeDebugLogLine(report);

        // 同时向玩家发送聊天消息
        Player player = Minecraft.getInstance().player;
        if (player != null)
        {
            player.sendSystemMessage(Component.translatable("tfcak.debug.failure",
                failureType, failureCountThisSession));
        }
    }

    /**
     * 将一行文本追加到 tfcak_debug.log 文件
     */
    private static void writeDebugLogLine(String text)
    {
        try
        {
            Path logPath = FMLPaths.GAMEDIR.get().resolve("tfcak_debug.log");
            String line = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")) + "] " + text + "\n";
            Files.writeString(logPath, line,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (Throwable e)
        {
            // 静默失败，不影响游戏
        }
    }

    // ==================== 渲染工具 ====================

    private static void drawHighlight(GuiGraphics guiGraphics, int x, int y)
    {
        int color = highlightColor.get();
        float alpha = ((color >> 24) & 0xFF) / 255.0f;
        float red = ((color >> 16) & 0xFF) / 255.0f;
        float green = ((color >> 8) & 0xFF) / 255.0f;
        float blue = (color & 0xFF) / 255.0f;

        PoseStack poseStack = guiGraphics.pose();
        Matrix4f matrix = poseStack.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(red, green, blue, alpha);

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        int thickness = 2;
        int size = 16;

        // 上边
        buffer.vertex(matrix, x - 1, y - 1, 0).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, x + size + 1, y - 1, 0).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, x + size + 1, y - 1 + thickness, 0).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, x - 1, y - 1 + thickness, 0).color(red, green, blue, alpha).endVertex();
        // 下边
        buffer.vertex(matrix, x - 1, y + size + 1 - thickness, 0).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, x + size + 1, y + size + 1 - thickness, 0).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, x + size + 1, y + size + 1, 0).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, x - 1, y + size + 1, 0).color(red, green, blue, alpha).endVertex();
        // 左边
        buffer.vertex(matrix, x - 1, y - 1, 0).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, x - 1 + thickness, y - 1, 0).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, x - 1 + thickness, y + size + 1, 0).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, x - 1, y + size + 1, 0).color(red, green, blue, alpha).endVertex();
        // 右边
        buffer.vertex(matrix, x + size + 1 - thickness, y - 1, 0).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, x + size + 1, y - 1, 0).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, x + size + 1, y + size + 1, 0).color(red, green, blue, alpha).endVertex();
        buffer.vertex(matrix, x + size + 1 - thickness, y + size + 1, 0).color(red, green, blue, alpha).endVertex();

        BufferUploader.drawWithShader(buffer.end());
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
    }
}
