package com.eternal130.tfcak;

import net.dries007.tfc.client.screen.KnappingScreen;
import net.dries007.tfc.common.recipes.KnappingRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/**
 * 配方可视化选择面板（不分类，纯网格布局，自动去重岩石变体）
 *
 * ┌────────────────────────────────────┐
 * │  选择配方 (点击图标开始打制)   [X]  │  标题栏
 * ├──────────────────────────────┬─────┤
 * │ Item Item Item Item Item Item │ ▓   │
 * │ Item Item Item Item Item Item │ ▓   │
 * │ Item Item Item Item Item Item │     │
 * │ Item Item Item Item Item Item │     │
 * │ Item Item Item Item Item Item │ ▓   │
 * │ Item Item Item Item Item Item │ ▓   │
 * └──────────────────────────────┴─────┘
 *          产物图标网格            滚动条
 *
 * 去重逻辑：
 * - TFC 的岩石打制配方按岩石类型分为 4 种变体：
 *   igneous_extrusive, igneous_intrusive, metamorphic, sedimentary
 * - 同一工具的不同岩石变体拥有相同的 5x5 打制图案
 * - 面板只显示每种工具的一个图标（通用模板）
 * - 实际打制时，TFC 服务器会根据输入材料自动匹配正确的配方
 *
 * 交互方式：
 * - 点击配方图标：关闭面板，开始自动打制
 * - 滚轮：滚动配方图标（向上滚→向上翻，向下滚→向下翻）
 * - 点击 X 或面板外部：关闭面板（手动打制模式）
 */
public class RecipeSelector
{
    // ===== 状态 =====
    /** 是否显示选择面板 */
    public static boolean selectionMode = false;
    /** 是否正在自动打制 */
    public static boolean autoKnappingActive = false;
    /** 选中的配方 ID */
    public static ResourceLocation selectedRecipeId = null;

    /** 显示用配方列表（已去重） */
    private static List<KnappingRecipe> displayRecipes = Collections.emptyList();
    /** 配方滚动偏移量 */
    private static int recipeScrollOffset = 0;

    // ===== 布局常量（18px 网格系统） =====
    private static final int ITEMS_PER_ROW = 6;
    private static final int ROWS_VISIBLE = 6;
    private static final int ITEM_SIZE = 18;       // 16px 物品 + 2px 内边距
    private static final int PADDING = 6;
    private static final int TITLE_HEIGHT = 20;
    private static final int SCROLL_BAR_WIDTH = 8;

    private static final int GRID_WIDTH = ITEMS_PER_ROW * ITEM_SIZE;
    private static final int GRID_HEIGHT = ROWS_VISIBLE * ITEM_SIZE;
    private static final int PANEL_WIDTH = PADDING + GRID_WIDTH + PADDING + SCROLL_BAR_WIDTH + PADDING;
    private static final int PANEL_HEIGHT = TITLE_HEIGHT + PADDING + GRID_HEIGHT + PADDING;

    // ===== 颜色 =====
    private static final int COLOR_BG          = 0xDD1a1d28;
    private static final int COLOR_BORDER      = 0xFFf59e0b;
    private static final int COLOR_TITLE       = 0xFFf59e0b;
    private static final int COLOR_SELECTED    = 0x8000FF00;
    private static final int COLOR_HOVER       = 0x40FFFFFF;
    private static final int COLOR_SCROLL_BG   = 0xFF334155;
    private static final int COLOR_SCROLL_FG   = 0xFF94a3b8;
    private static final int COLOR_CLOSE       = 0xFFFF4444;
    private static final int COLOR_CLOSE_HOVER = 0xFFFF8888;
    private static final int COLOR_TEXT_DIM    = 0xFF94a3b8;

    // ===== TFC 岩石类型后缀（去重用） =====
    private static final String[] ROCK_TYPE_SUFFIXES = {
        "_igneous_extrusive", "_igneous_intrusive",
        "_metamorphic", "_sedimentary"
    };

    // ===== 配方去重 =====

    /**
     * 从配方 ID 提取基础工具名称（去除岩石类型后缀）
     *
     * 例如:
     *   "tfc:rock_knapping/hoe_head_1_igneous_extrusive" → "hoe_head_1"
     *   "tfc:rock_knapping/hammer_head_sedimentary"      → "hammer_head"
     *   "tfc:clay_knapping/bowl"                          → "bowl"
     *   "tfc:leather_knapping/helmet"                     → "helmet"
     */
    private static String extractBaseName(KnappingRecipe recipe)
    {
        ResourceLocation id = recipe.getId();
        String path = id.getPath();
        String name = path.contains("/") ? path.substring(path.lastIndexOf("/") + 1) : path;

        // 去除岩石类型后缀
        for (String suffix : ROCK_TYPE_SUFFIXES)
        {
            if (name.endsWith(suffix))
            {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }

    /**
     * 加载配方列表并去重
     * 同一基础工具名的多个岩石变体只保留第一个
     */
    public static void categorizeRecipes(List<KnappingRecipe> recipes)
    {
        if (recipes == null || recipes.isEmpty())
        {
            displayRecipes = Collections.emptyList();
            recipeScrollOffset = 0;
            return;
        }

        // 按基础名称去重，保留每种工具的第一个配方
        Map<String, KnappingRecipe> deduped = new LinkedHashMap<>();
        int removedCount = 0;
        for (KnappingRecipe recipe : recipes)
        {
            String baseName = extractBaseName(recipe);
            if (!deduped.containsKey(baseName))
            {
                deduped.put(baseName, recipe);
            }
            else
            {
                removedCount++;
            }
        }

        displayRecipes = new ArrayList<>(deduped.values());
        recipeScrollOffset = 0;

        if (removedCount > 0)
        {
            TFCAutoKnapping.LOGGER.info("Recipe deduplication: {} recipes → {} (removed {} rock variants)",
                recipes.size(), displayRecipes.size(), removedCount);
        }
    }

    // ===== 面板位置计算 =====

    private static int getPanelX(AbstractContainerScreen<?> screen)
    {
        int guiLeft = KnappingUtil.getGuiLeft(screen);
        int guiWidth = 176;
        int rightSpace = screen.width - (guiLeft + guiWidth) - 4;
        if (rightSpace >= PANEL_WIDTH)
        {
            return guiLeft + guiWidth + 4;
        }
        else if (guiLeft >= PANEL_WIDTH + 4)
        {
            return guiLeft - PANEL_WIDTH - 4;
        }
        else
        {
            return guiLeft + guiWidth - PANEL_WIDTH;
        }
    }

    private static int getPanelY(AbstractContainerScreen<?> screen)
    {
        int guiTop = KnappingUtil.getGuiTop(screen);
        int panelY = guiTop;
        if (panelY + PANEL_HEIGHT > screen.height)
        {
            panelY = screen.height - PANEL_HEIGHT - 4;
        }
        return Math.max(4, panelY);
    }

    // ===== 区域坐标 =====

    private static int getGridX(int panelX)   { return panelX + PADDING; }
    private static int getGridY(int panelY)   { return panelY + TITLE_HEIGHT + PADDING; }
    private static int getScrollX(int panelX) { return panelX + PADDING + GRID_WIDTH + PADDING; }
    private static int getScrollY(int panelY) { return panelY + TITLE_HEIGHT + PADDING; }
    private static int getCloseX(int panelX)  { return panelX + PANEL_WIDTH - PADDING - 12; }
    private static int getCloseY(int panelY)  { return panelY + 4; }

    // ===== 渲染 =====

    public static void render(GuiGraphics gg, KnappingScreen screen,
                              List<KnappingRecipe> recipes,
                              double mouseX, double mouseY)
    {
        if (displayRecipes.isEmpty())
        {
            selectionMode = false;
            return;
        }

        int panelX = getPanelX(screen);
        int panelY = getPanelY(screen);
        var font = Minecraft.getInstance().font;

        // 1. 背景面板
        gg.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, COLOR_BG);
        gg.renderOutline(panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, COLOR_BORDER);

        // 2. 标题
        gg.drawString(font, Component.translatable("tfcak.panel.title"),
            panelX + PADDING, panelY + 7, COLOR_TITLE, false);

        // 3. 关闭按钮
        int closeX = getCloseX(panelX);
        int closeY = getCloseY(panelY);
        boolean closeHover = isPointIn(mouseX, mouseY, closeX, closeY, 12, 12);
        gg.fill(closeX, closeY, closeX + 12, closeY + 12, closeHover ? COLOR_CLOSE_HOVER : COLOR_CLOSE);
        gg.drawString(font, "x", closeX + 4, closeY + 2, 0xFFFFFFFF, false);

        // 4. 产物图标网格
        drawRecipeGrid(gg, screen, panelX, panelY, mouseX, mouseY, font);

        // 5. 滚动条
        drawScrollBar(gg, panelX, panelY);
    }

    private static void drawRecipeGrid(GuiGraphics gg, KnappingScreen screen,
                                        int panelX, int panelY,
                                        double mouseX, double mouseY,
                                        net.minecraft.client.gui.Font font)
    {
        int gridX = getGridX(panelX);
        int gridY = getGridY(panelY);

        if (displayRecipes.isEmpty())
        {
            gg.drawCenteredString(font, Component.translatable("tfcak.panel.empty"),
                gridX + GRID_WIDTH / 2, gridY + GRID_HEIGHT / 2 - 4, COLOR_TEXT_DIM);
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        var registryAccess = mc.level != null ? mc.level.registryAccess() : null;

        int maxScroll = Math.max(0, getTotalRows(displayRecipes) - ROWS_VISIBLE);
        recipeScrollOffset = Math.min(recipeScrollOffset, maxScroll);

        int hoverIndex = getRecipeIndexAt(mouseX, mouseY, gridX, gridY, displayRecipes);

        for (int i = 0; i < displayRecipes.size(); i++)
        {
            int visibleIndex = i - recipeScrollOffset * ITEMS_PER_ROW;
            if (visibleIndex < 0 || visibleIndex >= ROWS_VISIBLE * ITEMS_PER_ROW) continue;

            int row = visibleIndex / ITEMS_PER_ROW;
            int col = visibleIndex % ITEMS_PER_ROW;
            int itemX = gridX + col * ITEM_SIZE;
            int itemY = gridY + row * ITEM_SIZE;

            KnappingRecipe recipe = displayRecipes.get(i);
            ItemStack output = recipe.getResultItem(registryAccess);

            // 选中高亮
            if (isSelected(recipe))
            {
                gg.fill(itemX - 1, itemY - 1, itemX + ITEM_SIZE, itemY + ITEM_SIZE, COLOR_SELECTED);
            }

            // 悬停高亮
            if (i == hoverIndex)
            {
                gg.fill(itemX - 1, itemY - 1, itemX + ITEM_SIZE, itemY + ITEM_SIZE, COLOR_HOVER);
            }

            // 物品图标
            gg.renderItem(output, itemX, itemY);
            gg.renderItemDecorations(font, output, itemX, itemY);
        }

        // 悬停 Tooltip
        if (hoverIndex >= 0 && hoverIndex < displayRecipes.size())
        {
            KnappingRecipe hovered = displayRecipes.get(hoverIndex);
            ItemStack output = hovered.getResultItem(registryAccess);
            if (!output.isEmpty())
            {
                gg.renderTooltip(font, output, (int) mouseX, (int) mouseY);
            }
        }
    }

    private static void drawScrollBar(GuiGraphics gg, int panelX, int panelY)
    {
        int totalRows = getTotalRows(displayRecipes);
        if (totalRows <= ROWS_VISIBLE) return;

        int barX = getScrollX(panelX);
        int barY = getScrollY(panelY);
        int barHeight = GRID_HEIGHT;

        gg.fill(barX, barY, barX + SCROLL_BAR_WIDTH, barY + barHeight, COLOR_SCROLL_BG);

        int thumbHeight = Math.max(10, barHeight * ROWS_VISIBLE / totalRows);
        int maxScroll = totalRows - ROWS_VISIBLE;
        int thumbY = barY + (maxScroll > 0 ? (barHeight - thumbHeight) * recipeScrollOffset / maxScroll : 0);
        gg.fill(barX, thumbY, barX + SCROLL_BAR_WIDTH, thumbY + thumbHeight, COLOR_SCROLL_FG);
    }

    // ===== 点击处理 =====

    /**
     * 处理面板上的鼠标点击
     *
     * @return true 如果点击被面板消费（应取消事件）
     */
    public static boolean handleClick(double mouseX, double mouseY,
                                       KnappingScreen screen,
                                       List<KnappingRecipe> recipes)
    {
        if (!selectionMode || displayRecipes.isEmpty()) return false;

        int panelX = getPanelX(screen);
        int panelY = getPanelY(screen);

        // 点击面板外部 → 关闭面板，消费事件（防止误触网格）
        if (!isPointIn(mouseX, mouseY, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT))
        {
            selectionMode = false;
            return true;
        }

        // 关闭按钮
        int closeX = getCloseX(panelX);
        int closeY = getCloseY(panelY);
        if (isPointIn(mouseX, mouseY, closeX, closeY, 12, 12))
        {
            selectionMode = false;
            return true;
        }

        // 产物图标网格 → 选择配方，开始打制
        int gridX = getGridX(panelX);
        int gridY = getGridY(panelY);
        int index = getRecipeIndexAt(mouseX, mouseY, gridX, gridY, displayRecipes);
        if (index >= 0 && index < displayRecipes.size())
        {
            KnappingRecipe recipe = displayRecipes.get(index);
            selectedRecipeId = recipe.getId();
            selectionMode = false;
            autoKnappingActive = true;

            var player = Minecraft.getInstance().player;
            if (player != null && Minecraft.getInstance().level != null)
            {
                ItemStack output = recipe.getResultItem(Minecraft.getInstance().level.registryAccess());
                player.sendSystemMessage(Component.translatable(
                    "tfcak.recipe.selected", output.getHoverName()));
            }
            TFCAutoKnapping.LOGGER.info("Recipe selected, starting auto-knapping: {}", recipe.getId());
            return true;
        }

        return true; // 面板内空白处，消费事件
    }

    // ===== 滚动处理 =====

    public static boolean handleScroll(double mouseX, double mouseY,
                                        KnappingScreen screen,
                                        double direction,
                                        List<KnappingRecipe> recipes)
    {
        if (!selectionMode || displayRecipes.isEmpty()) return false;

        int panelX = getPanelX(screen);
        int panelY = getPanelY(screen);

        // 面板内任意位置都可以滚动
        if (!isPointIn(mouseX, mouseY, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT))
        {
            return false;
        }

        int maxScroll = Math.max(0, getTotalRows(displayRecipes) - ROWS_VISIBLE);
        // 滚动步进：每次滚动 2 行，使滚动效果更明显
        int scrollStep = 2;
        // Minecraft 滚轮 delta： > 0 = 向上滚, < 0 = 向下滚
        // 向上滚 → 偏移减少（看上方内容）
        // 向下滚 → 偏移增加（看下方内容）
        if (direction < 0)
            recipeScrollOffset = Math.min(recipeScrollOffset + scrollStep, maxScroll);
        else
            recipeScrollOffset = Math.max(recipeScrollOffset - scrollStep, 0);
        return true;
    }

    // ===== 工具方法 =====

    private static boolean isPointIn(double x, double y, int rx, int ry, int rw, int rh)
    {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    private static int getRecipeIndexAt(double mouseX, double mouseY,
                                         int gridX, int gridY,
                                         List<KnappingRecipe> recipes)
    {
        int gridWidth = ITEMS_PER_ROW * ITEM_SIZE;
        int gridHeight = ROWS_VISIBLE * ITEM_SIZE;
        if (!isPointIn(mouseX, mouseY, gridX, gridY, gridWidth, gridHeight)) return -1;
        int col = (int) ((mouseX - gridX) / ITEM_SIZE);
        int row = (int) ((mouseY - gridY) / ITEM_SIZE);
        int visibleIndex = row * ITEMS_PER_ROW + col;
        int actualIndex = visibleIndex + recipeScrollOffset * ITEMS_PER_ROW;
        return (actualIndex >= 0 && actualIndex < recipes.size()) ? actualIndex : -1;
    }

    private static int getTotalRows(List<KnappingRecipe> recipes)
    {
        return (recipes.size() + ITEMS_PER_ROW - 1) / ITEMS_PER_ROW;
    }

    private static boolean isSelected(KnappingRecipe recipe)
    {
        return selectedRecipeId != null && selectedRecipeId.equals(recipe.getId());
    }

    /**
     * 根据选中的 ID 从完整配方列表中查找配方
     * 注意：这里搜索的是 KnappingEvent 传入的完整配方列表（包含所有岩石变体），
     * 而非去重后的 displayRecipes。因为 selectedRecipeId 是去重列表中的某个变体 ID，
     * 需要在完整列表中找到它来获取正确的打制图案。
     */
    public static KnappingRecipe getSelectedRecipe(List<KnappingRecipe> recipes)
    {
        if (selectedRecipeId == null || recipes.isEmpty()) return null;
        for (KnappingRecipe recipe : recipes)
        {
            if (selectedRecipeId.equals(recipe.getId()))
            {
                return recipe;
            }
        }
        return null;
    }

    /**
     * 重置所有状态（界面关闭时调用）
     */
    public static void reset()
    {
        selectionMode = false;
        autoKnappingActive = false;
        selectedRecipeId = null;
        recipeScrollOffset = 0;
        displayRecipes = Collections.emptyList();
    }
}
