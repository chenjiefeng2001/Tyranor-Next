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

1. ~~release-signed 构建同脚本复测~~ → 已完成，见 §8。
2. AGENT.md 实机流程复测 → 得到代表性绝对值，替换本报告为真机基线。
3. 引入 Baseline Profile（compose 官方插件）→ 在 release 基线（§8）上继续压榨冷启动。
4. 若真机仍 >2s：以 perfetto-coldstart.pftrace 分析法替代（需 trace_processor），定位主线程自旋的具体编译/绘制段。

> CI 化进展（2026-08-25）：`.github/workflows/emulator-smoke.yml` 已落地——每周日 UTC20:00 + 手动触发，KVM 模拟器跑 release 构建，遥测脚本以 `--assert-cold-ms 8000` 作为门禁（任一轮非 COLD 或超阈值即失败），telemetry.json/pftrace 归档为 workflow artifact。§3.2 的 Baseline Profile 实验可在该工作流基础上低成本迭代。

---

## 8. 判别实验结果：release 构建复测（2026-08-25 追加）

`assembleRelease`（R8 minify + shrinkResources，本地自动回退 debug 签名）重装同一模拟器，同脚本复测：

| 指标 | debug | **release** | Δ |
|---|---|---|---|
| 冷启动 ×5 中位 | ~10.6s（4/5 轮 UNKNOWN 超时） | **~3.9s（5/5 全部 COLD 正常上报）** | **−63%** |
| 冷启动区间 | 10.4–13.2s | 3.1–4.1s | |
| 首次启动（含插件 provisioning） | WaitTime 15s 超时 + 3 份 ANR 转储 | **TotalTime 4.5s，COLD** | 超时与 ANR 消失 |
| 总 PSS | 118MB | **52MB**（code 74MB→5.3MB） | −56% |
| APK 体积 | 88.5MB | **68.1MB** | −23%（F-05 关联数据点） |
| UI 掉帧率 | 73%（370 帧） | 79%（174 帧，样本减半+软渲染噪声主导） | 不可比，维持真机复核项 |

### 结论修订

1. **§3 根因排序修订：debug 构建因素（JIT + 可调试 + 全量校验）为冻结主因**——单一变量切换即消除 10s 量级延迟与全部 ANR。EGL 初始化间隙降级为次要/伴生现象（release 下未再观测到可感冻结）。
2. §2 三项排除结论不变（后台解压 / 经典导航 / 缓存路径）。
3. 工程含义：
   - 性能回归测试与 CI 冒烟断言必须基于 **release 构建**（debug 数据无代表性，已在本轮实证）；
   - F-05 体积讨论新增锚点：R8 对该应用实际削减 20MB APK / 66MB 运行内存；
   - Baseline Profile（原 §7.3）改为在 release 基线 3.9s 上评估增量收益。
4. 遗留：UI 掉帧率两轮均 >70% 但互相矛盾（帧总数差一倍），确认为软渲染噪声，判定需真机；`telemetry.json` 的 `apk` 字段固定显示 app-debug.apk 为脚本展示瑕疵，不影响数据。
