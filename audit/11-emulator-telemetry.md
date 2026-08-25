# 11 模拟器遥测基线（首轮）

日期：2026-08-25。设备：emulator-5554（sdk_gphone64_x86_64，API 33，swiftshader 软渲染，无快照冷启）。
构建：`app-debug.apk`（debuggable，无 Baseline Profile）。采集器：`audit/tools/emulator_telemetry.py`（可复现）。
数据文件：`audit/artifacts/telemetry.json`；轨迹 `perfetto-coldstart.pftrace`（4.2MB，本地留档不入库）。

> **环境混淆声明**：软渲染 + debug JIT + x86_64 三重放大绝对数值；本报告结论以**相对结构**（哪一段耗时、排除什么嫌疑）为准，绝对值仅作回归基线。真机 arm64 数据待采（复用 AGENT.md 实机流程跑同一脚本即可）。

## 1. 启动遥测

| 指标 | 数值 | 备注 |
|---|---|---|
| 首次启动（含插件解压 ~116MB zip IO + dexopt） | WaitTime 15.0s，超时无 TotalTime | 首启另有后台解压与 JIT 叠加 |
| 冷启动 ×5 | 10.4–13.2s，中位 ~10.6s | **每轮均稳定复现** |
| 系统 Displayed（独立验证轮） | **+14s52ms** | ActivityTaskManager 权威值 |
| 主线程状态（冻结期 jdb 采样） | **RUNNING（CPU 自旋）**，非锁等待 | JDWP attach 实测 |
| 帧调度 | 单轮 Skipped 49 → 480 frames | Choreographer |

## 2. 已排除的嫌疑（负结论同样是产出）

| 嫌疑 | 排除依据 |
|---|---|
| 插件解压阻塞主线程 | provisionIfNeeded 在 MainActivity 中以**后台守护线程**启动（MainActivity.kt:25-27），首启跳帧属 IO 争用而非主线程占用 |
| 液态玻璃导航 shader 编译 | 默认 nav_style 即 `default` 经典 NavigationBar，本轮全部测量均在经典路径下完成 |
| GameStore/prefs 读库 | 进程内缓存命中路径为纯内存 map（GameStore），量级 μs |

## 3. 收敛后的根因候选（按可能性排序）

1. **EGL/HostConnection 初始化间隙**：日志实测 libEGL 加载 → HostConnection 就绪间隔 **~10s**（23:27:05.6→23:27:15.7），与冻结窗口吻合——swiftshader 环境伪影概率高。
2. debug 构建 JIT + 全量类校验（无 Baseline Profile、无可调试优化差异对照）。
3. MainNavigation 四 Tab Pager 首帧组合成本（次要）。

判别实验已设计未执行：release-signed 构建复测 / 真机 arm64 复测 / 引入 Baseline Profile 后复测——三者任一均可把候选 1 与 2/3 分离。

## 4. UI 遍历掉帧（3 轮 × 4 Tab）

| 指标 | 值 |
|---|---|
| 总帧 / 卡顿帧 | 370 / 271（**73.2%**） |
| frameMs P50 / P90 / P95 | 38 / 65 / 200 ms |

同样受软渲染污染；P95 200ms 的 Tab 切换尖刺值得在真机上复核（HorizontalPager 相邻页组合成本）。

## 5. 内存（空闲态 meminfo）

totalPss 118MB（code 74MB 占大头——debug 未压缩 + 多引擎类驻留）；javaHeap 11MB / nativeHeap 15MB。健康。

## 6. 附带发现

- `/data/anr/` 存在 3 份本应用 ANR 转储（对应 am start -W 超时轮次）——首启 15s 超时窗口内主线程无响应属实，真机发布前必须消除该量级首帧延迟。
- `am start -W` 的 LaunchState=UNKNOWN + 无 TotalTime 本身即是「首帧未及时上报」的信号，可纳入 CI 冒烟断言（阈值建议真机 <2s / 模拟器 <5s）。

## 7. 下一步（按序）

1. release-signed 构建同脚本复测 → 分离「debug 构建」因素（R-12 关联：体积方案落地后顺带）。
2. AGENT.md 实机流程复测 → 得到代表性绝对值，替换本报告为真机基线。
3. 引入 Baseline Profile（compose 官方插件）→ 针对 §3.2 验证。
4. 若真机仍 >2s：以 perfetto-coldstart.pftrace 分析法替代（需 trace_processor），定位主线程自旋的具体编译/绘制段。
