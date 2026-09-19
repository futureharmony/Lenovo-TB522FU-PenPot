# Domain Context & Architectural Glossary (CONTEXT.md)

This document defines the ubiquitous domain vocabulary and structural seams for the Lenovo Pen on ColorOS Port project (`Lenovo-TB522FU-PEN`).

---

## 1. Domain Vocabulary (领域核心词汇)

### 1.1 Core Entities & Concepts

- **StylusBridge (手写笔桥接引擎)**:
  协调联想手写笔硬件事件（BLE GATT, UHID, UEvent）与 ColorOS 系统级触控笔服务（`ipemanager`, `system_server`, `healthservice`）的中枢模块。

- **PanelCardExtension (面板卡片扩展模块)**:
  负责在设备空间或系统弹出的触控笔底栏对话框（`COUIBottomSheetDialog`）中，在首帧绘制前（`OnPreDrawListener`）无缝挂载扩展手势卡片槽位（长按、捏握）的深模块。

- **GesturePreference (手势设置注入模块)**:
  负责将非原厂支持的 13 种书写扩展/全局快捷动作无缝嵌入到 OEM 原厂手写笔手势设置页（`PencilGestureSettingActivity`），并维系全局桥接键（Bridge Keys）的数据同步。

- **StylusActionDispatcher (手势动作调度引擎)**:
  解耦 UI 渲染与功能执行的核心调度器。接收统一的手势语义信号，路由至截屏、圈选翻译、便签呼出、全屏涂鸦、激光笔等具体动作实现。

- **ScreenInteractionService (屏幕交互与捕获服务)**:
  负责屏幕截屏、圈选裁切与多进程共享（`PenShareProvider`），封装多代 ColorOS 截屏 Token 与反射机制。

---

## 2. Resilience & Adaptation Seams (容错与自适应缝隙)

- **ContractProbe (协议契约探针)**:
  在 Hook 初始化阶段针对目标 OEM 应用的类、方法签名及布局结构进行静态与动态特征探测，判断契约状态（`HEALTHY`, `DRIFTED`, `INCOMPATIBLE`）。

- **FallbackStrategy (优雅降级策略)**:
  当主链路因 OEM 升级或移植环境差异导致契约失效时，自动激活的兜底实现（例如：原生卡片注入失效时回退到浮动快捷转盘；`IExSystemService` 截屏失效时回退到 `DisplayManagerGlobal`）。

- **CircuitBreaker (熔断保护器)**:
  运行期异常计数保护器。当某个进程中的 Hook 连续发生异常达到阈值时，自动熔断并静默放行原生代码，确保宿主系统绝对不卡死、不崩溃。

---

## 3. Architectural Decisions (2026-09-19 Review)

- **ADR-001: Flat Package Layout in com.aclaniakea.colorosporttuning**:
  保持包平铺以避免 Xposed scope、ProGuard/R8 混淆映射及反射签名的不必要重排。将超巨石模块拆分为高内聚的独立 Java 类（如 `PanelCardExtension.java`, `GesturePreferenceManager.java`, `StylusActionDispatcher.java`, `ContractProbe.java`）。
- **ADR-002: Deletion of Historical Intermediate Artifacts**:
  清理无引用的死代码（`PenStateProvider.java`, `fix-module/`, `build_hook_v1.py`, `build_hook_v68.py`）以及 `IpeManagerHooks` 内部旧迭代遗留的注释与死分支。
- **ADR-003: Floating Overlay Fallback (Option A)**:
  当 OEM 底栏对话框因 OTA 协议变更无法完成卡片注入时，系统自动降级激活浮动快捷菜单（Floating Quick Menu），确保所有 5 项扩展手势功能不受阻断。

