package com.eternal130.tfcak;

import net.dries007.tfc.client.screen.KnappingScreen;
import net.dries007.tfc.common.container.KnappingContainer;
import net.dries007.tfc.common.recipes.KnappingRecipe;
import net.dries007.tfc.util.data.KnappingPattern;
import net.dries007.tfc.util.data.KnappingType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static com.eternal130.tfcak.ConfigFile.*;

/**
 * 打制事件处理器 — 模组核心逻辑 (NeoForge 1.21)
 *
 * NeoForge 1.21 变更：
 * - RecipeHolder<KnappingRecipe> 替代直接 KnappingRecipe 引用
 * - ClientTickEvent.Pre 从 client.event 包引入
 * - ScreenEvent.MouseScrolled.Pre 可取消，使用 getScrollDeltaY()
 * - EventBusSubscriber 自动检测总线，无需 bus 参数
 */
@EventBusSubscriber(modid = TFCAutoKnapping.MODID, value = Dist.CLIENT)
public class KnappingEvent
{
    private static List<RecipeHolder<KnappingRecipe>> cachedRecipes = null;
    private static KnappingType cachedKnappingType = null;
    private static boolean panelInitialized = false;
    private static int lastCellsToClickCount = 0;
    private static int debugCounter = 0;

    private static int totalClicksThisSession = 0;
    private static int failureCountThisSession = 0;
    private static int lastClickedCell = -1;
    private static String lastClickTime = null;
    private static RecipeHolder<KnappingRecipe> targetRecipeForDebug = null;

    // 产物槽验证状态（基于实际时间，非帧计数）
    private static boolean verifyingOutput = false;
    private static long verifyStartTime = 0;
    private static final long VERIFY_TIMEOUT_MS = 1000; // 1000ms 等待服务器同步

    // EMI 可选集成探测：EMI 存在时禁用自绘面板，由 EMI craft() 触发自动打制
    static final boolean emiLoaded = classExists("dev.emi.emi.EmiPort");

    private static boolean classExists(String className)
    {
        try
        {
            Class.forName(className);
            return true;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /**
     * 重置本次打制会话状态（完成/失败/放弃后统一调用）
     * selectionMode 由 RecipeSelector.reset() 按 emiLoaded 决定：
     * - 无 EMI：回到自绘选择面板（旧行为）
     * - 有 EMI：不弹面板，由 EMI 侧栏继续驱动
     */
    private static void resetSession()
    {
        RecipeSelector.reset();
        lastCellsToClickCount = 0;
        verifyingOutput = false;
        verifyStartTime = 0;
        totalClicksThisSession = 0;
        failureCountThisSession = 0;
        targetRecipeForDebug = null;
    }

    // ==================== 渲染事件 ====================

    @SubscribeEvent
    public static void onKnappingRender(ScreenEvent.Render.Post event)
    {
        if (!(event.getScreen() instanceof KnappingScreen screen))
        {
            return;
        }

        if (panelInitialized && !RecipeSelector.selectionMode && !RecipeSelector.autoKnappingActive)
        {
            return;
        }

        try
        {
            KnappingContainer container = screen.getMenu();
            KnappingPattern currentPattern = container.getPattern();
            KnappingType knappingType = container.getKnappingType();
            Level level = Minecraft.getInstance().level;

            if (currentPattern == null || knappingType == null || level == null)
            {
                return;
            }

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

            boolean typeChanged = (cachedKnappingType != knappingType);
            if (cachedRecipes == null || typeChanged)
            {
                // 按当前输入材料过滤配方（黑曜石配方不会在普通石头打制时出现）
                net.minecraft.world.item.ItemStack inputStack = container.getOriginalStack();
                cachedRecipes = KnappingUtil.findRecipes(level, knappingType, inputStack);
                cachedKnappingType = knappingType;
                TFCAutoKnapping.recipeCount = cachedRecipes.size();
                RecipeSelector.categorizeRecipes(cachedRecipes);
                panelInitialized = false;
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

            if (!panelInitialized)
            {
                // EMI 模式：不弹自绘面板（由 EMI 侧栏 + craft() 驱动）；无 EMI：保留旧面板行为
                RecipeSelector.selectionMode = !emiLoaded;
                RecipeSelector.autoKnappingActive = false;
                panelInitialized = true;
            }

            if (RecipeSelector.selectionMode)
            {
                RecipeSelector.render(
                    event.getGuiGraphics(), screen, cachedRecipes,
                    event.getMouseX(), event.getMouseY()
                );
                return;
            }

            if (!RecipeSelector.autoKnappingActive)
            {
                return;
            }

            RecipeHolder<KnappingRecipe> targetRecipe = RecipeSelector.getSelectedRecipe(cachedRecipes);
            targetRecipeForDebug = targetRecipe;
            if (targetRecipe == null)
            {
                TFCAutoKnapping.LOGGER.warn("No recipe selected, stopping auto-knapping");
                if (debugMode.get())
                {
                    writeFailureLog("NO_RECIPE_SELECTED", null,
                        currentPattern, null, null,
                        "RecipeSelector.selectedRecipeId = " + RecipeSelector.selectedRecipeId);
                }
                resetSession();
                return;
            }

            KnappingPattern recipePattern = KnappingUtil.getRecipePattern(targetRecipe.value());
            if (recipePattern == null)
            {
                TFCAutoKnapping.LOGGER.warn("Failed to get pattern for recipe: {}", targetRecipe.id());
                if (debugMode.get())
                {
                    writeFailureLog("PATTERN_NULL", targetRecipe,
                        currentPattern, null, null,
                        "getRecipePattern() returned null");
                }
                return;
            }

            if (!KnappingUtil.isAchievable(recipePattern, currentPattern))
            {
                TFCAutoKnapping.LOGGER.info("Recipe no longer achievable, stopping: {}", targetRecipe.id());
                if (debugMode.get())
                {
                    writeFailureLog("RECIPE_NOT_ACHIEVABLE", targetRecipe,
                        currentPattern, recipePattern, null,
                        "Pattern was ruined - recipe requires cells that have already been clicked");
                }
                resetSession();
                Player player = Minecraft.getInstance().player;
                if (player != null)
                {
                    player.sendSystemMessage(Component.translatable("tfcak.recipe.failed"));
                }
                return;
            }

            List<Integer> cellsToClick = KnappingUtil.computeCellsToClick(recipePattern, currentPattern);

            if (debugMode.get() && debugCounter++ % 20 == 0)
            {
                debugLog("State: timer=%d, waiting=%b, waitTicks=%d, cellsRemaining=%d, totalClicks=%d",
                    TFCAutoKnapping.timer, TFCAutoKnapping.isWaitingForServer,
                    TFCAutoKnapping.serverWaitTicks, cellsToClick.size(), totalClicksThisSession);
                debugLog("Current pattern:\n%s", KnappingUtil.patternToString(currentPattern));
                debugLog("Recipe pattern (with default_on):\n%s", KnappingUtil.recipePatternToString(recipePattern));
                debugLog("Cells to click: %s", KnappingUtil.cellsToString(cellsToClick));
            }

            // 检查图案是否变差（待点击格子数增加 = 服务器移除了不该移除的格子）
            if (lastCellsToClickCount > 0 && cellsToClick.size() > lastCellsToClickCount)
            {
                TFCAutoKnapping.LOGGER.warn("Pattern degraded: cells increased from {} to {}, stopping: {}",
                    lastCellsToClickCount, cellsToClick.size(), targetRecipe.id());
                if (debugMode.get())
                {
                    debugLog("=== PATTERN DEGRADED ===");
                    debugLog("Recipe: %s", targetRecipe.id());
                    debugLog("lastCellsToClickCount=%d, current cellsToClick=%d", lastCellsToClickCount, cellsToClick.size());
                    writeFailureLog("PATTERN_DEGRADED", targetRecipe,
                        currentPattern, recipePattern, cellsToClick,
                        "Cells to click increased from " + lastCellsToClickCount + " to " + cellsToClick.size());
                }
                resetSession();
                Player player = Minecraft.getInstance().player;
                if (player != null)
                {
                    player.sendSystemMessage(Component.translatable("tfcak.recipe.failed"));
                }
                return;
            }

            // 产物槽验证阶段：基于实际时间等待服务器同步
            if (verifyingOutput)
            {
                long elapsedMs = System.currentTimeMillis() - verifyStartTime;
                ItemStack outputSlotItem = container.getSlot(0).getItem();

                if (!outputSlotItem.isEmpty())
                {
                    // 产物槽有物品 → 打制成功
                    TFCAutoKnapping.LOGGER.info("Knapping verified via output slot: {} (total clicks: {}, wait ms: {})",
                        targetRecipe.id(), totalClicksThisSession, elapsedMs);
                    if (debugMode.get())
                    {
                        debugLog("=== KNAPPING COMPLETE (OUTPUT VERIFIED) ===");
                        debugLog("Recipe: %s", targetRecipe.id());
                        debugLog("Output item: %s", outputSlotItem.getHoverName().getString());
                        debugLog("Verified after %d ms, total clicks: %d", elapsedMs, totalClicksThisSession);
                    }
                    resetSession();
                    Player player = Minecraft.getInstance().player;
                    if (player != null)
                    {
                        player.sendSystemMessage(Component.translatable(
                            "tfcak.recipe.complete", outputSlotItem.getHoverName()));
                    }
                    return;
                }

                if (elapsedMs >= VERIFY_TIMEOUT_MS)
                {
                    // 超时仍未出现产物 → 打制失败
                    TFCAutoKnapping.LOGGER.warn("Output slot verification timeout for: {} (waited {} ms)",
                        targetRecipe.id(), elapsedMs);
                    if (debugMode.get())
                    {
                        debugLog("=== OUTPUT VERIFICATION TIMEOUT ===");
                        debugLog("Recipe: %s", targetRecipe.id());
                        debugLog("Waited %d ms but output slot is empty", elapsedMs);
                        debugLog("Current pattern:\n%s", KnappingUtil.patternToString(currentPattern));
                        debugLog("Recipe pattern (with default_on):\n%s", KnappingUtil.recipePatternToString(recipePattern));
                        writeFailureLog("OUTPUT_TIMEOUT", targetRecipe,
                            currentPattern, recipePattern, cellsToClick,
                            "Output slot empty after " + elapsedMs + " ms - server did not produce output");
                    }
                    resetSession();
                    Player player = Minecraft.getInstance().player;
                    if (player != null)
                    {
                        player.sendSystemMessage(Component.translatable("tfcak.recipe.failed"));
                    }
                    return;
                }

                // 仍在等待服务器同步，不继续点击
                if (debugMode.get() && elapsedMs % 200 < 50)
                {
                    debugLog("Waiting for output sync... elapsed=%d ms, recipe=%s", elapsedMs, targetRecipe.id());
                }
                return;
            }

            // 检查打制是否完成（所有需要移除的格子都已移除）
            if (cellsToClick.isEmpty())
            {
                // 进入产物槽验证阶段，等待服务器同步
                TFCAutoKnapping.LOGGER.info("All cells clicked, entering output verification: {}", targetRecipe.id());
                if (debugMode.get())
                {
                    debugLog("=== CELLS EMPTY, ENTERING OUTPUT VERIFICATION ===");
                    debugLog("Recipe: %s", targetRecipe.id());
                    debugLog("Total clicks: %d", totalClicksThisSession);
                }
                verifyingOutput = true;
                verifyStartTime = System.currentTimeMillis();
                lastCellsToClickCount = 0;
                return;
            }
            lastCellsToClickCount = cellsToClick.size();

            int nextCell = cellsToClick.get(0);

            int guiLeft = KnappingUtil.getGuiLeft(screen);
            int guiTop = KnappingUtil.getGuiTop(screen);

            if (enableKnappingTip.get())
            {
                int cellX = nextCell % KnappingPattern.MAX_WIDTH;
                int cellY = nextCell / KnappingPattern.MAX_WIDTH;
                int buttonX = guiLeft + 12 + 16 * cellX;
                int buttonY = guiTop + 12 + 16 * cellY;
                drawHighlight(event.getGuiGraphics(), buttonX, buttonY);
            }

            if (TFCAutoKnapping.timer == 0 && !TFCAutoKnapping.isWaitingForServer)
            {
                int cellX = nextCell % KnappingPattern.MAX_WIDTH;
                int cellY = nextCell / KnappingPattern.MAX_WIDTH;
                TFCAutoKnapping.LOGGER.info("Auto-clicking cell {} ({},{}) via direct API, recipe={}, remaining={}",
                    nextCell, cellX, cellY, targetRecipe.id(), cellsToClick.size());

                TFCAutoKnapping.lastPatternData = currentData;
                TFCAutoKnapping.isWaitingForServer = true;
                TFCAutoKnapping.serverWaitTicks = 0;
                TFCAutoKnapping.timer = autoKnappingCooldown.get();

                totalClicksThisSession++;
                lastClickedCell = nextCell;
                lastClickTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));

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

    // ==================== 鼠标点击拦截 ====================

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

    // ==================== 滚轮事件 ====================

    @SubscribeEvent
    public static void onMouseScroll(ScreenEvent.MouseScrolled.Pre event)
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
                event.getScrollDeltaY(), cachedRecipes
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
    public static void onTick(ClientTickEvent.Pre event)
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
            verifyingOutput = false;
            verifyStartTime = 0;
            totalClicksThisSession = 0;
            failureCountThisSession = 0;
            lastClickedCell = -1;
            lastClickTime = null;
            targetRecipeForDebug = null;
        }
    }

    // ==================== 调试日志工具 ====================

    private static void debugLog(String format, Object... args)
    {
        String msg = String.format(format, args);
        TFCAutoKnapping.LOGGER.info("[TFCAK-DEBUG] {}", msg);
        writeDebugLogLine(msg);
    }

    private static void writeFailureLog(String failureType, RecipeHolder<KnappingRecipe> recipe,
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
        sb.append("Recipe: ").append(recipe != null ? recipe.id() : "null").append("\n");
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
            sb.append("Recipe Pattern (1=keep, 0=remove, with default_on):\n");
            sb.append(KnappingUtil.recipePatternToString(recipePattern)).append("\n");
        }
        if (cellsToClick != null)
        {
            sb.append("Cells to click: ").append(KnappingUtil.cellsToString(cellsToClick)).append("\n");
        }
        sb.append("========================================\n\n");

        String report = sb.toString();
        TFCAutoKnapping.LOGGER.warn("TFCAK Failure Report:\n{}", report);
        writeDebugLogLine(report);

        Player player = Minecraft.getInstance().player;
        if (player != null)
        {
            player.sendSystemMessage(Component.translatable("tfcak.debug.failure",
                failureType, failureCountThisSession));
        }
    }

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
            // 静默失败
        }
    }

    // ==================== 渲染工具（使用 GuiGraphics.fill 替代底层 BufferBuilder）====================

    private static void drawHighlight(GuiGraphics gg, int x, int y)
    {
        int color = highlightColor.get();
        int thickness = 2;
        int size = 16;

        // 用四条 fill 矩形绘制边框（兼容 1.21 渲染 API 变更）
        // 上边
        gg.fill(x - 1, y - 1, x + size + 1, y - 1 + thickness, color);
        // 下边
        gg.fill(x - 1, y + size + 1 - thickness, x + size + 1, y + size + 1, color);
        // 左边
        gg.fill(x - 1, y - 1, x - 1 + thickness, y + size + 1, color);
        // 右边
        gg.fill(x + size + 1 - thickness, y - 1, x + size + 1, y + size + 1, color);
    }
}
