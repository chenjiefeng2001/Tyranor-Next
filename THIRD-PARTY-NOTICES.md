# 第三方组件声明（THIRD-PARTY NOTICES）

本项目（Tyranor Next，GPL-2.0）随发行物分发或引用的第三方组件清单。
各组件版权归其原作者所有；如条目与上游实际许可冲突，以上游为准并请提 Issue 更正。

## 一、Maven 依赖

| 组件 | 版本 | 许可证 | 来源 |
| --- | --- | --- | --- |
| Android Jetpack（Compose / Lifecycle / Navigation3 / Activity / Core / AppCompat / DocumentFile） | 见 `gradle/libs.versions.toml` | Apache-2.0 | developer.android.com/jetpack |
| Kotlin 标准库 / kotlinx-coroutines / Kotlin Serialization 插件 | 2.3.20 / 1.10.2 | Apache-2.0 | github.com/JetBrains/kotlin |
| Miuix（`top.yukonga.miuix.kmp`） | 0.9.2 | Apache-2.0 | github.com/compose-miuix-ui/miuix |
| Backdrop（`io.github.kyant0:backdrop`） | 1.0.2 | Apache-2.0 | github.com/Kyant0/AndroidLiquidGlass |
| JUnit 4 | 4.13.2 | EPL-1.0 | junit.org（仅测试） |
| Robolectric | 4.16.1 | MIT | github.com/robolectric/robolectric（仅测试） |
| org.json | 20240303 | JSON License | github.com/stleary/JSON-java（仅测试） |

## 二、随 APK 分发的原生库（engine/src/main/jniLibs/arm64-v8a）

| 库文件 | 组件 | 许可证 | 说明 |
| --- | --- | --- | --- |
| libBugly.so | 腾讯 Bugly SDK | 专有 SDK 条款 | crash 上报；使用其服务须遵守腾讯 Bugly 服务协议 |
| libmmkv.so | MMKV | BSD-3-Clause | github.com/Tencent/MMKV |
| libbytehook.so | ByteHook | MIT | github.com/bytedance/bYTEHOOK（MMKV 依赖） |
| libshadowhook.so、libshadowhook_nothing.so | ShadowHook | MIT | github.com/bytedance/android-inline-hook |
| libijkplayer.so、libijksdl.so、libijkffmpeg.so | ijkplayer | LGPL-2.1-or-later | github.com/bilibili/ijkplayer；含 FFmpeg（LGPL/GPL 配置视构建而定） |
| libSDL3.so | Simple DirectMedia Layer 3 | zlib | github.com/libsdl-org/SDL |
| libkrkr_bridge.so、libkrkr_bridge_v2.so*、libkrkrsdl3.so | Kirikiri 引擎桥 / krkrsdl3 宿主 | GPL-2.0-or-later | 本项目构建，基于 Kirikiroid2（github.com/uyjulian/kirikiroid2 及 Tyranor 上游衍生） |
| libONSPatch.so | ONScripter 补丁层 | GPL-2.0-or-later | 基于 ONScripter（onscripter-jh 衍生，Ogapee 原作） |
| libartemis_loader.so、libartemis_audio_bridge.so | Artemis 加载器/音频桥 | GPL-2.0 | Tyranor 模拟器逆向重写产物 |
| libandroidx.graphics.path.so | AndroidX graphics-path | Apache-2.0 | androidx.graphics 库传递依赖 |

\* `libkrkr_bridge_v2.so` 为 CMake 构建产物，打包期并入。

## 三、构建期打包的引擎插件（app/src/main/nativeplugins → assets/nativeplugins）

| 插件 | 内含库 | 上游项目与许可证 |
| --- | --- | --- |
| kirikiroid2 | libgame.so、libgame134.so、libgame126.so、libffmpeg.so 等 | Kirikiroid2（GPL-2.0-or-later）；FFmpeg（LGPL-2.1-or-later 或 GPL，视配置）；内嵌 SDL2（zlib）、bzip2（BSD 风格）、libjpeg（IJG）、Lua（MIT） |
| ons | libonsyuri.so、libSDL2*.so | onscripter-jh（GPL-2.0-or-later，基于 ONScripter）；SDL2 / SDL2_image / SDL2_mixer / SDL2_ttf（zlib） |
| artemis | libartemis.so、libartemis-compatible.so、libartemis-compatible-v2.so | Tyranor 模拟器逆向重写（GPL-2.0，随本项目分发） |

## 四、vendored Java 源码（engine/src/main/java）

| 包 | 上游 | 许可证 | 备注 |
| --- | --- | --- | --- |
| org.libsdl.app | SDL2 android-project | zlib | **来自上游 APK 反编译整理**（含 JADX 注释），出处说明见同目录 README |
| org.libsdl3.app | SDL3 android-project | zlib | 官方源码基线 |
| tv.danmaku.ijk | ijkplayer Java 层 | LGPL-2.1-or-later | 来自 APK 反编译整理（含 JADX 注释） |
| com.core.* / org.tvp.* / com.akira.tyranoemu.remote 等 | Tyranor / Kirikiroid2 衍生 | GPL-2.0-or-later | 本项目引擎宿主层 |

## 五、整体许可说明

1. 本项目以 **GPL-2.0-only** 发布（见 LICENSE）。由于链接/分发 Kirikiroid2、ONScripter 等 GPL-2.0 组件，发行物须遵循 GPL-2.0 条款并提供完整对应源码。
2. Bugly SDK 为专有组件，其引入与 GPL-2.0 的兼容性存在争议；当前仅面向非 Play 渠道分发。若未来上架 Google Play，需重新评估是否移除。
3. zlib / Apache-2.0 / BSD / MIT / IJG / JSON License 组件按各自条款要求保留版权与许可声明，本清单即为集中披露；完整许可文本以各上游仓库为准。
4. FFmpeg 若以 GPL 配置构建，则对应插件整体按 GPL 分发；LGPL 配置下需保证可重链接（插件以动态库形式分发满足该要求）。

## 六、致谢

Tyranor 模拟器、RinneMobile、Kirikiroid2、ONScripter / onscripter-jh、Artemis 引擎（逆向）、ijkplayer（bilibili）、SDL（libsdl-org）、Miuix、Jetpack Compose 社区。
