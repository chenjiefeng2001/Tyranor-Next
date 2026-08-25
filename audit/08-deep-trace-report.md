# 08 深度可追溯 Trace 报告（契约 / 隐藏依赖 / JNI 面）

基线：commit `8747664`。本报告是 [06-code-trace-report.md](./06-code-trace-report.md) 的证据层深化：
06 回答「链路怎么走、哪里最热」，08 回答「**每条跨模块契约是否闭合、每个动态依赖落在哪**」。
全部结论来自脚本提取的 `文件:行号` 证据，数据文件 [tools/deep-trace-data.json](./tools/deep-trace-data.json)，
复现：

```bash
python audit/tools/deep_trace.py        # 提取并打印全部证据链
python audit/tools/hotspot_trace.py     # （06 报告的热点度量）
```

> 口径说明：Intent getter 支持字符串字面量匹配；经 Kotlin/Java **常量间接**引用的键已在第 4 节人工补核并标注。

---

## A. 入口组件全景（8 进程 × 17 组件）

| 进程 | 组件 | exported | 说明 |
|---|---|---|---|
| :main | MainActivity | **true** | 唯一对外入口 |
| :main | SaveManagementActivity | false | 存档管理 |
| :main | KrkrOnlinePatchActivity | false | KR 在线补丁 |
| :main | EngineSettingsActivity | false | 引擎设置 |
| :main | PerGameSettingsActivity | false | 单游戏设置 |
| :main | AppSettingsActivity | false | 应用外观 |
| :kirikiri2 | Kirikiroid139 / 134 / 126 | false | KR 三版本宿主 |
| :krkrsdl3 | Krkrsdl3Activity | false | krkrsdl3 宿主 |
| :tyrano | TyranoActivity | false | Web 栈宿主 |
| :artemis | ArtemisActivityV1 + VideoViewActivity | false | V1 + ijk 视频页 |
| :artemis.compat | ArtemisActivityV2 | false | V2 |
| :artemis.compat.v2 | ArtemisActivityV3 | false | V3 |
| :ons | ONScripter + OnsVideoActivity | false | ONS 宿主 + 系统视频页 |

无 Service / Receiver / Provider；除 MainActivity 外全部 `exported=false`，攻击面收敛于单 Activity。

复核：`python audit/tools/deep_trace.py`（A 节）或直接读两份 Manifest。

## B. 隐藏依赖清单（静态 import 之外的动态边）

### B1 原生库加载点（Java/Kotlin 层 12 处）

| 库名 | 加载位置 |
|---|---|
| krkr_bridge_v2 ×4 | Kirikiroid126.java:28、Kirikiroid134.java:16、Kirikiroid139.java:16、KR2Activity.java:299 |
| SDL3 + krkrsdl3 | KRKRActivity.java:23-24（static 块） |
| ONSPatch | OnsLibLoader.kt:28 |
| artemis_audio_bridge | ArtemisActivity.kt:13 |
| ijkffmpeg / ijksdl / ijkplayer | IjkMediaPlayer.java:320-322 |

`System.load(绝对路径)` ×4：ArtemisActivityV1/V2/V3.java 与 NativeLibraryLoader.kt:57（插件目录 so）。
native 层：krkr_bridge.cpp dlsym×3（:156-158）；ByteHook.java:8 声明 dlopen libbytehook.so。

复核：`rg 'System\.loadLibrary|System\.load\(' engine/src/main`

### B2 反射点（5 类目标）

| 目标 | 位置 | 判定 |
|---|---|---|
| `com.apps.LauncherActivity.launcherPrimaryColor` ×3 | KirikiroidLauncherBaseActivity.java:694/719/748 | **遗留兼容反射**：类在本仓库不存在，`try/catch(Throwable ignored)` 兜底（源码注释含 TODO 移除），恒走 Intent extra 主路径 → 无风险，属可清理项 |
| `org.libsdl.app.SDLAudioManager` | KR2Activity.java:348（forName+getDeclaredField） | SDL2 上游机制，类存在 ✓ |
| ReLinker 装载器 | org.libsdl/app+3 的 SDL.java:31/32/67/72 | getDeclaredMethod 反射调用，存在则用、缺失回退 System.loadLibrary ✓ |

JNI FindClass ×5：全部指向 `bridge/NativeBridge`（krkr_bridge.cpp:199/307/337/724/735）——与 E 节 RegisterNatives 属同一契约面。

## C. Intent extras 契约矩阵（41 键）

分布：**app→engine 单向 24 键** · engine 内部 6 · 混合（app put + engine 中转 put/get）4 · 仅生产 7。

### C1 app→engine 主干键（24 个，全部有消费方）

| 键 | 生产 | 消费 |
|---|---|---|
| path / gamedir / projectRoot / rootUri / gamePath | EngineLauncher.kt:188-191 等 | KirikiroidLauncherBase:178,788 / KrPathUtils:40-43 / TyranoActivity:352-356 / ONScripter:80-82 等 |
| scopedSaveDir / scopedSaveRoot / gameSaveRoot | EngineLauncher.kt:267,273,275,529,530 | KR2Activity:254,437,472 / KrPathUtils:74,88 / ArtemisLauncherBase:31 |
| safFileFallback | EngineLauncher.kt:271 | KR2Activity:246 / KirikiroidLauncherBase:502 / **NativeBridge.kt:98**（传入 relocate） |
| krEngineVersion | EngineLauncher.kt:279 | KrGLSurfaceView.java:20（触摸管线分派） |
| krkr_engine_prefs / default_font / force_default_font | EngineLauncher.kt:285-293 | KirikiroidLauncherBase:223,236,261 |
| darkMode / primaryColor / themeColor* ×5 | EngineLauncher.kt:213-219 | KirikiroidLauncherBase:689,744 / KrDialogStyle.java:203-207 / Krkrsdl3Activity.kt:217 |
| orientation / focus / artemisAutoFallback | EngineLauncher.kt:246-247,497 | ArtemisLauncherBase:47,102 / Krkrsdl3Activity:63,123 / KirikiroidLauncherBase:810,849 |
| type（WebGameType） | EngineLauncher.kt:526 | TyranoActivity.kt:111 |

### C2 常量间接消费键（脚本字面量匹配不到，人工补核为**活键**）

| 键 | 常量定义 | 实际消费 |
|---|---|---|
| gameargs | KRKRActivity.java:18 `SHAREDPREF_GAMECONFIG`；OnsSettings.kt:21 `EXTRA_GAME_ARGS` | KRKRActivity.java:56,59（ArrayList/StringArray 二态）；OnsSettings.kt:32 |
| gameuri | OnsSettings.kt:22 | ONScripter.java:83 |
| ignorecutout | OnsSettings.kt:23 | ONScripter.java:88 |

复核：`rg 'SHAREDPREF_GAMECONFIG|EXTRA_GAME_' engine/src/main`

### C3 仅生产键逐键判定（7 个）

| 键 | 生产点 | 判定 |
|---|---|---|
| engineLibName | EngineLauncher.kt:496 → ArtemisLauncherBase:118 重放 | **活键（原生消费）**：libartemis_loader.so 二进制内含该字符串（Select-String -Quiet = True），bootstrap 按其拼 `lib<name>.so` 路径 |
| launchMode ×5 / launchTarget ×5 | EngineLauncher.kt:192-193,244-245,269-270,491-492,517-518 | Java/Kotlin 层无消费方；定位为**诊断/向前兼容标记**（日志排查用）。无害，可在下次契约清理时决定去留 |
| command | IjkVideoView.java:114 | ijk 内部自产（同文件后续读取走本地字段），非跨进程契约 |

孤儿消费 1 例：`GAME_DIR` ← VideoViewActivity.kt:60，全仓库无生产方；消费侧可空容错（resolvePath(rawPath, gameDir)），属上游遗留可选键，不构成缺陷。

## D. SharedPreferences 契约

| prefs 文件 | 归属 | 直接访问点 |
|---|---|---|
| game_scanner | app 扫描库 | EngineScanner（经 PREFS 常量，见注） |
| app_settings | app 外观 | AppSettingsStore（经常量） |
| yukihub_prefs | app 写 / engine 读 | EngineSettingsStore + engine 侧 2 处 |
| onsyuri | app 写 / engine 读 | OnsSettings.kt:32（engine 读 gameargs JSON） |
| tyranor_game_overrides | app 单游戏覆盖 | PerGameSettingsStore（经常量） |
| **hidapi** | **engine 自建** | org.libsdl/app HIDDeviceManager（SDL HID 设备缓存） |
| **krkr_bridge_diagnostics** | **engine 自建** | KR 桥诊断状态 ×2 |

新发现：engine 侧存在两个自治 prefs（hidapi / krkr_bridge_diagnostics），此前审计文档未记载；
F-04 关闭 allowBackup 后它们同样不入云备份，行为一致无需处理。
注：部分 store 以常量变量传参打开，字面量正则只命中 5 个直书文件名，归属列已结合常量定义人工补全。

## E. JNI 符号面核对

### E1 显式注册桥（自有代码）

| Java/Kotlin 声明 | C++ 注册 | 结果 |
|---|---|---|
| bridge/NativeBridge.kt:21-26 `initialize / isLaunchSceneReady / launch / interceptor / relocate / write` | krkr_bridge.cpp:745-755 RegisterNatives 同名六项 | **6/6 精确匹配 ✓** |

krkr_bridge.cpp 无 `Java_*` 导出（纯 RegisterNatives 方案），另有 JNI_OnLoad(:725)。
Artemis 侧 `external fun` 8 个（ArtemisActivity.kt:17-36 七项 + moe/artemis/gui/Dialog.kt:23 OnClose）
由预编译 libartemis_loader.so 内部注册（不可静态比对，运行时以 R8 keep 保命，consumer-rules.pro:14-17）。

### E2 名称约定绑定（vendored，200 个 Java native 方法）

| 归属库 | 代表类 | 数量级 |
|---|---|---|
| libSDL2.so / libSDL3.so | org.libsdl(.3) SDLActivity/SDLControllerManager/HIDDeviceManager | ~90 |
| libgame*.so（Kirikiri TVP） | org.cocos2dx.lib 渲染回调 + org.tvp.* | ~60 |
| libonsyuri.so | com.yuri.onscripter.nativeInitJavaCallbacks 等 | ~10 |
| libartemis*.so / ijkplayer | com.ies_net.artemis / tv.danmaku.ijk | ~40 |

名称约定绑定依赖 `-keepclasseswithmembernames class * { native <methods>; }`（consumer-rules.pro:18-20）——该规则是全局保命线，任何 ProGuard 调整不得移除。

## F. 资产引用链

| 资产 | 代码锚点 | 闭环 |
|---|---|---|
| assets/nativeplugins/&lt;id&gt;.zip | EnginePluginBootstrap.kt:148（模板串 `$ASSET_PLUGIN_DIR/$engineId.zip`） | 构建期 Zip 任务(app/build.gradle.kts:23-39) → 首启解压 → NativeLibraryLoader System.load |
| assets/engine/__tyrano__.js / __rpg__.js / __rmmz__.js / __hook_rmmz_core.js / __hook_rmmz_managers.js | TyranoActivity.kt:748-752 五常量 → loadAsset → TyranoLocalHttpServer 注入 | app assets ↔ engine 常量一一对应，无悬空资源 ✓ |
| engine assets locale/*.xml、ui/*.csb、字体 | EngineUiText / Cocos UI | 见 03 报告 §4 |

---

## G. 本轮新增发现汇总（均可按上文命令复核）

| # | 发现 | 等级 | 建议 |
|---|---|---|---|
| N-01 | `com.apps.LauncherActivity` 遗留反射 ×3，目标类不存在但 try/catch 兜底 | Low | 随上游迁移完成后删除（源码已有 TODO） |
| N-02 | `launchMode`/`launchTarget` 共 10 处生产、0 处消费 | Low | 定位为诊断标记保留，或写入 04 映射表注明「无消费方」防误判 |
| N-03 | `GAME_DIR` 孤儿消费（VideoViewActivity.kt:60，可空容错） | Info | 上游遗留可选键，保持现状 |
| N-04 | engine 自治 prefs：hidapi / krkr_bridge_diagnostics 未见于既有文档 | Info | 已补记于本文档 D 节 |
| N-05 | NativeBridge 六方法契约 6/6 闭合；41 个 extras 键中 34 个确认双向闭合、3 个常量间接闭合 | 正面 | 契约健康度高；建议 E1 表随 EnginePrefs.kt 一同作为冻结契约维护 |

## H. 可追溯性声明

- 本文每个表格行的行号锚点均由 `deep_trace.py` 在基线 commit 上机械提取，非人工回忆；
- 行号会随后续提交漂移，引用前请重跑脚本或以 deep-trace-data.json 为准；
- 与 04 映射表的分工：04 是「功能视角」的人读地图，08 是「契约视角」的机器可验证据链。
