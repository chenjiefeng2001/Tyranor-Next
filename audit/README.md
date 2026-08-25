# Tyranor-Next 审计报告包

## 基线信息

| 项目 | 值 |
|---|---|
| 审计对象 | D:\EMU_DEV\Tyranor-Next |
| 代码基线 | git commit `0da87aa`（2026-08-24 22:43 +0800，docs: update README by removing outdated feature and process docs） |
| 审计日期 | 2026-08-25 |
| 审计方式 | 静态扫描（目录/源码/清单/构建脚本）+ 本机构建验证（assembleDebug 成功，产物 app-debug.apk 88.5 MB） |
| 行号有效性 | 文中所有 `文件:行号` 以基线 commit 为准；后续提交可能使行号漂移，引用前请先核对 |

## 文件索引

| 文件 | 内容 |
|---|---|
| [01-repo-and-build-audit.md](./01-repo-and-build-audit.md) | 仓库总体、构建体系、工具链状态、CI 流水线审计 |
| [02-app-module-audit.md](./02-app-module-audit.md) | app 模块（启动器 UI / 扫描 / 设置）结构与审计 |
| [03-engine-module-audit.md](./03-engine-module-audit.md) | engine 模块（五条引擎运行链路 / 原生桥 / 插件协议）审计 |
| [04-feature-code-map.md](./04-feature-code-map.md) | 功能 ↔ 代码位置完整映射表（跨模块契约） |
| [05-findings-and-remediation.md](./05-findings-and-remediation.md) | 发现问题清单（编号 F-xx）、严重级别与整改建议 |
| [06-code-trace-report.md](./06-code-trace-report.md) | 全量静态 trace：九条核心链路调用追踪、热点排行与解读 |
| [07-hotspot-map.svg](./07-hotspot-map.svg) | 代码热点图（文件级热力网格，悬停查看度量） |
| [08-deep-trace-report.md](./08-deep-trace-report.md) | 深度可追溯 trace：入口清单、隐藏依赖、Intent/prefs/JNI 契约矩阵与闭合核验 |
| [tools/hotspot_trace.py](./tools/hotspot_trace.py) | 热点度量与 SVG 生成脚本（可复现，含 hotspot-data.json 数据） |
| [tools/deep_trace.py](./tools/deep_trace.py) | 契约/隐藏依赖/JNI 证据提取脚本（可复现，含 deep-trace-data.json 数据） |

## 总体结论

1. 项目结构清晰：`app`（UI 与业务编排）+ `engine`（引擎宿主与原生桥），依赖单向（app → engine），prefs 键契约由 `EnginePrefs` 单点维护。
2. 本机工具链已补齐并验证：Gradle 9.5.1 / AGP 9.2.1 / JDK 17 toolchain / NDK r28(28.0.13004108) / CMake 3.22.1 / platforms 36+37.0 / build-tools 36+37；`assembleDebug` 构建通过。
3. 主要风险集中在：测试覆盖薄弱（仅 5 个引擎识别单测）、备份规则模板残留且 `allowBackup=true`、明文流量全局放行、APK 体积（88.5 MB）、文档偏差（AGENT.md 旧包名、krkr_bridge.cpp 注释）。
4. 正面发现：外置插件导入有 SHA-256 校验与 zip-slip 防护；Artemis pfs 解包有 zip 炸弹防护；引擎按进程隔离（每引擎独立进程）。

## 维护约定

- 整改某项后，请在 `05-findings-and-remediation.md` 对应条目追加「整改记录」小节（日期 / commit / 结果），不要删除原发现。
- 结构性变更（新增模块、移动包）后请同步更新 `04-feature-code-map.md`。
