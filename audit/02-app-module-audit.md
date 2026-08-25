# 02 app 模块审计

基线：commit `0da87aa`。根包：`com.tyranor.next`（app/src/main/java/com/tyranor/next/，39 个 Kotlin 文件，8 个包）。

## 1. 包结构与职责

### 根包（3 文件）
- `MainActivity.kt:19` — 唯一 Launcher 入口；onCreate 起守护线程调 `EnginePluginBootstrap.provisionIfNeeded()`(:25)，edge-to-edge + `TyranorNextTheme` 后 setContent `MainNavigation()`(:35)
- `ui/main/MainNavigation`（Navigation.kt）— 导航壳，委托 `MainScreen()`
- `NavigationKeys.kt` — navigation3 NavKey（`@Serializable data object Main`）

### scanner 包（核心业务，13 文件）
| 文件 | 关键符号:行号 | 职责 |
|---|---|---|
| EngineType.kt | `enum class EngineType`:4 | KIRIKIRI/ONS/TYRANO/RPG_MV/RPG_MZ/VN/WEB_OTHER/ARTEMIS/UNKNOWN |
| ScanGame.kt | `ScanGame`:7、`ScannedRoot`:22、`ScanGameIntents.putGame/getGame`:37/47 | 游戏条目模型与 Intent 序列化契约 |
| EngineScanner.kt (700 行) | detectEngine(DocumentFile):519 / detectEngine(File):615 / scanRootIncremental:344 / serializeGame:188 / parseGame:204 | SAF+File 双路径扫描与引擎识别；prefs `game_scanner` 持久化 |
| EngineLauncher.kt (655 行) | launch:53 / buildIntent:139 / buildKirikiriIntent:229 / buildArtemisIntent:440 / buildWebIntent:504 | 引擎分发启动；ArtemisPatchChoice:49 |
| EnginePluginBootstrap.kt | provisionIfNeeded:51 / ensureForLaunch:60 / extractPluginZip:144 | 首启从 assets 解压安装三引擎插件 |
| GameSaveManager.kt (352 行) | resolveSaveLocation:25 / exportToZip:102 / importFromZip:117 / cleanupAppData:145 | 各引擎存档目录解析与备份导入导出 |
| KrkrOnlinePatchService.kt | search:40 / parseLine:80 / copyIntoGameDir:123 | Kirikiroid2_patch 在线补丁（正则解析 alldata.js） |
| VndbCoverService.kt | fetchBestCover:33 / searchCandidates:84 / downloadCover:156 / throttle:197 | VNDB 封面查询落盘缓存（限速 ≥1100ms，封面 ≤20MB） |
| ArtemisPfsUnpacker.kt (263 行) | needsBasePatch:27 / applyBasePatch:35 / unpackPfs:66 | .pfs 归档解包（条目数/大小上限防 zip 炸弹 :120-154；system_ini 修补 :174） |

### settings 包
- `AppSettingsStore.kt:12` — prefs `app_settings`：theme_color:67 / nav_style:74 / scan_depth:83 / game_sort:89 / theme_mode:105 / tone_switch:112
- `EngineSettingsStore.kt:14` — prefs `yukihub_prefs`（KR 版本:76、内核:81、渲染器:101、内存:109、FPS:115；KR 设置汇总 JSON `buildKrEnginePrefsJson`:119；ONS `Ons`:158-176；Artemis 版本/旋转/补丁:185-196；Tyrano 外网/存档隔离:199-202）
- `PerGameSettingsStore.kt:11` — prefs `tyranor_game_overrides`，每游戏 JSON 快照（load:52 / setStr:72 / setBool:80 / ONS 覆盖合并:88-94 / clear:102）

### theme 包
- `AppThemeColors` — 全局主题色单一状态源（snapshot state），持久化走 AppSettingsStore，默认 #307DEF
- `Color.kt` — 中性色常量（PageGrey/NavWhite/TextColor/UnselectedGrey）
- `Theme.kt` TyranorNextTheme / `MiuixSettingsTheme.kt` — Material 与 Miuix 双主题，均要求 `@NonSkippableComposable` + 函数体内读全局色（AGENT.md 规范）

### ui.pages / ui.main / ui.common
| 文件 | 关键符号:行号 |
|---|---|
| MainScreen.kt | `MainScreen`:59、Tab 数据:46-53、HorizontalPager+双导航栏样式 |
| HomeScreen.kt | HomeScreen:69、QuickLaunchSlot:257、RecentGameRow:297 |
| GameScreen.kt (1049 行) | GameScreen:96、sortGames:272、GameActionsSheet:429、RenameGameDialog:670、VndbSearchDialog:705、LaunchFileDialog:794、GameGrid:911、GameCard:942 |
| EngineScreen.kt | 引擎管理页（静态列表） |
| SettingsScreen.kt | SettingsScreen:74、EngineSettingsDetailScreen:227、EngineSettingsKind:348、importFont:622 |
| AppSettingsActivity.kt | Activity:70、色调轮盘入口:163、色调切换:216、轮盘弹窗:383 |
| EngineSettingsActivity / PerGameSettingsActivity / PerGameSettingsScreen | 全局与单游戏设置宿主 |
| SaveManagementActivity.kt | 存档管理 UI（基于 GameSaveManager） |
| KrkrOnlinePatchActivity.kt | 在线补丁检索下载 UI |
| PlaceholderPage / AppAlertDialog / ui.common(TopBarIcon, TimeFormats, PressIndication, LiquidGlassNavigation, AppSearchField) | 规范收口组件（AGENT.md 强制复用） |

### updater 包
- GitHubUpdateChecker.kt — check:16（GitHub Releases API，仓库 Weiss-UltimateSavior/Tyranor-Next）、compareVersions:104；结果 sealed interface UpToDate/UpdateAvailable/Failed

## 2. Manifest 审计（app/src/main/AndroidManifest.xml）

- 权限：INTERNET、READ_EXTERNAL_STORAGE(maxSdk=32)、WRITE_EXTERNAL_STORAGE(maxSdk=28)、**MANAGE_EXTERNAL_STORAGE**
- application：allowBackup=true、requestLegacyExternalStorage=true、usesCleartextTraffic=true
- Activity ×6：MainActivity(exported=true) + SaveManagement/KrkrOnlinePatch/EngineSettings/PerGameSettings/AppSettings(均 exported=false)
- 无 Service/Receiver/Provider；引擎 Activity 由 :engine 清单合并提供
- ProGuard：proguard-rules.pro 含 `-keep class com.akira.** / com.core.** / com.yuri.**` 与 native 方法保底

## 3. 资产审计

- `assets/engine/`：5 个 JS hook —— `__tyrano__.js`(9.7KB)、`__rpg__.js`(19.8KB，RPG Maker PIXI 补丁)、`__rmmz__.js`(53B 桩)、`__hook_rmmz_core.js`(193B)、`__hook_rmmz_managers.js`(699B)；由 engine 侧 WebView 注入使用
- `nativeplugins/{kirikiroid2,ons,artemis}`：manifest.json(engineId/pluginVersion=1/abi=arm64-v8a) + so。kirikiroid2 约 91MB(libSDL2/libffmpeg/libgame{,126,134}.so)；ons 约 3.9MB(SDL2 全家桶+lua+jpeg+bz2+libonsyuri)；artemis 约 26MB(三个兼容版本 so)。构建期经 Zip 任务打包为 `build/generated/assets/nativeplugins/<engine>.zip`（app/build.gradle.kts:23-39），assets sourceSet 已加入生成目录(:99-103)

## 4. 测试与资源

- 测试：仅 `app/src/test/java/com/tyranor/next/scanner/EngineScannerRpgMvTest.kt`（JUnit4+TemporaryFolder，5 用例，全部针对 detectEngine）。androidTest 依赖已配置但无源集。
- res：无 layout（纯 Compose）；strings.xml 仅 app_name 一条（中文文案硬编码于 Kotlin）；themes.xml 单一 Theme.TyranorNext(parent Material.Light.NoActionBar)；anim 4 个页面转场；xml/backup_rules.xml 与 data_extraction_rules.xml 为空模板。

## 5. 本模块发现汇总

见 05 报告：F-01（测试覆盖）、F-03（明文流量）、F-04（备份规则）、F-05（体积）、F-06（i18n）、F-08（MANAGE_EXTERNAL_STORAGE 合规）、F-09（UNKNOWN 引擎兜底到 TyranoActivity 的静默行为）、F-10（requestLegacyExternalStorage 在 targetSdk 36 下无效标志）。
