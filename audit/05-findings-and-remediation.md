# 05 发现问题与整改清单

基线：commit `0da87aa`（2026-08-24）。审计日期：2026-08-25。
严重级别：High（功能/安全/合规风险）＞ Medium（质量/可维护性）＞ Low（规范/文档）。

| 编号 | 级别 | 状态 | 摘要 |
|---|---|---|---|
| F-01 | High | 已整改 | 测试覆盖薄弱 |
| F-02 | Low | 已整改 | 文档与注释偏差（AGENT.md 旧包名；krkr_bridge.cpp 注释库名） |
| F-03 | Medium | 未整改 | usesCleartextTraffic 全局放行 |
| F-04 | High | 已整改 | 备份规则为空模板 + allowBackup=true |
| F-05 | Medium | 未整改 | APK 体积 88.5 MB，插件 so 双重打包 |
| F-06 | Medium | 未整改 | app 侧无多语言资源 |
| F-07 | Medium | 已整改 | CI 未显式安装 NDK/CMake |
| F-08 | Medium | 未整改 | MANAGE_EXTERNAL_STORAGE 上架合规风险 |
| F-09 | Low | 已整改 | UNKNOWN 引擎静默兜底 TyranoActivity |
| F-10 | Low | 已整改 | requestLegacyExternalStorage 为无效标志 |
| F-11 | Medium | 未整改 | vendored SDL2 Java 层含反编译产物痕迹 |
| F-12 | Low | 未整改 | 第三方 SDK 许可披露不全 |
| F-13 | Medium | 已核实（无风险） | TyranoLocalHttpServer 绑定地址未显式约束 |

---

## F-01 [High] 测试覆盖薄弱

**现状**：全仓库仅 `app/src/test/.../EngineScannerRpgMvTest.kt`（5 用例，仅覆盖 detectEngine 的 RPG MV/MZ/VN/WEB 分支）。engine 模块零测试；androidTest 依赖已配置（app/build.gradle.kts:147-150）但源集不存在。
**影响**：EngineLauncher/GameSaveManager/KrkrOnlinePatchService 等核心逻辑回归风险高；AGENT.md 要求的多角度审核缺乏自动化兜底。
**证据**：app/src/test 目录树；engine/src 下无 test/androidTest。
**建议**：优先为纯逻辑类补 JUnit 测试——`GameSaveManager.resolveSaveLocation`(GameSaveManager.kt:25)、`AsarArchive`(AsarArchive.kt:22)、`ArtemisPfsUnpacker.shouldExtract/unpackPfs`(:120/:66)、`EngineScanner.serializeGame/parseGame`(:188/:204)、`GitHubUpdateChecker.compareVersions`(:104)。均为不依赖 Android 框架或可注入临时目录的可测单元。

### 整改记录
- 日期：2026-08-25
- 变更内容：
  - 构建配置：`gradle/libs.versions.toml` 新增 robolectric(4.16.1)/org.json 依赖坐标；`app/build.gradle.kts` 与 `engine/build.gradle` 为 unit test 开启 `includeAndroidResources`/`returnDefaultValues` 并接入 testImplementation。
  - app 模块新增 25 个用例：
    - `EngineScannerPersistenceTest`（6）：serializeGame/parseGame 经公开 API 往返、分隔符清洗、非法行丢弃与 UNKNOWN 兜底、快捷启动独立持久化。
    - `GameSaveManagerResolveTest`（8）：resolveSaveLocation 全引擎分支（KRKR 镜像/回退、ONS/Tyrano/RPG scoped 布局、Artemis 根目录、VN/WEB/UNKNOWN 不可用、不可解析 URI）。
    - `ArtemisPfsUnpackerTest`（5）：按 pfs 格式手工构造分卷端到端解包，覆盖 shouldExtract 白名单、SHA-1 XOR 解密、system.ini patch、list_windows 重命名与 config 翻转、路径穿越拦截、损坏分卷兜底。
    - `GitHubUpdateCheckerVersionTest`（6）：compareVersions 数值逐段比较语义。
  - engine 模块新增 `AsarArchiveTest`（8）：ASAR 头解析、目录/文件判定、路径归一化、坏 magic/坏 header/缺文件异常、close 后软失败。
- 验证方式与结果：`gradlew :app:testDebugUnitTest :engine:testDebugUnitTest` → BUILD SUCCESSFUL，app 30/30（含既有 5 例）、engine 8/8 通过。

## F-02 [Low] 文档与注释偏差

a) `AGENT.md:9-10` 引用包名 `com.example.tyranornext/ui/main/MainScreen.kt` 与 `.../ui/pages/`，实际包为 `com.tyranor.next`。
b) `krkr_bridge.cpp:719-724` 注释写「只能在 :kirikiri2 进程加载 System.loadLibrary("krkr_bridge")」，实际加载名为 `"krkr_bridge_v2"`（Kirikiroid139.java:16、KR2Activity.java:299）。
c) `README.md` 描述 compileSdk 37/minSdk 26/targetSdk 36 与代码一致，但「产物位于 app/build/outputs/apk/debug」未提 NDK/CMake 前置要求（本机曾因缺 build-tools 36.0.0 构建失败）。
**建议**：三处一次性修正；README 构建章节补充工具链前置表。
**整改记录**：（待填）

### 整改记录
- 日期：2026-08-25
- 变更内容：
  - a) `AGENT.md` 技术栈两处包路径改为 `com.tyranor.next/ui/main/MainScreen.kt` 与 `.../ui/pages/`（已核实文件实际存在）。
  - b) `krkr_bridge.cpp:719-724` 注释库名改为 `libkrkr_bridge_v2.so` / `System.loadLibrary("krkr_bridge_v2")`，加载方描述更正为 Kirikiroid126/134/139 与 KR2Activity；`:739` 错误提示文案同步更正（与 CMakeLists.txt 的 `add_library(krkr_bridge_v2 ...)` 及 4 处 Java 加载点一致）。
  - c) `README.md` 构建章节新增「工具链前置要求」表（JDK 17 / android-36+37.0 / build-tools 36.0.0+37.0.0 / NDK r28 / CMake 3.22.1）与 sdkmanager 安装命令、单测运行命令。
- 验证方式与结果：`gradlew assembleDebug` BUILD SUCCESSFUL（注释变更触发原生重编译通过）；文档改动经人工比对实际代码路径。

## F-03 [Medium] 明文流量全局放行

**现状**：application 级 `usesCleartextTraffic="true"`（app Manifest）。
**影响**：所有 http 明文通信被放行，超出实际需要。实际明文需求仅为：Tyrano 本地环回 HTTP 服务（127.0.0.1）、部分游戏资源站可能为 http、KR 补丁站 zeas2.github.io 为 https。
**建议**：改用 networkSecurityConfig，仅对 `127.0.0.1`/`localhost` 放行 cleartext，其余强制 TLS；若游戏目录内含 http 资源链接确需放行，按域名白名单收敛。
**整改记录**：（待填）

## F-04 [High] 备份规则为空模板且 allowBackup=true

**现状**：Manifest `allowBackup="true"`；res/xml/backup_rules.xml 为空 `<full-backup-content>` 示例模板；data_extraction_rules.xml 内为 TODO 注释。
**影响**：Android 12+ 云设备迁移会把 prefs（game_scanner/yukihub_prefs/tyranor_game_overrides 等）与 filesDir（含已安装的 engine_plugins/*.so，约 120MB）一并备份恢复；跨设备恢复的 so 与 manifest.json 可能与新设备状态不一致，且扫描结果中的外部存储 URI 在新设备失效。
**建议**：在 data_extraction_rules 中排除 `engine_plugins/` 目录与各 prefs，或整体关闭 allowBackup；同步填充 backup_rules.xml。

### 整改记录
- 日期：2026-08-25
- 变更内容：采用审计建议的「整体关闭」方案——Manifest `allowBackup` 改为 `false`；删除两个未被 Manifest 引用的空模板 `res/xml/backup_rules.xml` 与 `res/xml/data_extraction_rules.xml`（全仓库 grep 确认零引用，随删除一并移除空的 res/xml/ 目录）。
- 决策依据：应用私有数据中 engine_plugins/*.so 约 120MB 远超系统备份配额（~25MB，必然被静默跳过）；prefs 内 SAF URI 为设备绑定，跨设备恢复即失效；KRKR 镜像存档目录名含路径哈希，跨设备不一致。云备份收益趋近于零而状态不一致风险实在。游戏与大部分存档位于共享存储，不受此开关影响。
- 验证方式与结果：`gradlew assembleDebug` BUILD SUCCESSFUL；合并产物 Manifest 确认 `android:allowBackup="false"` 且无 legacy/requestLegacy 属性残留。

## F-05 [Medium] APK 体积与插件 so 双重打包

**现状**：nativeplugins 约 121MB so → Gradle Zip 打包进 assets → 首启再解压到私有目录（双份存储）；debug APK 88.5 MB。
**影响**：下载体积、安装后磁盘占用（APK+私有目录双份）、构建时间。
**建议**：短期评估 zip 压缩级别与 so strip 状态（libgame*.so 是否已去符号）；中期评估 Play Asset Delivery（install-time pack）替代 assets 方案；kirikiroid2 三内核(139/134/126 共 ~80MB)可评估按需下载。
**整改记录**：（待填）

## F-06 [Medium] app 侧无多语言资源

**现状**：strings.xml 仅 app_name 一条，UI 中文文案硬编码于 Kotlin；engine 侧反而已有 en_us/ja_jp/zh_cn/zh_tw 四语（engine assets/locale/ + res/values-{en,ja}），并通过 EngineUiText 按语言切换。
**影响**：无法跟随系统语言；与 engine 层能力不对称。
**建议**：若有多语言规划，先抽取 SettingsScreen/GameScreen 高频文案到 strings.xml；至少把无障碍 contentDescription 抽出。
**整改记录**：（待填）

## F-07 [Medium] CI 未显式安装 NDK/CMake

**现状**：两条 workflow 仅安装 platforms/build-tools（android-ci.yml:36、android-beta-release.yml:78）；engine 需要 NDK 28.0.13004108 与 CMake 3.22.1，当前依赖 GitHub runner 预装 + AGP 自动安装机制。
**影响**：runner 预装版本变化或 AGP 自动安装策略变化时构建会突然失败；失败信息对新人不友好。
**建议**：在 sdkmanager 行追加 `"ndk;28.0.13004108" "cmake;3.22.1" "build-tools;36.0.0"`，与本地工具链对齐（build-tools 36.0.0 同理，AGP 默认请求版本，CI 目前靠 setup-android 兜底）。

### 整改记录
- 日期：2026-08-25
- 变更内容：`android-ci.yml` 与 `android-beta-release.yml` 的 Install Android SDK packages 步骤追加 `"build-tools;36.0.0" "ndk;28.0.13004108" "cmake;3.22.1"`（换行续写），与 engine/build.gradle 声明的 ndkVersion/cmake version 及 AGP 请求的 build-tools 36.0.0 对齐。
- 验证方式与结果：YAML 结构经人工核对；本地工具链版本一致（NDK r28 / CMake 3.22.1 已验证 assembleDebug 通过）。CI 实际安装效果待下次 workflow 运行确认。

## F-08 [Medium] MANAGE_EXTERNAL_STORAGE 合规风险

**现状**：Manifest 声明 MANAGE_EXTERNAL_STORAGE；EngineLauncher.requestAllFilesAccessIfNeeded(:102) 引导授权。
**影响**：Google Play 对该权限有严格审批（需核心功能依赖）；国内渠道一般无碍但需隐私政策披露。
**建议**：保留但确保「所有文件访问」仅在引擎需要直读外部真实路径时请求（当前逻辑已按 path 判断 ：129，属合理最小化）；Play 渠道发布前准备使用说明视频与申报材料。
**整改记录**：（待填）

## F-09 [Low] UNKNOWN 引擎静默兜底 TyranoActivity

**现状**：EngineLauncher.buildIntent:203 将 UNKNOWN 引擎也路由到 TyranoActivity。
**影响**：识别失败的游戏会以 WebView 打开目录，用户得到的是黑屏/文件列表而非明确错误。
**建议**：UNKNOWN 时返回用户可见错误提示或引导手动指定引擎，而非静默兜底。

### 整改记录
- 日期：2026-08-25
- 变更内容：
  - `EngineLauncher.launch()` 入口新增 UNKNOWN 拦截，返回错误文案「未能识别该游戏的引擎类型，暂不支持启动；可尝试重新扫描游戏目录」，经 UI 既有 `launchError` 弹窗展示（HomeScreen/GameScreen 均已接入）。
  - `buildIntent()` 的 `EngineType.UNKNOWN` 分支改为 `error()` 不变式兜底（正常流不可达），不再静默路由到 TyranoActivity。文案未引导「手动指定引擎」——经核实单游戏设置暂无该功能（仅有 KRKR/Artemis 版本覆盖）。
- 验证方式与结果：`gradlew assembleDebug` + 全部单元测试 BUILD SUCCESSFUL（38/38）；代码走查确认 launch() 返回值在两处 UI 调用点均以弹窗呈现。

## F-10 [Low] requestLegacyExternalStorage 无效标志

**现状**：Manifest 含 `requestLegacyExternalStorage="true"`，但 targetSdk=36 下该标志自 API 30 起即被忽略。
**影响**：无功能危害，但误导维护者以为存在 legacy 存储路径。
**建议**：删除该属性并在注释中说明 SAF/MANAGE_EXTERNAL_STORAGE 才是实际路径策略。
**整改记录**：（待填）

### 整改记录
- 日期：2026-08-25
- 变更内容：`app/src/main/AndroidManifest.xml` 删除 `android:requestLegacyExternalStorage="true"` 属性（targetSdk 36 下自 API 30 起被系统忽略，纯误导性残留）。实际路径策略为 SAF + MANAGE_EXTERNAL_STORAGE，见 EngineLauncher。
- 验证方式与结果：`gradlew assembleDebug` BUILD SUCCESSFUL；合并后 Manifest 不再包含该属性。

## F-11 [Medium] vendored SDL2 Java 层为反编译产物

**现状**：org/libsdl/app/SDLActivity.java:8 存在「JADX INFO」注释，说明该 Java 层来自 APK 反编译而非官方源码；AudioRouteWatcher(:27) 为本项目新增增强。
**影响**：上游许可（SDL zlib 许可要求保留版权声明）合规性存疑；后续升级 SDL2 无法直接 diff 官方源。
**建议**：以官方 SDL2 android-project Java 源为基线重新对照整理，保留 AudioRouteWatcher 补丁为显式 diff；补齐 LICENSE 注记。
**整改记录**：（待填）

## F-12 [Low] 第三方组件许可披露不全

**现状**：jniLibs 含 libBugly.so（腾讯 Bugly）、libmmkv.so（微信 MMKV）、ijkplayer、SDL/SDL2/ffmpeg（经 kirikiroid2 插件分发）等；README 致谢仅列 Tyranor/RinneMobile/Miuix 及 GPL-2.0 总纲。
**影响**：GPL-2.0 项目内引入 Bugly/MMKV 等专有或宽松许可二进制，需逐一核对兼容性与声明义务。
**建议**：建立 THIRD-PARTY-NOTICES 清单（组件/版本/来源/许可证），随发行物分发。
**整改记录**：（待填）

## F-13 [Medium] TyranoLocalHttpServer 绑定地址待核实

**现状**：静态扫描确认其为线程池+ServerSocket 实现（TyranoLocalHttpServer.kt:24,70），未见 bind 到 loopback 的显式约束行；服务对象为游戏目录内容并注入 hook JS。
**风险**：若绑定 0.0.0.0，同网段设备可访问游戏文件与本地服务。
**建议**：人工核实 ServerSocket 构造参数；如为全地址绑定，改为 `InetSocketAddress(InetAddress.getLoopbackAddress(), port)`，并保留端口随机化。

### 整改记录
- 日期：2026-08-25
- 核实结果：**无风险，无需修改代码**。`TyranoLocalHttpServer.kt:53` 构造为 `ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))`——显式绑定 loopback + 端口随机化（backlog 50），初版审计静态扫描漏检了该行。另全仓库 grep 确认这是唯一的 `ServerSocket(` 使用点，不存在其他宽绑定服务。
- 验证方式与结果：人工读取构造函数源码；`rg "ServerSocket\("` 全仓库仅 1 处命中。状态从「待核实」改为「已核实（无风险）」。

---

## 整改优先级建议

1. **立即**（低成本高收益）：F-02 文档修正、F-10 删除无效标志、F-07 CI 工具链显式化。✅ 已完成（2026-08-25）
2. **本迭代**：~~F-04 备份规则收紧~~ ✅、~~F-13 loopback 核实~~ ✅ 已核实无风险、~~F-09 UNKNOWN 兜底改造~~ ✅ 均已完成（2026-08-25）
3. **规划中**：~~F-01 测试补齐~~ ✅ 已完成（2026-08-25）、F-03 networkSecurityConfig、F-11/F-12 许可治理。
4. **发布前决策**：F-05 体积方案（PAD/按需下载）、F-06 多语言范围、F-08 渠道合规材料。

## 维护约定

每项整改完成后在该条目下追加：
```
### 整改记录
- 日期 / commit：
- 变更内容：
- 验证方式与结果：
```
