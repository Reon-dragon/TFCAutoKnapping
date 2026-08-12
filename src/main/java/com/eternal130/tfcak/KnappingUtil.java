package com.eternal130.tfcak;

import net.dries007.tfc.client.screen.KnappingScreen;
import net.dries007.tfc.common.container.KnappingContainer;
import net.dries007.tfc.common.recipes.KnappingRecipe;
import net.dries007.tfc.common.recipes.TFCRecipeTypes;
import net.dries007.tfc.util.data.KnappingPattern;
import net.dries007.tfc.util.data.KnappingType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 打制工具类 (NeoForge 1.21)
 *
 * TFC 1.21 API 变更：
 * - KnappingType: net.dries007.tfc.util → net.dries007.tfc.util.data (record, 非 enum)
 * - KnappingPattern: net.dries007.tfc.util → net.dries007.tfc.util.data
 * - KnappingRecipe.getKnappingType() 返回 DataManager.Reference<KnappingType>，需 .get() 解包
 * - RecipeManager.getAllRecipesFor() 返回 Collection<RecipeHolder<R>>，需 .value() 解包
 * - ScreenButtonPacket 是 CustomPacketPayload record，通过 PacketDistributor.sendToServer() 发送
 */
public class KnappingUtil
{
    public static final int TOTAL_CELLS = KnappingPattern.MAX_WIDTH * KnappingPattern.MAX_HEIGHT;

    private static Field leftPosField;
    private static Field topPosField;
    private static Field imageWidthField;
    private static Field imageHeightField;
    private static int leftPosState = 0;
    private static int topPosState = 0;
    private static Field recipePatternField;
    private static Field recipeTypeField;
    private static Field patternDataField;

    // ==================== 反射工具方法 ====================

    private static Field findFieldInHierarchy(Class<?> startClass, String... names)
    {
        Class<?> clazz = startClass;
        while (clazz != null && clazz != Object.class)
        {
            for (String name : names)
            {
                try
                {
                    Field f = clazz.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                }
                catch (NoSuchFieldException ignored) { }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private static Integer tryInvokeGetter(Object obj, String... methodNames)
    {
        for (String name : methodNames)
        {
            try
            {
                Method m = obj.getClass().getMethod(name);
                Object result = m.invoke(obj);
                if (result instanceof Integer) return (Integer) result;
            }
            catch (Exception ignored) { }
        }
        return null;
    }

    private static Field findIntFieldByValue(Object obj, Class<?> searchClass, int expectedValue)
    {
        try
        {
            for (Field f : searchClass.getDeclaredFields())
            {
                if (f.getType() == int.class)
                {
                    try
                    {
                        f.setAccessible(true);
                        if (f.getInt(obj) == expectedValue) return f;
                    }
                    catch (Exception ignored) { }
                }
            }
        }
        catch (Exception ignored) { }
        return null;
    }

    public static int getImageWidthStatic(AbstractContainerScreen<?> screen)
    {
        return getImageWidth(screen);
    }

    public static int getImageHeightStatic(AbstractContainerScreen<?> screen)
    {
        return getImageHeight(screen);
    }

    private static int getImageWidth(AbstractContainerScreen<?> screen)
    {
        if (imageWidthField != null)
        {
            try { return imageWidthField.getInt(screen); } catch (Exception ignored) { }
        }
        Field f = findFieldInHierarchy(screen.getClass(), "imageWidth", "f_97718_", "xSize");
        if (f != null)
        {
            imageWidthField = f;
            try { return f.getInt(screen); } catch (Exception ignored) { }
        }
        return 176;
    }

    private static int getImageHeight(AbstractContainerScreen<?> screen)
    {
        if (imageHeightField != null)
        {
            try { return imageHeightField.getInt(screen); } catch (Exception ignored) { }
        }
        Field f = findFieldInHierarchy(screen.getClass(), "imageHeight", "f_97719_", "ySize");
        if (f != null)
        {
            imageHeightField = f;
            try { return f.getInt(screen); } catch (Exception ignored) { }
        }
        return 166;
    }

    public static int getGuiLeft(AbstractContainerScreen<?> screen)
    {
        if (leftPosState == 1 && leftPosField != null)
        {
            try { return leftPosField.getInt(screen); } catch (Exception ignored) { }
        }
        if (leftPosState == -1) return (screen.width - getImageWidth(screen)) / 2;

        if (leftPosField == null)
        {
            leftPosField = findFieldInHierarchy(screen.getClass(), "leftPos", "f_97716_", "guiLeft");
        }
        if (leftPosField != null)
        {
            try
            {
                int val = leftPosField.getInt(screen);
                leftPosState = 1;
                return val;
            }
            catch (Exception ignored) { }
        }
        Integer getterVal = tryInvokeGetter(screen, "getLeftPos", "getGuiLeft");
        if (getterVal != null) { leftPosState = 1; return getterVal; }

        int expected = (screen.width - getImageWidth(screen)) / 2;
        Field byVal = findIntFieldByValue(screen, AbstractContainerScreen.class, expected);
        if (byVal != null) { leftPosField = byVal; leftPosState = 1; return expected; }

        leftPosState = -1;
        TFCAutoKnapping.LOGGER.warn("Failed to get leftPos via all strategies, using fallback: {}", expected);
        return expected;
    }

    public static int getGuiTop(AbstractContainerScreen<?> screen)
    {
        if (topPosState == 1 && topPosField != null)
        {
            try { return topPosField.getInt(screen); } catch (Exception ignored) { }
        }
        if (topPosState == -1) return (screen.height - getImageHeight(screen)) / 2;

        if (topPosField == null)
        {
            topPosField = findFieldInHierarchy(screen.getClass(), "topPos", "f_97717_", "guiTop");
        }
        if (topPosField != null)
        {
            try
            {
                int val = topPosField.getInt(screen);
                topPosState = 1;
                return val;
            }
            catch (Exception ignored) { }
        }
        Integer getterVal = tryInvokeGetter(screen, "getTopPos", "getGuiTop");
        if (getterVal != null) { topPosState = 1; return getterVal; }

        int expected = (screen.height - getImageHeight(screen)) / 2;
        Field byVal = findIntFieldByValue(screen, AbstractContainerScreen.class, expected);
        if (byVal != null && byVal != leftPosField) { topPosField = byVal; topPosState = 1; return expected; }

        topPosState = -1;
        TFCAutoKnapping.LOGGER.warn("Failed to get topPos via all strategies, using fallback: {}", expected);
        return expected;
    }

    // ==================== TFC 配方访问 ====================

    /**
     * 获取配方的 KnappingPattern
     * TFC 1.21: getPattern() 应仍返回 KnappingPattern
     */
    public static KnappingPattern getRecipePattern(KnappingRecipe recipe)
    {
        try
        {
            return (KnappingPattern) recipe.getClass().getMethod("getPattern").invoke(recipe);
        }
        catch (Exception ignored) { }

        try
        {
            if (recipePatternField == null)
            {
                recipePatternField = KnappingRecipe.class.getDeclaredField("pattern");
                recipePatternField.setAccessible(true);
            }
            return (KnappingPattern) recipePatternField.get(recipe);
        }
        catch (Exception e)
        {
            TFCAutoKnapping.LOGGER.error("Failed to get recipe pattern: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取配方的 KnappingType
     * TFC 1.21: getKnappingType() 返回 DataManager.Reference<KnappingType>，需解包
     */
    public static KnappingType getRecipeType(KnappingRecipe recipe)
    {
        try
        {
            Object result = recipe.getClass().getMethod("getKnappingType").invoke(recipe);
            if (result instanceof KnappingType kt) return kt;
            if (result instanceof java.util.function.Supplier<?> sup) return (KnappingType) sup.get();
            // DataManager.Reference — try get() method
            if (result != null)
            {
                try { return (KnappingType) result.getClass().getMethod("get").invoke(result); }
                catch (Exception ignored) { }
            }
        }
        catch (Exception ignored) { }

        try
        {
            if (recipeTypeField == null)
            {
                recipeTypeField = KnappingRecipe.class.getDeclaredField("knappingType");
                recipeTypeField.setAccessible(true);
            }
            Object value = recipeTypeField.get(recipe);
            if (value instanceof KnappingType kt) return kt;
            if (value instanceof java.util.function.Supplier<?> sup) return (KnappingType) sup.get();
            if (value != null)
            {
                try { return (KnappingType) value.getClass().getMethod("get").invoke(value); }
                catch (Exception ignored) { }
            }
        }
        catch (Exception e)
        {
            TFCAutoKnapping.LOGGER.error("Failed to get recipe type: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 获取 KnappingPattern 的位域数据 (int data)
     */
    public static int getPatternData(KnappingPattern pattern)
    {
        try
        {
            if (patternDataField == null)
            {
                patternDataField = KnappingPattern.class.getDeclaredField("data");
                patternDataField.setAccessible(true);
            }
            return patternDataField.getInt(pattern);
        }
        catch (Exception e)
        {
            int data = 0;
            for (int i = 0; i < TOTAL_CELLS; i++)
            {
                if (pattern.get(i)) data |= (1 << i);
            }
            return data;
        }
    }

    // ==================== 配方查找 ====================

    /**
     * 查找当前打制类型的所有可用配方
     * TFC 1.21: getAllRecipesFor() 返回 Collection<RecipeHolder<KnappingRecipe>>
     */
    public static List<RecipeHolder<KnappingRecipe>> findRecipes(Level level, KnappingType type)
    {
        return findRecipes(level, type, ItemStack.EMPTY);
    }

    /**
     * 查找当前打制类型的所有可用配方，并按输入材料过滤
     * TFC 1.21: KnappingRecipe.matchesItem() 检查配方是否接受当前输入材料
     * 这会自动过滤掉不匹配的岩石变体（如用普通石头时不会显示黑曜石配方）
     */
    public static List<RecipeHolder<KnappingRecipe>> findRecipes(Level level, KnappingType type, ItemStack inputStack)
    {
        List<RecipeHolder<KnappingRecipe>> result = new ArrayList<>();
        if (level == null || type == null) return result;

        Collection<RecipeHolder<KnappingRecipe>> allRecipes = level.getRecipeManager()
            .getAllRecipesFor(TFCRecipeTypes.KNAPPING.get());

        boolean hasInput = inputStack != null && !inputStack.isEmpty();

        for (RecipeHolder<KnappingRecipe> holder : allRecipes)
        {
            KnappingType recipeType = getRecipeType(holder.value());
            if (type.equals(recipeType))
            {
                // 按输入材料过滤：只显示匹配当前材料的配方
                if (!hasInput || holder.value().matchesItem(inputStack))
                {
                    result.add(holder);
                }
            }
        }
        return result;
    }

    /**
     * 从配方列表中筛选出"仍可完成"的配方
     */
    public static List<RecipeHolder<KnappingRecipe>> findAchievableRecipes(
        List<RecipeHolder<KnappingRecipe>> recipes, KnappingPattern current)
    {
        List<RecipeHolder<KnappingRecipe>> achievable = new ArrayList<>();
        for (RecipeHolder<KnappingRecipe> holder : recipes)
        {
            KnappingPattern recipePattern = getRecipePattern(holder.value());
            if (recipePattern == null) continue;
            if (isAchievable(recipePattern, current)) achievable.add(holder);
        }
        return achievable;
    }

    public static boolean isAchievable(KnappingPattern recipe, KnappingPattern current)
    {
        for (int i = 0; i < TOTAL_CELLS; i++)
        {
            if (recipe.get(i) && !current.get(i)) return false;
        }
        return true;
    }

    /**
     * 验证当前图案是否完全匹配配方图案
     * 用于在 cellsToClick.isEmpty() 时做最终确认，防止假成功
     */
    public static boolean patternMatches(KnappingPattern recipe, KnappingPattern current)
    {
        for (int i = 0; i < TOTAL_CELLS; i++)
        {
            if (recipe.get(i) != current.get(i)) return false;
        }
        return true;
    }

    // ==================== 格子计算 ====================

    public static List<Integer> computeCellsToClick(KnappingPattern recipe, KnappingPattern current)
    {
        List<Integer> cells = new ArrayList<>();
        for (int i = 0; i < TOTAL_CELLS; i++)
        {
            if (!recipe.get(i) && current.get(i)) cells.add(i);
        }
        return cells;
    }

    public static int[] getCellClickPos(int cellIndex, int guiLeft, int guiTop)
    {
        int cellX = cellIndex % KnappingPattern.MAX_WIDTH;
        int cellY = cellIndex / KnappingPattern.MAX_WIDTH;
        int buttonX = guiLeft + 12 + 16 * cellX;
        int buttonY = guiTop + 12 + 16 * cellY;
        return new int[]{buttonX + 8, buttonY + 8};
    }

    // ==================== 直接 API 调用 ====================

    /**
     * 直接通过 TFC 的网络包发送打制点击
     * NeoForge 1.21: 使用 PacketDistributor.sendToServer() 发送 CustomPacketPayload
     */
    public static boolean clickCellDirect(KnappingContainer container, int buttonId)
    {
        try
        {
            container.getPattern().set(buttonId, false);

            net.dries007.tfc.network.ScreenButtonPacket packet =
                new net.dries007.tfc.network.ScreenButtonPacket(buttonId);
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(packet);

            return true;
        }
        catch (Throwable e)
        {
            TFCAutoKnapping.LOGGER.error("Failed to send direct knapping click for cell {}: {}", buttonId, e.getMessage());
            return false;
        }
    }

    /**
     * 备用方案：通过反射发送 ScreenButtonPacket
     */
    public static boolean clickCellDirectFallback(KnappingContainer container, int buttonId)
    {
        try
        {
            container.getPattern().set(buttonId, false);

            Class<?> packetClass = Class.forName("net.dries007.tfc.network.ScreenButtonPacket");
            Object packet;
            try
            {
                packet = packetClass.getDeclaredConstructor(int.class).newInstance(buttonId);
            }
            catch (NoSuchMethodException e)
            {
                packet = packetClass.getDeclaredConstructor(int.class, java.util.Optional.class)
                    .newInstance(buttonId, java.util.Optional.empty());
            }

            Class<?> distributorClass = Class.forName("net.neoforged.neoforge.network.PacketDistributor");
            java.lang.reflect.Method sendMethod = distributorClass.getMethod("sendToServer",
                Class.forName("net.neoforged.network.payload.CustomPacketPayload"));
            sendMethod.invoke(null, packet);

            return true;
        }
        catch (Throwable e)
        {
            TFCAutoKnapping.LOGGER.error("Fallback click also failed for cell {}: {}", buttonId, e.getMessage());
            return false;
        }
    }

    // ==================== 调试工具 ====================

    public static String patternToString(KnappingPattern pattern)
    {
        if (pattern == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < KnappingPattern.MAX_HEIGHT; y++)
        {
            for (int x = 0; x < KnappingPattern.MAX_WIDTH; x++)
            {
                sb.append(pattern.get(x, y) ? "1" : "0");
                if (x < KnappingPattern.MAX_WIDTH - 1) sb.append(" ");
            }
            if (y < KnappingPattern.MAX_HEIGHT - 1) sb.append("\n");
        }
        return sb.toString();
    }

    public static String cellsToString(List<Integer> cells)
    {
        if (cells == null || cells.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cells.size(); i++)
        {
            int cell = cells.get(i);
            sb.append(String.format("(%d,%d)", cell % KnappingPattern.MAX_WIDTH, cell / KnappingPattern.MAX_WIDTH));
            if (i < cells.size() - 1) sb.append(", ");
        }
        return sb.append("]").toString();
    }

    // ==================== 服务器响应检测 ====================

    public static boolean hasPatternChanged(KnappingPattern current, int oldData)
    {
        return getPatternData(current) != oldData;
    }

    /**
     * 获取配方的显示名称
     * TFC 1.21: Recipe 不再有 getId()，需从 RecipeHolder 获取
     */
    public static String getRecipeName(RecipeHolder<KnappingRecipe> holder)
    {
        try { return holder.id().toString(); }
        catch (Exception e) { return "unknown"; }
    }
}
