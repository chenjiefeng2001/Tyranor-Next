# 12 上游 Issue 分诊（对照本仓库整改现状）

拉取时间：2026-08-25。上游：`Weiss-UltimateSavior/Tyranor-Next`（157★，本镜像 fork 源），开放 issue 共 13 个。
方法：逐条与审计包（05 发现清单 / 08 契约矩阵 / 09 行动计划）及当前代码比对，标注处置路径。

## 处置总览

| 状态 | 数量 | 条目 |
|---|---|---|
| ✅ 本镜像已修复（可回提 PR） | 2 | #16、#34 |
| 🟢 可立即开工（小中体量） | 1 | #5 |
| ✅ 已实现（2026-08-25） | 2 | #28、#6 |
| 🟡 需设计/引擎侧配合（中大体量） | 4 | #35、#30、#27、#36 |
| ⏸ 上游已标 postponed / 大型功能 | 4 | #7、#8、#17、#20 |

## 逐条明细

### ✅ #34 [bug] 部分设备无法启动游戏——krkr2 大写目录名无法打开启动文件 → 本轮修复
**根因（双处叠加）**：
1. `detectEngine` 两变体把 xp3 相对路径**小写化后**写入 `launchTarget`（`xp3Files.add(childRel)`）；
2. `pickKrActivateEntry` 的 launchTarget 分支与用户手动 launchFile 分支使用大小写敏感的 `File(path, x).isFile`。
大小写敏感存储的设备上回配失败 → 兜底落到「目录本身」→ 引擎拿不到启动条目。

**修复**（commit 见 git log）：
- 扫描侧保留真实大小写（`childRel` 用原始名拼接，比较仍走小写副本）；
- 启动侧新增 `resolveEntryIgnoreCase(dir, relPath)`：按段对实际目录列表 ignoreCase 回配真实条目，接入 manual 与 target 两分支。

**回归测试**：矩阵新增「DATA.XP3 / data/SCENE.XP3 大小写保留」用例；LauncherPure 新增解析器错配命中 + 缺失段返回 null 共 2 例。单测总数 110 → **113**。

### ✅ #16 [bug] 进游戏后连耳机无声音（krkr 已复现）
本镜像已由 PR #21 合并的耳机热插拔修复覆盖（AudioRouteWatcher + commit eb6bfec，SDL2 层冻结策略下的显式补丁）。**动作建议**：向上游提交该修复的 cherry-pick PR；其他引擎待真机复现清单已在 10 号报告 §6 回归协议内。

### 🟢 #28 外置显式跳转（为其他前端提供快捷启动）→ ✅ 已实现
`tyranor://launch?path=<游戏目录>&engine=<可选>&launchFile=<可选>`：
- Manifest MainActivity 新增 VIEW/BROWSABLE intent-filter；onCreate/onNewIntent 分发；
- `parseExternalLaunchLink`（纯解析，5 例单测）+ `launchFromExternalLink`（目录校验→engine 省略时自动识别→复用 launch 全套门禁：权限引导/插件就绪/KR 存档预建）；
- 模拟器 release 包端到端实证：显式 engine 与自动识别两路均分发至 `:kirikiri2` 进程（Start proc Kirikiroid139），权限门禁与不存在目录错误分支行为正确，主进程全程存活。

### 🟢 #6 弹窗布局组件风格统一 → ✅ 已实现
AppAlertDialog 容器 PageGrey→**NavWhite**（对齐「弹窗背景白色」规范，且经 NavWhite 计算常量随色调切换）；ModalBottomSheet 顶部圆角统一 8dp；VNDB 候选项与启动文件列表行统一 NavWhite+8dp 容器。

### 🟢 #5 封面搜索与在线补丁改为独立 Activity
UI 结构改造：把 VndbSearchDialog/KrkrOnlinePatch 列表升级为 Activity（复用 startActivityWithPageTransition 转场）。中等体量，建议排在 #6 之后一次做完。

### 🟡 其余
| 条目 | 体量判断 |
|---|---|
| #35 MZ 虚拟按钮 | 复用 MV 按钮 JS 注入（assets/engine/__rmmz__*），引擎 hook 侧改动+真机验证 |
| #30 MV 按钮自定义 | 设置面 UI + gameargs/JS 参数下发链路 |
| #27 平板横屏抽屉栏抖动 | ModalBottomSheet 手势冲突，需平板实机复现定位 |
| #36 RPG 汉化 json 定位 | 文本替换管线 + 语言 json 定位策略，大型特性 |

### ⏸ postponed/大型：#7 PSP、#8 3DS、#17 元数据推送、#20 游玩进度同步 —— 维持上游节奏，不纳入本仓库近期迭代。

## 维护约定

后续每处理一条上游 issue，在本表更新状态并在对应 commit message 引用 `upstream#NN`。
