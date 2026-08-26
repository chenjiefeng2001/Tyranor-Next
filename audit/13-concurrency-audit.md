# 13 并发面审查报告（死锁 / 无限等待 / 饥饿）

基线：commit `d3f9ed2` + 本轮未提交加固。方法：全仓锁/latch/线程池/阻塞 IO 盘点 → 逐点判定 → 修复确认。
范围说明：vendored 第三方（SDL/ijkplayer/Cocos/Artemis 宿主）按冻结策略仅做黑盒行为评估，不改动。

## 一、盘点与判定矩阵

| # | 并发点 | 机制 | 判定 | 处置 |
|---|---|---|---|---|
| C-1 | **KRKRCall.WaitInputResult**（:krkrsdl3） | volatile CountDownLatch，native 线程阻塞等 UI 对话框 | ⚠️ **无限等待窗口**：初始 latch 未触发态 + UI 守卫早退不 countDown + 无超时保险丝 | ✅ 已修复 |
| C-2 | **EnginePluginBootstrap 插件解压** | 后台 daemon 线程，无单飞 | ⚠️ **写坏竞态**：快速重建/双入口并发触发时两线程同删同写 `current/` 目录 | ✅ 已修复 |
| C-3 | **VndbCoverService.downloadCover** | IO 协程直写最终路径 | ⚠️ **半截文件永久缓存**：协程取消/中断留下 `.jpg` 部分写入，下次命中短电路直接返回坏图 | ✅ 已修复 |
| C-4 | GameStore 三缓存 | 单 monitor + @Volatile DCL | ✅ 无死锁（单锁无环）；写侧免锁为引用换新，语义=最后写胜（UI 单线程调用下安全）；多读客串行化上限=一次 prefs 读，饥饿仅理论 | 记录不改 |
| C-5 | TyranoLocalHttpServer | accept 单线程 + TPE(core2/max8/queue64/Abort) + soTimeout15s | ✅ 有界：慢客户端占满 worker 时新连接排队→溢出被拒并关闭（降级非挂死）；stop() shutdownNow 后残余读阻塞受 15s 超时兜底 | 记录不改 |
| C-6 | AsarArchive | synchronized(raf) 包 read/close | ✅ close 与并发 read 竞争时 read 抛 IOException 被 catch 返回 null，软失败正确 | 记录不改 |
| C-7 | GitHubUpdateChecker / KrkrOnlinePatchService 网络调用 | connect/read timeout 均显式设置 | ✅ 无无限等待 | 记录不改 |
| C-8 | UI 协程（scan/syncMissingCovers/bind） | rememberCoroutineScope + Dispatchers.IO，scanning 标志去重 | ✅ 结构性安全；`syncMissingCovers` 串行抓取为性能项非正确性（见 06 §7 备注） | 记录不改 |
| C-9 | SharedPreferences apply() | 内存同步落盘异步 | ✅ 同进程可见性即时 | 不适用 |

vendored 区（SDL 消息泵、ijk 回调线程、Cocos GL 线程）：黑盒观察未见新增风险；SDL 层冻结策略维持（10 号报告）。

## 二、本轮修复明细

### C-1 KRKRCall（engine/.../org/tvp/krkrsdl3/KRKRCall.java）
- 初始 latch 改为 **已触发态**（count=0）：无弹窗时的杂散等待立即按取消返回；
- UI 守卫早退分支补 `latch.countDown()`：不再依赖 onDestroy 时序兜底；
- `WaitInputResult` 重写为 **分片轮询**：200ms 切片 + 「latch 被更新调用取代」作废检测 + **10 分钟保险丝**——用户长输入不受影响，极端孤儿场景最迟 10 分钟解锁引擎线程；
- 可测性缝：四个状态字段改包内可见、保险丝时长抽为非 final 的 `maxWaitMs`（默认值不变），
  仅供同包回归套件注入/复位；生产路径无行为变化。
> 原缺陷复现推演：act 在 post 与执行间销毁且 onDestroy 未及触达 → latch 永不归零 → SDL 主循环永久冻结（表现为游戏画面卡死后台耗电）。

### C-2 EnginePluginBootstrap（app/.../scanner）
- 引入 `provisionLock` 单飞 + 锁内双检：快路径仍 O(状态读取)；并发第二线程在锁内发现已装即返回，杜绝「同时 deleteRecursively + 交错解压」。
- 跨进程崩溃恢复依赖既有语义保持不变：无标记的残留目录会在下次启动被整体删除重建。
- 测试注记：单飞逻辑已由 `EnginePluginBootstrapConcurrencyTest` 以密闭插件树（哨兵字节 + 静止态完整性不变量）覆盖并发压测；真实 assets zip 的端到端解压仍由装机冒烟兜底，后续可抽 `Installer` 接口注入伪资产补更细粒度用例。

### C-3 VndbCoverService
- 下载改写 `*.part` 临时文件 → 成功后 `renameTo(target)`；失败/异常清理 part，旧缓存不被污染。
- `throttle()` 的 @Synchronized+sleep 为有界串行（≤MIN_REQUEST_INTERVAL_MS），保留。

## 三、验证

- `:app:testDebugUnitTest :engine:testDebugUnitTest` → **139/139 通过**（app 115 / engine 24）。
- `assembleDebug` / `assembleRelease -x lintVitalRelease` 双构建通过。
- 本报告与全部加固代码当前处于**工作区未提交状态**（遵前次指令），待确认后一并入库。

### 3.1 回归护栏套件（本轮追加，防回退）

| 套件 | 锁定目标 | 关键断言 |
|---|---|---|
| `KRKRCallWaitInputResultTest`（engine，6 例） | C-1 四条防冻结语义 | 杂散等待立即返回；latch 被替换→旧等待作废；保险丝封顶孤儿等待；中断恢复标记；cancelPendingInput 放行。全部带 @Test(timeout)，语义回退=挂死=超时失败 |
| `VndbCoverServiceDownloadTest`（app，5 例） | C-3 缓存原子性 | 失败/断流后无 `.jpg` 也无 `.part` 残留；缓存命中零网络请求；文件名派生契约稳定 |
| `EnginePluginBootstrapConcurrencyTest`（app，4 例） | C-2 单飞+双检 | 已装快路径不重解压（哨兵字节）；启用原地翻转；8 线程并发后目录「完整有效或全空」的静止态不变量；缺资源优雅失败文案 |
| `EngineLauncherWriteGateTest`（app，5 例） | §5.2 写路径门禁 | <R 直放行；未授权命中外置存储路径→提示+拉起系统页（校验 action/data）；私有路径永不拦截；已授权放行 |

> 测试基建注记：Robolectric 4.16 内置 shadow 未覆盖 `Environment.isExternalStorageManager`，
> 由测试侧 `TestableShadowEnvironment`（继承内置 shadow 补齐该方法，Java 实现——静态方法的
> shadow 必须是 static）注入授权状态。

### 3.2 保险丝单位缺陷（护栏首战告捷，已修复）

C-1 初版实现的比较写的是 `System.nanoTime() - startNs > MAX_WAIT_MS`——nanoTime 差值是
**纳秒**却与毫秒常量直接比较：600,000 ms 的「10 分钟保险丝」实际等于 0.6ms，效果是首个
200ms 切片结束必然触发 → **用户输入框弹出约 200ms 后即被引擎线程按取消处理，输入丢失**。
该缺陷由 `fuseCapsOrphanWait` 用例暴露（断言返回时机应在时限附近，实测恒为单切片 ~208ms），
修复为 `TimeUnit.MILLISECONDS.toNanos(maxWaitMs)` 换算后比较，并由同用例锁定回归。
教训：**时间量纲必须经 TimeUnit 显式转换**，禁止裸数值跨量纲比较。

## 四、遗留观察（不构成行动项）

1. GameStore 若未来出现跨进程访问需求，需迁移 MMKV 或 contentProvider——当前单进程假设成立。
2. TLP server 可选调优：corePool 2→4 可降低突发首包延迟；以遥测数据驱动，暂不动。
3. KRKRCall 10 分钟保险丝数值可经真机反馈调整；常量已集中定义。

---

## 五、外置存储卡（SD/OTG）支持专项审查

### 5.1 支持现状：**全链路支持，双通道设计**

| 环节 | 机制 | 证据 |
|---|---|---|
| 根目录添加 | `OpenDocumentTree` 系统选择器天然列出 SD/OTG 卷；两处入口（GameScreen:129 / AppSettingsActivity:117）均 `takePersistableUriPermission` 持久化授权，重启后仍有效 | grep 证据 |
| 目录扫描 | SAF 主路径 `DocumentFile.fromTreeUri` 遍历（增量剪枝同样适用）；无 provider 时回退 File 直读 | EngineScanner.kt:137-146 |
| 路径映射 | documentId `XXXX-YYYY:Rel` → `/storage/XXXX-YYYY/Rel`，主卷特判 emulated/0 | PathResolver.safUriToPath |
| 引擎读取 | 双通道：krkr2 在可移动存储**豁免全文件权限**改走 GOT relocate+SAF 桥读（`safFileFallback` extra）；其余引擎统一引导 MANAGE_EXTERNAL_STORAGE | EngineLauncher:148/:176-178 |
| 内核降级 | 可移动存储强制 krkrsdl3→kirikiri2（SDL3 不支持该场景） | effectiveKrKernel |
| 存档目录 | SD 上 krkr2 非独立存档的 `savedata/` 创建失败时自动转 SAF 创建 | ensureKrGameSaveDirViaSaf |

### 5.2 发现缺口 → 已补门禁

**缺口**：krkr2-on-SD 的「读」走 SAF 桥被豁免权限，但**写路径页面**（存档管理的导出/导入/删除、krkr 在线补丁落盘）走 java.io 直写——Android 11+ 无 MANAGE 时静默 EACCES：表现为导出报错、补丁下载成功但文件未落盘。此前无任何提示。

**修复**：
1. `EngineLauncher.requestManageAllFilesForWrite(context, path)`（internal）：写路径统一门禁，不含 krkr2 启动豁免；
2. `SaveManagementActivity`：解析出的存档目录命中门禁时渲染阻断页（错误文案 + 「去授权」按钮→系统页→返回自动重检）；
3. `KrkrOnlinePatchActivity`：进入即校验，缺失则 Toast+finish，避免下载完成后才发现写不进去。

### 5.3 更进一步扫描能力的边界说明

- 扫描深度已有用户设置（1–5 层，默认 3），对慢速 SD 的 IO 放大 = 每节点一次 ContentResolver 往返 × 剪枝前目录数；P1 剪枝测试保证已知游戏目录不再下钻。
- OTG/USB 卷与 SD 同为非 primary volume，映射与判定通用；多卷需用户逐卷添加根目录（无卷列表 UI，属产品决策项）。
- 二级用户 emulated/1 形态会被判为「可移动」（正则仅排除 emulated/0）→ krkr2 走 SAF 桥、其他引擎要权限——行为安全但提示文案中的「外置存储」措辞在平板分身场景略不精确，暂不改。
