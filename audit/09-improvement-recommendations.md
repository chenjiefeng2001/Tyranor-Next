# 09 改进建议报告（基于审计证据的行动方案）

输入：[05 发现清单](./05-findings-and-remediation.md)（整改后余量）、[06 热点报告](./06-code-trace-report.md)、[08 契约核验](./08-deep-trace-report.md)（N 系列）、本轮工程观察。
基线：commit `911c29b`（2026-08-25）。

每条建议给出：依据（可溯源）→ 具体改动（文件级）→ 成本（S<0.5 天 / M=0.5–2 天 / L>2 天）→ 风险 → 验收标准。

## 0. 总览与排期建议

| 编号 | 建议 | 优先级 | 成本 | 风险 | 关联 | 状态 |
|---|---|---|---|---|---|---|
| R-01 | CI 增加单测门禁 | **P0** | S | 无 | 补 F-01 长效机制 | ✅ 已完成（47a1313） |
| R-02 | 清理遗留反射 com.apps.LauncherActivity | P0 | S | 低 | N-01 | ✅ 已完成（78a2bdd） |
| R-03 | 契约文档补注（无消费方键 / 冻结接口声明） | P0 | S | 无 | N-02 | ✅ 已完成（43492d7） |
| R-04 | 构建弃用 API 清理 | P0 | S | 低 | 工程观察 | ✅ 已完成（aebc8f5），弃用告警归零 |
| R-05 | GameScreen.kt 三步拆分 | **P1** | M | 中 | 06 §5.2 | ✅ 已完成（384a58f/f6ef4f5，998→347 行） |
| R-06 | EngineScanner 职责分离 | P1 | M | 中 | 06 §5.3 | ✅ 已完成（4cb765d，GameStore+PathResolver） |
| R-07 | EngineLauncher 纯函数补测 | P1 | S | 无 | 06 §5.4 | ✅ 已完成（603f6fb，13 例） |
| R-08 | TyranoLocalHttpServer 回环集成测试 | P1 | M | 无 | F-13 延伸 | ✅ 已完成（注入/追加/Range/穿越/端口释放，6 例） |
| R-09 | SDL2 官方源对照重整 | **P2 专项** | L | 高 | F-11 收尾 | 待办 |
| R-10 | 测试矩阵延伸（存档 zip 往返 / Asar 边界 / detectEngine 全分支） | P2 | M | 无 | F-01 延伸 | ✅ 已完成（dad55ac/1ed0abd/4daaec2，+23 例） |
| R-11 | Gradle 10 兼容预演 | P2 | S | 低 | 工程观察 | ✅ 已随 R-04 完成主项（engine Groovy 赋值语法已迁移） |
| R-12 | APK 体积决策框架（F-05） | P3 决策 | — | — | F-05 | 待决策 |
| R-13 | 多语言最小可行范围（F-06） | P3 决策 | — | — | F-06 | 待决策 |
| R-14 | 渠道合规材料清单（F-08） | P3 决策 | — | — | F-08 | 待决策 |

> 执行记录：2026-08-25 完成 P0 全部四项与 P1 的 R-07/R-08；新增测试 19 例（13+6），全仓库单测总数 57 例。
> 执行记录：2026-08-25 完成 R-10 测试矩阵延伸，新增 23 例（detectEngine 矩阵 16 + Asar 负例 3 + 存档 zip 4），全仓库单测总数 80 例。R-05/R-06 结构重构待专项会话执行。
> 执行记录：2026-08-25 完成 R-06（GameStore/PathResolver 抽离 + 后端调用方迁移）与 R-05 三步（GameSorter+5 例单测；弹窗→ui/dialogs；网格卡片→ui/components，GameScreen 998→347 行）。全仓库单测总数 85 例。
> 剩余：R-09 SDL2 官方源对照重整（需实机五链回归资源）；R-12/13/14 待产品决策；GameStore 门面在 UI 层调用方的逐步直连为低优先级后续项。

---

## 1. P0 —— 立即可做（合计 <1 天，全部低风险）

### R-01 CI 增加单测门禁 【成本 S｜风险 无】
**依据**：android-ci.yml 仅执行 `assembleDebug`；R-07/R-08/R-10 新增的测试若无 CI 强制，回归兜底形同虚设（F-01 整改的长效保障缺失）。
**改动**：`.github/workflows/android-ci.yml` 在 Assemble 步骤前插入：
```yaml
      - name: Run unit tests
        run: ./gradlew :app:testDebugUnitTest :engine:testDebugUnitTest --no-daemon
```
可选：失败时 `actions/upload-artifact` 上传 `**/build/reports/tests/`。
Robolectric 4.16.1 所需 android-all jar 由 Maven 拉取，无需追加 SDK 包。
**验收**：CI 在含失败用例的 PR 上变红。

### R-02 清理遗留反射 【成本 S｜风险 低】
**依据**：N-01——KirikiroidLauncherBaseActivity.java:694/719/748 三处 `Class.forName("com.apps.LauncherActivity")`，目标类在本仓库不存在且恒走 try/catch 兜底（源码自带 TODO：migration 完成即删）。
**改动**：删除三处 try/catch 反射块，保留 `primaryColor` Intent extra 直读路径（KirikiroidLauncherBaseActivity.java:689 已是主路径）。同步删除 ：50 注释段。
**风险**：仅影响上游 Tyranor 壳的历史兼容分支，主链路不经过。
**验收**：assembleDebug 通过 + KRKR 启动手测一次主题色注入正常（弹窗按钮颜色随 App 色调轮盘变化）。

### R-03 契约文档补注 【成本 S｜风险 无】
**依据**：N-02——launchMode/launchTarget 共 10 处生产 0 处消费，易被后续维护者误判为断链缺陷。
**改动**：
1. `audit/04-feature-code-map.md` H 节追加一行：launchMode/launchTarget 为诊断标记，无运行时消费方；
2. `engine/.../com/core/engine/EnginePrefs.kt` 类注释追加「冻结契约」声明：Intent extras 键与 prefs 键为跨模块冻结契约，变更须同步 08 报告 C/E 节矩阵。
**验收**：文档评审通过即可。

### R-04 构建弃用 API 清理 【成本 S｜风险 低】
**依据**：构建告警 `app/build.gradle.kts:108 'fun setSrcDirs(srcDirs: Iterable<*>)' is deprecated`（每次构建出现）；Gradle 9 已提示 Gradle 10 不兼容项。
**改动**：按提示改为 `sourceSets { getByName("main") { assets.srcDirs(...) } }` 新 DSL；顺手处理 `--warning-mode all` 列出的其余项（预计 ≤3 处）。
**验收**：构建输出无 deprecation 告警。

## 2. P1 —— 结构优化（本迭代～下迭代）

### R-05 GameScreen.kt 三步拆分 【成本 M｜风险 中】
**依据**：06 热点榜——churn ×34 全仓库第一、998 SLOC、同时承载网格渲染/排序/四弹窗/长按面板/启动触发，是回归概率最高单点。
**改动**（三步独立提交，每步可单独回滚）：
1. 抽 `ui/components/GameGrid.kt + GameCard.kt`（纯渲染，参数化列表与回调）；
2. 抽 `ui/dialogs/` 子包：RenameGameDialog(:670) / VndbSearchDialog(:705) / LaunchFileDialog(:794) / GameActionsSheet(:429)；
3. sortGames(:272) 移入独立文件并补单测（bracketTag 与拼音两模式边界）。
GameScreen 保留状态编排与导航。AGENT.md 顶部栏/文字尺寸规范对拆出组件继续生效。
**验收**：拆分后 assembleDebug + 全部单测通过；游戏页手测排序/重命名/VNDB 绑定/长按四路径；churn 观察一个迭代确认修改不再集中于单文件。

### R-06 EngineScanner 职责分离 【成本 M｜风险 中】
**依据**：06 §5.3——700 行多职责枢纽（扫描遍历+双路检测+序列化+缓存+SAF 映射+封面发现），fan-in 6 但被三条不同关注点依赖。
**改动**（保持 object facade 不破坏调用方）：
1. 序列化+缓存 → `GameStore`（saveGames/loadGames/recent/quickLaunch，serializeGame/parseGame 归位）；
2. safUriToPath/isRemovableStoragePath/safeSaveName → `PathResolver`（EngineScanner 保留转发委托，逐步迁移调用方 GameSaveManager/EngineLauncher 后移除委托）；
3. detectEngine 双实现保持原位（与测试耦合最紧，最后动或不动）。
现有 EngineScannerPersistenceTest 直接迁移为 GameStoreTest。
**验收**：测试全绿；`rg "EngineScanner\.(saveGames|loadGames|safUriToPath)" app/src/main` 迁移完成后归零。

### R-07 EngineLauncher 纯函数补测 【成本 S｜风险 无】
**依据**：06 §5.4——分发枢纽五引擎 Intent 组装零覆盖；四个纯函数无需 Robolectric 即可测。
**改动**：新增 `app/src/test/.../scanner/EngineLauncherPureTest.kt`：
- pickKrActivateEntry：launchFile 手动指定优先 / preferred 六名匹配 / launchTarget 非 bg 前缀 / 任意 xp3 兜底 / 目录兜底 五分支；
- parseStoragePath：primary 全量、/sdcard 别名、/storage/<vol> 卷、非法 null 四分支；
- normalizeKrkrsdl3Renderer：gl/gpu/sw 别名与非法值回退 software；
- safeSharpnessValue：0.1~10.0 边界、NaN/Inf、非数字回退 "2"。
需将 private 收窄调整：parseStoragePath/safeSharpnessValue 改 internal（同包可见，不扩大 API 面）。
**验收**：新增 ≥12 用例全绿。

### R-08 TyranoLocalHttpServer 回环集成测试 【成本 M｜风险 无】
**依据**：T6/Tyrano 栈零测试；该服务是唯一自建网络面，loopback 绑定（F-13）与 JS 注入逻辑值得真实 socket 级验证。
**改动**：engine 模块新增 `TyranoLocalHttpServerTest`（JUnit + 真实 ServerSocket，JVM 可跑）：
1. 临时目录放 index.html + hook 字节 → start() → `http://127.0.0.1:<port>/index.html` GET 断言注入位置（</head> 前）；injectBeforeBody=true 时 </body> 前；
2. scriptAppends 对 .js 的追加行为；
3. Range 请求 206 分片正确性；
4. 路径穿越 `../` 返回 404；
5. stop() 后端口释放。
**验收**：新增 ≥5 用例全绿；顺带固化 F-13 结论为可执行断言。

## 3. P2 —— 专项规划

### R-09 SDL2 官方源对照重整 【专项 L｜风险 高】
**依据**：F-11 待办——org/libsdl/app 22 个文件为反编译产物（45 处 JADX 标记），SDL2 Java 层 fan-in 高（SDLActivity 63.7），升级 SDL2 前必须完成。
**前置协议**（先于任何替换）：建立五链回归清单——KRKR(139/134/126) / krkrsdl3 / ONS 各启动一台真机实机游戏至首帧+音频+触摸三点验证。
**步骤**：锁定当前插件内嵌 SDL2 版本（libSDL2.so strings 查 SDL_VERSION）→ 取对应 tag 官方 java 源 → 逐文件 diff 替换 → AudioRouteWatcher 与热插拔修复（eb6bfec 合入的 headphone-hotplug）重放为显式补丁 → 全量回归。
**验收**：五链回归清单全过；JADX 标记清零；diff 文件归档 docs/。

### R-10 测试矩阵延伸 【成本 M】
优先序：GameSaveManager exportToZip/importFromZip 往返（临时目录+ZipInputStream 断言，含数量上限与重复条目拒绝）＞ AsarArchive 负例（jsonLen 超界/offset 越界/size<0）＞ detectEngine(File) 全引擎分支矩阵表驱动（现仅 RPG MV/MZ/VN/WEB 四支）。

### R-11 Gradle 10 兼容预演 【成本 S】
跑 `./gradlew --warning-mode all` 记录全量弃用项并建 issue 清单；AGP 升级窗口前清零。与 R-04 合并执行亦可。

## 4. P3 —— 发布前决策框架（需产品拍板，此处给量化输入）

### R-12 APK 体积（F-05）
实测更新：插件总量 **115.8 MB**（kirikiroid2 87.2 ／ artemis 24.9 ／ ons 3.7），其中三个 KR 内核 libgame{,126,134}.so 合计 76.1 MB；**五大 so 均已 strip**（symtab/debug_info 缺失，本轮 ELF 全字节校验）——去符号已无收益，剩余杠杆只在分发结构：

| 方案 | 预期收益 | 成本 | 备注 |
|---|---|---|---|
| 维持现状 | 0 | 0 | debug APK 88.5MB |
| KR 三内核按需下载（保留 auto 默认内核打包） | APK −50~70MB | M | 需下载源与失败兜底 UI；离线场景退化 |
| Play Asset Delivery install-time | 首装体积感知下降 | M-L | 仅 Play 渠道有意义，与 F-08 决策联动 |
| zip 压缩级别微调 | 个位数 MB | S | 收益有限 |

建议：国内渠道走「KR 内核按需」；Play 渠道与 PAD 方案绑定评估。

### R-13 多语言（F-06）
最小可行范围建议：第一期只抽 contentDescription 与设置页高频文案（≈40 条），复用 engine 侧已有 en/ja/zh-cn/zh-tw locale 机制思路；第二期再考虑 Compose 全量化。避免一次性大改 churn 最高的 GameScreen（与 R-05 时序冲突）。

### R-14 渠道合规（F-08）
MANAGE_EXTERNAL_STORAGE 保留现状（启动时按需引导已是合理最小化）。若上架 Play 需备：使用说明视频、核心功能依赖申报、隐私政策（Bugly 数据收集条款见 THIRD-PARTY-NOTICES §五）、以及 MAD 权限豁免被拒后的 SAF-only 降级预案（工程上需评估 krkr relocate 依赖直读路径的影响面）。

## 5. 负空间——明确不建议动的部分

| 区域 | 理由 |
|---|---|
| vendored SDL3 / ijkplayer / Cocos Java 层 | 上游基线，改动破坏可升级性；问题统一走 R-09 流程 |
| IMediaPlayer / NativeBridge / EnginePrefs 接口签名 | fan-in 19/9 的冻结契约（08 §E），改签名波及全局且 native 侧不可静态比对 |
| 进程隔离架构（八进程） | 符号冲突隔离的设计根基（engine Manifest L13/L42 注释），收益不抵回归风险 |
| networkSecurityConfig 白名单粒度 | 当前仅 loopback 是安全最优解；如遇 http 资源反馈再评估域名白名单，不开全局明文回头路 |
| TyranoLocalHttpServer 绑定地址 | F-13 已核实最优实践，勿"优化" |

## 6. 执行顺序建议

```
本周      R-01 → R-02 → R-03 → R-04          （P0 一个 PR 或四个小 PR）
迭代 N    R-07 → R-08 → R-05(三步)           （测试先行，再动高 churn 文件）
迭代 N+1  R-06 → R-10                        （扫描器分离 + 测试延伸）
专项窗口   R-09（SDL2 重整，需实机回归资源）
决策会    R-12 / R-13 / R-14（P3 输入已备齐）
```

原则：**测试先行于重构**（R-07/R-08 先于 R-05/R-06）；**文档随手更新**（每项落地后回写 05 整改记录与 04 映射表）。
