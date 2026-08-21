package com.eternal130.tfcak.emi;

import java.util.List;

import com.eternal130.tfcak.RecipeSelector;
import com.eternal130.tfcak.TFCAutoKnapping;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiPlayerInventory;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.handler.EmiCraftContext;
import dev.emi.emi.api.recipe.handler.EmiRecipeHandler;
import dev.emi.emi.api.stack.EmiStack;
import net.dries007.tfc.client.screen.KnappingScreen;
import net.dries007.tfc.common.container.KnappingContainer;
import net.dries007.tfc.compat.emi.recipe.EmiKnappingRecipe;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;

/**
 * TFC 打制配方处理器 (NeoForge 1.21)
 *
 * 接入 EMI 的合成链路：
 * - getInventory(): 将当前打制材料（KnappingContainer.getOriginalStack）作为合成材料来源，
 *   使 EMI craftables 侧栏能反查出可打制配方；
 * - canCraft(): 打制无前置门槛，始终可用；
 * - craft(): 关闭 EMI 配方页回到打制界面，置位 RecipeSelector 状态启动 tfcak 自动打制。
 */
public class TfcakEmiRecipeHandler implements EmiRecipeHandler<KnappingContainer>
{
    @Override
    public EmiPlayerInventory getInventory(AbstractContainerScreen<KnappingContainer> screen)
    {
        if (screen instanceof KnappingScreen knappingScreen)
        {
            ItemStack stack = knappingScreen.getMenu().getOriginalStack();
            if (!stack.isEmpty())
            {
                return new EmiPlayerInventory(List.of(EmiStack.of(stack)));
            }
        }
        return new EmiPlayerInventory(List.of());
    }

    @Override
    public boolean supportsRecipe(EmiRecipe recipe)
    {
        return recipe instanceof EmiKnappingRecipe;
    }

    @Override
    public boolean canCraft(EmiRecipe recipe, EmiCraftContext<KnappingContainer> context)
    {
        return supportsRecipe(recipe);
    }

    @Override
    public boolean craft(EmiRecipe recipe, EmiCraftContext<KnappingContainer> context)
    {
        // 取底层打制界面（EMI 配方页打开时返回其背后的 KnappingScreen）
        AbstractContainerScreen<?> handled = EmiApi.getHandledScreen();
        if (!(handled instanceof KnappingScreen))
        {
            return false;
        }

        // 必须先切回打制界面再启动打制：自动点击循环由 KnappingScreen 的渲染事件驱动，
        // 配方页覆盖期间渲染事件不会触发
        Screen current = Minecraft.getInstance().screen;
        if (current != handled)
        {
            Minecraft.getInstance().setScreen(handled);
        }

        // 置位状态，下一个 KnappingScreen 渲染 tick 即开始自动打制
        RecipeSelector.selectedRecipeId = recipe.getId();
        RecipeSelector.autoKnappingActive = true;
        TFCAutoKnapping.timer = 0;
        return true;
    }
}
