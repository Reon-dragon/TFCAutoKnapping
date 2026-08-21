package com.eternal130.tfcak.emi;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import net.dries007.tfc.common.container.TFCContainerTypes;

/**
 * TFCAutoKnapping × EMI 集成入口 (NeoForge 1.21)
 *
 * TFC 4.2.7 已内置 EMI 兼容（打制配方分类 / EmiKnappingRecipe 图案展示 / 工作站 / 输入反查），
 * 但未注册 KnappingContainer 的 recipe handler —— 本插件只补这一块：
 * 注册自动打制 handler 后，EMI craftables 侧栏即可显示打制配方，
 * 配方页合成按钮调用 TfcakEmiRecipeHandler.craft() 启动自动打制。
 */
@EmiEntrypoint
public class TfcakEmiPlugin implements EmiPlugin
{
    @Override
    public void register(EmiRegistry registry)
    {
        registry.addRecipeHandler(TFCContainerTypes.KNAPPING.get(), new TfcakEmiRecipeHandler());
    }
}
