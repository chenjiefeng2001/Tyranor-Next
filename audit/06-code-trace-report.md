# 06 全量静态 Trace 与热点报告

基线：commit `47dc6d8`（2026-08-25）。工具与口径见第 1 节；数据文件 [tools/hotspot-data.json](./tools/hotspot-data.json)；可视化 [07-hotspot-map.svg](./07-hotspot-map.svg)。复现方式：

```bash
python audit/tools/hotspot_trace.py
```

---

## 1. 方法论与口径

| 度量 | 定义 |
|---|---|
| SLOC | 非空行数（含注释行，不含空行） |
| fun | 函数声明数（Kotlin `fun` / Java 方法体声明） |
| branch | 分支关键字计数（if/when/switch/for/while/catch/try）——圈复杂度代理 |
| fan-out | 本文件引用的**项目内其他文件**的顶层符号去重数（跨文件耦合度） |
| fan-in | 引用了本文件顶层符号的**其他源文件数**（被依赖面 = 修改影响面） |
| churn | git 全历史中该文件的提交次数（实际开发热度） |

综合热力分 `HEAT = 100 × (0.35·fan_in + 0.25·churn + 0.25·sloc + 0.15·fan_out)`，各分量 min-max 归一化。权重含义：修改影响面 > 实际变更频率 ≈ 体量 > 出度。

**局限**：静态近似——反射/字符串 FindClass 的动态依赖不计入 fan-in（如 SDL JNI_OnLoad、Artemis Dialog 反查桥）；vendored 第三方代码天然高体量高扇入，解读时按「上游基线」与「自有代码」分开看（第 5 节）。

## 2. 全景统计

| 模块 | 文件数 | SLOC | 占比 |
|---|---|---|---|
| app | 39 | 7,573 | 26% |
| engine | 140 | 21,034 | 74%（其中 vendored 约 97 个文件） |
| 合计 | 179 | 28,607 | — |

**模块边界核验**：`engine/src/main` 对 `com.tyranor.next.*` / `com.example.tyranornext.*` 的引用数为 **0** —— app→engine 单向依赖成立，契约仅经 Intent extras 与 prefs 键（EnginePrefs.kt）传递，与审计结论一致。

**churn 榜（全历史 Top 6）**：GameScreen.kt ×34、EngineLauncher.kt ×23、EngineScanner.kt ×21、SettingsScreen.kt ×19、HomeScreen.kt ×15、AppSettingsActivity.kt ×13——**高频迭代全部集中在 app 层 UI 与编排**，engine 自有代码改动频率低（符合「引擎层稳定宿主」定位）。

## 3. 核心链路 Trace

路径缩写同 04 报告：`app/...` = app/src/main/java/com/tyranor/next，`engine/...` = engine/src/main/java。

### T1 应用冷启动链
```
MainActivity.onCreate (MainActivity.kt:19)
  ├─ TyranorNextTheme { setContent }                    ← AppThemeColors.primary 全局主题色单点 (theme/AppThemeColors.kt)
  ├─ EnginePluginBootstrap.provisionIfNeeded (:25)      ← 首启解压 assets/nativeplugins/<engine>.zip → filesDir/engine_plugins/
  │    └─ extractPluginZip:144 → NativePluginManager 安装目录协议 engine_plugins/<id>/current/{manifest.json, arm64-v8a/*.so}
  └─ MainScreen (ui/main/MainScreen.kt:59)
       ├─ LiquidGlassNavigationBar (ui/common/LiquidGlassNavigation.kt) ← backdrop 液态玻璃
       ├─ HorizontalPager 四 Tab：HomeScreen / GameScreen / LibraryTab / SettingsScreen
       └─ 页面转场 anim/page_slide_*（水平移动）
```
热区：MainScreen 承接四 Tab 装配；EnginePluginBootstrap 是冷启动唯一 IO 阻塞点。

### T2 游戏库扫描链（扫描 → 识别 → 持久化 → 展示）
```
GameScreen/SettingsScreen 触发
  → EngineScanner.scanAll/rescanLibrary/incrementalScan (scanner/EngineScanner.kt:287/298/323)
     ├─ loadRoots/saveRoot (:249)                       prefs game_scanner · scan_roots
     ├─ scanRootIncremental(:344, SAF) / scanRootIncrementalFile(:442, File) → traverseDirectories:392
     ├─ detectEngine(DocumentFile):519 / detectEngine(File):615 → Detection(engine, confidence, launchTarget)
     ├─ applyLocalCover:427 / findLocalCoverUri:434      本地封面发现
     ├─ serializeGame:188 ⇄ parseGame:204                \u0001 分隔持久化（分隔符清洗已测）
     └─ saveGames:88 → gamesCache 进程内缓存             ← 切页免重复解析
  → GameScreen sortGames:272（bracketTag/拼音） → GameGrid:911 / GameCard:942 渲染
```
热区：EngineScanner 为 700 行多职责枢纽（扫描+识别+序列化+缓存+SAF 映射），churn 21。

### T3 启动分发链（app → engine 唯一入口）
```
HomeScreen/GameScreen 点击启动 → EngineLauncher.launch (scanner/EngineLauncher.kt:53)
  ├─ UNKNOWN 拦截 → 返回错误文案 → launchError 弹窗        ← F-09 整改后行为
  ├─ resolveGameDirectory:622   SAF documentId → 真实路径（safUriToPath → _data 兜底查询）
  ├─ requestAllFilesAccessIfNeeded:102               MANAGE_EXTERNAL_STORAGE 引导（needsAllFilesAccess:129 最小化判断）
  ├─ EnginePluginBootstrap.ensureForLaunch           插件就绪校验
  ├─ ensureKrSaveDir:350（KRKR 独有存档目录预建，SAF 兜底 :365/:380）
  ├─ buildIntent:139 分支：
  │    KIRIKIRI  → buildKirikiriIntent:229（内核选择 effectiveKrKernel:330；入口挑选 pickKrActivateEntry:551）
  │    ONS       → 内联 :150（gameargs 组装、scoped 存档目录 :170-178）
  │    TYRANO/RPG_MV/RPG_MZ/VN/WEB_OTHER → buildWebIntent:504（WebGameType 标记 :521-529）
  │    ARTEMIS   → buildArtemisIntent:440（版本路由 :484-488、补丁策略 applyArtemisBasePatchIfNeeded:541）
  ├─ 注入主题 extras（darkMode/primaryColor/themeColor* :214-221）→ 双套 key 兼容两套引擎样式系统
  └─ startActivity → recordRecentGame → null
```
热区：EngineLauncher churn 23 + fan-out 19，是全项目扇出最广的自有类（分发枢纽）。

### T4 Kirikiri 链（进程 :kirikiri2）
```
Kirikiroid139/134/126.java → KirikiroidLauncherBaseActivity.java:61 (extends KR2Activity)
  onCreate:97 → onLoadNativeLibraries:486（加载状态机 nativeBridgeInitialized/firstFrameRendered :70/:72）
    ├─ NativeLibraryLoader.loadKirikiroid139/134/126 (.kt:15/26/35)  System.load 插件 so（SDL2/ffmpeg/libgame*.so）
    ├─ System.loadLibrary("krkr_bridge_v2")                ← KR2Activity.java:299 等 4 处
    │    → JNI_OnLoad (krkr_bridge.cpp:725) RegisterNatives 六方法 (:743-756)
    ├─ NativeBridge.initialize/launch/interceptor/relocate/write (NativeBridge.kt:16)
    │    initialize:589 dlopen libgame*.so 解析 getScene/startupFrom
    │    launch:596   startupFrom(TVPMainScene, path) 进入 TJS
    │    relocate:652 GOT 打桩 14 libc 符号重定向 SAF 前缀（open/fopen/stat/rename/unlink…）
    └─ 渲染 KRGLSurfaceView.java:10（按 krEngineVersion 分触摸管线）+ KrDialog*/KrTextInputView 自绘弹窗
```
热区：KR2Activity.java（fan-in 12 / branch 133）、krkr_bridge.cpp（766 行原生桥）。SDL2 Java 层为其渲染基座（org.libsdl.app，反编译产物，F-11）。

### T5 krkrsdl3 链（进程 :krkrsdl3）
```
Krkrsdl3Activity.kt:28 (extends KRKRActivity.java:15 extends org.libsdl3.app.SDLActivity)
  ├─ KRKRActivity static{} 加载 SDL3 + krkrsdl3 (:22-25)
  ├─ argv 协议：Intent extra "gameargs" 首项=启动文件绝对路径（buildKrkrsdl3Args:302 组装 -render/-savedir）
  ├─ SDL3 Java 基座：SDLActivity.java(1988 行, fan-in 23) → SDLMain 线程进 native mainloop
  └─ native→Java 回调：KRKRCall.java:27（CountDownLatch 跨线程同步菜单/对话框）
```
热区：**org.libsdl3.app 包平均热力全仓库第一（19.3）**，SDLActivity.java 为 HEAT 最高单元格（78.0）。任何 SDL3 升级都是高风险动作。

### T6 Tyrano/Web 链（进程 :tyrano，纯 WebView 零原生）
```
TyranoActivity.kt:49
  ├─ detectWebGameType:183（Tyrano/RPG/RMMZ/VN/WebOther）
  ├─ AsarArchive.kt:22 解析 Electron ASAR（magic=4 校验；已测 8 用例）
  ├─ TyranoLocalHttpServer.kt:24  ServerSocket(0,50,"127.0.0.1") 随机端口 loopback（F-13 已核实）
  │    └─ GET 服务游戏目录/ASAR + 注入 assets/engine/__tyrano__.js 等 hook JS + scriptAppends
  ├─ WebView loadUrl http://localhost:<port>/index.html (:171)   ← networkSecurityConfig 白名单覆盖（F-03）
  ├─ JS 桥：RpgMakerSaveBridge / TyranoJsBridge @JavascriptInterface (:694+) ↔ 存档读写
  └─ TyranoStorage.kt:9 沙箱 KV（单键 ≤8MB）
```

### T7 Artemis 链（三进程 :artemis / :artemis.compat / :artemis.compat.v2）
```
ArtemisActivityV1/V2/V3.java (Manifest meta-data lib_name=artemis_loader)
  → ArtemisLauncherBaseActivity.java:12 → NativeActivity bootstrap dlopen libartemis*.so
     Vx.loadEngineLibrary() System.load 登记 Java native（V1:11-14）
  ArtemisActivity.kt:10 audio_bridge 预载(:12) external fun(:16-25) PlayVideo→VideoViewActivity(:31)
  视频 ijkplayer：IjkMediaPlayer.java(77 fun) → IMediaPlayer 接口(fan-in 19)
  native FindClass 反查桥：moe/artemis/gui/Dialog.kt:8 静态注册表 + R8 keep(consumer-rules.pro:14-17)
  启动前 .pfs 补丁：ArtemisPfsUnpacker.applyBasePatch ← EngineLauncher:541（已测 5 用例端到端）
```

### T8 ONS 链（进程 :ons）
```
ONScripter.java:41 (extends SDL2 SDLActivity; Yuri 0.7.6)
  OnsLibLoader.load (.kt:20)：字体拷贝 → loadLibrary("ONSPatch") → libonsyuri
  NativeLibraryLoader.loadOns:44 按序加载 SDL2/image/mixer/ttf/lua/bz2/jpeg/libonsyuri
  设置 OnsSettings.kt:9（prefs onsyuri gameargs JSON）；视频 OnsVideoActivity.kt:16 系统 VideoView
```

### T9 设置三层覆盖链与周边服务
```
应用外观   AppSettingsStore (prefs app_settings)：theme_color/nav_style/scan_depth/sort/theme_mode
引擎全局   EngineSettingsStore (yukihub_prefs + onsyuri)：KR 版本/内核/渲染器/内存/FPS、ONS gameargs、Artemis 版本/补丁策略、ty_scoped
单游戏覆盖 PerGameSettingsStore (tyranor_game_overrides)：getStr/getBool → 各处 `per-game ?: global` 合并模式
UI         SettingsScreen:74 / EngineSettingsDetailScreen:227 / PerGameSettingsScreen / AppSettingsActivity:70(色调轮盘)

周边：VndbCoverService.kt:24（搜索/下载/限速 https）
      GitHubUpdateChecker.check → compareVersions（已测 6 用例）
      KrkrOnlinePatchService.kt:27（zeas2.github.io https 拉取 → copyIntoGameDir:123）
      GameSaveManager.kt:16（resolveSaveLocation 已测 8 分支；export/import zip 带数量与体积上限）
```

## 4. 热点图

完整可视化见 [07-hotspot-map.svg](./07-hotspot-map.svg)：每个色块 = 一个源文件，按包分组、组内按热力降序排列，颜色由蓝（低）到红（临界），悬停可查看六项度量。

### Top 15 自有关注点（剔除 vendored 后的重排）

| # | 文件 | HEAT | fan-in | churn | SLOC | branch | 关注理由 |
|---|---|---|---|---|---|---|---|
| 1 | app/.../ui/pages/GameScreen.kt | 45.7 | 0 | **34** | 998 | 48 | churn 全仓库第一；UI 状态+弹窗+排序集中 |
| 2 | app/.../scanner/EngineLauncher.kt | 43.4 | 4 | 23 | 611 | 85 | 分发枢纽 fan-out 19；五引擎分支汇聚 |
| 3 | engine/.../org.tvp/kirikiri2/KR2Activity.java | 38.7 | 12 | 1 | 517 | 133 | kirikiri 宿主核心，分支密度最高档 |
| 4 | app/.../scanner/EngineScanner.kt | 33.6 | 6 | 21 | 633 | 82 | 多职责枢纽（扫描/识别/序列化/缓存/SAF） |
| 5 | engine/.../tv/danmaku/ijk/IMediaPlayer.java | 33.0 | 19 | 1 | 113 | 0 | 接口扇入之王（vendored，稳定勿动） |
| 6 | app/.../ui/pages/SettingsScreen.kt | 26.1 | 1 | 19 | 653 | 25 | 高频迭代设置面板 |
| 7 | app/.../settings/AppSettingsStore.kt | 20.9 | 10 | 7 | 98 | 3 | fan-in 10 的小型高扇入配置源 |
| 8 | engine/.../com.akira/.../KirikiroidLauncherBaseActivity.java | 24.0 | 4 | 5 | 806 | 138 | KR 加载状态机 + 138 分支 |
| 9 | app/.../ui/pages/AppSettingsActivity.kt | 18.0 | 1 | 13 | 424 | 12 | 色调轮盘等外观功能 |
| 10 | app/.../ui/pages/HomeScreen.kt | 18.0 | 0 | 15 | 375 | 12 | 快捷启动位/最近游玩 UI |
| 11 | engine/.../com.core/nativeplugin/NativePluginManager.kt | 17.7 | 7 | 1 | 455 | 62 | 插件协议实现（安全敏感） |
| 12 | app/.../ui/pages/KrkrOnlinePatchActivity.kt | 16.3 | 1 | 12 | 300 | 7 | 在线补丁 UI+服务 |
| 13 | engine/.../com.core/tyrano/TyranoActivity.kt | 20.1 | 2 | 5 | 739 | 67 | Web 引擎宿主 + JS 桥 |
| 14 | engine/bridge/NativeBridge.kt + krkr_bridge.cpp | ~10 | 9 | — | 206+766 | — | JNI 契约双侧（cpp 未计入本表统计口径） |
| 15 | engine/.../com.yuri/onscripter/ONScripter.java | 17.1 | 2 | 1 | 551 | 106 | ONS 宿主 |

### 包级聚合 Top 8（平均热力）

| 包 | 文件 | SLOC | 平均 HEAT | 峰值 | 定性 |
|---|---|---|---|---|---|
| engine/org.libsdl3.app | 12 | 5,318 | **19.3** | 78.0 | 全仓库最热包；SDL3 基座，升级需回归三条引擎链 |
| engine/com.yuri.onscripter | 1 | 551 | 17.1 | 17.1 | 单文件包，稳定 |
| app/com.tyranor.next.scanner | 9 | 2,408 | 15.8 | 43.4 | **自有代码最热包**：分发+扫描+存档+插件引导 |
| app/com.tyranor.next.ui.pages | 12 | 3,784 | 14.5 | 45.7 | churn 密集区（34+23+19+15…） |
| engine/org.libsdl.app | 22 | 3,861 | 11.8 | 63.7 | SDL2 反编译基座（F-11 待重整） |
| engine/com.core.nativeplugin | 4 | 806 | 11.8 | 17.7 | 插件协议（SHA-256/zip-slip 防护所在） |
| engine/org.tvp.kirikiri2 | 9 | 1,087 | 10.8 | 38.7 | KR2 宿主群 |
| engine/com.core.tyrano | 4 | 1,387 | 9.1 | 20.1 | Tyrano Web 栈 |

## 5. 热点解读与建议

1. **SDL3/SDL2 基座是全局风险放大器**。两个 SDLActivity 合计 fan-in 46 处引用（所有 SDL 系引擎 Activity 继承它们），且 SDL2 侧为反编译产物。任何触碰 SDL 层的改动必须跑全五条引擎链回归；F-11 的官方源对照重整应作为独立专项排期。
2. **app 层 churn 高度集中于 GameScreen.kt（×34）**。该文件同时承载网格渲染、排序、四个弹窗、长按面板与启动触发，是回归概率最高的单点。建议拆分：`GameGrid/GameCard` → ui/components；`Rename/Vndb/LaunchFile 弹窗` → dialogs 子包；排序逻辑已有 sortGames 可提为独立用例覆盖。
3. **EngineScanner.kt 呈 god-object 苗头**（扫描遍历 + 双路检测 + 序列化 + 缓存 + SAF 映射 + 封面发现共居一类）。低风险拆分方向：serialize/parse 与缓存独立为 GameStore；safUriToPath/isRemovableStoragePath 独立为 PathResolver（GameSaveManager/EngineLauncher 均在用，拆出可同时降低三处耦合）。
4. **EngineLauncher.kt 是契约枢纽但测试薄弱**：五引擎 Intent 组装目前零单测覆盖（UNKNOWN 拦截除外）。其纯函数部分（pickKrActivateEntry、parseStoragePath、normalizeKrkrsdl3Renderer、safeSharpnessValue）可低成本补测——建议列入下一轮 F-01 延伸。
5. **engine 自有代码 churn 低、结构稳**：nativeplugin 协议、tyrano 栈、kirikiri 桥近年改动少且关键路径已有测试兜底（AsarArchive/PfsUnpacker/HttpServer loopback）。维持现状即可。
6. **IMediaPlayer/EnginePrefs 等高扇入接口是隐性契约**：改动签名会波及 10~20 个文件，应保持冻结并在 THIRD-PARTY-NOTICES/04 映射表中持续标注。

## 6. 结论

- 架构健康度良好：模块单向依赖成立、进程隔离清晰、自有热点集中在 app 编排层且与开发活跃度吻合——热区即活跃区，无「无人敢动」的暗雷区（vendored SDL2 除外，已知并立项）。
- 风险优先级：SDL 层稳定性 > GameScreen 拆分 > EngineScanner 职责分离 > EngineLauncher 纯函数补测。
