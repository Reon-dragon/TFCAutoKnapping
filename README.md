# TFCAutoKnapping

## TerraFirmaCraft Auto Knapping Mod

Automatically complete 5x5 grid knapping without manual clicking.

## Features

- **Auto Knapping**: Automatically execute all knapping steps after clicking a recipe icon, no manual grid clicks required.
- **Recipe Selection Panel**: Visual recipe panel pops up automatically when opening any knapping UI.
- **Smart Rock Deduplication**: Merge rock variants (igneous, metamorphic, sedimentary rocks) into universal templates for a cleaner UI.
- **Client-side Only**: No server installation required; only vanilla TFC network packets are transmitted.
- **Scrollable Recipe List**: Navigate all available recipes via mouse wheel.
- **Highlight Indicator**: Highlights the next grid position to click during auto-knapping (configurable).
- **Debug Mode**: Optional verbose logging and failure reports for troubleshooting.

## Requirements

表格

| Dependency | Version |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.x |
| TerraFirmaCraft | 3.2+ (1.20.1) |

## Installation

1. Place `tfcak-*.jar` into your `.minecraft/mods/` folder
2. Ensure TerraFirmaCraft is installed
3. Launch the game

> 
> This mod is client-side only. Only TFC needs to be installed on the server.

## Usage

1. Open any TFC knapping screen (clay, leather, rock, etc.)
2. The recipe selection panel automatically loads and displays all available recipes
3. Click the icon of the item you want to craft
4. The mod will automatically finish knapping
5. The panel reappears after completion to select another recipe

## Configuration

Config file path: `config/tfcak-client.toml`

表格

| Config Key | Default Value | Description |
| --- | --- | --- |
| `enableKnappingTip` | `true` | Enable highlight frame for the next knapping position |
| `autoKnappingCooldown` | `2` | Delay between each click action (ticks) |
| `highlightColor` | `0x8000FF00` | Highlight frame color (ARGB format) |
| `debugMode` | `false` | Enable debug logs and failure reports |

## Technical Implementation

- Recipe data is fetched and filtered by `KnappingType` via TFC's `RecipeManager`.
- Auto-knapping sends native TFC `ScreenButtonPacket` containing `buttonId = x + 5 * y` to the server.
- Rock variant deduplication is implemented by parsing rock suffixes within recipe IDs.
- All event handlers are annotated with `Dist.CLIENT` to enforce client-side execution.

## Credits

- [TerraFirmaCraft](https://link.wtturl.cn/?target=https%3A%2F%2Fgithub.com%2FTerraFirmaCraft%2FTerraFirmaCraft&scene=im&aid=582478&lang=zh) — Knapping system & recipe framework
- [TFCAutoForging](https://link.wtturl.cn/?target=https%3A%2F%2Fgithub.com%2Femilyploszaj%2F&scene=im&aid=582478&lang=zh) — Architecture reference

## License

MIT License

---

# TFCAutoKnapping

## TerraFirmaCraft 自动打制模组

一键自动完成 5x5 网格打制，无需手动点击。

## 功能特性

- **自动打制**：点击配方图标后自动完成所有打制步骤，无需手动点击网格
- **配方选择面板**：打开打制界面时自动弹出可视化配方选择面板
- **智能去重**：自动合并岩石变体（火成岩、变质岩、沉积岩等）为通用模板，界面更简洁
- **纯客户端模组**：服务器无需安装，仅发送 TFC 原生网络包
- **可滚动配方列表**：支持鼠标滚轮浏览所有可用配方
- **高亮提示**：自动打制时高亮下一个要点击的格子（可配置）
- **调试模式**：可选的详细日志和失败报告，方便排查问题

## 环境要求

表格

| 依赖 | 版本 |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.x |
| TerraFirmaCraft | 3.2+ (1.20.1) |

## 安装

1. 将 `tfcak-*.jar` 放入 `.minecraft/mods/` 文件夹
2. 确保已安装 TerraFirmaCraft
3. 启动游戏

> 
> 服务器端无需安装本模组，只需安装 TFC 即可。

## 使用方法

1. 打开任意 TFC 打制界面（粘土、皮革、岩石等）
2. 配方选择面板自动弹出，显示所有可用配方
3. 点击想要制作的配方图标
4. 模组自动完成打制
5. 打制完成后面板再次弹出，可继续制作下一个物品

## 配置项

配置文件位于 `config/tfcak-client.toml`：

表格

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
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

- [TerraFirmaCraft](https://link.wtturl.cn/?target=https%3A%2F%2Fgithub.com%2FTerraFirmaCraft%2FTerraFirmaCraft&scene=im&aid=582478&lang=zh) — 提供打制系统和配方管理
- [TFCAutoForging](https://link.wtturl.cn/?target=https%3A%2F%2Fgithub.com%2Femilyploszaj%2F&scene=im&aid=582478&lang=zh) — 架构参考

## 许可证

MIT License
