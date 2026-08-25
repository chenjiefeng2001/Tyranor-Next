# 03 engine 模块审计

基线：commit `0da87aa`。namespace `com.core.engine`（engine/build.gradle:6），Android Library，compileSdk 36 / minSdk 26 / NDK 28.0.13004108(:8) / CMake 3.22.1(:35) / 仅 arm64-v8a(:16) / Java 17。依赖仅 appcompat、documentfile、kotlin-stdlib(:50-54)。仅 `src/main` 一个源集，无测试。

## 1. 源码构成

- 自有代码约 43 个文件（Java/Kotlin 混排于 `src/main/java`）
- vendored 第三方约 97 个：Cocos2d-x Java 层(15, org.cocos2dx.lib)、SDL2 Java 层(22, org.libsdl.app，SDLActivity.java:8 有 JADX 反编译注释痕迹)、SDL3 Java 层(12, org.libsdl3.app)、ijkplayer Java 绑定(48+, tv.danmaku.ijk)、ByteHook/ShadowHook Java API 壳
- C++ 仅 2 文件：`cpp/CMakeLists.txt`(9 行) 与 `cpp/krkr_bridge.cpp`(766 行)

## 2. 五条引擎运行链路

### ① Kirikiri —— 进程 `:kirikiri2`
```
Kirikiroid139/134/126.java:7 (Manifest L14-40, singleInstance)
  └─ KirikiroidLauncherBaseActivity.java:61 (extends KR2Activity)
       onCreate:97 → onLoadNativeLibraries:486
       加载状态机: nativeBridgeInitialized:70 / firstFrameRendered:72
  ├─ NativeLibraryLoader.loadKirikiroid139/134/126 (NativeLibraryLoader.kt:15/26/35)
  │    System.load 插件目录 libSDL2/libffmpeg/libgame*.so
  ├─ System.loadLibrary("krkr_bridge_v2") (Kirikiroid139.java:16; KR2Activity.java:299)
  │    → JNI_OnLoad (krkr_bridge.cpp:725) RegisterNatives 绑定 bridge/NativeBridge 六方法 (:743-756)
  ├─ NativeBridge.kt:16 (object) external fun initialize/isLaunchSceneReady/launch/interceptor/relocate/write (:21-26)
  └─ krkr_bridge.cpp:
       initialize:589 dlopen 游戏库并解析 getScene/startupFrom 符号
       launch:596   startupFrom(TVPMainScene, path) 启动 TJS
       interceptor:644 设置 gPathPrefix
       relocate:652 dl_iterate_phdr + GOT 打桩 14 个 libc 符号(open/fopen/stat/access/rename/unlink/mkdir/opendir 等)重定向到 SAF 前缀
       write:702    白名单写回
  UI 层: KR2Activity.java:33 (extends Cocos2dxActivity) + KrGLSurfaceView.java:10(按 krEngineVersion 分触摸管线:14-26)
        弹窗/输入: KrDialogModel.kt:7、KrDialogStyle.java:29、Show*Runnable.java、KrTextInputView/KrInputConnection
路径归一化: KrPathUtils.kt:10 (normalizeFilePath:21 / canonicalizeKrStoragePath:37-50)
```

### ② krkrsdl3 —— 进程 `:krkrsdl3`
```
Krkrsdl3Activity.kt:28 (extends KRKRActivity; onCreate:39; 方向锁防 surface 竞态 :30-36)
  └─ KRKRActivity.java:15 (extends org.libsdl3.app.SDLActivity)
       static{ loadLibrary("SDL3"); loadLibrary("krkrsdl3"); } :22-25
       argv 协议: Intent extra "gameargs" (SHAREDPREF_GAMECONFIG, :20)
  native→Java 回调 UI 工具: KRKRCall.java:27 (CountDownLatch 跨线程同步 :33-41, MenuItemType:236)
```

### ③ Tyrano —— 进程 `:tyrano`（纯 WebView，零原生库）
```
TyranoActivity.kt:49
  ├─ TyranoLocalHttpServer.kt:24 (线程池+ServerSocket, start:70/stop:72; 服务明文目录或 ASAR 并注入 hook JS)
  ├─ AsarArchive.kt:22 (Electron ASAR 只读解析器, magic=4 头校验 :41-46)
  ├─ JS 桥注册: RpgMakerSaveBridge/TyranoJsBridge (TyranoActivity.kt:167-168)
  │    @JavascriptInterface 存档/控制方法 :694-728 (openUrl:707 等)
  ├─ TyranoStorage.kt:9 (沙箱化 KV 存储, 单键 ≤8MB)
  └─ 外部 http 跳转 openExternalHttpUrl:336
hook 脚本源: app assets/engine/__tyrano__.js / __rpg__.js / __rmmz__.js / __hook_rmmz_*.js
```

### ④ Artemis —— 三进程 `:artemis` / `:artemis.compat` / `:artemis.compat.v2`
```
ArtemisActivityV1/V2/V3.java:6 (Manifest L64-96; meta-data android.app.lib_name="artemis_loader" L73/84/95)
  └─ ArtemisLauncherBaseActivity.java:12 (extends com.ies_net.artemis.ArtemisActivity)
       抽象 loadEngineLibrary():17; getExternalFilesDir 重定向存档 :19-29
  启动机制: NativeActivity 读 lib_name → libartemis_loader.so bootstrap dlopen 真实 libartemis*.so
            → Vx.loadEngineLibrary() 再 System.load 登记 Java native 方法 (V1:11-14)
  ArtemisActivity.kt:10 (open class : NativeActivity; audio_bridge 预载 :12-14; external fun :16-25; PlayVideo→VideoViewActivity :31-40)
  视频: VideoViewActivity.kt:19 (ijkplayer IMediaPlayer 回调)
  native FindClass 反查桥: moe/artemis/gui/Dialog.kt:8 (静态注册表 :25-40; consumer-rules.pro L14-17 keep 保命)
.pfs 基础补丁由 app 侧 ArtemisPfsUnpacker 在启动前解包 (EngineLauncher.kt:541 applyArtemisBasePatchIfNeeded)
```

### ⑤ ONS —— 进程 `:ons`
```
ONScripter.java:41 (extends SDLActivity[SDL2]; Yuri_0.7.6 :43)
  └─ OnsLibLoader.load (OnsLibLoader.kt:20)
       拷 DroidSansFallback.ttf :23 → System.loadLibrary("ONSPatch") :28 → 校验并加载 libonsyuri :39-43
  └─ NativeLibraryLoader.loadOns:44 按序加载 SDL2/image/mixer/ttf/lua/bz2/jpeg/libonsyuri
  视频: OnsVideoActivity.kt:16 (系统 VideoView 全屏)
  设置: OnsSettings.kt:9 (gameargs JSON, prefs onsyuri)
```

跨引擎共性：主题色/语言/方向全部经 Intent extras 传入（EngineThemeColors.fromIntent:32、EngineUiText:12 createConfigurationContext 四语）；双击返回与预测性返回统一 `DoubleBackExit.java:13`；prefs 键契约单点 `EnginePrefs.kt:9`。

## 3. Manifest 审计（engine/src/main/AndroidManifest.xml，124 行）

- 权限：VIBRATE、BLUETOOTH(≤30)、BLUETOOTH_CONNECT、RECORD_AUDIO
- 全部 Activity exported=false；每引擎独立进程 + singleInstance + 独立 taskAffinity（进程隔离设计目的：隔离 SDL2/SDL3 原生符号冲突，见 L13/L42 注释）
- 所有引擎 Activity 固定 sensorLandscape + 全量 configChanges
- 辅助视频页：OnsVideoActivity(:ons)、VideoViewActivity(:artemis)

## 4. jniLibs 与插件包

- `jniLibs/arm64-v8a`（15 个 .so）：libSDL3、libkrkrsdl3、libkrkr_bridge(.so)、libartemis_loader、libartemis_audio_bridge、libijkplayer/ijkffmpeg/ijksdl、libONSPatch、libBugly、libmmkv、libbytehook、libshadowhook(_nothing)、libandroidx.graphics.path
- `src/main/nativeplugins/{artemis,ons}` 默认包（同 app 侧打包内容）
- assets（91 文件）：D3DEmute.tjs(36KB)、中文字体 DroidSansFallback.ttf(7MB)、Default/(控件贴图)、img/(自绘界面图标 30+)、locale/(en_us/ja_jp/zh_cn/zh_tw 四语 XML)、ui/(约 20 个 .csb Cocos Studio 二进制 UI)
- res：values/values-en/values-ja 的 strings.xml（引擎加载文案）+ styles.xml（Theme.Krkrsdl3）

## 5. 构建脚本要点（engine/build.gradle）

- externalNativeBuild：path=src/main/cpp/CMakeLists.txt(:34)、version 3.22.1(:35)；cppFlags -std=c++17(:15)
- jniLibs.srcDirs=src/main/jniLibs(:28)；useLegacyPackaging true(:43-47)
- CMakeLists.txt：唯一 target `krkr_bridge_v2 SHARED krkr_bridge.cpp`(:4)，仅链接 log/dl(:6-9)

## 6. consumer-rules.pro（R8 保底，20 行）

- 整包 keep：org.tvp.kirikiri2.** / org.tvp.krkrsdl3.** / org.libsdl3.app.** / com.yuri.onscripter.** / org.libsdl.app.** / org.cocos2dx.lib.** / bridge.NativeBridge / com.akira.tyranoemu.remote.**（L2-13）
- 依据：SDL JNI_OnLoad 按字面名反查类；Artemis native 以字符串 FindClass("moe/artemis/gui/Dialog") 反查（L14-17）
- 全局 `-keepclasseswithmembernames class * { native <methods>; }`（L18-20）

## 7. 本模块发现汇总

正面：插件导入 SHA-256+zip-slip 防护（NativePluginInstaller.kt:22-23,149,163,179）、ASAR magic 校验、进程级符号隔离。
问题见 05 报告：F-02a（krkr_bridge.cpp:719 注释写 "krkr_bridge"，实际为 "krkr_bridge_v2"）、F-11（vendored SDL2 Java 为反编译产物，许可与维护风险）、F-12（Bugly/mmkv/ijkplayer 等第三方未在 README 许可章节披露）、F-13（TyranoLocalHttpServer 未显式绑定 loopback 需核实——静态扫描未见 bind 地址约束）、F-01b（engine 无任何测试）。
