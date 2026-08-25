# Tyranor Next

基于 **Tyranor 模拟器逆向重写**的多引擎视觉小说（Galgame）聚合启动器，面向 Android 平台。内置 Kirikiri / ONScripter / Tyrano / Artemis 四套引擎运行时，提供游戏库管理、封面获取、存档镜像、引擎参数调节等一体化体验。

主打轻便、简单、快捷，不引入其他冗余功能的简约设计思路

## 参与贡献

欢迎参与项目开发与维护！

在提交 Issue 或 Pull Request 前，请先阅读 [贡献指南](./CONTRIBUTING.md)。

## 技术架构

### 模块划分

| 模块 | 职责 |
| --- | --- |
| `app` | 启动器 UI：游戏扫描/管理、封面、存档、设置入口（Kotlin + Jetpack Compose） |
| `engine` | 引擎运行时核心：SDL2/SDL3、Kirikiri TVP、krkrsdl3、ONScripter、Artemis、Tyrano 执行环境 |

### 技术栈

- **语言**：Kotlin（引擎层含 Java 桥接代码）
- **UI**：Jetpack Compose + Material 3 + [Miuix](https://github.com/compose-miuix-ui/miuix)
- **导航**：底部导航 `NavigationBar`；主 Tab 内容页使用水平移动切换，详情/设置等独立 Activity 进入使用向上翻页、退出使用向下翻页
- **构建**：Gradle 9.5.1 / AGP 9.2.1 / Kotlin 2.x + Compose Compiler，`compileSdk 37`、`minSdk 26`、`targetSdk 36`
- **持久化**：SharedPreferences（扫描结果、引擎全局设置、单游戏设置覆盖、最近记录）
- **文件访问**：Storage Access Framework（SAF）管理外部游戏目录，`documentFile` 库辅助

### 引擎集成设计

- 引擎原生插件（`kirikiroid2` / `ons` / `artemis` 的 `.so`）以 assets 形式随 APK 打包（`nativeplugins/`），首次启动由 `NativePluginManager` 自动解压安装到应用私有目录
- `app` 模块通过 `EngineLauncher` 将扫描结果映射到对应引擎 Activity 启动（SAF URI → 真实路径转换）
- Tyrano 运行环境内置本地 HTTP 服务器、Asar 归档解析与 JS 钩子脚本（`__tyrano__.js` 等），无需外部依赖即可运行网页式脚本游戏
- 原生库仅提供 `arm64-v8a` 架构

## 构建

### 工具链前置要求

| 工具 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 17 | Gradle toolchain 固定版本 |
| Android SDK Platform | android-36、android-37.0 | engine 用 36，app 因 Miuix 传递依赖需 37 |
| Build-Tools | 36.0.0+ | AGP 默认请求 36.0.0 |
| NDK | r28（28.0.13004108） | 编译 engine 原生桥 `krkr_bridge_v2` |
| CMake | 3.22.1 | 与 engine `externalNativeBuild` 声明一致 |

可用 sdkmanager 一键安装：

```bash
sdkmanager "platforms;android-36" "platforms;android-37.0" "build-tools;37.0.0" \
  "build-tools;36.0.0" "ndk;28.0.13004108" "cmake;3.22.1"
```

### 编译

```bash
# 编译 Debug APK（仅产出 arm64-v8a）
./gradlew assembleDebug --no-daemon

# 运行单元测试（app + engine）
./gradlew :app:testDebugUnitTest :engine:testDebugUnitTest --no-daemon
```

产物位于 `app/build/outputs/apk/debug/`。

## 目录结构

```
app/    启动器（UI、游戏扫描、封面、存档、设置）
engine/ 引擎运行时核心（SDL、Kirikiri、krkrsdl3、ONScripter、Artemis、Tyrano）
docs/   设计文档
```

## 许可证

本项目基于 **GNU General Public License v2.0** 发布，详见 [LICENSE](LICENSE)（GPL-2.0-only）。

- `engine/` 引擎运行时基于 Tyranor 模拟器逆向重写，上游涉及 Kirikiroid2 / ONScripter 等 GPL-2.0 项目，因此整个项目以 GPL-2.0 授权分发
- 基于本项目发布的衍生作品须遵循 GPL-2.0 条款，并随发行物提供完整源码
- Miuix 等第三方依赖按各自许可证引入

## 致谢

- **Tyranor 模拟器**：本项目引擎运行时与核心架构的逆向重写基础
- **RinneMobile**：游戏扫描识别/SAF路径映射逻辑/独立存档映射/krkrsdl3 等多个功能的参考实现
- [Miuix](https://github.com/compose-miuix-ui/miuix)：设置界面组件库
- 各引擎运行时均基于其开源许可引入
