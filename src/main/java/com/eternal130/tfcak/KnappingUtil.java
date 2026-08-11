package com.eternal130.tfcak;

import net.dries007.tfc.client.screen.KnappingScreen;
import net.dries007.tfc.common.container.KnappingContainer;
import net.dries007.tfc.common.recipes.KnappingRecipe;
import net.dries007.tfc.common.recipes.TFCRecipeTypes;
import net.dries007.tfc.util.KnappingPattern;
import net.dries007.tfc.util.KnappingType;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.level.Level;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 打制工具类
 *
 * 提供以下核心功能：
 * 1. 反射访问 TFC 私有字段（GUI 位置、配方图案等）
 * 2. 查找当前打制类型的所有可用配方
 * 3. 计算需要点击的格子列表（配方图案 vs 当前图案）
 * 4. 服务器响应检测（通过 pattern 位域比较）
 *
 * 与 TFCAutoForging/Util.java 的对比：
 * - 无 DP 预计算（打制不需要最优步骤算法）
 * - 无操作映射表（打制只有"点击格子"一种操作）
 * - 新增配方查找逻辑（锻造配方通过 Forging.getRecipe() 获取，打制需主动查找）
 */
public class KnappingUtil
{
    /** 5x5 网格的总格子数 */
    public static final int TOTAL_CELLS = KnappingPattern.MAX_WIDTH * KnappingPattern.MAX_HEIGHT;

    /** 反射缓存：AbstractContainerScreen.leftPos */
    private static Field leftPosField;
    /** 反射缓存：AbstractContainerScreen.topPos */
    private static Field topPosField;
    /** 反射缓存：AbstractContainerScreen.imageWidth */
    private static Field imageWidthField;
    /** 反射缓存：AbstractContainerScreen.imageHeight */
    private static Field imageHeightField;
    /** 反射状态：0=未尝试, 1=成功, -1=彻底失败 */
    private static int leftPosState = 0;
    private static int topPosState = 0;
    /** 反射缓存：KnappingRecipe.pattern */
    private static Field recipePatternField;
    /** 反射缓存：KnappingRecipe.knappingType */
    private static Field recipeTypeField;
    /** 反射缓存：KnappingPattern.data */
    private static Field patternDataField;

    // ==================== 反射工具方法 ====================

    /**
     * 在类层次结构中查找字段（从 startClass 向上遍历到 Object）
     * 支持多个候选名称（Mojmap / SRG / MCP）
     */
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

    /**
     * 尝试调用无参 getter 方法获取 int 值
     */
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

    /**
     * 扫描类中的 int 字段，返回值与 expectedValue 匹配的字段
     * 用于在字段名未知时通过值反查
     */
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
                        if (f.getInt(obj) == expectedValue)
                        {
                            return f;
                        }
                    }
                    catch (Exception ignored) { }
                }
            }
        }
        catch (Exception ignored) { }
        return null;
    }

    /**
     * 获取 AbstractContainerScreen 的 imageWidth（GUI 宽度）
     * TFC KnappingScreen 的 imageWidth = 176
     */
    private static int getImageWidth(AbstractContainerScreen<?> screen)
    {
        // 方式1：反射缓存
        if (imageWidthField != null)
        {
            try { return imageWidthField.getInt(screen); } catch (Exception ignored) { }
        }
        // 方式2：查找字段
        Field f = findFieldInHierarchy(screen.getClass(), "imageWidth", "f_97718_", "xSize", "field_214136_g");
        if (f != null)
        {
            imageWidthField = f;
            try { return f.getInt(screen); } catch (Exception ignored) { }
        }
        // 方式3：TFC KnappingScreen 固定值
        return 176;
    }

    /**
     * 获取 AbstractContainerScreen 的 imageHeight（GUI 高度）
     * TFC KnappingScreen 的 imageHeight = 166
     */
    private static int getImageHeight(AbstractContainerScreen<?> screen)
    {
        if (imageHeightField != null)
        {
            try { return imageHeightField.getInt(screen); } catch (Exception ignored) { }
        }
        Field f = findFieldInHierarchy(screen.getClass(), "imageHeight", "f_97719_", "ySize", "field_214137_h");
        if (f != null)
        {
            imageHeightField = f;
            try { return f.getInt(screen); } catch (Exception ignored) { }
        }
        return 166;
    }

    /**
     * 获取 KnappingScreen 的 GUI 左上角 X 坐标
     *
     * 反射策略（按优先级）：
     * 1. 缓存字段直接读取
     * 2. 在类层次中按名称查找（Mojmap: leftPos, SRG: f_97716_）
     * 3. 调用 getter 方法（getLeftPos / getGuiLeft）
     * 4. 通过值反查：leftPos = (width - imageWidth) / 2
     * 5. Fallback：使用默认 imageWidth=176 计算
     *
     * 所有策略只尝试一次，成功后缓存，失败后不再重试（避免每帧刷屏）
     */
    public static int getGuiLeft(AbstractContainerScreen<?> screen)
    {
        // 快速路径：已成功，直接读
        if (leftPosState == 1 && leftPosField != null)
        {
            try { return leftPosField.getInt(screen); } catch (Exception ignored) { }
        }
        // 快速路径：已彻底失败，用 fallback
        if (leftPosState == -1)
        {
            return (screen.width - getImageWidth(screen)) / 2;
        }

        // 策略1：按名称在类层次中查找（Mojmap + SRG + MCP 名）
        if (leftPosField == null)
        {
            leftPosField = findFieldInHierarchy(screen.getClass(),
                "leftPos", "f_97716_", "field_214138_i", "guiLeft");
        }

        // 策略2：尝试读取字段值
        if (leftPosField != null)
        {
            try
            {
                int val = leftPosField.getInt(screen);
                leftPosState = 1;
                TFCAutoKnapping.LOGGER.debug("leftPos resolved via field: {} = {}", leftPosField.getName(), val);
                return val;
            }
            catch (Exception ignored) { }
        }

        // 策略3：尝试 getter 方法
        Integer getterVal = tryInvokeGetter(screen, "getLeftPos", "getGuiLeft");
        if (getterVal != null)
        {
            leftPosState = 1;
            TFCAutoKnapping.LOGGER.debug("leftPos resolved via getter: {}", getterVal);
            return getterVal;
        }

        // 策略4：通过值反查 — leftPos 应该等于 (width - imageWidth) / 2
        int expected = (screen.width - getImageWidth(screen)) / 2;
        Field byVal = findIntFieldByValue(screen, AbstractContainerScreen.class, expected);
        if (byVal != null)
        {
            leftPosField = byVal;
            leftPosState = 1;
            TFCAutoKnapping.LOGGER.info("leftPos resolved via value scan: field={} expected={}", byVal.getName(), expected);
            return expected;
        }

        // 策略5：Fallback
        leftPosState = -1;
        TFCAutoKnapping.LOGGER.warn("Failed to get leftPos via all strategies, using fallback: {}", expected);
        return expected;
    }

    /**
     * 获取 KnappingScreen 的 GUI 左上角 Y 坐标
     * 反射策略与 getGuiLeft 相同
     */
    public static int getGuiTop(AbstractContainerScreen<?> screen)
    {
        if (topPosState == 1 && topPosField != null)
        {
            try { return topPosField.getInt(screen); } catch (Exception ignored) { }
        }
        if (topPosState == -1)
        {
            return (screen.height - getImageHeight(screen)) / 2;
        }

        // 策略1：按名称查找
        if (topPosField == null)
        {
            topPosField = findFieldInHierarchy(screen.getClass(),
                "topPos", "f_97717_", "field_214139_j", "guiTop");
        }

        // 策略2：读取字段
        if (topPosField != null)
        {
            try
            {
                int val = topPosField.getInt(screen);
                topPosState = 1;
                TFCAutoKnapping.LOGGER.debug("topPos resolved via field: {} = {}", topPosField.getName(), val);
                return val;
            }
            catch (Exception ignored) { }
        }

        // 策略3：getter 方法
        Integer getterVal = tryInvokeGetter(screen, "getTopPos", "getGuiTop");
        if (getterVal != null)
        {
            topPosState = 1;
            TFCAutoKnapping.LOGGER.debug("topPos resolved via getter: {}", getterVal);
            return getterVal;
        }

        // 策略4：值反查
        int expected = (screen.height - getImageHeight(screen)) / 2;
        // 排除已识别为 leftPos 的字段
        Field byVal = findIntFieldByValue(screen, AbstractContainerScreen.class, expected);
        if (byVal != null && byVal != leftPosField)
        {
            topPosField = byVal;
            topPosState = 1;
            TFCAutoKnapping.LOGGER.info("topPos resolved via value scan: field={} expected={}", byVal.getName(), expected);
            return expected;
        }

        // 策略5：Fallback
        topPosState = -1;
        TFCAutoKnapping.LOGGER.warn("Failed to get topPos via all strategies, using fallback: {}", expected);
        return expected;
    }

    /**
     * 获取配方的 KnappingPattern
     * 优先尝试 getPattern() 公开方法，失败后使用反射
     */
    public static KnappingPattern getRecipePattern(KnappingRecipe recipe)
    {
        // 方式1：尝试公开方法
        try
        {
            return (KnappingPattern) recipe.getClass().getMethod("getPattern").invoke(recipe);
        }
        catch (Exception ignored) { }

        // 方式2：反射访问私有字段
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
     * 优先尝试公开方法，失败后使用反射
     */
    public static KnappingType getRecipeType(KnappingRecipe recipe)
    {
        // 方式1：尝试公开方法
        try
        {
            Object result = recipe.getClass().getMethod("getKnappingType").invoke(recipe);
            if (result instanceof KnappingType kt) return kt;
            if (result instanceof java.util.function.Supplier<?> sup) return (KnappingType) sup.get();
        }
        catch (Exception ignored) { }

        // 方式2：反射访问私有字段
        try
        {
            if (recipeTypeField == null)
            {
                recipeTypeField = KnappingRecipe.class.getDeclaredField("knappingType");
                recipeTypeField.setAccessible(true);
            }
            Object value = recipeTypeField.get(recipe);
            if (value instanceof java.util.function.Supplier<?> sup) return (KnappingType) sup.get();
            return (KnappingType) value;
        }
        catch (Exception e)
        {
            TFCAutoKnapping.LOGGER.error("Failed to get recipe type: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 获取 KnappingPattern 的位域数据 (int data)
     * 用于快速比较 pattern 是否发生变化
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
            // 备用方案：遍历 25 位逐一比较
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
     *
     * @param level 世界实例
     * @param type  当前打制类型
     * @return 匹配类型的配方列表
     */
    public static List<KnappingRecipe> findRecipes(Level level, KnappingType type)
    {
        List<KnappingRecipe> result = new ArrayList<>();
        if (level == null || type == null) return result;

        List<KnappingRecipe> allRecipes = level.getRecipeManager()
            .getAllRecipesFor(TFCRecipeTypes.KNAPPING.get());

        for (KnappingRecipe recipe : allRecipes)
        {
            KnappingType recipeType = getRecipeType(recipe);
            if (recipeType == type)
            {
                result.add(recipe);
            }
        }
        return result;
    }

    /**
     * 从配方列表中筛选出"仍可完成"的配方
     *
     * 一个配方可完成 = 配方图案中所有值为 1 的格子（需保留）在当前图案中也为 1（未点击）
     *
     * @param recipes   所有配方
     * @param current   当前 pattern
     * @return 可完成的配方列表
     */
    public static List<KnappingRecipe> findAchievableRecipes(List<KnappingRecipe> recipes, KnappingPattern current)
    {
        List<KnappingRecipe> achievable = new ArrayList<>();
        for (KnappingRecipe recipe : recipes)
        {
            KnappingPattern recipePattern = getRecipePattern(recipe);
            if (recipePattern == null) continue;

            if (isAchievable(recipePattern, current))
            {
                achievable.add(recipe);
            }
        }
        return achievable;
    }

    /**
     * 检查配方图案是否仍可从当前状态完成
     *
     * @param recipe  配方图案（1=需保留, 0=需移除）
     * @param current 当前图案（1=存在, 0=已移除）
     * @return true 如果配方中所有需保留的格子当前仍然存在
     */
    public static boolean isAchievable(KnappingPattern recipe, KnappingPattern current)
    {
        for (int i = 0; i < TOTAL_CELLS; i++)
        {
            // 配方要求保留 (1) 但当前已移除 (0) → 不可完成
            if (recipe.get(i) && !current.get(i))
            {
                return false;
            }
        }
        return true;
    }

    // ==================== 格子计算 ====================

    /**
     * 计算需要点击的格子列表
     *
     * 原理：
     * - 配方图案中 1 = 该格子需保留（不点击）
     * - 配方图案中 0 = 该格子需移除（需点击）
     * - 当前图案中 1 = 格子存在（未点击）
     * - 当前图案中 0 = 格子已移除（已点击）
     *
     * 因此需要点击 = 配方为 0 且当前为 1 的格子
     *
     * @param recipe  目标配方图案
     * @param current 当前网格图案
     * @return 需要点击的格子索引列表 (0-24)
     */
    public static List<Integer> computeCellsToClick(KnappingPattern recipe, KnappingPattern current)
    {
        List<Integer> cells = new ArrayList<>();
        for (int i = 0; i < TOTAL_CELLS; i++)
        {
            // 配方要求移除 (false) 且当前仍存在 (true) → 需要点击
            if (!recipe.get(i) && current.get(i))
            {
                cells.add(i);
            }
        }
        return cells;
    }

    /**
     * 计算格子索引对应的 GUI 坐标
     *
     * TFC KnappingScreen.init() 中的按钮布局：
     *   bx = guiLeft + 12 + 16 * cellX
     *   by = guiTop  + 12 + 16 * cellY
     *
     * 其中 cellX = index % 5, cellY = index / 5
     * 点击位置为按钮中心：x + 8, y + 8
     *
     * @param cellIndex 格子索引 (0-24)
     * @param guiLeft   GUI 左上角 X
     * @param guiTop    GUI 左上角 Y
     * @return [clickX, clickY] 点击坐标
     */
    public static int[] getCellClickPos(int cellIndex, int guiLeft, int guiTop)
    {
        int cellX = cellIndex % KnappingPattern.MAX_WIDTH;
        int cellY = cellIndex / KnappingPattern.MAX_WIDTH;
        int buttonX = guiLeft + 12 + 16 * cellX;
        int buttonY = guiTop + 12 + 16 * cellY;
        // 点击按钮中心
        return new int[]{buttonX + 8, buttonY + 8};
    }

    // ==================== 直接 API 调用 ====================

    /**
     * 直接通过 TFC 的网络包发送打制点击（绕过按钮系统）
     *
     * 原理：
     * 1. 客户端立即调用 pattern.set(buttonId, false) 更新图案（视觉反馈）
     * 2. 构造 ScreenButtonPacket(buttonId, null) 发送到服务器
     * 3. 服务器收到后调用 KnappingContainer.onButtonPress() 更新图案 + 查询配方
     *
     * 相比 screen.mouseClicked() 的优势：
     * - 不依赖 KnappingButton 的 visible 状态（按钮点击后会 visible=false 导致后续点击失效）
     * - 不需要计算精确的 GUI 坐标
     * - 更高效，直接发送网络包
     *
     * @param container 打制容器
     * @param buttonId  按钮ID (0-24, 与 pattern 索引一致: x + 5*y)
     * @return true 如果成功发送
     */
    public static boolean clickCellDirect(KnappingContainer container, int buttonId)
    {
        try
        {
            // 1. 客户端立即更新 pattern（与 TFC 的 undoAccidentalButtonPress 等效）
            container.getPattern().set(buttonId, false);

            // 2. 发送 ScreenButtonPacket 到服务器
            //    这会触发服务器端 KnappingContainer.onButtonPress(buttonId, null)
            net.dries007.tfc.network.ScreenButtonPacket packet =
                new net.dries007.tfc.network.ScreenButtonPacket(buttonId, null);
            net.dries007.tfc.network.PacketHandler.send(
                net.minecraftforge.network.PacketDistributor.SERVER.noArg(), packet);

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
     * 当直接导入 TFC 网络类失败时使用
     */
    public static boolean clickCellDirectFallback(KnappingContainer container, int buttonId)
    {
        try
        {
            // 客户端更新 pattern
            container.getPattern().set(buttonId, false);

            // 反射构造 ScreenButtonPacket
            Class<?> packetClass = Class.forName("net.dries007.tfc.network.ScreenButtonPacket");
            Object packet = packetClass.getDeclaredConstructor(int.class, net.minecraft.nbt.CompoundTag.class)
                .newInstance(buttonId, null);

            // 反射调用 PacketHandler.send
            Class<?> handlerClass = Class.forName("net.dries007.tfc.network.PacketHandler");
            java.lang.reflect.Method sendMethod = handlerClass.getMethod("send",
                net.minecraftforge.network.PacketDistributor.PacketTarget.class, Object.class);
            sendMethod.invoke(null, net.minecraftforge.network.PacketDistributor.SERVER.noArg(), packet);

            return true;
        }
        catch (Throwable e)
        {
            TFCAutoKnapping.LOGGER.error("Fallback click also failed for cell {}: {}", buttonId, e.getMessage());
            return false;
        }
    }

    // ==================== 调试工具 ====================

    /**
     * 将 KnappingPattern 可视化为 5x5 字符串网格
     * 用于调试日志输出
     */
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

    /**
     * 将格子列表转换为可读字符串
     */
    public static String cellsToString(List<Integer> cells)
    {
        if (cells == null || cells.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cells.size(); i++)
        {
            int cell = cells.get(i);
            int x = cell % KnappingPattern.MAX_WIDTH;
            int y = cell / KnappingPattern.MAX_WIDTH;
            sb.append(String.format("(%d,%d)", x, y));
            if (i < cells.size() - 1) sb.append(", ");
        }
        sb.append("]");
        return sb.toString();
    }

    // ==================== 服务器响应检测 ====================

    /**
     * 检查 pattern 是否已更新（服务器已响应）
     *
     * 在自动锻造中，通过比较 work 值检测服务器响应。
     * 在自动打制中，客户端 pattern 在 mouseClicked 时同步更新，
     * 但仍可通过 pattern data 比较来确认点击是否生效。
     *
     * @param current 当前的 KnappingPattern
     * @param oldData 上次记录的 pattern data
     * @return true 如果 pattern 已变化
     */
    public static boolean hasPatternChanged(KnappingPattern current, int oldData)
    {
        return getPatternData(current) != oldData;
    }

    /**
     * 获取配方的显示名称（用于聊天提示）
     */
    public static String getRecipeName(KnappingRecipe recipe)
    {
        try
        {
            return recipe.getId().toString();
        }
        catch (Exception e)
        {
            return "unknown";
        }
    }
}
