# 01 仓库总体与构建体系审计

基线：commit `0da87aa`（2026-08-24）。审计日期：2026-08-25。

## 1. 仓库规模

| 指标 | 值 |
|---|---|
| 文件数（不含 .git/build/.cxx/.gradle） | 405 |
| 总体积 | 约 201.7 MB |
| 主要构成 | Java 119 / Kotlin 61 / PNG 82 / SO 42 / CSB(Cocos UI) 35 / XML 23 / JS 10 |
| 体积大头 | `app/src/main/nativeplugins/`（约 121 MB arm64 so）+ `engine/src/main/jniLibs/` |

## 2. 模块划分

```
Tyranor-Next/
├── app/       启动器：UI、游戏扫描、设置、封面、存档管理（Kotlin + Compose）
├── engine/    引擎运行时库：五条引擎链路宿主 Activity + JNI 桥 + vendored 第三方
├── docs/      设计文档 ×3
├── .github/   CI ×2（android-ci / android-beta-release）
└── AGENT.md   AI 开发规范（顶部栏/字号/主题色/组件收口等强约束）
```

依赖方向：`app --implementation--> :engine`（app/build.gradle.kts:163），engine 不反向依赖 app（见 EnginePrefs.kt 头注释约束）。

## 3. 版本与 SDK 目标

| 项 | 值 | 出处 |
|---|---|---|
| applicationId / namespace | com.tyranor.next / com.tyranor.next | app/build.gradle.kts:42,50 |
| versionName / versionCode | 1.16 / 1 | app/build.gradle.kts:54-55 |
| minSdk / targetSdk | 26 / 36 | app/build.gradle.kts:51-53 |
| compileSdk(app) | release(37) minorApiLevel=0（平台包 `androids;android-37.0`） | app/build.gradle.kts:44-48 |
| compileSdk(engine) | 36 | engine/build.gradle:7 |
| Java 兼容级 / JVM toolchain | 17（双模块一致） | app:78-81,113-115；engine:21-24 |

注意：app 与 engine 的 compileSdk 不一致（37 vs 36），属可用但需在升级时同步检查。

## 4. 构建工具链现状（本机已补齐并验证）

| 组件 | 状态 | 说明 |
|---|---|---|
| JDK 17 (Microsoft OpenJDK 17.0.20.1+1) | 已装于 `%USERPROFILE%\.jdks\jdk-17.0.20.1+1` | 项目要求 toolchain 17；本机另有 JDK 21 作 Gradle 运行时。已通过用户级 `~/.gradle/gradle.properties` 的 `org.gradle.java.installations.paths` 注册；foojay 自动下载在本网络不可达（GitHub 阻断），故本地安装 |
| Android cmdline-tools 19.0 | 已装 | `$SDK/cmdline-tools/latest`，SDK=`C:\Users\14977\AppData\Local\Android\Sdk`（由 local.properties 指定） |
| platforms;android-36 / android-37.0 | 已装 | CI 同款包名（android-ci.yml:36） |
| build-tools 35.0.0 / 36.0.0 / 37.0.0 | 已装 | 36.0.0 为 AGP 9.2.1 默认请求版本（缺失曾导致构建失败） |
| ndk;28.0.13004108 | 已装 | engine/build.gradle:8 精确匹配 |
| cmake;3.22.1 | 已装 | engine/build.gradle:35 |
| Gradle wrapper 9.5.1 | 已预置 | 官方源下载超慢，经腾讯镜像下载后放入 wrapper 缓存（dists/gradle-9.5.1-bin/iq79hdu3mqx29lgffhp8bfmx） |
| 许可证 | 已接受 | `$SDK/licenses/android-sdk-license`、`android-sdk-preview-license` |

构建验证：`gradlew assembleDebug --no-daemon` → BUILD SUCCESSFUL（4m34s，含原生 CMake 编译），产物 `app/build/outputs/apk/debug/app-debug.apk`（88.5 MB）。

## 5. CI 流水线

| Workflow | 触发 | 步骤要点 |
|---|---|---|
| android-ci.yml | push/PR to main | checkout → JDK 17 (temurin) → setup-android → `sdkmanager --channel=3 platforms;android-36 platforms;android-37.0 build-tools;37.0.0`(:36) → assembleDebug → 上传 artifact |
| android-beta-release.yml | tag | 同上 SDK 包(:78) + base64 keystore 还原(:101-105，env 注入 ANDROID_KEYSTORE_*) → assembleRelease → 打 tag `beta-<version>` 发 GitHub Release |

发现（详见 05 报告 F-07）：CI 未显式安装 NDK/CMake，依赖 AGP 运行期自动安装；若上游自动安装行为变化或许可未预接受，CI 会失败。

## 6. 配置与规范文件

| 文件 | 要点 | 问题 |
|---|---|---|
| gradle.properties | configuration cache 开启、AndroidX、nonTransitiveRClass | 无异常 |
| settings.gradle.kts | google/mavenCentral/gradlePluginPortal，foojay-resolver 1.0.0，FAIL_ON_PROJECT_REPOS | 无镜像配置；国内网络直连缓慢（Gradle 分发需手动预置） |
| AGENT.md | AI 开发强规范：64dp 顶栏结构、双档字号、主题色唯一入口 AppThemeColors、TopBarIcon/AppSearchField 收口、8dp 圆角、多 Agent 审核流程 | L9-10 引用旧包名 `com.example.tyranornext`（实际为 `com.tyranor.next`），见 F-02 |
| .coderabbit.yaml | 中文 PR 自动审核；明确「静态审核不等于运行时测试」 | 无异常 |
| docs/ | krkrsdl3-engine-config-plan.md、miuix-settings-migration-plan.md、native-plugin-release-hardening-plan.md | 计划类文档，与代码现状的一致性未逐一核对 |
