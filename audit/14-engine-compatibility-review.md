# 14 主流 Galgame 引擎兼容性与接口抽象评审

基线：commit 后工作区（含未提交的并发加固）。方法：全仓 `EngineType` 触点统计、五条运行链路代码走查、业界主流引擎清单比对。

---

## 一、主流引擎兼容矩阵

### 1.1 已支持（覆盖日式经典四大系 + Web 三形态）

| 引擎 | 特征物 | 运行形态 | 深度亮点 |
|---|---|---|---|
| Kirikiri / KAG（krkr2/krkrz） | *.xp3、startup.tjs | 双内核（139/134/126 + krkrsdl3）、独立进程 | GOT relocate SAF 桥读、SAF 存档预建、内核降级策略、在线补丁、渲染/内存/FPS 全参数 |
| NScripter / ONScripter(-jh/onsyuri) | nscript.dat、*.sar/*.nsa | :ons 进程，SDL2 | 字体拷贝、gameargs JSON、独立视频页 |
| Artemis | *.pfs、system.ini+first.iet | 三版本进程（V1/V2/V3）+ 兼容回退 | PFS 基础补丁端到端（已测）、audio_bridge、native 反查桥 R8 保命 |
| TyranoScript / TyranoVino | index.html + tyrano/ 或 app.asar | :tyrano WebView + 本地回环 HTTP | hook JS 注入、ASAR 解析、JS 存档桥、四语 |
| RPG Maker MV / MZ | www/js/rpg_core.js(rmmz) | 同上 Web 宿主 | 存档 JS 桥、hook 脚本 |
| VN / WebOther（兜底） | globaldata.vndata / index.html | Web 宿主 | 兜底分类保证未知 web 游戏可进 |

### 1.2 未支持的业界主流（按用户量级排序）

| 引擎 | 特征物 | 现状 | 接入难度评估 |
|---|---|---|---|
| **Ren'Py** | *.rpa、renpy/ 目录、大量自带官方 Android 移植 | ❌ | **中低**：多数作品已是独立 APK——最优路径是「外部跳转」复用 #28 的 `tyranor://launch` 机制反向调用目标包，或做识别+引导安装；内置运行时成本极高不建议 |
| **Unity 系**（Naninovel/Fungus/UTAGE） | 通用 Unity APK | ❌ | 同上：属「外部应用启动器」范畴，非引擎集成 |
| **BKEngine（国产 BK）** | *.bke | ❌ | **中高**：官方有开源 Android 运行时，理论上可走 NativePluginManager 插件协议接入，需验证许可（MIT？需核对）与符号冲突（进程隔离天然友好） |
| CatSystem2 | *.int | ❌ | 高：无现成 Android 移植 |
| Ethornell / Buriko | *.arc | ❌ | 高 |
| Majiro | *.mj | ❌ | 高 |

> 判断：对「日式经典 galgame」覆盖完整且深度（SAF 桥/镜像存档/多内核）业内领先；空白集中在「现代通用引擎」——其中 Ren'Py/Unity 的正确姿势是启动器级外部跳转而非内置移植，属产品决策（R-12/13 同级的范围问题）。

## 二、当前接口结构（事实盘点）

**不存在任何引擎级接口/SPI**。`grep interface.*Engine` 零命中。引擎差异以三种形态硬编码：

```
EngineType(enum,9值)
 ├─ EngineScanner.detectEngine(File/DocumentFile)   // 单函数优先级梯子 ×2 变体
 ├─ EngineLauncher.buildIntent(...)                  // when(engine) 五大分支 + supportedEngines 表
 ├─ GameSaveManager.resolveSaveLocation/cleanupAppData/excludeFor  // 引擎策略内嵌
 ├─ EngineSettingsStore / PerGameSettingsStore       // KR/ONS/Artemis/Tyrano 各自段落
 ├─ NativePluginManager + EnginePluginBootstrap      // 三插件 spec（这部分已是协议化 ✅）
 └─ TyranoActivity.WebGameType(enum)                 // Web 四形态统一宿主 ✅
```

触点统计：`EngineType.` 引用 Top —— Launcher 28 / Scanner 24 / SaveManager 14 / PerGameSettings 11 / GameCard 10 / PluginBootstrap 9 / EngineScreen 9 …

## 三、抽象度评审

### 3.1 做得好的（应保持的设计资产）

1. **进程级隔离**：每引擎独立进程 + taskAffinity，把 NDK 符号冲突挡在架构层——这是比接口抽象更重要的物理边界，也是敢同时塞 SDL2+SDL3 的前提。
2. **契约单点治理**：Intent extras / prefs 键收敛到 EnginePrefs + 08 报告矩阵化（含「无消费方键」「冻结声明」）——字符串契约虽原始，但有纪律。
3. **Web 统一宿主先例**：`WebGameType` 让 MV/MZ/VN/WebOther 共享 TyranoActivity 与整套 HTTP/hook 基础设施——证明本仓库有能力做收敛式抽象。
4. **穷尽 when 的编译器护栏**：enum 扩值会强制补全全部分支，「漏改某处」在编译期即失败——封闭扩展的安全网真实有效。
5. **原生分发协议化**：NativePluginConstants/Installer/Manager 把 so 分发、SHA-256、zip-slip 防护做成与引擎无关的基础设施。

### 3.2 不足（按演进阻力排序）

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| A1 | **无 Engine SPI**：能力散落 ≥8 文件硬编码 | 上表触点统计 | 新增引擎违反 OCP，多点修改易漏（穷尽 when 只保护 scanner/launcher 两处） |
| A2 | **存档策略内嵌**：resolveSaveLocation/cleanupAppData/excludeFor 把 ARTEMIS 资源排除表等策略焊死在 GameSaveManager | GameSaveManager 5 处引擎分支 + isArtemisResourceName | 新引擎存档语义无处安放 |
| A3 | **字符串 Intent 契约**：跨进程 extras 无类型化载体 | 08 报告 C 节 41 键 | 依赖文档纪律；拼错键名静默失效（C 节已列死键佐证） |
| A4 | **检测器不可插拔**：detectEngine 单体梯子与遍历耦合 | Scanner 两变体重复实现 | 新特征=改核心扫描器，且 File/DocumentFile 双实现漂移风险（已用双变体测试压制） |
| A5 | 设置面无能力声明：哪些设置属于哪个引擎靠 Screen 硬编码段落 | PerGameSettingsScreen 11 处引用 | 引擎增删需同步设置 UI |

## 四、新增引擎成本实测推演（以 Ren'Py 外部跳转型为例）

| 步骤 | 触点 |
|---|---|
| 1. `EngineType` 加 `RENPY` | 1 处 → 编译器逼出全部 when 补全 |
| 2. detectEngine 特征（renpy/ 目录或 *.rpa） | Scanner 两变体各 +1 when 分支 |
| 3. buildIntent 分支：`Intent(Intent.ACTION_VIEW).setPackage("org.renpy.android")` 类外部拉起（复用 #28 思路） | Launcher +1 分支 |
| 4. resolveSaveLocation 返回「不可用」；cleanupAppData no-op | SaveManager +2 分支 |
| 5. supportedEngines 展示表 / 设置页可见性 / GameCard 封面色 | 3 处小改 |
| 合计 | **~8 触点 / 约 150–250 行**，其中 2 处受穷尽 when 保护、其余靠人工 |

SPI 化后预期：**1 个新文件实现 EngineContract + 注册表 1 行**，其余触点归零。

## 五、演进蓝图：EngineContract SPI（建议列为 R-15，独立专项）

```kotlin
interface EngineContract {
    val id: EngineType
    val displayName: String get() = id.displayName
    // 检测
    fun detect(features: DirFeatures): Detection        // Phase C 由特征表驱动
    // 启动
    fun buildLaunch(context: Context, spec: LaunchSpec): Intent
    val requiresAllFilesAccess: Boolean                  // krkr2-on-removable=false 等
    fun supportsSafBridge(): Boolean = false
    // 存档
    fun saveLocation(game: ScanGame, root: File): GameSaveManager.SaveLocation?
    fun excludedResourceNames(): Set<String> = emptySet()
    fun cleanupAppData(context: Context, game: ScanGame) {}
}
object EngineRegistry { val all: List<EngineContract> }   // 注册表单点
```

**三阶段迁移（全程不破坏 08 冻结契约与既有 119 测试）**
- **Phase A（零行为变化）**：建 registry 查表，把 GameSaveManager/Launcher 的 when 改为委托；穷尽性校验移至注册表构建（init 块 assert 覆盖所有 enum 值）。
- **Phase B**：LaunchSpec 类型化 Parcelable，extras v2 键 + 旧键双写一个版本过渡；08 矩阵同步 v2 键。
- **Phase C**：Detector 拆有序管道，File/DocumentFile 双变体共享同一特征表（顺带消除双实现漂移）。

风险控制：A/B/C 每阶段独立提交，Phase A 由现有测试直接护航；Phase B 需真机回归五链（10 号报告 §6 清单）。

## 六、结论

1. **兼容性**：日式经典四大系覆盖完整、深度业内领先（SAF 桥/镜像存档/多内核/补丁管线均为差异化能力）；空白在现代通用引擎（Ren'Py/Unity），且其正确解法是「外部跳转」而非内置——建议作为 #28 机制的延伸立项而非引擎移植。
2. **抽象度**：**中等**。物理隔离与契约纪律优秀，但逻辑层是「封闭枚举 + 多文件 when」的中等偏低抽象——当前 9 引擎规模尚可控（有编译器护栏兜底），第 10 个引擎接入时应先行 Phase A 再动业务，否则触点成本随每次扩展线性累积。
