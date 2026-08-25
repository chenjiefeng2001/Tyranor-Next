# org.libsdl.app 出处与许可说明（PROVENANCE）

本目录为 **SDL2 android-project Java 层**（上游：https://github.com/libsdl-org/SDL，
`android-project/app/src/main/java/org/libsdl/app/`），许可证为 **zlib License**。

## 当前基线状态

- 本目录文件来自**上游 APK 反编译整理**（多个文件残留 `JADX INFO` 注释），
  非官方源码逐字拷贝；变量名、控制流可能与官方版本存在反编译差异。
- 尚未与任一 SDL2 官方 release 的 Java 源做过系统对照，升级 SDL2 前必须先完成该对照。

## 本项目新增文件

| 文件 | 说明 |
| --- | --- |
| AudioRouteWatcher.java | 本项目新增：耳机热插拔音频路由监听（非 SDL 上游内容） |

SDLActivity.java 等文件中如存在对 AudioRouteWatcher 的调用点，均为本项目本地补丁；
后续以官方源重新整理时，应将上述差异保留为显式 diff（见审计报告 F-11）。

## 许可义务

zlib License 要求在发行物中保留版权声明与本许可文本；
集中披露见仓库根目录 THIRD-PARTY-NOTICES.md。
