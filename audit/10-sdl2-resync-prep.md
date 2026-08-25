# 10 SDL2 Java 层重整前置研究（R-09 决策输入）

基线：commit `133667f`（2026-08-25）。本文回答 R-09（F-11 收尾）开工前的三个前置问题：
内嵌 SDL2 到底是什么版本、官方基线长什么样、当前反编译 Java 层与两颗 .so 的 JNI 契约是否自洽。
**核心结论先行：当前「老谱系 Java 层 × 双 .so」的配对经静态双向核验自洽（硬伤 0），盲目替换为官方源反而会破坏配对——R-09 策略从「重整」改判为「冻结 + 定向补丁」。**

复现脚本（本报告全部数据的提取逻辑）：`audit/tools/sdl2_contract_trace.py`。

---

## 1. 内嵌版本测定

| 插件 | 库 | 测定结果 | 方法 |
|---|---|---|---|
| ons | libSDL2.so (0.7MB) | **vanilla SDL 2.26.3** | ELF 字符串 `2.26.3` |
| kirikiroid2 | libSDL2.so (26MB) | **fork 构建，无版本串** | 同上（零命中）；结合上游 uyjulian/kirikiroid2 自带 SDL2 port 判断 |

## 2. 官方基线实况（release-2.26.3，commit adf31f6）

```bash
git clone --depth 1 --branch release-2.26.3 --filter=blob:none --sparse \
  https://github.com/libsdl-org/SDL.git && \
git sparse-checkout set android-project/app/src/main/java/org/libsdl/app
```

意外事实：官方 2.26.3 的 Java 层只有 **9 个文件**（HIDDevice×4、SDL.java、SDLActivity.java、SDLAudioManager、SDLControllerManager、SDLSurface）——SDLInputConnection / SDLJoystickHandler 系 / SDLHapticHandler 系等均以内部类形式并入 SDLActivity.java。

## 3. 文件集对照（vendored 22 vs official 9）

| 分类 | 文件 | 定性 |
|---|---|---|
| 双方共有（9） | HIDDevice{,.java 后缀略}… 见 §4 表 | 大幅偏离，见 diff 量级 |
| vendored-only：本项目新增 | **AudioRouteWatcher.java**、README.md | 保留 |
| vendored-only：旧谱系残留（11） | SDLMain、DummyEdit、SDLInputConnection、SDLClipboardHandler、SDLGenericMotionListener_API12/24/26、SDLHapticHandler(_API26)、SDLJoystickHandler(_API16/19) | 对应 ≤2.0.x 时代的独立文件布局 |

重叠文件的 diff 量级（official→vendored）：SDLActivity +955/−1812、SDLControllerManager +33/−753、HIDDeviceManager +351/−515……方向一致地表明 **vendored 层是显著更老的上游谱系，与 2.26.3 不存在可平移关系**（非「少几个补丁」，而是两代布局）。

## 4. JNI 契约双向核验（本次核心产出）

背景约束：三引擎分进程但**共享同一 APK 类空间**——一份 org.libsdl.app 必须同时服务 ons(2.26.3) 与 kirikiroid2(fork) 两颗 .so。

### 4.1 so→Java 导出面（native 期望的回调目标）

| so | 导出数 | 覆盖类 |
|---|---|---|
| ons 2.26.3 | 50 | SDLActivity×30、HIDDeviceManager×8、SDLControllerManager×9、SDLInputConnection×2、SDLAudioManager×1 |
| kr2 fork | 32 | 无 HID 系；多 `SDLInputConnection.nativeSetComposingText` 导出（死符号，见 §4.3） |

### 4.2 Java→so A 向核验（崩溃判据：Java 声明的 native 找不到导出即 UnsatisfiedLinkError）

严格行级提取（修正了初版正则把普通方法 `setOrientationBis` 误计为 native 的跨 token 匹配缺陷）：
vendored 共 **50 个 native 声明**（SDLActivity 30 / HID 8 / ControllerManager 9 / InputConnection 2 / AudioManager 1）。

| 判据 | 结果 |
|---|---|
| 双侧皆缺（硬伤候选） | **0** ✅ |
| ons 覆盖率 | **50/50 = 100%** ✅ |
| kr2 单侧缺失 | 19（HID×8、nativeAddTouch/FocusChanged/GetHintBoolean/GetVersion/PermissionResult/SendQuit/SetScreenResolution/onNativeLocaleChanged/OrientationChanged/SoftReturnKey/SurfaceCreated） |

kr2 侧 19 个缺口的风险定性：kr2 fork 为旧谱系，其引擎路径不会触达这些现代入口（HID 仅现代 SDLActivity.onCreate 创建；locale/orientation 回调仅新 native 调用）。上游 Tyranor 以同构配对实证发行，判定**可达性为零**；如需运行时兜底可在上述方法加 try/catch 空实现守卫——不建议，徒增噪声。

### 4.3 方法学备注
- 「so 多余导出」无害：JNI 绑定方向是 Java 声明→查找 C 符号；`nativeSetComposingText` 在 kr2 so 中属无人声明的死导出。
- 初版宽松正则会把「注释/修饰符中的 native 关键词 + 远处括号」跨 token 误联——所有 JNI 面统计必须用行级严格匹配（见工具脚本）。

## 5. 结论与对 R-09 的策略修订

1. **撤销「以官方源逐文件对照重整」的原方案**（09 报告 R-09 / F-11 待办）：官方 2.26.3 布局与 vendored 层是两代谱系，替换必然同时破坏 kr2 配对（fork 无 HID/新版回调）与既有行为，且收益仅为「消除 JADX 注释」这一洁癖项。
2. **改判为：冻结现状 + 定向补丁流程**。org.libsdl/app 视作与随包 .so 绑定的配对工件（paired artifact）：
   - 任何修改只允许「新增独立文件 + 显式调用点」（先例：AudioRouteWatcher / 耳机热插拔 eb6bfec）；
   - 禁止对 9 个共有文件做结构性重写；
   - 升级 SDL2 时必须整组替换（Java + 两颗 so 同源同版本）并走五链实机回归。
3. F-11 的残余风险（许可溯源/无法 diff 官方源）已在 THIRD-PARTY-NOTICES §四 与 org/libsdl/app/README.md 披露，维持即可；JADX 注释本身无害。
4. 若未来确需统一到单一上游版本，前置条件是**按插件隔离 Java 层**（每插件自带 dex/classloader 或拆分为多 AAR）——属架构级改造，超出当前迭代范围。

## 6. 五链回归清单（保留自 09/R-09，任何触碰 SDL 层的改动后必跑）

KRKR(139/134/126 各一游) / krkrsdl3 / ONS：启动至首帧 + 音频 + 触摸 + 软键盘输入四点验证。
