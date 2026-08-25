# 04 功能 ↔ 代码位置映射表

基线：commit `0da87aa`。路径缩写：`app/...` = app/src/main/java/com/tyranor/next，`engine/...` = engine/src/main/java。

## A. 应用骨架

| 功能 | 入口/实现 | 位置 |
|---|---|---|
| App 启动 + 插件预装 | MainActivity.onCreate → EnginePluginBootstrap.provisionIfNeeded | app/.../MainActivity.kt:19,25 |
| 四 Tab 主导航 | MainScreen（HorizontalPager + 双导航样式） | app/.../ui/main/MainScreen.kt:59 |
| 液态玻璃底部导航 | LiquidGlassNavigationBar（backdrop layerBackdrop） | MainScreen.kt:31,37；app/.../ui/common/LiquidGlassNavigation.kt |
| 页面转场动画 | anim 资源 page_slide_*（260ms fast_out_slow_in） | app/src/main/res/anim/ |

## B. 游戏库（扫描→展示→管理）

| 功能 | 实现 | 位置 |
|---|---|---|
| 引擎类型定义 | enum EngineType（9 种） | app/.../scanner/EngineType.kt:4 |
| 扫描根目录管理 | saveRoot / removeRootAndGames / loadRoots | EngineScanner.kt:222/237/249 |
| 目录遍历扫描（SAF 与 File 双路径） | scanRootIncremental:344、scanRootIncrementalFile:442、traverseDirectories:392 | EngineScanner.kt |
| 引擎识别核心 | detectEngine(DocumentFile):519、detectEngine(File):615、Detection:517；单测 EngineScannerRpgMvTest.kt | EngineScanner.kt |
| 游戏数据持久化（prefs game_scanner） | serializeGame:188 / parseGame:204 / saveGames:88 | EngineScanner.kt |
| 最近游玩记录 | recordRecentGame:99 / loadRecentGames:105 | EngineScanner.kt |
| 快捷启动位(≤3) | addQuickLaunch:144 / refreshQuickLaunch:160；UI QuickLaunchSlot | EngineScanner.kt; HomeScreen.kt:257 |
| 本地封面发现 | applyLocalCover:427 / findLocalCoverUri:434 | EngineScanner.kt |
| 游戏网格/卡片 UI | GameGrid:911 / GameCard:942 | GameScreen.kt |
| 排序（bracketTag/拼音） | sortGames:272；模式存 AppSettingsStore.getGameSort:89 | GameScreen.kt |
| 长按操作面板 | GameActionsSheet:429 | GameScreen.kt |
| 重命名/VNDB 搜索/启动文件选择弹窗 | RenameGameDialog:670 / VndbSearchDialog:705 / LaunchFileDialog:794 | GameScreen.kt |
| 删除清理 | cleanupDeletedGame(GameScreen.kt) + GameSaveManager.cleanupAppData:145 | GameScreen.kt; GameSaveManager.kt |

## C. 启动分发（app → engine 契约点）

统一入口 `EngineLauncher.launch`（EngineLauncher.kt:53）→ `buildIntent`:139：

| 引擎分支 | 构建函数:行号 | 目标 Activity（engine 进程） |
|---|---|---|
| KIRIKIRI | buildKirikiriIntent:229（SDL3 内核分支 :239；内核选择 :254-256；effectiveKrKernel:330） | Krkrsdl3Activity(:krkrsdl3) 或 Kirikiroid139/134/126(:kirikiri2) |
| ONS | buildIntent 内 :150 | ONScripter(:ons) |
| TYRANO/RPG_MV/RPG_MZ/VN/WEB_OTHER | buildWebIntent:504（WebGameType 标记 :522-525）；UNKNOWN 兜底 :203 | TyranoActivity(:tyrano) |
| ARTEMIS | buildArtemisIntent:440（版本映射 :485-487）；needsArtemisPatchConfirm:89；ArtemisPatchChoice:49 | ArtemisActivityV1/V2/V3(三进程) |
| 全文件权限前置 | requestAllFilesAccessIfNeeded:102 / needsAllFilesAccess:129 | EngineLauncher.kt |
| KR 存档目录 SAF 预创建 | ensureKrSaveDirViaSaf:365 / createSafDirectoryForStoragePath:380 | EngineLauncher.kt |
| KR 启动文件选择 | pickKrActivateEntry:551 / listKrLaunchFiles:591 | EngineLauncher.kt |
| Intent 注入主题/语言 | engine 侧消费：EngineThemeColors.fromIntent:32、EngineUiText:12 | engine/.../com/core/engine/ |

## D. 外置引擎插件体系（nativeplugins）

| 环节 | 实现 | 位置 |
|---|---|---|
| APK 内 zip 打包进 assets | Gradle Zip 任务 ×3 | app/build.gradle.kts:23-39 |
| 首启安装编排 | provisionIfNeeded:51 / extractPluginZip:144 | app/.../scanner/EnginePluginBootstrap.kt |
| 协议常量（engineId/ABI/so 名） | NativePluginConstants | engine/.../nativeplugin/NativePluginConstants.kt:11 |
| 导入校验（整包 SHA-256:149、zip-slip 防护:163,179） | NativePluginInstaller | engine/.../nativeplugin/NativePluginInstaller.kt |
| 安装状态/路径解析 | NativePluginManager | engine/.../nativeplugin/NativePluginManager.kt:33 |
| 运行时 dlopen | loadKirikiroid139/134/126:15/26/35、loadOns:44 | engine/.../nativeplugin/NativeLibraryLoader.kt |

## E. 五条引擎运行链路（engine）

详细调用树见 [03-engine-module-audit.md](./03-engine-module-audit.md) 第 2 节。锚点速查：

| 链路 | 入口 Activity | 关键原生加载点 | JNI/桥接 |
|---|---|---|---|
| Kirikiri(:kirikiri2) | Kirikiroid139.java:7 等 | KirikiroidLauncherBaseActivity.onLoadNativeLibraries:486 | NativeBridge.kt:16 ↔ krkr_bridge.cpp(JNI_OnLoad:725; relocate:652 GOT 挂钩 14 符号) |
| krkrsdl3(:krkrsdl3) | Krkrsdl3Activity.kt:28 | KRKRActivity.java:22-25 (SDL3+krkrsdl3) | argv="gameargs" extra (:20)；KRKRCall.java:27 回调 |
| Tyrano(:tyrano) | TyranoActivity.kt:49 | 无原生库 | JS 桥注册 :167-168；@JavascriptInterface :694+ |
| Artemis(×3 进程) | ArtemisActivityV1/V2/V3.java:6 | meta-data lib_name=artemis_loader (Manifest L73/84/95)；V1.loadEngineLibrary:11-14 | ArtemisActivity.kt external fun:16-25；Dialog.kt FindClass 反查桥 |
| ONS(:ons) | ONScripter.java:41 | OnsLibLoader.load:20 → NativeLibraryLoader.loadOns:44 | SDL2 SDLMain 线程进入 native main |

## F. 设置体系（三层覆盖模型）

| 层 | 存储(prefs 文件) | 关键符号 | 位置 |
|---|---|---|---|
| 应用外观 | app_settings | theme_color:67 / nav_style:74 / scan_depth:83 / game_sort:89 / theme_mode:105 / tone_switch:112 | app/.../settings/AppSettingsStore.kt |
| 引擎全局 | yukihub_prefs（KR/Artemis/Tyrano）+ onsyuri(ONS gameargs) | KR 版本:76、渲染器:101、内存:109、FPS:115；buildKrEnginePrefsJson:119；Ons load/save:158/176；Artemis:185-196；Tyrano:199-202 | app/.../settings/EngineSettingsStore.kt |
| 单游戏覆盖 | tyranor_game_overrides | hasOverride:46 / load:52 / setStr:72 / setBool:80 / ONS 合并:88-94 / clear:102 | app/.../settings/PerGameSettingsStore.kt |
| 设置 UI | — | SettingsScreen:74 / EngineSettingsDetailScreen:227 / EngineSettingsKind:348 / importFont:622 | SettingsScreen.kt；PerGameSettingsScreen.kt |
| 色调轮盘 | — | AppSettingsActivity:70（轮盘入口:163、切换:216、弹窗:383）；全局色源 AppThemeColors | AppSettingsActivity.kt; theme/AppThemeColors.kt |

## G. 周边功能

| 功能 | 实现位置 |
|---|---|
| VNDB 封面搜索/绑定/限速 | VndbCoverService.kt:24（searchCandidates:84 / downloadCover:156 / throttle:197） |
| Kirikiri 在线补丁 | KrkrOnlinePatchService.kt:27（parseLine:80 / copyIntoGameDir:123）；UI KrkrOnlinePatchActivity |
| 存档导出/导入/清除 | GameSaveManager.kt:16（resolveSaveLocation:25 / exportToZip:102 / importFromZip:117 / deleteSaves:135）；UI SaveManagementActivity |
| 应用内更新 | GitHubUpdateChecker.kt:16（compareVersions:104），仓库 Weiss-UltimateSavior/Tyranor-Next |
| Artemis .pfs 解包 | ArtemisPfsUnpacker.kt:18（unpackPfs:66 / shouldExtract:120 / patchSystemIni:174）；启动应用点 EngineLauncher.kt:541 |
| R8 保底规则 | engine/consumer-rules.pro L2-20 + app/proguard-rules.pro |

## H. 跨模块契约速查

| 契约 | 单一来源 | 说明 |
|---|---|---|
| Intent extras 键 | engine/.../com/core/engine/EnginePrefs.kt:9 | engine 不反依赖 app 的前提；**冻结契约，变更须同步 08 报告 C/E 节矩阵** |
| launchMode / launchTarget extras | EngineLauncher 五分支 putExtra | **无运行时消费方**（诊断/向前兼容标记，见 08 报告 N-02），勿误判为断链缺陷 |
| prefs 文件分工 | app_settings / yukihub_prefs / tyranor_game_overrides / game_scanner(app) ；onsyuri(ONS gameargs)；yukihub_prefs 由 app 写、engine 读 | 见 F 节 |
| 插件目录协议 | filesDir/engine_plugins/<engine>/current/{manifest.json, arm64-v8a/*.so} | NativePluginManager.kt:39-41 |
| 引擎进程隔离 | Manifest 每引擎独立进程 + taskAffinity | engine/src/main/AndroidManifest.xml L13-120 |
| hook JS 注入 | app assets/engine/*.js ↔ TyranoLocalHttpServer 注入逻辑 | TyranoLocalHttpServer.kt |
