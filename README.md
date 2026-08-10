# TFCAutoKnapping

TerraFirmaCraft 自动打制模组 — 一键自动完成 5x5 网格打制，无需手动点击。

## 功能特性

- **自动打制**：点击配方图标后自动完成所有打制步骤，无需手动点击网格
- **配方选择面板**：打开打制界面时自动弹出可视化配方选择面板
- **智能去重**：自动合并岩石变体（火成岩、变质岩、沉积岩等）为通用模板，界面更简洁
- **纯客户端模组**：服务器无需安装，只发送 TFC 原生网络包
- **可滚动配方列表**：支持鼠标滚轮浏览所有可用配方
- **高亮提示**：自动打制时高亮下一个要点击的格子（可配置）
- **调试模式**：可选的详细日志和失败报告，方便排查问题

## 环境要求

| 依赖 | 版本 |
|------|------|
| Minecraft | 1.20.1 |
| Forge | 47.x |
| TerraFirmaCraft | 3.2+ (1.20.1) |

## 安装

1. 将 `tfcak-*.jar` 放入 `.minecraft/mods/` 文件夹
2. 确保已安装 TerraFirmaCraft
3. 启动游戏

> 服务器端无需安装本模组，只需安装 TFC 即可。

## 使用方法

1. 打开任意 TFC 打制界面（粘土、皮革、岩石等）
2. 配方选择面板自动弹出，显示所有可用配方
3. 点击想要制作的配方图标
4. 模组自动完成打制
5. 打制完成后面板再次弹出，可继续制作下一个物品

## 配置项

配置文件位于 `config/tfcak-client.toml`：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `enableKnappingTip` | `true` | 打制时高亮下一个要点击的格子 |
| `autoKnappingCooldown` | `2` | 每次点击的冷却时间（tick） |
| `highlightColor` | `0x8000FF00` | 高亮框颜色（ARGB 格式） |
| `debugMode` | `false` | 调试模式，输出详细日志和失败报告 |

## 技术实现

- 配方数据通过 TFC 的 `RecipeManager` 按 `KnappingType` 过滤获取
- 自动打制通过 TFC 的 `ScreenButtonPacket` 直接发送 `buttonId`（`x + 5 * y`）到服务器
- 岩石变体去重通过识别配方 ID 中的岩石类型后缀实现
- 所有事件处理器标记为 `Dist.CLIENT`，纯客户端运行

## 致谢

- [TerraFirmaCraft](https://github.com/TerraFirmaCraft/TerraFirmaCraft) — 提供打制系统和配方管理
- [TFCAutoForging](https://github.com/emilyploszaj/) — 架构参考

## 许可证

MIT License
