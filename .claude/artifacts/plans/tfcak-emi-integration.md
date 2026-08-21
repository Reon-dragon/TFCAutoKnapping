# TFCAutoKnapping × EMI 集成实施计划（简化版）

> Status: APPROVED（待用户确认）
> Source: user request（EMI 检测到物品后，把 TFC 打制界面识别为可打制配方，点击后调用 tfcak 打制程序）
> Mode: default（Planner → Architect → Critic），v2 简化迭代
> Iterations: 2 / 3
> Author: Reon_dragon
> Last updated: 2026-08-21

## Requirements summary

将 tfcak 的自绘配方选择面板替换为 EMI UI：EMI 在检测到打制材料后，将 TFC 打制配方以 craftables 形式展示在侧栏；点击配方进入 EMI 配方页，点击合成按钮后调用 tfcak 现有自动打制核心完成打制。

**v2 关键变更**：发现 TFC 4.2.7 已内置 EMI 兼容（`compat/emi`：分类注册、`EmiKnappingRecipe` 图案展示、工作站、`EmiSizedIngredient`），但未注册 `KnappingContainer` 的 recipe handler。因此 tfcak **不再编写配方包装/展示层**，只补 handler 这一块拼图。

## Acceptance criteria

- AC-1 `gradlew build` 通过，产出 `build/libs/tfcak-2.2.0-1.21.jar`，含新增 EMI handler class。
- AC-2 游戏内安装 tfcak + TFC 4.2.7 + EMI 1.1.24，手持材料打开打制界面后，EMI 侧栏 craftables 出现匹配材料的打制配方（TFC 官方已注册的 `EmiKnappingRecipe`）。
- AC-3 点击侧栏配方 → EMI 配方页显示 5x5 图案（TFC 的 `PatternWidget`）与产物 → 点击合成 → tfcak 自动完成打制，产物进入输出槽并收到完成消息。
- AC-4 移除 EMI 后启动游戏，tfcak 不崩溃，且回退到旧自绘面板行为（面板正常弹出、点击可打制）。
- AC-5 打制失败（图案退化/超时）后状态正确重置，EMI 侧栏可再次选择。

## RALPLAN-DR

### Principles

- 最小代码：复用 TFC 已实现的 EMI 配方展示，tfcak 只新增插件注册 + handler 两个类。
- 不假设：EMI 为可选依赖（compileOnly），无 EMI 时 tfcak 降级为旧行为。
- 外科手术式改动：`KnappingEvent` 只动"面板自动弹出"与输入拦截的触发条件，点击循环/产物验证/失败报告一行不改。
- 可验证成功标准：每条 AC 都有明确游戏内操作步骤。

### Decision drivers

1. 现有用户兼容（无 EMI 不能崩、不能丢功能）。
2. 与 TFC 内置 `compat/emi` 类的版本耦合。
3. 维护成本（不重复实现 TFC 已有的展示逻辑）。

### Viable options

**Option A（推荐）：复用 TFC 内置 EMI 兼容，tfcak 只补 handler**
- 实现思路：`TfcakEmiPlugin` 只注册 `TFCContainerTypes.KNAPPING` 的 `EmiRecipeHandler`；`TfcakEmiRecipeHandler` 的 `getInventory()` 提供打制材料让 craftables 出现，`craft()` 切回打制界面并启动自动打制。无 EMI 时保留旧面板。
- 改动文件：`build.gradle`、`gradle.properties`、`KnappingEvent.java`、新增 `src/main/java/com/eternal130/tfcak/emi/` 下 2 个类。
- Pros：新增代码最少（约 200 行）；展示层（图案 widget/分类/工作站/输入反查）全部复用 TFC 官方实现；craftables 输入粒度（精确 Ingredient + 类型默认回退）由 TFC 的 `EmiKnappingRecipe` 自动解决。
- Cons：tfcak 编译期引用 TFC 的 `compat/emi` 类（版本耦合 ≥4.2.7）；TFC 未提供 Knapping handler 的现成参考实现。

**Option B：完全重写 EMI 配方注册（不依赖 TFC 内置集成）**
- 实现思路：tfcak 自行注册分类、`TfcakEmiRecipe`、工作站、handler。
- Pros：不依赖 TFC 的 `compat/emi` 类。
- Cons：重复实现 TFC 已完成的图案 widget、输入解析、工作站注册（约 350 行），且可能与 TFC 内置集成产生重复分类/配方冲突，需用 `removeRecipes` 兜底。违背最小代码。

**Invalidation rationale**：
- B 被砍：TFC 4.2.7 jar 已确认包含 `compat/emi` 全量类（本地 Gradle 缓存实测），复用成本远低于重写，且重写会与官方集成重复注册。

### Implementation steps（基于 Option A）

1. `gradle.properties` — 新增 `emi_version=1.1.24+1.21.1`。
2. `build.gradle:46-59` — `repositories` 块新增 `maven { name = "Sleeping Town"; url = "https://repo.sleeping.town/" }`；`dependencies` 块新增 `compileOnly("dev.emi:emi-neoforge:${emi_version}:api")`。
3. 新建 `src/main/java/com/eternal130/tfcak/emi/TfcakEmiPlugin.java`：
   - `@EmiEntrypoint` 注解 + `implements EmiPlugin`（NeoForge 插件加载方式）。
   - `register(EmiRegistry)` 仅一行核心注册：
     `registry.addRecipeHandler(TFCContainerTypes.KNAPPING.get(), new TfcakEmiRecipeHandler());`
     （容器类型已确认：`TFCContainerTypes.KNAPPING` 是 `Id<KnappingContainer>`，`.get()` 返回 `MenuType<KnappingContainer>`。）
4. 新建 `src/main/java/com/eternal130/tfcak/emi/TfcakEmiRecipeHandler.java` — `implements EmiRecipeHandler<KnappingContainer>`：
   - `getInventory(screen)`：`screen instanceof KnappingScreen` 时用 `container.getOriginalStack()` 构造单元素 `EmiPlayerInventory`（`EmiStack.of(stack)`）；否则返回空列表构造。
   - `supportsRecipe(r)` → `r instanceof net.dries007.tfc.compat.emi.recipe.EmiKnappingRecipe`（TFC 4.2.7 内置类）。
   - `canCraft(r, ctx)` → 打制无前置门槛，返回 true。
   - `craft(r, ctx)`：
     1. `EmiApi.getHandledScreen()` 取底层屏幕（null 返回 false）；
     2. 若为 `RecipeScreen`，`Minecraft.getInstance().setScreen(rs.old)` 切回 `KnappingScreen`；
     3. `RecipeSelector.selectedRecipeId = r.getId()`（`EmiKnappingRecipe` 的 id 即 TFC 配方 id，与 `cachedRecipes` 的 `RecipeHolder.id()` 一致）；
     4. `RecipeSelector.autoKnappingActive = true`、`TFCAutoKnapping.timer = 0`；
     5. 返回 true。屏幕切回后下一个 `KnappingScreen` 渲染 tick 即启动点击循环。
   - 若需从 recipe 取原始 `KnappingRecipe`（如读取图案），用 `((EmiKnappingRecipe) r)` 后调 TFC `BasicRecipe` 的取值方法（实施时确认 `BasicRecipe.getRecipe()` API）。
5. `src/main/java/com/eternal130/tfcak/KnappingEvent.java` — 新增静态探测 `private static final boolean emiLoaded = classExists("dev.emi.emi.EmiPort");`（`Class.forName` 捕获异常返回 false，避免直接引用 EMI 类导致无 EMI 时加载崩溃）：
   - `onKnappingRender` 中"自动进入 selectionMode 弹面板"分支（约 `KnappingEvent.java:137-151`）改为：`if (emiLoaded) { RecipeSelector.autoKnappingActive = false; RecipeSelector.selectionMode = false; return; }`（EMI 模式：不弹面板，等待 `craft()` 触发）；否则保留原逻辑。
   - 打制完成/失败重置处（`selectedRecipeId = null; selectionMode = true;` 多处）统一改为调用 `RecipeSelector.reset()`，并将 `reset()` 的 `selectionMode` 初始值设为 `!emiLoaded`（EMI 模式不弹面板）。
   - `onMouseClick` / `onMouseScroll` 保留不动：EMI 模式下 `selectionMode=false`，两个 handler 的现有 `if (!selectionMode) return;` 已自动短路，无 EMI 时旧面板交互照常。
6. `src/main/java/com/eternal130/tfcak/RecipeSelector.java` — 本次**不改渲染代码**（保留旧面板作为无 EMI 降级路径）；仅调整 `reset()` 的 `selectionMode` 初始值为 `!KnappingEvent.emiLoaded`。面板代码删除列入 Follow-ups，等确认不再需要降级后再做。
7. 验证构建：`gradlew build`（见 Verification）。

### Workspace setup

- 实施前运行 `git status --short` 与 `git branch --show-current`。
- 当前仓库为本地项目，若 tree 干净且处于非 `main`/`master`/`release/*` 分支，直接在当前分支实施；若处于主干分支则询问是否建 worktree。
- 若 tree 已 dirty，先保护现有改动，不与本计划改动混同。

### Risks & mitigations

| Risk | Mitigation |
|---|---|
| EMI artifact 与 NeoForge 21.1.234 版本不兼容（构建失败） | 锁定 `emi_version=1.1.24+1.21.1`；构建报 API 缺失时退回 1.1.23 或调整 `neoforge_version` |
| TFC `compat/emi` 类在版本升级后被移除（版本耦合） | 记录约束 TFC ≥ 4.2.7；`supportsRecipe` 用 `instanceof EmiKnappingRecipe` 单点引用，未来如需兼容旧 TFC 再改反射 |
| `BasicRecipe.getRecipe()` API 与预期不符（取不到原始 `KnappingRecipe`） | craft() 实际不需要原始配方（`r.getId()` 即足够），该调用仅作可选增强；拿不到就跳过 |
| craft() 屏幕切换时序错误（RecipeScreen 未关闭就打制循环不驱动） | `onKnappingRender` 只响应 `KnappingScreen` 渲染；craft() 必须**先** `setScreen(rs.old)` **再**置 `autoKnappingActive`，步骤 4 已固定顺序 |
| 无 EMI 时 `@EmiEntrypoint` 类加载导致崩溃 | `compileOnly` 依赖 + 插件类仅由 EMI 扫描加载；tfcak 自身代码只通过 `emiLoaded` 布尔开关降级，不直接引用 EMI 类 |
| TFC 的 `EmiKnappingRecipe` 输入（`EmiSizedIngredient`）与 tfcak `getInventory()` 返回的 `EmiStack` 匹配不一致 | 两者同源于 TFC 输入 Ingredient；实施后先验证 AC-2（craftables 出现），不匹配时在 `getInventory()` 改用 `EmiIngredient.of(inputItem.getItems())` 同源构造 |

### Verification steps

- AC-1：`gradlew build` 退出码 0；`build/libs/tfcak-2.2.0-1.21.jar` 存在且含 `com/eternal130/tfcak/emi/` 下 2 个 class。
- AC-2：`gradlew runClient`，手持石头/粘土打开打制界面，EMI 侧栏切到 craftables 标签，确认出现对应产物（与 tfcak 旧面板列表一致，且黑曜石配方不会在普通石头下出现）。
- AC-3：点击配方 → 配方页渲染 5x5 图案（TFC `PatternWidget`，材质循环动画）→ 点合成 → 观察自动点击到完成，输出槽出现产物，聊天栏出现 `tfcak.recipe.complete`。
- AC-4：临时从 mods 移除 EMI 启动，打开打制界面旧面板正常弹出，手动点选配方自动打制可用。
- AC-5：调试模式开启，故意选一个不可能完成的配方触发失败分支，确认 `tfcak_debug.log` 写失败报告且侧栏可再次选择。

## ADR

- **Decision**: 复用 TFC 4.2.7 内置 EMI 兼容（分类/图案/工作站/输入），tfcak 只注册 `KnappingContainer` 的 `EmiRecipeHandler` 并接入自动打制；无 EMI 时保留旧自绘面板（Option A）。
- **Drivers**: 现有用户兼容（决定性）、TFC 内置集成可用性（决定性）、维护成本。
- **Alternatives considered**:
  - Option A — chosen：最少新增代码，展示层全部复用官方实现。
  - Option B — rejected：重复实现 TFC 已有逻辑，且可能与官方集成重复注册，需 `removeRecipes` 兜底。
- **Why chosen**: 本地 TFC 4.2.7 jar 实测包含完整 `compat/emi`，EMI 的 craftables 侧栏"无 handler 即空"的设计恰好让 tfcak 只需补 handler 一块拼图。
- **Consequences**:
  - 正向：新增代码约 200 行（2 个类）；配方展示、输入反查、图案交互（点击查看配方/用途）全部免费获得；`RecipeSelector` 渲染代码暂不动，降级零风险。
  - 负向：编译期引用 TFC `compat/emi` 类（版本耦合 ≥4.2.7）；EMI 版本锁定 1.1.24；`KnappingEvent` 增加 `emiLoaded` 分支。
- **Follow-ups**: 待降级路径确认无用后，删除 `RecipeSelector` 面板渲染（约 250 行）与 `emiLoaded` 分支收敛为 EMI-only；支持 Fabric 平台不在本次范围。

## Review trail

- Planner draft v1: 三方案，推荐自建 EMI 配方注册（Option A 旧版），8 步实施。
- Architect challenge v1: craft() 屏幕切换时序风险（先切屏再启动）已固化。
- Critic verdict v1: APPROVED。
- **v2 迭代**：用户追问输入粒度差异时，发现 TFC 4.2.7 内置 `compat/emi`（分类/`EmiKnappingRecipe`/工作站/`EmiSizedIngredient`），但无 Knapping handler。Planner v2 重排方案为"只补 handler"；Architect v2 steelman：TFC `compat/emi` 类版本耦合风险，Mitigation 为记录 TFC ≥4.2.7 + 单点 `instanceof`；Critic v2：APPROVED，保留 1 条 reservation（`EmiSizedIngredient` 与 `getInventory()` 的匹配一致性依赖 AC-2 实测验证，风险表已列）。
- Final iterations: 2 / 3

## Open questions

1. 打制完成/失败后，是否自动关闭 `KnappingScreen` 回到主界面？当前计划保持界面打开（玩家在 EMI 侧栏继续选）。
2. 无 EMI 降级路径（旧自绘面板）是否保留？当前计划保留（AC-4），确认稳定后可在 Follow-up 中删除。
