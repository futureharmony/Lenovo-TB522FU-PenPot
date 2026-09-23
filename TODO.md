# TODO

> **路线决策（2026-09-18）：走运行时 hook 路线。** 静态 patch / 重打包 ROM 方案暂缓，
> 待 hook 逻辑实机迭代稳定后再考虑固化进底包。
> **框架是 Vector（JingMatrix，`zygisk_vector`），不是 LSPosed。** 安装与验证步骤见
> [`docs/install-vector-route.md`](docs/install-vector-route.md)。

## P0.13 按应用翻页策略 + 触摸注入重写（Hook 4.8.1–4.8.6，2026-09-23 实机闭环）

**需求**（用户 2026-09-23）：109/110 在抖音 / 小红书这类垂直信息流里不生效。要求：进入某 App
首次触发翻页时弹窗让用户选「水平模拟 / 垂直模拟 / KeyEvent 上下 / KeyEvent 左右」，选择持久化，
同一 App 后续直接生效、不再弹；设备中心**笔面板主界面**（弹窗，非 Activity）新增一栏
「翻页功能触发方式」，可查看 / 修改 / 清除已配置 App。

**新增** `hook/source/sources/com/aclaniakea/colorosporttuning/PageTurnConfig.java`：
- 策略 `0 水平模拟 / 1 垂直模拟 / 2 KeyEvent 上下 / 3 KeyEvent 左右`；
- 持久化：`Settings.Global` 单键 JSON map `lenovo_pen_pageturn_map`（`{包名:策略}`），
  **system_server uid 写入**（跨重启保留；app 进程直写会被 provider 丢弃）；
- 面板写入双通道：直写 + `PAGETURN_CONFIG` 广播
  （`SystemStylusHooks.registerPageTurnConfigReceiver` → `PageTurnConfig.persist()` 无条件落盘，
  避免「上下文判定失手 → 广播自环」）；
- 设备中心入口：`PanelCardExtension` 把「翻页功能触发方式」并进**已验证可渲染的 in-card 通道**
  （与「长按 / 捏握」同一张卡片），view tag 防重复注入，不动 OEM 卡片。

**四个实机 bug 与修法**：
1. **弹窗出现但四个选项不可见 / 不可点** —— `AlertDialog.Builder(systemCtx).setItems()` 的 items 走
   framework 内部布局 `select_dialog_item`，文字色取自宿主系统主题，而原实现又把窗口背景设为
   `TRANSPARENT` ⇒ 文字与底同色。**修**：彻底弃用 `AlertDialog`，改为**完全自绘 `android.app.Dialog`**
   （自绘圆角卡片 + 显式文字色 + 自建可点选项行 + 按压态 + 应用图标），按 `uiMode` 走深 / 浅两套
   调色板（ColorOS 绿 accent：浅 `#00A863` / 深 `#43D17F`）。顺带修：`sPrompting` 清理挪到
   `OnDismissListener`（原实现在用户点「取消」后会让该 App **整个会话不再弹窗**）。
2. **设备中心笔面板看不到那一栏** —— 面板是 `COUIBottomSheetDialog`（宿主 `PencilPanelActivity`），
   `Dialog.show` hook 收到的 `ctx` 是 `ContextThemeWrapper`，`instanceof Activity` 恒为假
   ⇒ 早期的宿主判定永远不命中。**修**：不再判定宿主，并入「长按 / 捏握」那条已验证通道。
3. **改了配置仍反复弹初始化窗** —— `getForegroundPackage()` 用
   `queryUsageStats(INTERVAL_DAILY, now-5s, now)` 取 `lastTimeUsed` 最大者。DAILY 桶是「今天用过的
   **所有** App」聚合，胜出者常是 systemui / 桌面 / ipemanager ⇒ 写进 map 的**键就是错的**，
   配置永远匹配不上真实前台。**修**：分层检测 `foregroundCandidates()` ——
   L1 `getRunningTasks(1)` topActivity → L2 `UsageEvents` 正序回放取「最后一个仍 RESUMED 且未被
   PAUSED / STOPPED」的包 → L3 旧 API 仅兜底；「已配置」判定只用 L1/L2；浮层宿主黑名单
   （systemui / launcher / ipemanager / android）不作为写入目标；无可用候选则退回横向且**不弹窗**
   （不写脏键）。
4. **模拟滑动 / KeyEvent 在小红书里完全无效**（本段核心）—— 配置、检测、分发**全部正常**：
   日志 `perform pkg=com.xingin.xhs strategy=1` + `injected vertical swipe (1920.0 -> 640.0)`；
   按住 `(1920,1920)` 6s 期间 `dumpsys input` 的 `TouchStatesByDisplay` 显示
   `name='com.xingin.xhs/.index.v2.IndexActivityV2' ... touchingPointers=[Pointer(id=0,FINGER)]`
   ⇒ **事件确实送达 App 窗口，是 App 主动丢弃**。与 `adb shell input swipe`（同机 100% 能切视频）
   逐项对照出三处构造差异：`deviceId=0`（无设备）／12 个事件在 1~3ms 内灌完且 `eventTime`
   **落在未来 120ms**／`injectInputEvent(..., ASYNC)`。
   **修**（`StylusActionDispatcher.java`）：新增 `touchDeviceId()`（取真实 `SOURCE_TOUCHSCREEN`
   设备 id，本机 = 6）、12 参 `MotionEvent.obtain(..., pressure, size, deviceId, edgeFlags)`、
   **逐帧 `Thread.sleep(16)` 使 `eventTime` = 真实墙钟**、`INJECT_WAIT_FOR_FINISH(2)`；因
   WAIT_FOR_FINISH 阻塞，整条手势移入 `pen-swipe` 工作线程 + `sInjecting` 互斥。
   **横 / 纵两条路径合并为 `swipeInternal(vertical)`**，`injectSwipe` / `injectVerticalSwipe`
   对外签名不变 ⇒ **水平滑动同样吃到本次修复**。
   `injectKey` **故意未改**（仍是 ASYNC + deviceId 0）：它被 BACK / HOME / APP_SWITCH 复用，
   且系统键事件不做 hit-test，`deviceId` 无影响。

**实机验证**（v4.8.6，2026-09-23 14:0x）：装包 → `su -c 'setprop ctl.restart zygote'` 软重启
（system_server pid 2273 → 10844，Vector 重载模块）→ 小红书前台连按两次 110，截图比对笔记作者：
**诗卿（便利店）→ Meyers时尚笔记（Prada）→ 一舰大白菜（高速救援）**，连续前向翻页生效；
日志 `injected vertical swipe next=true dev=6 (1920.0,1920.0) -> (1920.0,640.0)`。
编译 `ACL_VERSION=4.8.6` 走 `hook/tools/build_hook_source.py`（`ANDROID_SDK=/tmp/android-sdk`，
build-tools 必须 `35.0.0`），`versionName=4.8.6 / versionCode=480006`。

**遗留**：
- `KeyEvent上下 / KeyEvent左右` 依赖目标 App 自己挂 `onKeyDown`；抖音 / 小红书都不吃键 ⇒ 对它们
  只有模拟滑动有效。这是 **App 侧语义**，不是模块缺陷。
- 旧版本检测器可能已写入**错误键**的条目：升级后建议在面板逐条清除，或
  `adb shell settings delete global lenovo_pen_pageturn_map` 后重新配置一次。
- 设备中心那栏的布局对齐仍需真机核对（本机无法复现面板渲染）。

## P0.14 轨迹校准改为显式「自定义范围」（Hook 4.8.7，未装机自测）

**需求**（用户 2026-09-23）：
1. 部分 App 里模拟滑动失效，是因为**默认滑动的起始位置不在可滚动区域内**（评论区上方只剩视频区、
   分栏布局里只有一栏可滚、固定头图下的轮播……）。需要让用户录一次自己的轨迹，之后该 App 按此回放。
2. **但校准层不许自动弹**：只有用户主动点「自定义范围」才出现，且 UI 必须与 ColorOS 一致。

**结论（这一版把上一版的「自动弹」整段推翻）**：4.8.1–4.8.6 里选完「垂直 / 水平模拟」会**立刻弹**
全屏校准层（先看默认范围动画 → 确认执行 / 点自定义记录）。用户否决了这个交互 —— 一次普通翻页不该
被全屏窗口打断。4.8.7 起：

- **选择策略后直接执行**（有记录用记录，无记录用默认比例路径），`SwipeCalibrateOverlay` 不再被
  隐式拉起。改动点：`PageTurnConfig.showPrompt()` 的 `onPick` 去掉 `startCalibration(...)` 分支，
  只剩 `perform(ctx, pkg, next, strategy)`。
- **唯一入口 = 设备中心 → 翻页功能触发方式 → 某个 App →「自定义「下一页」/「上一页」滑动范围」**
  （`PageTurnConfig.showChangeDialog`）。该行描述直出当前状态：
  `当前：默认范围 · 点按后滑一次即可记录` / `当前：自定义 · 向上滑动 · 268ms · 点按可重录`；
  另有「恢复默认滑动范围」行（两个方向任一有记录时才出现）。
  面板走 `PAGETURN_CONFIG` 广播 → system_server 用 system uid 开 overlay 窗口（面板自己开不了）。
- **`SwipeCalibrateOverlay` 重写为「记录 → 结果」两态**（原 `PHASE_PREVIEW` 删除）：
  初始即 `PHASE_RECORD`；录完落盘转 `PHASE_RESULT`，循环播放刚保存的轨迹供确认，行变为
  「重新记录 / 恢复默认范围 / 完成」。**完成只关闭，不执行任何翻页** —— 这是设置，不是动作。
  返回键一律 `finish()`（已保存的照旧保存）。`ACTION_CANCEL` 不再关闭面板，只清点并提示重试。
- 记录时把**当前生效的范围**用虚线淡线 + 两个空心圈画在底层（`drawReference`），用户能看出自己的新
  轨迹相对旧范围偏了多少；`dimAmount` 0.45 → **0.30**，好让背后的 App / 面板看得见，便于瞄准起始点。
- 卡片本体 `setClickable(true)`，避免记录时点到卡片也被算进轨迹。
- 若校准时的前台不是目标 App（在设备中心里校准的常见情形），卡片多一行提示：
  「当前不在「XX」内，坐标按屏幕比例记录 —— 请按该应用中可滚动内容的实际位置滑动」。
  （`awayHint()`，黑名单判定：ipemanager / systemui / launcher / android。）
- **ColorOS 化**：`pressable()` 由「纯色按压底」改为 **bounded `RippleDrawable`**（圆角遮罩裁剪，
  并保留 StateListDrawable 回退）；卡片圆角 26 → 28dp；列表行 15 → 16sp、行高 60 → 62dp；
  新增 `sheetHandle()`（36×4dp 拖拽提示条，仅视觉）；调色板新增 `ripple` / `handle` 两色，
  浅 `#00A863` / 深 `#43D17F` accent 不变。

**构建**：`ANDROID_SDK=/tmp/android-sdk ACL_VERSION=4.8.7 python3 hook/tools/build_hook_source.py`
→ `versionName=4.8.7 / versionCode=480007`，0 error；dex 校验含 `SwipeCalibrateOverlay`(+$1..$7/$Surface/
$Listener/$TrailView)、`sheetHandle`、`drawReference`、`strategyRows`、`anyCalibration`。
APK `releases/PenBridge-Hook-tb522fu-v4.8.7.apk`（sha256 `d824a787…4169c3`）。

**遗留**：
- 设备 USB 掉线，**未装机自测**；本轮全部为编译期 + dex 校验，UI 观感需真机确认。
- 「自定义范围」一旦从设备中心进入，滑动的屏幕是面板而不是目标 App（只能靠比例换算 + 提示语引导）。
  若实测难瞄准，再加一条「先跳到该 App 再校准」的入口（`getLaunchIntentForPackage` + 延时开窗）。

## P0.15 校准层挂载点审查：留在 system_server，但把爆炸半径钉死（Hook 4.8.8）

**问题**（用户 2026-09-23）：「模拟滑动窗口挂在 system 下，是否会影响 system 稳定性？有没有更合理的
挂载点（比如挂到某个 app）？」

**事实核验**（不是推测，逐条对应代码）：
- `PageTurnConfig.sMain = new Handler(Looper.getMainLooper())`，`startCalibration()` 把
  `SwipeCalibrateOverlay.show()` post 到这个 looper；接收方 `registerPageTurnConfigReceiver` 注册在
  **system_server** ⇒ `wm.addView()` 由 **system_server 主线程**执行。
- `ViewRootImpl` 在**调用 `addView()` 的那个线程**上构造 ⇒ 该窗口的 measure / layout / draw /
  `dispatchTouchEvent` / **所有行点击回调**都在 system_server 主线程上跑。这是这个挂载点的真实代价，
  不是「有个窗口」这么轻。
- 该窗口是**全屏 + 可触摸 + 可获焦**（无 `FLAG_NOT_FOCUSABLE`，`Surface.setFocusable(true)`）。
  Home 键不会移除它，息屏不会移除它，底下的 App 被杀也不会移除它 ⇒ 一旦残留，**全设备触摸被吃掉**，
  表现就是「平板卡死」，只能重启。

**判决：不迁到 app 进程。** 迁到模块自己的进程只能买到「崩溃隔离」，代价却是：模块 APK 目前
**只有 Receiver + Provider、没有任何 Activity**（`hook/source/resources/AndroidManifest.xml`）⇒ 要新加
组件 + 启动入口；要 `SYSTEM_ALERT_WINDOW` 且要用户手动授权「显示在其他应用上层」（ROM 还可能回收）；
轨迹要经 IPC 回 system_server 才能落盘（而 `Settings.Global` 只有 system uid 能写）；注入本身也仍必须
留在 system_server。同一个功能两套代码路径，换来的是稳定性边际收益 —— 不划算。

**做法：把三类风险就地钉死（4.8.8）**：
1. **异常防火墙**。`Surface.dispatchTouchEvent` / `dispatchKeyEvent`、`TrailView.onDraw`
   （拆出 `drawTrail`）、帧循环 `previewTick`、`heartbeat` 全部 try/catch 到底。理由：行点击回调是在
   `dispatchTouchEvent` 里跑的，漏出去的异常不是「overlay 坏了」，而是**框架重启**。
   （`onDraw` 另有依仗：framework 的软件绘制路径只 log 不中断，但不能赌这个。）
2. **空闲看门狗 + 息屏自关**。新增 `heartbeat`（1 秒一条消息）：`PowerManager.isInteractive()` 为假
   → `finish()`；连续无输入 `WATCHDOG_MS = 90s` → `finish()`。任何触摸/按键都会重置计时
   （`lastInputRt`，在 `dispatchTouchEvent` 里打点，这样被行消费掉的点击也计入）。这是「窗口绝不会
   残留」的兜底 —— 之前的实现**只**靠用户主动点取消/完成/返回。
3. **主线程开销封顶**。结果态预览动画 `MAX_ANIM_CYCLES = 4` 圈后停在完成帧、不再 post（之前是 16ms
   无限循环）；`FRAME_MS` 16 → 24；记录态实时轨迹 `invalidateTrail()` 节流到 `TRAIL_MIN_FRAME_MS = 24ms`
   （≈40fps，**只影响画面刷新率，不影响采样** —— 采样仍是逐事件的）。`attach()` 失败时补 `detach()`
   （原来只清 `sCurrent`，半挂载状态会让 `isVisible()` 永远为真、静默废掉整条翻页分发链路）。

**构建**：`ANDROID_SDK=/tmp/android-sdk ACL_VERSION=4.8.8 python3 hook/tools/build_hook_source.py`
→ `versionName=4.8.8 / versionCode=480008`，0 error；dex 校验含 `WATCHDOG_MS`/`heartbeat`/
`invalidateTrail`/`drawTrail`/`dispatchTouchEvent`。APK `releases/PenBridge-Hook-tb522fu-v4.8.8.apk`。

**未做（等用户定）**：把「从设备中心点自定义范围」改成**先 `getLaunchIntentForPackage(pkg)` 拉起目标 App、
延时 ~900ms 再开窗** —— 这是真正解决「盲瞄」的做法，且不必迁进程。属交互变更，未擅自改。

## P0.16 四选一里没有通往校准层的路（4.8.7 砍过头）+ 面板弹窗 WindowLeaked（Hook 4.8.9）

**用户复现（2026-09-23 17:39，`com.brave.browser` 首次触发）**：「弹出了设置框，四个选项没问题，
但是点击模拟上下/左右，不会有自定义的弹窗弹出，也无法设置自定义滚动轨迹」。

**定位（日志铁证，不是推测）**：
```
17:39:48.307 PageTurnConfig: prompt pkg=com.brave.browser candidates=[com.brave.browser]
17:39:49.959 PageTurnConfig: persist com.brave.browser -> 1
17:39:49.959 PageTurnConfig: prompt choice com.brave.browser -> 1
17:39:51.311 PageTurnConfig: perform pkg=com.brave.browser strategy=1
```
`prompt choice` 之后**直接** `perform`，全程**没有一条 `PenSwipeCalib`**。即：4.8.7 把
`onPick` 里的 `startCalibration(...)` 整段删掉后，**四选一这条路彻底不通往校准层**；唯一入口只剩
「设备中心 → 翻页功能触发方式 → App → 自定义…滑动范围」。而面板那条路还有第二个坑：校准行
`if (isSwipeStrategy(cur))` 才出现，**未配置策略的 App 打开面板只看得到四个策略行**，用户极易
判定「根本没法录轨迹」。4.8.7 的反向过度修正在此——用户原话「只有点自定义范围才弹校准层」指的是
**别自动弹全屏层**，不是「把四选一里那条路删掉」。

**修法（4.8.9）——加一页小的，全屏层仍然只在显式点击后才出现**：
- 四选一点中**模拟**类策略时，不再直接 `perform`，而是弹**第二页小卡片**
  （`showRangePage`）：标题「「下一页」的滑动范围」+ 两行
  「用默认范围（内置比例路径 · 直接返回也是这个）」/「自定义滑动范围（按你习惯的方式上滑一次…）」，
  另有「取消」（**什么都不做**）。
- 「自定义滑动范围」→ `startCalibration(..., listener)`，录完 `onDone` 再 `perform` —— 全屏层
  依旧只在这一次显式点击后出现，普通翻页不会被它打断。
- **返回键 = 用默认范围**，按需求原文「② 如果用户确认或直接返回，就按照默认的滑动范围执行」：
  翻页请求本来就已发出，这一页只问「怎么滑」。副标题里写明「直接返回也是这个」，不让它变成暗雷。
- KeyEvent 类策略**不问**（没有范围可校准）；`getCalibration(pkg,next) != null` 时也不问
  （已有轨迹，直接用）。
- 并发保护：`sRangePageUp` 为真期间 `performWithPrompt` 直接忽略笔触发（策略此时已落盘，
  否则会在用户还没答完时就用默认路径执行）；第二页显示期间把 pkg 重新塞回 `sPrompting`。

**顺带修一个用户没报的真 bug（同一次日志捞出来的）**：
```
17:27:41.317 E WindowManager: android.view.WindowLeaked: Activity
  com.oplus.ipemanager.btadsorb.pencilPanel.activity.PencilPanelActivity has leaked window
  com.android.internal.policy.DecorView{…}[PencilPanelActivity] that was originally added here
  … at android.app.Dialog.show(Dialog.java:370)
     at com.aclaniakea.colorosporttuning.PageTurnConfig.styleAndShow(PageTurnConfig.java:1016)
     at PageTurnConfig$3.run(PageTurnConfig.java:807)
```
我们在**不属于自己的 OEM Activity**（`PencilPanelActivity`）上挂弹窗，`reopenConfigSoon` 的
250ms 定时器到点时面板可能**已经关了** ⇒ 弹窗无主、窗口泄漏。修法两条：
1. `reopenConfigSoon` 先查 `hostGone(ctx)`（`isFinishing() || isDestroyed()`）再 show；
2. 新增 `HostLifetime implements Application.ActivityLifecycleCallbacks`，由 `bindToHostLifetime`
   在 `styleAndShow` 里挂上（仅当 `activityOf(ctx) != null`；system_server 路径天然 no-op），
   宿主 `onActivityDestroyed` 时 `dismiss()` 我们的弹窗并自我注销；弹窗自行关闭时（`!isShowing()`）
   在下一次生命周期回调里回收，避免回调堆积。

**构建**：`ACL_VERSION=4.8.9` → `versionName=4.8.9 / versionCode=480009`，0 error；dexdump 校验
283 个类定义中 xposed/UEventObserver **0 个**（旧 skill 的 `strings` 计数判据会误报 2，已修）；
dex 含 `showRangePage`/`sRangePageUp`/`HostLifetime`/`bindToHostLifetime`/`hostGone`。
**已装机 + 重启生效**（设备 17:43:56 boot，`pageturn config receiver registered`）。

## P0.17 校准改为「透传录制」：不吞输入，只看一份副本（Hook 4.9.0 → 4.9.1）

**需求**（用户 2026-09-23）：自定义滑动范围时，滑动事件要**从 overlay 透传给下层 App**，让用户
在录制时看到目标应用的真实表现；同时 overlay **左上角展示当前下层 App 信息**。

**问题定性**：原校准层是全屏**可触摸**窗口，录制时下层 App 被冻住 —— 用户对着一块不动的屏幕
瞄准，录到的轨迹描述的是 App 从没见过的一次滑动。「滑了没反应」因此无法区分是轨迹不对还是
功能坏了。

**两件事，缺一不可**：
1. **不吞**：录制态窗口 flags 改为 `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE |
   FLAG_NOT_TOUCH_MODAL`，且**去掉 `FLAG_DIM_BEHIND`**（把 App 调暗是同一个谎的另一种形式）。
   `NOT_TOUCH_MODAL` 不是可选项 —— modal 窗口会把可触摸区域之外的事件**吸收**而非下传。
2. **照记**：新增 `TouchSpy.java`，用 **gesture monitor**（`InputManager.monitorGestureInput`）
   拿到事件流的**只读副本**，App 照常收到并响应原始事件；轨迹与时间戳从副本取样。窗口没变
   touchable，所以这**不是「转发/注入」**（注入会被最上层窗口再吃一次，且绕不开死循环）。

**实机踩到的三个硬事实（全部有日志/代码证据）**：
1. **`monitorGestureInput` 的返回类型变了** —— Android 15 / ColorOS 返回
   `android.view.InputMonitor` **包装对象**，不是 `InputChannel`。4.9.0 直接把返回值当 channel 用，
   于是 `PenTouchSpy: unexpected channel type class android.view.InputMonitor` →
   `no monitor channel available, recorder stays blocking`：**日志一片健康，功能静默降级**。
   修法：防御式解包 `getInputChannel()` → 字段 `mInputChannel` → 才放弃；解包出的 wrapper 要留着，
   `close()` 它才是注销 monitor（且它自己会 dispose channel，别重复释放）。
2. **这个 API 是 system-uid 专属**（`callingUid == SYSTEM_UID || SHELL_UID`）。实测
   `com.oplus.ipemanager` 跑在 **u0_a121**（普通 app uid）⇒ **从面板进程起的录制永远拿不到 spy**。
   因此把「自定义范围」全部改成广播交给 system_server 开窗（`showRangePage` 不再本地
   `startCalibration`），并把「先拉起目标 App」也放在那边做。路由收敛成单入口：
   `startCalibration` 只被 `SystemStylusHooks` 调用，面板两处只发 `requestCalibration`。
3. **必须在目标 App 里录**：透传意味着「下层是谁」不再是装饰 —— 在设备中心面板上录会得到一条
   目标 App 永远收不到的轨迹。所以点「自定义滑动范围」先 `getLaunchIntentForPackage` +
   `FLAG_ACTIVITY_NEW_TASK` 拉起目标 App，再**轮询前台**（220ms × 14 ≈ 3s，有界，失败照常开窗）。

**UI**：录制态没有卡片（窗口不可触摸，卡片上的按钮也点不到），改为画布提示（两行，带阴影保证
在任何 App 上都可读）+ 左上角**独立小窗** App 芯片（图标 / 名称 / 包名，浮层上点击 = 取消）。
芯片必须另起一个窗口：**一个窗口没法靠 flag 做到「部分区域透传、部分区域可交互」**。前台不是目标
App 时芯片转 warn 色并显示「≠ 目标「X」」，画布首行也照实说当前前台是谁。

**语义修正**：透传时那次滑动**已经作用于 App**，所以「完成」绝不能重放（否则翻两页）。规则收敛到
`finish()` 一处：`listener != null && !passThrough` 才执行欠下的翻页；blocking 降级时照旧执行。
顺带把「安全网关闭」与「用户关闭」分开（`autoClose(reason)` 不执行欠账）—— 息屏/看门狗触发时
注入手势是用户没要求过的动作（4.8.9 实测有过这条日志）。

**构建与验证（4.9.1，2026-09-23 18:02 装机 + 重启）**：
- 新增 `hook/source/stubs/android/view/{InputChannel,InputEventReceiver}.java`（**编译期 stub**，
  `android.jar` 里没有这两个类）+ `build_hook_source.py` 的 `_provided_prefixes` 增加两条 ⇒
  产物里这两个类定义 **0 个**（dexdump 292 类），反射字符串
  `monitorGestureInput` / `monitorInput` / `getInputChannel` / `mInputChannel` 都在 dex 里。
- 实测日志（`am broadcast ... --es op calibrate --es pkg com.android.settings`）：
  `monitorGestureInput via client -> channel (InputMonitor)` → `spy armed` →
  `recording started ... passthrough=true`，随后**一次真实手指滑动被录下并落盘**
  （`persistCalibration ... pts=32 向右滑动 · 882ms space=3840x2560`）→ `result ... custom=true`
  → `closing ... why=user`，**整个过程没有出现 `injected ... swipe`**（透传模式不重放，符合设计）。
- 用户随后自测也走了同一条新链路（`recording started for com.oplus.wirelesssettings
  ... passthrough=true`，来自 app 进程经广播到 system_server）⇒ 路由改造在真机成立。
- **未做**：录制态的观感（芯片 / 画布提示 / 虚线参考范围）与「下层 App 真的跟着滚」这最后一步
  需要人眼确认 —— 我在设备侧时它是锁屏/息屏状态，没有可看的画面，也不该去动用户的锁屏。
- 中间产物 4.9.0（返回类型 bug 版）已删，不提交。

## P0.18 设备中心不再提供重录入口；已自定义轨迹的应用锁定两个模拟项（Hook 4.9.2）

**需求**（用户 2026-09-23）：
1. **不允许**在设备中心的 App 设置里触发滚动轨迹重设，移除相关选项；该页**只保留 4 个选项**，
   与 App 内首次触发的四选一保持一致。
2. 若某 App 已有自定义滚动轨迹 ⇒ 该页的**水平模拟 / 垂直模拟置为不可选**，并提示用户
   「清空设置后回到 App 中重新触发自定义滚动」。

**为什么这两条是同一个判决**：轨迹是**按轴**录的（`lenovo_pen_pageturn_swipe` 的键是
`pkg|dir`，录的是真实手指路径）。在面板里重录必然发生在**别的 App 之上** —— 4.9.1 之后录制层
是透传的，「下层是谁」直接决定录到哪条轨迹，从设备中心发起等于录一条目标 App 永远收不到的路径
（这正是 `getLaunchIntentForPackage` 要解决的坑）。所以录制入口**只能**在目标 App 内；
面板退化成纯粹的策略选择器 + 清除。同理，已有轨迹时再改模拟轴 = 拿 A 轴录的路径放到 B 轴上重放，
起点与方向都不对 ⇒ 直接锁死，而不是让用户踩一个静默失效。

**改动**：
- `showChangeDialog` 删掉 4 处入口行：「自定义「下一页」滑动范围」「自定义「上一页」滑动范围」
  「恢复默认滑动范围」（原 `if (isSwipeStrategy(cur))` 整块），只剩四行策略 + 清除 + 取消。
  校准的**唯一**入口回到 App 内：`performWithPrompt` → `showRangePage` →「自定义滑动范围」。
- `strategyRows(..., boolean locked)`：`locked && isSwipeStrategy(i)` 的行传 `enabled=false`；
  **KeyEvent 两行不禁**（不注入手势、与轨迹无关，仍可切换）。
- `optionRow` 新增 `enabled` 重载：不可选时**没有 ripple、没有 click listener、不可聚焦**，
  名称/描述/圆点用新加的 `Palette.muted`（深色 0x4DFFFFFF / 浅色 0x42000000）。
  保留圆点本身是刻意的 —— 被锁的行很可能**就是当前值**，把它涂成一个"没选中"的空圈反而更差。
- 新增 `lockedNotice`（说明为什么锁 + 给出路）与 `calibrationDetail`（列出已录方向与时长，
  如「「下一页」向上滑动 · 882ms」），标题副行同时显示「当前：垂直模拟 · 已自定义滑动轨迹」。
- **保留「清除该应用配置」行**：提示语指向它，删掉它这条提示就是死路（清除会同时清掉策略与两个
  方向的轨迹 ⇒ 回 App 触发即重新走四选一 + 自定义录制）。若用户要求连它一起去掉，需另给出路。
- 空态提示语（`showConfigDialog`）里「进入对应应用后选「自定义「上一页 / 下一页」滑动范围」」
  改为「在该应用里触发翻页、选模拟时点「自定义滑动范围」」——旧文案描述的是已删除的入口。

**副作用（已知并接受）**：从面板把策略改成 KeyEvent 后，自定义轨迹仍然存在（休眠但不删），
因此两个模拟项**仍然锁着**；要回到模拟滑动必须先清除再回 App 重选重录。这是"锁定状态 = 存在轨迹"
这条简单规则的直接推论，若嫌绕，可选方案是改策略为 KeyEvent 时顺带清轨迹（未采纳：静默销毁
用户亲手录的数据更糟）。

**构建与验证（4.9.2，18:30 装机 + 重启，`versionCode=490002`）**：0 error；dex 里
`lockedNotice` / `calibrationDetail` 均在，旧的面板文案「自定义「下一页」滑动范围」
**出现 0 次**（`grep -ac` 原始 dex），289 个类定义中 xposed/UEventObserver **0 个**。
Hook 上载日志：`system_server stylus hooks installed (startOtherServices=1 run=1)`、
`pageturn config receiver registered`。**观感（灰显是否够明显、提示是否可读）仍需人眼确认。**

**文案定稿（4.9.4，19:11 装机 + 重启，`versionCode=490004`）**：`lockedNotice` 按用户最终原话定为
两行 —— `已应用自定义轨迹：<detail>。` / `「水平模拟 / 垂直模拟」暂不可选 —— 重设模拟轨迹请点击
清除该应用配置后，到App界面，用笔翻页，触发模拟手势设置。`（原三行的第二行「改轴会让这条轨迹失效」
被并入，不再单独成句）。**按钮名以提示为准**：4.9.3 曾把两处 `optionRow` 改名「重置」，但用户定稿的
提示指向的是「清除该应用配置」，故 4.9.4 全部回退原名（`showChangeDialog` 副行「清空后到App界面用笔
翻页，重新触发模拟手势设置」；`showChoice` 保留「下次触发时重新询问」）。**功能全程未动** —— 仍是
`clearCalibration(ctx,pkg,true/false)` ×2 + `setStrategy(PICK_CLEAR)`。
**规矩（写进注释）**：提示语里出现的按钮名，必须在界面上逐字存在；两侧任一侧改动时一起改。
dex 校验：新文案命中 1 次、旧「重置」行文案 0 次，289 类、xposed/UEventObserver 定义 0。

## P0.19 笔断开后，「长按 / 捏握 / 翻页」三行与原始选项同步置灰（Hook 4.9.6）

**需求**（用户 2026-09-23）：笔断开连接后，捏握和长按两个设置项**仍可选**；要求与其它原始选项
（下滑 / 双击 / 上滑触控条）同步，断开时置为不可选。

**为什么不能自己造一个「笔是否连接」判据**：这三行是我们注入的，ipemanager 不认识它们，所以
**没有任何原厂逻辑会去禁用它们** —— 这是缺失功能，不是判据错误。最贴近需求的做法是**照抄原厂行
此刻的状态**：同一屏、同一时刻，原厂灰我们就灰，原厂亮我们就亮。

**判据（三层，全部朝「可用」一侧容错）**：
1. 原厂三行（`titleToGestureType` 命中的那些）容器 `isEnabled()==false` 或 `alpha<=0.95`
   ⇒ 若**全部**如此则禁用。Android 原生灰显（`setEnabled(false)` + color selector）必然伴随
   `isEnabled=false`，所以这个信号是充分的；原厂若改用 textColor 灰显也一定同时 setEnabled(false)，
   否则点了还有反应、逻辑不成立。
2. 原厂行可用 ⇒ **启用**，不理会 BT 栈怎么说。读错 profile 把**本来可用**的行灰掉，比现在的视觉
   不一致更糟 —— 功能不可用 > 观感不统一。
3. 只有拿不到原厂行（`stock==null`）才回退 `BluetoothAdapter.getProfileConnectionState(4)`
   （`HID_HOST_PROFILE` 是 hidden，本地常量 4）；读不到（无权限/异常）⇒ 未知 ⇒ 启用。

⚠️ **不复用 `HookUtils.bluetoothConnected`**：它在 HID profile 与 live uhid link 不一致时**乐观返回
true** —— 那是回答「能不能用笔」的正确取值，却是回答「要不要灰」的错误取值。灰显要的是悲观值。

**观感**：`setEnabled/setClickable/setFocusable(false)` + 整行 `setAlpha(原厂灰显 alpha)`
（取原厂最小 alpha；拿不到用 0.4）。alpha 向下传递给 title / 值文本 / 箭头，一次调用整行变灰；
`clickable=false` 同时让按压态 StateListDrawable 不再闪。点击回调里再加一道 `if (!v.isEnabled()) return;`。

**刷新**：可用性不是一次性事实 —— 面板等 BT 栈是异步的，笔也可能在面板开着时断。在原 0/400ms 安全
pass 之后追加 **600ms × 最多 20 次**的轮询（每次一次树遍历 + 几次属性读），面板 detach 或次数用尽即停；
只在状态变化时打日志：`panel usability stockRows=3 verdict=DISABLED dim=0.4 stockEnabled=[off@1.0,…]
hidHost=false`。

**构建与验证（4.9.6，19:21 装机 + 重启，`versionCode=490006`）**：0 error；dex 含 `lenovo_extra_row_`
与可用性日志串，290 类定义、xposed/UEventObserver 定义 0。**待用户断笔后打开面板目视确认三行变灰
且点不动、连笔时恢复正常**；诊断看 logcat 的 `PanelCardExtension: panel usability …`。
中间产物 **4.9.5（双判据 AND 版，存在误灰风险）已删、不提交**。

**补修（4.9.7，`versionCode=490007`）—— 刷新窗口太短**：用户清光所有翻页配置后，面板那行右侧仍显示
「已配置 3 个应用」，**重开面板才变**。根因是上面那个轮询的 **12 秒上限**（600ms × 20）：用户清除时
面板已开了十几分钟，tick 早停了，而面板自身的生命周期**不会**因为在其上方弹 dialog 再关掉而重走
`onResume`。改成**跟随面板生命周期长驻**：`TICK_MS=1000`、`TICK_LIMIT=900`，`!root.isAttachedToWindow()`
即停；面板不可见时只花一次 `isShown()` 跳过工作。顺带把 tick 变幂等（值文本仅在变化时 setText、
`applyRowUsable` 仅在状态变化时 set*），否则每秒一次无条件 `setText` 会让面板每秒 requestLayout。
这同时修掉同一类缺陷的另一面：**面板开着时断笔，12 秒后可用性也不再刷新**。
⚠️ 教训记账：**"限时轮询"是错误形态** —— 只要刷新目标是随时间变化的外部状态，生命周期就该跟宿主走，
上限只做防泄漏兜底，不做功能窗口。

## P0.20 「重新记录」未重新武装手势旁路；录制 hint 加半透明底（Hook 4.9.8）

**问题 1**（用户 2026-09-23）：App 内选模拟 → 第一次录制保存 → 点「重新记录」→ 第二次滑动
**能响应**（底层 App 照常动），但**结束不弹「已保存滑动范围」**，画布也仍显示第一次的轨迹。

**根因**：`enterResult()` 里 `stopSpy()` 把 `spy` 置 null 并释放 monitor，而 `startRecord()`
**没有重新武装**。于是第二次录制时 `passThrough` 还是上一次的 `true` ⇒ 窗口依旧
`FLAG_NOT_TOUCHABLE` ⇒ 手势照常落到底层 App（所以"能响应"），但**没有任何监听者** ⇒ 收不到
`ACTION_UP` ⇒ `commitRecord()` 永不执行 ⇒ 不保存、不弹结果卡；`trail.live` 一直是 `startRecord()`
写进去的 `null` ⇒ 画布只剩参考虚线（上一次保存的轨迹），看起来就是"第二次的轨迹没被展示"。

**修**：`startRecord()` 里补
`if (spy == null) { spy = TouchSpy.start(ui, spyListener); passThrough = spy != null; }`，
且**必须放在 `applyRecordWindowStyle()` 之前** —— 窗口样式取决于新的 `passThrough`，拿不到旁路时
要正确降级回 blocking 模式（而不是留着一个透传但没有监听的窗口）。
⚠️ 形态教训：**结束态释放的资源，回到起始态时必须重新申请**。这类 bug 症状极具误导性 ——
功能"半活"：输入通了、输出没了，用户看到的现象（"手势能响应"）恰好把最可能的方向带偏。

**问题 2**：录制时画布上的提示（"在「xx」上按你习惯的方式上滑一次"）只有 shadow，浅色 / 花哨背景上
看不清（用户要求加带透明度的背景）。
**修**：新增 `Paint hintBg` 给两行提示垫一块圆角半透明底 ——
颜色 `(pal.card & 0xFFFFFF) | (HINT_BG_ALPHA << 24)`，`HINT_BG_ALPHA = 0xE6`（≈90%）；
宽度取两行最宽者 + 左右各 `dp(16)`，并夹到 `sw - dp(24)` 以免整条跑出屏幕；高度从 main 基线上方
一行覆盖到 sub 基线下方。shadow 保留作第二道保险。**半透明是刻意的**：录制的全部意义就是能看见
底层 App，不透明底会把这件事抹掉。

**构建与验证（4.9.8，19:32 装机 + 重启，`versionCode=490008`）**：0 error；dex 字符串池含
`drawRoundRect`、290 类、xposed/UEventObserver 定义 0。**待用户验证**：① 结果卡点「重新记录」后，
第二次滑动能正常保存并弹结果卡、画布实时显示新轨迹；② hint 在浅色 / 花哨界面上可读。

## P0.21 「第一次滑动不弹结果卡、第二次才弹」= 拒绝理由画不出来 + 60ms 阈值太高（Hook 4.9.9）

**用户报告（2026-09-23 19:43）**：App 内选模拟 → 第一次滑动**不弹**「已保存滑动范围」→ 第二次才弹 →
点「重新记录」第三次**又不弹**。看起来像奇偶交替的玄学。

**取证**（设备日志 + 远端注入复现，见下"复现手法"）：

1. 用户那次会话的日志里，三次 commit **全部成功**（`persistCalibration … 149ms / 113ms / 66ms`），
   唯一异常是第三次之后 `closing … why=screen off`。⇒ "不弹"的尝试**没有留下任何痕迹** ——
   提交成功会打日志，被拒绝不会（`setFeedback` 当时不写日志）。
2. 远端注入 `input swipe … 50`（50ms 快划）复现：**没有任何 persist、没有结果卡**，录制层停在 RECORD。
   ⇒ 命中 `commitRecord()` 的 `dur < MIN_DURATION_MS`（当时 **60ms**）拒绝分支。
   用户自然笔划的实测值是 **66ms**（19:43:32 那条成功记录，只比阈值高 6ms）⇒ 随手一快就落在阈值下，
   这就是"奇偶交替"的真相：**不是交替，是手速在阈值两侧摆动**。

**根因（真正的缺陷，比阈值严重）**：`drawTrail()` 只在**没有轨迹可画**时才 `drawHint()`：

```java
float[][] pts = current();
if (pts == null || pts.length < 2) { if (recording) drawHint(cv); return; }   // ← 唯一调用点
... 画 band / 轨迹 / 手柄 ...
```

⇒ **只要录制过一次手势（哪怕是被拒绝的那次），`live` 就不为空，hint 从此再也不画**。
而所有拒绝理由（`滑动太快了 / 距离太短 / 没有捕捉到滑动 / 滑动被中断`）**只走 hint 这一条通道**。
用户看到的是：自己那条线画出来了、没有卡片、**没有任何原因**。这正是"不弹窗"的完整体感。

**修（4.9.9）**：

| # | 改动 | 理由 |
|---|---|---|
| 1 | `drawTrail()` 尾部补 `if (recording && !gestureActive) drawHint(cv)` | 拒绝理由必须画得出来；只在手势进行中隐藏（那时用户看的是轨迹，不是文字） |
| 2 | `MIN_DURATION_MS` 60 → **25**；拒绝文案由常量拼出 | 自然快划不该被拒；误触由 `MIN_TRAVEL`（0.12 屏幅）兜底，不靠时长。硬编码的 `（<60ms）` 文案与常量同源，避免再次漂移 |
| 3 | 窗口加 `FLAG_KEEP_SCREEN_ON`（录制 + 结果两态）；`screen off` 自关需要**连续 2 次**心跳确认 | 实测**两次**录制层被 `why=screen off` 自杀式关闭，其中一次发生在用户操作中途（19:43:34，距结果卡出现 1.8s）⇒ 之后的滑动落在空气上。`isInteractive()` 是单次采样，而这台机器有待机屏 / 笔唤醒 / 皮套传感器 |
| 4 | 诊断日志：`commit raw=N dur=Xms travel=Y`、`feedback: <原文>`、`spy disarmed, saw N events`、`event#1..3 action/tool/src`、`UP without DOWN, dropped` | **"滑了没反应"有三种可能：通道没事件 / 手势被拒 / 会话被外力关掉**，画面上一模一样。日志必须能一次分清 |
| 5 | `phase` / `dismissed` / `gestureActive` 加 `volatile` | 主线程写、spy 线程读，"会话已结束"应当是事实而不是提示 |

**复现手法（本机可用，不需要人操作设备）**：录制层只需要一次广播 + 注入事件即可全流程驱动：

```sh
adb shell am broadcast -a com.futureharmony.lenovopenbridge.PAGETURN_CONFIG \
    --es pkg <任意包名> --es op calibrate --es dir next      # 打开录制层（不存在启动器也行）
adb shell input swipe 1000 1300 3000 1300 250                # 注入滑动（monitor 收得到，已实测）
adb shell input tap <x> <y>                                   # 点结果卡上的「重新记录」
adb exec-out screencap -p > s.png && sips -c 700 3840 s.png   # 截图看画布/hint
adb shell am broadcast … --es op clear_calib --es pkg <spy包>  # 清理测试写入
```

**验证（4.9.9，19:53 装机 + 重启，`versionCode=490009`）**：

- 注入 40ms 快划 → `commit raw=6 dur=47ms travel=0.521` → `persistCalibration … 47ms` —— **旧阈值下会被静默拒绝**
- 注入 200px 短划 → `commit raw=12 dur=307ms travel=0.052` → `feedback: 滑动距离太短，请滑得长一些`
  → **截图确认该行文字与副行都画在轨迹之上**（旧代码下这一行根本不存在）
- 窗口旗标实测 `fl=NOT_FOCUSABLE NOT_TOUCHABLE NOT_TOUCH_MODAL KEEP_SCREEN_ON LAYOUT_IN_SCREEN`；
  录制层挂起 25s 屏幕仍 `mWakefulness=Awake`（此前同等条件下 9~10s 就被 `screen off` 关掉）
- dex：290 类、stub 定义 0、`（<60ms）` 旧文案命中 0
- 测试写入 `zz.test.calib` 已用 `op=clear_calib` 清除并复核；用户自有配置（readest、gallery3d）未动

**教训（已进 skill）**：① **一条反馈只有一个渲染分支 = 隐藏缺陷**：只要画布上有别的可画，
理由就永远说不出口 —— 凡是"失败必须解释自己"的 UI，绘制路径要保证在**所有**状态下都经过它。
② **拒绝阈值要和真实输入量级对齐**：66ms 的样本说明阈值贴着用户手速，等于随机拒绝。
③ "看起来像交替 / 像玄学"的现象，先怀疑**两个不同的失败模式各占一半**，而不是同一个 bug 的奇偶性。

## P0.22 翻页自定义滑动改为「双向连续录入」（Hook 4.9.10，2026-09-23）

- [x] **需求**（用户 2026-09-23）：在“选择翻页触发方式”弹窗中，如果用户选择了“水平模拟”或“垂直模拟”，并且选择了“自定义滑动范围”，那么需要用户先后录入两个动作：
  1. 一个是向上，一个是向下（垂直模拟）；
  2. 或者一个是向左，一个是向右（水平模拟）。
  这样，当我们给同一个应用同时配备了“上一页”和“下一页”的功能时，就能够分别触发到同一个应用录入的两个不同动作中。
- [x] **根因定性**：
  此前 `showRangePage` 只接收触发瞬间单向的 `next` 布尔值，并在调用 `requestCalibration` 时只录制了这一单个方向；
  录完落盘后，`lenovo_pen_pageturn_swipe` 里只有 `pkg|next` 一个方向的轨迹；
  当用户后续触发另一个按键（例如已录「翻页下」，后按「翻页上」）时，`getCalibration(ctx, pkg, false)` 为空，
  走内置比例路径（而在抖音/小红书等应用里内置比例路径正是无法生效的），导致用户体感上「只能设置翻页上或翻页下，另一个动作无法生效」。
- [x] **改动实现（4.9.10）**：
  1. **`SwipeCalibrateOverlay` 支持两步连续录制（`step 1 -> step 2`）**：
     - 初始状态 `step = 1`：画布与提示指引用户录入第 1 个动作（垂直：向上滑动对应翻页下；水平：向左滑动对应翻页下）。
     - 智能方向判定：若用户在第 1 步先划了相反方向（例如向下划），系统自适应将该划痕绑定为「翻页上」，第 2 步动态调整提示为引导录入「向上滑动（翻页下）」；同向重复划动会友好提示划向相反方向。
     - 第 2 步录制完毕后，一次性将两个方向的轨迹持久化写入 `lenovo_pen_pageturn_swipe`（`pkg|next` 与 `pkg|prev`）。
  2. **结果卡与预览增强**：
     - 结果卡同时展示「翻页下（下一页）」与「翻页上（上一页）」两条自定义轨迹的时长与说明。
     - 动画预览循环交替播放两条轨迹，供用户直观核验。
     - 点「重新记录」会重置两步状态并从第 1 步重新录入；点「恢复默认范围」会同时清空两个方向。
  3. **弹窗与单向遗留容错**：
     - `showRangePage` 标题改为「翻页滑动范围」，副标题清晰注明「先后录入两个动作（向上/向下或向左/向右），分别对应「翻页下」与「翻页上」」。
     - `performWithPrompt` 增加容错：若历史版本残留了「仅录入单向」的旧配置，当用户触发未录入的另一方向时，自动补弹 `showRangePage`，引导用户补齐完整双向轨迹。
- [x] **构建验证**：Hook 4.9.10（`versionCode=490010`），编译通过，0 error。

## Bug-fix: xposed_scope 遗漏 `android`（system_server）（2026-09-22）

- [x] **问题**：`arrays.xml` 的 `xposed_scope` 只有 `system`（SystemUI）而**缺少 `android`**
      （system_server / framework）。LSPosed / Vector 的推荐作用域直接读此数组，用户按推荐
      勾选后 `system_server` 不会被注入 → `SystemStylusHooks.installAsync()` 整套系统级
      手写笔 Hook（按键拦截、UEvent 桥、触控条、手势分发等）全部静默失效。
- [x] **修复**：在 `hook/source/resources/res/values/arrays.xml` 的 `<array name="xposed_scope">`
      首位加入 `<item>android</item>`。（2026-09-22）

## v0.1.24 架构重构（2026-09-22）：service.sh 拆库 + 收掉手写重复

- [x] **动机**：v0.1.23 减法后 `service.sh` 仍有 1464 行，且**基础设施全靠手写重复** ——
      43 处 `settings get global X 2>/dev/null | tr -d '\r'`、38 处 `settings put global X v >/dev/null 2>&1`、
      51 处 `echo "[$(date '+%F %T')] ..."`。这些管道与重定向不是业务逻辑，只是访问
      SettingsProvider 的语法噪音，占了 131 个调用点。
- [x] **拆分**：新增 `module/lib/` 七个库（`core` / `pen_id` / `pen_hw` / `pen_link` / `pen_ui` /
      `boot` / `monitors`），`service.sh` **1464 → 289 行**，只留配置区、单例锁、加载与调用编排、
      启动尾巴和历史留档。**仅搬位置，不改字**：用行区间机械切片，不手抄。
- [x] **抽 helper 消重**：`log`/`log_err`（51）、`get_global`（42）、`put_global`（38）、
      `put_global_diff`（3 处读-比-写收口）；另新增 `is_uint` / `norm_bool`。
      ⚠️ 严格只替换**日志与 settings 读写**，绝不触碰被 `$()` 捕获的返回值 `echo`
      ⇒ `lib/pen_hw.sh` 最终**零 `log` 调用**，`log_err` 只出现在 `pen_id.sh` 那个被捕获的
      `resolve_pen_mac` 里。
- [x] **等价性对账（脚本断言，非目测）**：函数定义 45 → 54（+9 helper/提函数）**零丢失零重复**；
      settings 字面量键 **24/24 一致**；`am broadcast` action **3/3 一致**；单文件 `sh -n` 与
      `cat lib/*.sh service.sh | sh -n` 合并校验双通过；离线真实 source 加载后 **52 个符号全部就位**，
      且命令替换结果不被日志污染。lib 层零顶层执行语句、零 `$0` 依赖，
      `exit` 仅剩 `core.sh::sleep_sec` 那处已知兜底。
- [x] **构建白名单根治老坑**：`build_root.py` 的 `INCLUDE` 现在支持**目录条目**，`lib/` 以目录
      形式声明、构建时自动展开（只收直系 `*.sh`，排序保证 zip 条目顺序稳定）。
      以后新增库文件**不会再因漏加白名单而静默缺失**。
- [x] **修掉两处历史笔误**（非本次引入）：`service.sh` 头注释写「仅适用于 SM8650Q / pineapple」
      与实际门禁 `*SM8750P*/*sun*` 矛盾；监视器启动处注释说「helper 已在上方解析完毕」，
      拆库后改为「lib 已全部 source 完毕」。
- [x] **实机重启验证通过（2026-09-22 09:04）**：模块 0.1.24 + Hook 4.8.0；`service.sh` 289 行、
      `lib/` 7 个文件齐全；进程树 7 个 shell（重生循环 + 主体 + 5 监视子壳，无孤儿）；
      日志 `not found`/`syntax error`/`No such file`/`Permission denied` 零命中；
      `system_server stylus hooks installed` 在；笔 `link_connected=1 battery=100 oem_control_ready=1`；
      `action.sh status` 面板渲染正常。
- [x] **部署踩坑留档**：`ksud module install` 是「暂存 + 重启生效」两步 —— 装完立刻看
      `/data/adb/modules/<id>/` **仍是旧版**（只有 `module.prop` 被改写 + 一个 0 字节 `update` 标记，
      新构建新增的 `lib/` 在那里根本不存在）。判据应看 `/data/adb/modules_update/<id>/`。
      另：本机 shell 是 zsh，不做无引号变量分词，`S="-s <serial>"; adb $S shell` 会报
      误导性的 `adb: -s requires an argument`，需显式写全或用 zsh 数组。

## v0.1.23 减法重构（2026-09-21 深夜）：深睡唤醒能力整段下线

- [x] **决策**：不再尝试让深睡笔自动唤醒 —— **原厂固件本身就没这个能力**（v0.1.17 取证已证
      「充满断电 → 笔深休眠 → 重新吸附唤醒」是官方完整行为链）。我们是在给不存在的能力补补丁。
- [x] **当晚三层实测（结论写进 service.sh 的「深睡唤醒守护：已整段移除」注释块）**：
      1. **Profile/GATT 层 —— 无效**。`run_hidctl disconnect`（`setConnectionPolicy(FORBIDDEN)`）
         23:04 实测 HOGP 2→0 但 ACL handle 仍是 0x0002；OEM `DISCONNECT_PENCIL` 23:16 实测只有
         `LE HID Closed` + `ACL Ignore connection from`。根因：笔的 ACL 上挂着 5 个持有者，其中
         `hid(49)`/`BatteryService(59)` 属 `com.android.bluetooth` 内部系统 profile，App 层无从释放
         ⇒ ACL 永不断开，笔固件感知不到"链路没了"所以不醒。
      2. **单条 ACL 层 —— 本机硬件不可达**。本板 QTI 用户态 H4 架构，HAL
         （`android.hardware.bluetooth@aidl-service-qti`）独占 `/dev/ttyHS0`，kernel 未注册 hci 设备
         （无 `/dev/hci*`、无 AF_BLUETOOTH HCI socket、无 hcitool/hciconfig/btmon、无 python）。
         强写 ttyHS0 会打乱 HAL 的 H4 帧同步，比整机重启蓝牙更糟，**故未实施**。
      3. **整片蓝牙适配器 —— 有效但全局**。23:20 实测 `svc bluetooth disable/enable` 后 ACL handle
         0x0002→0x0001（真断真建），笔完全恢复（用户确认书写/震动/按键回来）。代价是连累平板上
         **所有**蓝牙设备闪断，拿不到"只重连笔"的效果 ⇒ 代价与收益不成比例。
- [x] **删除范围**：
      - KernelSU：`service.sh` 中 `do_pen_wake_cycle` / `run_wake_guard_once` / `monitor_wake_guard` /
        `verify_pen_awake` / `pen_undock_age` / `resolve_pen_input_node` / `pen_input_silent` /
        `trigger_haptic_refresh` / `run_hidctl` / `hidctl_result_file` /
        `grant_hidctl_bluetooth_permissions` / `hide_hidctl_launcher` / `retry_hidctl_setup`，
        连同 WAKE_* 变量与 penhidctl 三个常量；`action.sh` 的 `wake`/`wake-guard-on`/`wake-guard-off`；
        `customize.sh` 的 penhidctl 安装分支；`build_root.py` 白名单两行。**service.sh 1940 → 1466 行。**
      - priv-app：`penhidctl/` 源码目录、`tools/build_penhid.py`、`module/system/priv-app/aclpenhid/`、
        `privapp-permissions-com.aclaniakea.penhidctl.xml` 全部移除；`scripts/push_to_device.sh` 去掉该产物。
      - Hook：`PenBridgeConstants` 的 `HAPTIC_REFRESH(_LEGACY)`、`SystemStylusHooks.registerHapticRefresh`
        及其接收器与 ready 标志、`PenHapticGatt.refreshSession`/`replayHandshake`/`pendingRefresh`/
        `CONNECTED_WAVEFORM` 与回调里的消费分支。
- [x] **保留**（不属于深睡唤醒，属正常链路管理）：`real_bt_connected`/`bt_stack_hogp_live`、
      吸附边沿的 `request_pen_connect`、用户在设置页点断开的 `request_pen_disconnect` 与
      `monitor_hid_latch`、状态镜像、磁吸胶囊、电量与充电监控、boot guard、panic、inkdye 切换。
      ⚠️ `request_pen_connect_bounded` 原本只是"HID 重试"包装，删 HID 后已无意义 → 一并删除，
      `monitor_hid_latch` 的调用点改回 `request_pen_connect`。
- [x] **实机重启验证通过**：模块 0.1.23 + Hook 4.8.0；`system_server stylus hooks installed`（00:01:57，
      pid 2305 内 338 条本方日志）；penhidctl 已卸载且 overlay 目录消失；模块目录无 wake 状态文件；
      service.sh 进程从 8 个降到 7 个（少一个 `monitor_wake_guard`）；启动日志无报错。
- [x] **作用域清理**：设备 scope 表里有一条历史遗留的 `android/0`（实际 9 条 vs 真源 8 条）。
      UI 里看到的「Android System」对应包名 `android`，而 system_server 的 Xposed 伪包名是
      `system`（一直在表里、一直在生效）。用 `scope rm` 外科式删除，回读 8/8 与
      `hook/source/resources/META-INF/xposed/scope.list` 逐条一致。
      ⚠️ **踩坑：`vector-cli scope ls` 的参数是模块包名，不是被 Hook 的包名** ——
      传 `system`/`android` 都返回 `No records found.`，极易误判为作用域丢失。
- [ ] **待用户实写确认**：笔的书写/震动/按键在正常场景（非深睡）下均可用。
      - [x] **书写（2026-09-22 19:31 已确认）**：`penwake_ctrl.sh` 判决实验「阶段 3 阳性对照」——
            吸附 3-5s → 取下 → 书写，`ev5` 合计 **530 672 字节**（19/25 采样点有信号），
            IC 侧 `penraw distinct` 合计 **520**。两路判据一致 ⇒ 正常场景书写可用。
            实测恢复延迟 ≈10 s（前 6 个采样点仍为 0）。
      - [ ] 震动 / 按键仍未单独确认。

## v0.1.17 减法重构（2026-09-21）：移除官方系统没有的逻辑
- [x] **依据**：`docs/pen_official_rom_verdict_20260921.md` 五层取证（.ko/dtbo/HAL/官方笔框架/笔固件）证明
      「充满断电 → 笔深休眠 → 重新吸附唤醒」是官方驱动+笔固件的完整行为链，官方系统层
      **没有任何**充电/TX/唤醒/重连干预逻辑。适配层（inkdye）与本模块同卡，问题不在软件层。
- [x] **整体删除**（模块 payload 内）：
      1. `charge-guard.sh`（充电守护：通知 + IPeManager 充电状态修正）；
      2. `pen-revive-guard.sh`（链路守护：僵尸连接检测 + `lenovo_pen_powered_down` 标记）；
      3. CPS GPIO keeper（`start/stop/monitor_cps_gpio`、`wait_for_cps_power`、`bin/pen-cps-gpio`、gpioset）；
      4. `pen_wakeup_mode/switch` 写入（`apply_pen_wake`/`monitor_pen_wake` 及开机等待循环）；
      5. D1a 硬重连全套（笔尖 evdev 探针、三态判定、看门狗、挂起等待者、`request_pen_reconnect_hard`、
         离座边沿决策块、`request_pen_connect_bounded` 的硬重连兜底）；
      6. `real_bt_connected()` 的 powered_down 否决（守护已删，标志无写入方）；
      7. uninstall/panic/post-fs-data 中的 `tx_status` 写入与守护 pid 清理；action.sh 的守护启停/日志段；
         customize.sh 的对应 ui_print 与 set_perm；build_root.py 白名单两行。
- [x] **保留**（移植桥接必需 / 官方行为等价物）：开机与吸附边沿 `CONNECT_PENCIL`、设置页断开/连接对账、
      磁吸胶囊、电量/充电/Hall/蓝牙状态镜像（**只读**，喂 ColorOS UI）、Hook、
      ~~PenHidCtl priv-app~~（**已于 v0.1.23 随唤醒一同下线**）、
      keylayout overlay、boot guard、panic、inkdye 切换、note engine 钉版守卫（移植崩溃修复，非笔电源逻辑）。
- [x] service.sh 2163 → 1513 行；版本 0.1.17 / 117；zip `releases/tb522fu-pen-bridge-v0.1.17.zip`
      （md5 786933fa194eb7a667cb98c792a81bf8）。
- [ ] **回归实测**（装 v0.1.17 后）：① 开机吸附自动连接；② 吸附弹胶囊；③ 设置页断开/连接；
      ④ 长时息屏吸附后取笔——预期与官方一致（笔深睡需重新吸附，这是官方设计，不再尝试软件复活）。

## 唤醒守护 v0.1.18（2026-09-21 实验定案后实施）
- [x] **实验定案**（`docs/pen_wake_experiment_E0_E4_20260921.md`）：深睡笔的唤醒触发器 =
      自身 BLE 链路完整 link-down → link-up。真断链 5s 后 `CONNECT_PENCIL` 重建即唤醒，
      无需重启蓝牙/重新吸附/任何 GATT 命令。haptic 命令总线深睡时可达（笔会震动）但唤醒不了触控。
- [x] **唤醒后书写震动丢失定案**（19:15 定案，**19:19 实测确认修复**）：link cycle 会重置笔端震动会话，系统侧无人重放
      inkdye 的连接握手（SWITCH=01 → REQ_INF=01 → IMP CONNECTED 波形 `020501000000`）。
      握手重放后用户实测"震动了"——重放即修复，无需重启蓝牙。
      **唤醒守护动作序列必须在 CONNECT_PENCIL 完成后追加本握手重放**（实验文档 §3b）。
- [x] **v0.1.18 实现完成**（默认关闭，`action.sh wake-guard-on/off` 开关）：
      - Hook v4.7.0：`PenHapticGatt.refreshSession()`（存量/重建传输上重放 SWITCH→REQ_INF→CONNECTED 波形，
        传输未就绪时置 pendingRefresh 由 onServicesDiscovered 收尾）；SystemStylusHooks 注册
        `haptic.REFRESH` 动态 receiver（RECEIVER_EXPORTED，root 可达）。
      - service.sh：`monitor_wake_guard`（2s 轮询）；触发 = 开关文件 + 离座边沿武装（pen-wake-arm.state，
        每吸附会话一轮）+ hall=0 + link_connected=1 + ev5 零发射 6s（`pen_input_silent`，timeout+dd 单事件探针，
        evdev 多客户端广播不抢事件；节点按 /proc/bus/input/devices 名字含 pen 解析）；动作 =
        `request_pen_disconnect` → 轮询确认 link=0（≤6s，防 E4c 假重连）→ 失连 3s → `request_pen_connect` →
        轮询 link=1（≤8s）→ 广播 `haptic.REFRESH`（不加 -p：system_server 动态 receiver 不属于 app 包名）；
        冷却 120s；手动入口 `action.sh wake`（touch pen-wake-now，同一条代码路径）。
      - panic.sh/uninstall.sh 同步清理唤醒状态文件。
- [ ] **回归实测**（装 v0.1.18 + Hook 4.7.0 后）：
      ① 笔满电深睡 → 吸附存放数分钟 → 取下静置 ~10s → 期望自动断链重连（log 搜 "wake guard"）且能书写、有震动；
      ② 清醒笔取下后正常书写 → 期望守护静默（不触发，pen_input_silent=1）；
      ③ `action.sh wake` 手动触发 → 同上闭环；
      ④ 开关关闭时行为与官方一致（深睡笔需重新吸附）。
      > ⚠️ **该回归实测从未生效**：接续的 v0.1.19 定位证明唤醒守护**一次都没执行过**
      > （节点解析恒失败，且默认关闭）。①②④ 之前若有"不生效"的观察，应归因于此，而非实验结论有误。
- [ ] 探针 APK `com.exp.penprobe` 如需长期复用——源码与构建说明已存档 `tools/penprobe/`。

## v0.1.19 唤醒守护静默空转修复（2026-09-21 用户实测报告后定位）
**用户报告**：dock 睡死的笔取下后，震动可用但**写不出字**；有时连震动也没有；
必须重新吸附再断连才恢复 —— 即项目声称已解决的场景**实际未修复**。

- [x] **根因 1（决定性）：`resolve_pen_input_node` 在本机永不命中，唤醒守护恒不执行。**
      实测 `/proc/bus/input/devices` 的写法是 `H: Handlers=event5 cpufreq` —— 第一个
      handler 与 `Handlers=` **粘在一起**，按空白分词得到的字段是 `H:` / `Handlers=event5` /
      `cpufreq`，没有任何一个等于裸的 `eventN`，所以 `$i ~ /^event[0-9]+$/` 永远为假。
      ⇒ 每次离座只打一行 `pen input node unresolvable; skipping`（**失败是静默的**），
      唤醒循环从 v0.1.18 部署起**一次都没跑过**。设备证据：20:21 / 20:22 / 20:23 / 20:25 /
      20:57 / 21:00 共 7 次离座，全部是这个形态。
      修法：主路径改回 v0.1.14 的 **sysfs 精确名匹配**（`/sys/class/input/input*/name`
      == `NVTCapacitivePen` → `/dev/input/event5`），`/proc` 仅作兜底且改用 `match()` 在整行里定位。
- [x] **根因 2：断而不建（v0.1.15 修过，随 D1a 在 v0.1.17 被一并删除）。**
      `request_pen_disconnect` 发出的 `DISCONNECT_PENCIL` 会让 Hook 把
      `lenovo_pen_disconnect_requested` 与 `lenovo_pen_user_disconnect_requested`
      **两个闩锁一起置 1**，而 `request_pen_connect` 见到闩锁直接跳过 ⇒ 变成"断而不建"，
      比僵尸连接更糟。20:11:57 那次手动唤醒的日志实证：`pen connect skipped: Settings
      disconnect latch is set`，链路是**靠当时还在跑的 boot 重试循环**（`real OEM boot
      connect retry attempt=2`）才回来的 —— 稳态下没有这个循环。
      修法：循环内先快照"这是不是用户在设置页的显式选择"，不是则断链后**主动清掉两个闩锁**再连。
- [x] **根因 3：默认关闭。** v0.1.17 删掉了原来无条件执行的 D1a 硬重连，v0.1.18 的守护又默认关闭
      ⇒ **默认安装下"睡死笔"场景无人接管**。修法：`customize.sh` 安装时 `touch pen-wake-guard.state`
      （默认开启；`action.sh wake-guard-off` 删除该文件即持久关闭）。
- [x] **顺带：`link != 1` 分支不再空转。** 原实现打一行 `nothing to cycle` 就返回 —— 而"连蓝牙载波
      都掉了"正是用户说的"连震动都没了"。改为走同一套 link cycle（先断后连是破僵尸 HOGP 的唯一路径）。
- [x] **失败保险向**：节点判不了（rc=2）不再"跳过"，而是当作"笔没在发射"照常唤醒 ——
      宁可多走一次 ~13 秒循环，也不能让"判据坏了"表现为"笔坏了"。
- [x] **实机验证（2026-09-21 21:11–21:13，免重启替换 service.sh + 两分支实测）**：
      - 睡死分支（笔尖 6s 零事件）：`link cycle start(was_connected=1)` → `link down confirmed
        after 0s` → `cleared self-inflicted disconnect latches` → `link restored after 0s;
        replaying haptic handshake`；Hook 侧 `haptic session refresh: inkdye handshake replay
        requested (post-wake)` ×2（新旧 action 各一）确认送达。
      - 清醒分支（窗口内注入 12 次笔尖事件）：`pen emitted input; awake, no wake needed`，
        **未写冷却戳 = 未触发断链**，无误伤。
      - 两闩锁在循环后均为 0；`pen-wake-arm.state` 被消费；冷却戳正常写入。
- [ ] **待用户复测真·深睡场景**：满电吸附数分钟（等驱动 close tx → 笔深睡）→ 取下静置 → 期望
      自动唤醒后可书写且有震动。判据：`sh action.sh log` 搜 `wake guard`，必须看到
      `link cycle start`，**不得**再出现 `unresolvable; skipping`。
- [ ] **未运行时验证**：`link=0` 那条分支（改后走循环）只做了代码级检查，未构造该状态实测。
- [x] 版本 0.1.19 / 119；zip `releases/tb522fu-pen-bridge-v0.1.19.zip`（md5 33b106656253aa4baa4e0f0af6c97584）；
      Hook 无改动（仍 v4.7.0，scope 8 项回读一致）。

### 排查坑记录（本次踩到，写进模块注释）
- **手动重启本服务的正确姿势**：必须 `/data/adb/ksu/bin/busybox sh`（且 PATH 前置
  `/data/adb/ksu/bin`，否则重生循环里的 `sh "$0"` 会解析到 mksh）。原因：`/system/bin/sh` 是
  mksh，`exec 9>file` 在 exec 时**丢 fd**，于是入口的 `flock -n 9` 报
  `flock: Bad file descriptor` → 脚本静默 `exit 0`（表现：进程树空、日志无新行，极易误判为"改坏了"）。
  开机时 KernelSU 用 busybox ash 调，所以正式路径不受影响。
- **`/sdcard` 在锁屏（user 0 未解锁）下整条不挂载**：`adb push … /sdcard/…` 会报
  `remote secure_mkdirs() failed`（甚至可能先报一次假的 "1 file pushed"），
  `su -c 'ls /sdcard/Download/…'` 也说不存在。`/sdcard` 是 user 0 的 FUSE 挂载点，
  FBE 首次解锁前不存在。锁屏时要落盘就 root 直写 `/data/media/0/…`。
  **别据此判断包没推成功。**
- **热替换 `service.sh` 后别急着看进程树**：重生循环里是 `sh "$0"` 之后 `sleep 8`，
  杀掉旧主体后要等 ~8–9 秒新主体才出现；且旧主体的 6 个监视子壳会被 reparent 到 PID 1
  变成**孤儿**（继续独立轮询、写日志），必须单独 `kill` 或重启清理。

## v0.1.20 唤醒守护第四处静默空转：锁屏窗口（2026-09-21 重启验证时发现）

**发现经过**：v0.1.19 用 `ksud module install` + 重启做正式部署验证，开机日志每 30 秒出现
`OEM … CONNECT_PENCIL request failed … unlocked=0` / `OEM haptic recovery not deliverable;
channel unreachable, budget kept (n/40)`。顺着 `unlocked=0` 查下去。

- [x] **根因 4：`CoreService` 无 `directBootAware`，锁屏窗口内 OEM 通路结构性不可用。**
      设备侧复现（当 `State: RUNNING_LOCKED`）：
      `am startservice --user 0 -n com.oplus.ipemanager/.btadsorb.CoreService -a …CONNECT_PENCIL`
      → `Error: Not found; no service started`，`rc=255`。
      manifest 佐证（`aapt2 dump xmltree`）：`btadsorb.CoreService` 只有
      `permission/enabled/exported/process=":ble"`，**没有** `android:directBootAware`。
      ⇒ user 0 未首次解锁时 PMS 直接过滤该组件。
      **窗口边界**：FBE 首次解锁后 State 恒为 `RUNNING_UNLOCKED`，之后息屏/锁屏都不再回到
      `RUNNING_LOCKED`，所以暴露面 = **开机 → 首次解锁**。
- [x] **危害：一次性武装被一个"必然失败"的窗口吃掉。** `run_wake_guard_once()` 第一行就是
      `rm -f "$WAKE_GUARD_ARM_FILE"`，所以锁屏时用户先取下笔（屏幕还没解锁）的流程是：
      武装 → 6s 零事件 → DISCONNECT/CONNECT 双双 rc=255 → `link never went down within 6s;
      abort` → **武装已烧掉，本次离座会话不再重试**。用户随后解锁、落笔 —— 笔是死的。
      与用户原始报告**同形**，只是换了条静默路径。
- [x] **修法：在 `monitor_wake_guard` 里、消费武装之前加锁屏闸门。**
      `user_unlocked` 为假则**保持武装不消费**，只打一行日志（新增 `pen-wake.defer` 做日志
      去重，并在离座边沿复位，使命中范围限定在一次吸附会话内）；解锁后的下一次 2 秒轮询
      就会正常补做这次唤醒。手动入口 `action.sh wake`（`pen-wake-now`）**不受**闸门约束。
      注：`user_unlocked()` 原是"只写日志、不参与决策"的既有函数，v0.1.20 起参与一次决策，
      注释已同步更新。
- [x] **设备侧逻辑验证（设备当时确为 `RUNNING_LOCKED`）**：第 1 次判定打印 deferring、
      **武装标记仍在（未消费）**、去重标记写入；第 2 次判定静默（去重生效）。
      `wm dismiss-keyguard` 未能解锁（设备设了安全锁）⇒ **"解锁后闸门打开"这半未在真机观察到**。
- [ ] **待用户复测**：锁屏状态下（开机后不先解锁）从 dock 取下睡死笔 → 期望日志出现
      `user not unlocked yet; OEM channel unreachable, deferring (arm kept)`，
      **且解锁后**同一次离座会话能补上 `link cycle start` → `replaying haptic handshake`。
- [x] **重启验证（v0.1.19 + v0.1.20 各一轮）**：`ksud module install` 暂存内容与 release zip
      逐字节一致（`service.sh` md5 `edc415fc96fd554ca002cbd2093dc9da`）；开机后
      `version=0.1.20`、守护开关 = 开启、`system_server stylus hooks installed` 命中、
      Hook `4.7.0`、Vector scope 8 项回读一致、笔尖节点解析到 `/dev/input/event5`、
      进程树 1 循环 + 1 主体 + 监视组且**无 PPID=1 孤儿**、两闩锁为 0。
- [x] 版本 0.1.20 / 120；zip `releases/tb522fu-pen-bridge-v0.1.20.zip`
      （md5 bbde8293ec448d6b525260ff77398505）；Hook 无改动（仍 v4.7.0）。


## v0.1.21 唤醒时序调参 + 循环验证（2026-09-21 21:36 生产样本驱动）

> 用户报"解锁后笔有震动但无笔画，过了一会就正常了"。这是 v0.1.19 上线后**第一次真实复现**
> 该场景且带完整日志。完整时间线、判定与证据见
> [`docs/pen_wake_guard_silent_noop_20260921.md`](docs/pen_wake_guard_silent_noop_20260921.md) §12。

- [x] **确认修复有效**：守护完整跑完一轮（`link cycle start` → `link restored`），
      **无** `unresolvable; skipping`。窗口内 Android 侧 `NVTCapacitivePen` 是启用态
      （`mode DIRECT`），零事件来自笔端而非上层丢弃。
- [x] **根因（配置层）**：断链 21:36:44.58 → 首个笔画 21:36:48.5 = **3.9s**，
      而失连保持只有 3s ⇒ 重连只比笔醒来早 **0.37s**。3s 是 E4 区间(2–5s)的下沿。
      ⇒ 新增 `WAKE_OFFLINE_HOLD_SECONDS=5`（区间上沿，实测离线约 7s）。
- [x] **补"循环跑完 ≠ 修好了"**：新增 `verify_pen_awake()` / `WAKE_VERIFY_SECONDS=12`，
      重连后看笔尖节点，三态落日志（`cycle OK` / `WARN cycle FAILED` / `WARN cycle UNVERIFIABLE`）。
      **只记日志不改行为** —— 先让"白跑一趟"可见，再按真实发生率决定要不要加重试。
- [x] 顺带补掉 `run_wake_guard_once` 里最后一处**无声 return**：冷却跳过现在会打
      `in cooldown (Ns < 120s); skipping this undock`。
- [x] **埋标定点**：正常分支与 `cycle OK` 分支都打 `${n}s after undock` —— 用来标定
      `WAKE_QUIET_SECONDS=6` 该不该放宽（见下方待办）。
- [x] 三分支实机验证：`cycle OK - pen emitted input 18s after undock`（注入事件命中验证窗）/
      `WARN cycle FAILED - pen still silent 12s after cycle (30s after undock)` /
      `in cooldown (55s < 120s)`。
- [x] 版本 0.1.21 / 121；zip `releases/tb522fu-pen-bridge-v0.1.21.zip`
      （md5 8b1796861e3681e5428cc99c05dc0a79）；Hook 无改动（仍 v4.7.0）。
      已 `ksud module install` + 重启验证：模块 0.1.21 / 守护开启 /
      `service.sh` md5 35096ae29c37963d436a6951c240e5a9 / Hook 4.7.0 /
      `system_server stylus hooks installed (startOtherServices=1 run=1)` /
      Vector 作用域 8/8（含 `system`）/ 进程树 1 循环 + 1 主体 + 6 监视壳且无孤儿 / 两闩锁 0。

### 待办（需要真实数据，不要凭猜改）

- [ ] **对照组：标定"离座 → 首笔"的健康延迟**。当前唯一样本是 **11s**，而
      `WAKE_QUIET_SECONDS=6` ⇒ 窗口内必然判"睡死" ⇒ 守护实际退化成**每次离座都断链重连**
      （代价约 7s BLE 离线 + 握手重放）。**该样本已被循环污染**（循环在 6–8s 介入，
      之后测到的延迟必然 ≥ 循环耗时）。
      做法：`sh action.sh wake-guard-off` → 吸附等 ~10 分钟 → 取下**立即书写**，
      记录首个笔画出现的时刻；再开回守护重测一组。两组都取 3–5 次。
      判据：若健康延迟稳定 < 4s → 6s 窗口合适；若稳定 ≥ 8s → 应放宽窗口
      （并同时考虑把"每轮断链"改成"仅在窗口内确无自愈迹象时"）。
- [ ] **决定要不要加重试**：`WARN cycle FAILED` 日志的**真实发生率**。
      若确有"一轮不成、需重新吸附"的样本，再实现第二轮（当前一次性武装，
      一轮失败后本次吸附会话不再重试 —— 这正是用户最早报的"必须重新吸附一次"形态）。
      注意"验证窗口内静默"是**歧义条件**（用户把笔放一边不碰屏幕同样静默），
      加重试前先想清楚怎么避免每轮都白做。
- [ ] 用户复测真·深睡场景（吸附充满断电 → 笔深睡 → 取下书写）：
      `sh action.sh log` 搜 `wake guard`，须见 `link cycle start` 与 `cycle OK|FAILED`。


## v0.1.22 锁屏期唤醒通路（PenHidCtl DBA 化，2026-09-21 深夜）
- [x] **发现**：这台 ROM **息屏就把 user 0 锁回 RUNNING_LOCKED**（不是"仅开机首解前"），
      v0.1.20 的锁屏闸门建立在错误假设上，实际把每次息屏期间的守护全部挡死。
      锁屏期 PMS 只放行 directBootAware 组件：OEM CoreService 与旧 penhidctl 的
      `am` 投递双双 rc=255（对照实验确认）。
- [x] **PenHidCtl 4.1.5**：`PenHidService` 加 `directBootAware`；新增执行回执
      `penhid.result`（默认 fail，policy 调用成功才 ok）；发现 1 参
      `connect()/disconnect()` 隐藏方法在此 ROM 不存在 ——
      `setConnectionPolicy(FORBIDDEN/ALLOWED)` 即踢链/回链触发器（HOGP 2→0→2 实测）。
- [x] **PMS mtime 重扫陷阱**：overlay 更新 APK 后重启 PMS 仍跑旧版（缓存 timeStamp 与
      overlay 呈现 mtime 逐秒相同）。解法 = 覆盖安装场景走 `pm install -r`
      （UPDATED_SYSTEM_APP，实测保留 PRIVILEGED + BLUETOOTH_PRIVILEGED granted，
      且根治 App 进程打不开 overlay APK 的历史崩溃）；全新安装仍走 overlay。
- [x] **service.sh**：`run_hidctl` 升级为回执轮询的已验证调用；删除锁屏闸门；
      回链窗口 8s→25s（锁屏态实测回链 ~23s）；断链确认改双判据（镜像或栈）；
      abort 时恢复 ALLOWED（否则笔被留在 FORBIDDEN 死态）。
- [x] **验证**：最严苛窗口（重启后 FBE 从未解锁、CE 未挂载）断链/回链 + 回执 +
      0 崩溃全通过；`action.sh wake` 模块路径完整闭环。
- [ ] 待用户样本：息屏后取睡死笔，确认自动唤醒 + 笔画可用；锁屏期唤醒后震动是否
      需等解锁（预期退化形态"笔画先回、震动后回"）。
- [ ] 待对照数据：健康"离座→首笔"延迟（§12.5），决定 WAKE_QUIET_SECONDS 是否放宽。

## P0 — 侦察（决定项目成败，先做）
- [ ] 离线：`strings kernel_abs.elf | grep -iE 'lenovo_penraw|PEN_FRAMEWORK|pen_hall|cps'` — 确认内核是否导出笔事件接口
- [ ] 设备在线：跑 `scripts/recon_sysfs.sh`，找 TB522FU 的 Hall / CPS / 笔 uevent 节点
- [ ] 若无内核节点：确认退化路径（纯 GATT 事件源，无磁吸充电管理）

## P0.5 严重已知问题（阻塞性风险，2026-09-18 实机命中一次）
- [~] **Hook 注入 system_server 会「偶发卡开机」** —— 同配置多数开机正常，偶发卡死在开机动画且**不自恢复**（Watchdog 也救不了）。
  - 根因（`debuggerd -b` 实证）：Vector 安装 hook 需 `ThreadList::SuspendAll`（持独占 mutator 锁），
    而 system_server 主线程此时正卡在 `BatteryService.onStart → IHealth.update()` 的 **binder JNI 调用**里，
    JNI 退出处要重新获取 mutator 锁 → 互等死锁。Vector 日志停在 `Loading class …UiWorkingSetPrefetch`，无后续完成行。
  - 恢复：重启即可（本次重启 35s 起来）。`debuggerd -b <pid>` 对全线程 suspend/resume **有可能短暂打破该死的锁**（实测疑似生效）。
  - 方案②（Vector 免 deopt / 懒加载配置）：**已关闭，无解**。CLI `config get` 全部候选键被拒；`modules_config.db` 的 `configs` 表无任何 hook 时序项；
    上游 `--late-inject`（#564）是 NeoZygisk 专用且**仍会 deopt**，不能规避死锁。
  - 方案①（延后/异步安装）：**已落地并验证**（v4.1.4，2026-09-18）。
    - 实现：`UiWorkingSetPrefetch case "android"` → `SystemStylusHooks.installAsync()`，在 `LenovoPenInstall` 守护线程里
      `sleep(2500ms)` 后才真正 `install()`。延迟可运行时调：`setprop persist.lenovo.penbridge.install_delay_ms <ms>`（0 关闭，上限 30000）。
      依据：注入后 system_server 最忙的 `startCoreServices`（PackageManager/BatteryService/SensorService）在前几秒，
      而真正要 hook 的 `SystemServer#startOtherServices` / `#run` 约 18s 后才进；2.5s 延迟把 deopt 挪出最密窗口。
    - 验证（连续 6 次重启，无一次卡开机）：每次均见
      `handleLoadPackage pkg=android` → `install deferred by 2500ms` → `stylus hooks installed (startOtherServices=1 run=1)` → `deferred install done`，
      随后完整钩子链（`PEN_FRAMEWORK uevent bridge started` / `NVT transition suppressor registered` / `touchscreen haptics initialized` / `pen state synced`）。
    - ⚠️ 这是**概率降低而非根治**：底层 ART/framework 竞态无法从模块侧消除。**统计样本仍偏小**，后续回归矩阵需继续多刷几次。
  - [ ] 方案③（作用域回退 app-only 的降级开关）：仍作为最后保底，暂未实现。
  - ⚠️ **可观测性前提**：框架侧 `XposedBridge.log` → Vector module log **会丢弃注入早期的消息**（实测注入时刻的日志一条都不落盘，
    第一条存活日志要 ~18s 后才出现）。因此 `HookUtils.log` 已同时镜像到 `android.util.Log`，用
    `adb logcat -s LenovoPenBridge` 读取；读启动早期日志前建议先放大缓冲：`setprop persist.logd.size 64M`（开机即生效）。
  - 相关文档：`docs/install-vector-route.md` 踩坑表；技能 `android-ksu-vector-module-triage` 的 Bootloop triage。

## 已知缺口（2026-09-18 观测）
- [x] **笔身触控条手势打通（v4.1.8，实机验证）**：v4.1.5 的两处判断**都**是错的 ——
      ①**设备名**：真正把键事件送进 `PhoneWindowManager` 的节点是 **`Lenovo Tab Pen Pro 2 Mouse`**（`event8`，uhid `0005:17EF:622E.0001`）；
      兄弟节点 `…Consumer Control`（`event9`）只上报 `EV_MSC/MSC_SCAN + EV_KEY KEY_UNKNOWN` 且**从不进按键队列**，不能拿来匹配。
      `dumpsys input` 对 Mouse 节点打印空 `KeyLayoutFile`，但它的 `code=131/132/133` 恰恰来自
      `Vendor_17ef_Product_622e.kl` 的 `key usage` 行（`key usage <hid-usage> <key>` 语法 Android 确实支持，`Generic.kl` 里用了 25 处）。
      ②**分派字段**：`getScanCode()` 恒为 **240**（=KEY_UNKNOWN，即内核原始 keycode，不是 HID usage），手势信息在 **`getKeyCode()`**。
      v4.1.5 的 `switch (getScanCode())` 因此永远走 default，且 `contains("consumer control")` 对 Mouse 节点不成立 → 触控条全死。
      真机实测（用户做 4 个手势）：`code=131/132/133 scan=240`。v4.1.8 的 `stripGestureToNativeKey()` 把它们注入成 OEM「原生笔」键 `767/768/769`。
      ⚠️ **`767/768/769` 在 TB522FU 上是确认的空操作（no-op）**：注入事件确实进了管线（日志可见 `dev=Virtual code=768 act=0/1`），
      但整条日志里**没有任何组件消费它们**，用户实测「完全没反应」。→ v4.1.10 弃用注入，改走移植版**自身**的 ColorOS 手势管线。
      ⚠️ **还有一个更隐蔽的前置坑（已修）**：`vector-cli scope set` 会**整表覆盖** scope，而仓库脚本的 scope 列表漏了 `system/0`，
      于是 module **完全不再注入 system_server**（`handleLoadPackage pkg=android` 消失、`interceptKeyBeforeQueueing hooks` 永不安装），
      触控条与整条笔键路径全死，而**所有 app 侧 hook 仍正常**——极容易误判成代码 bug。判据：`vector-cli log cat` 里没有 system_server
      的 `Loading legacy module` 行。已修 `scripts/deploy_vector.sh`（`system/0` 必须保留；`deploy_vector.sh` 新增注释说明）。

- [x] **动作层打通（v4.1.10，实机验证「有反应」）**：action 层从 767/768/769 换成移植版自己的 `click()`/`longAction()` 后仍无反应，
      根因是 **`click()` 广播发错了包**。反编译 `com.oplus.healthservice` + `dumpsys package` 全量枚举接收器后坐实：
      **只有 `com.oplus.healthservice`**（`com.oplus.globalcollect.collect.receiver.StylusPressReceiver`）声明了
      `PENCIL_SINGLE_CLICK` / `PENCIL_DOUBLE_CLICK` / `STYLUS_BUTTON_STATE_CHANGED`；`com.coloros.note` 与 `com.oplus.screenshot`
      **一个都没声明**——旧目标列表因此把每个单击/双击动作静默丢弃（笔身按键不走此函数，所以一直「正常」）。
      修法：`click()` 目标列表改为 `{com.oplus.healthservice, com.coloros.note, com.oplus.screenshot}`（无接收器=无害 no-op，保留后两者做兼容）。
      另：`StylusPressReceiver` **完全忽略 `action` extra**——单击→`onStylusSingleDoubleClick(true)`、双击→`(false)`。
      OEM 行为（`StylusPressManager.onStylusSingleDoubleClick`）：**屏幕解锁**时 `startRouletteService` 弹**笔功能转盘**（`CallRouletteService`）；
      **锁屏/儿童模式/折叠提示**时 `stopRouletteService`（按设计什么都不做）——首次测试无反应正是因为当时**锁屏**（`isScreenLocked=true`）。
      长按（`longAction`→`button("down")`→`STYLUS_BUTTON_STATE_CHANGED`）→ **收藏/圈选浮窗**（`StylusTouchInterceptWindow`），已手动广播验证能弹窗。
      实机结果：4 个手势现在**都能弹出转盘**（用户确认「所有都是弹出转盘」）。
      另修：`dispatchStripGesture` 的 131 桶改用 `tap()`（320ms 双击判别），因为真机上**双击常被固件拆成两次 131**，
      逐次 `click(false)` 会让 OEM 把转盘**开一下又关一下**（净无反应）；`tap()` 归并成一次 `click(true)`。132（F2/双击）路径也实测可达。
      ⏳ **待决策**：目前「滑动/单击/双击」全部落到同一个转盘，未做区分（框架层天然分不开上滑/下滑）。
      若要四手势各自独立 → 见下一条 `.kl` overlay 方案。
      ✅ **已解决（v4.1.14，2026-09-18 23:19 实机验证）**：`.kl` overlay + `stripGesture()` 六手势 +
      `swipeAction()` 上下滑动作层全部落地。见下一条。
- [x] **手势原始判别位已拿到（供将来细分 6 手势）**：笔的 HID notify `00002a4d-…` 报文字节可区分手势 ——
      `02 00 08 00 00`=上滑、`02 00 04 00 00`=下滑、`02 02 00 00 00`=双击、`02 00 00 02 00`=长按（与日志时间戳一一对应）。
      但 ROM 的 `Vendor_17ef_Product_622e.kl` 把「上滑|下滑|单击」都并成 **F1(131)**、「双击」→F2(132)、「长按|挤捏」→F3(133)，
      框架层只能分 3 组。要 6 手势各自独立：加一个**模块级 `.kl` overlay**（`module/system/usr/keylayout/Vendor_17ef_Product_622e.kl`）
      把 6 个 `0x0c06xx` usage 映成 6 个不同键，再把 `stripGesture()`（v4.1.10 命名，原 `stripGestureToNativeKey()`）扩成 6 项即可
      （KernelSU magic mount 在 post-fs-data 生效，早于 system_server 读 keylayout）。
      ⚠️ 磁盘上现有那份 `.kl` **属于 ROM**（无模块覆盖，md5 `eb89c57180b3309badb03aaf9d2630bf`）；覆盖它前先确认 `dumpsys input` 里
      该节点的 `KeyLayoutFile` 指向新文件。
      ✅ **已落地（v4.1.14，2026-09-18 实机验证）**：
      - `module/system/usr/keylayout/Vendor_17ef_Product_622e.kl`：6 usage → F1..F6（131 单击 / 132 双击 / 133 长按 / 134 挤捏 / 135 上滑 / 136 下滑）；
      - `SystemStylusHooks.stripGesture()` 扩到 6 项；新增 `swipeAction()`：读 `ipe_pencil_slide_up`/`ipe_pencil_slide_down`
        （默认 **上滑=5 随心圈 / 下滑=3 调色盘**；值 5 = 复用 OEM 长按圈选会话 `longAction()`，即
        `STYLUS_BUTTON_STATE_CHANGED down` → `StylusTouchInterceptService` 圈选浮窗，已真机广播验证链路通）；
      - ⚠️ 热替换 `.kl` 的坑：bind mount 的临时文件必须 `chcon u:object_r:system_file:s0`，否则 InputReader
        读 `shell_data_file` 被拒 → 回退 Generic.kl → keyCode=0（抓 `dmesg | grep avc` 定位）；
        且换 `.kl` / 换 APK 后需**重启**（system_server 的 hook 代码是开机注入的，热装 APK 不生效）。
      - 实机（合成事件 + 真笔 GATT 双重验证）：135→`slide-up action`→圈选 armed；136→`slide-down action=3`→色盘广播；132→双击。
- [ ] `LenovoConsumerGestureReader` 依赖 native 库 `libpeninput.so`（`System.load(nativeLibraryDir+"/libpeninput.so")` 提供 `nativeGrab`=EVIOCGRAB），
      移植版 Hook APK **未打包**该 .so（v4.1.3 / v4.1.4 / v4.1.5 均无 `lib/`），运行期报
      `native pen input load failed: UnsatisfiedLinkError … libpeninput.so not found` →「consumer gesture reader」这条路（Lenovo Tab Pen Pro
      触控条原始事件直读）当前不可用。**但触控条已由框架路径（`PhoneWindowManager` 拦截 + `getKeyCode` 分派）覆盖，功能不受影响**；
      且此路 `EVIOCGRAB` 会独占设备，与框架路径**只能二选一**（真机已确认 `native pen input load failed: … libpeninput.so not found`
      只是这一路不可用，不影响框架路径）。名称串在 v4.1.8 里按真机事实改成了 `lenovo tab pen`，此路仅作将来备选。
      若要启用：从原始 TB710FU 移植源取预编译 .so 放进 `hook/source/resources/lib/arm64-v8a/`，或自写 ~20 行 JNI（一个 `ioctl` 封装）。

## P1 — 最小闭环（连接 + 电量）
- [ ] 构建 Hook APK（`hook/tools/build_hook_source.py`）并按 scope.list 勾选 LSPosed 作用域
- [ ] 构建 Root 模块 zip（`module/tools/build_root.py`）刷入
- [ ] 确认 inkdye 被禁用且基础书写（NVTCapacitivePen HID）不受影响
- [ ] 验证：BLE 连接 / 电量 / 设置页断开（CONNECT_PENCIL / DISCONNECT_PENCIL）

## P2 — 磁吸链路（最大不确定项）
- [ ] 找到磁吸检测替代源（Hall 节点 / 蓝牙 RSSI / 其他），重写 `PEN1_HALL/PEN2_HALL` 及 `CPS_*` 路径
- [ ] CPS 充电上电（gpiochip0 线号需按 TB522FU dts 重新确认，**勿直接沿用 10/108**）
- [ ] 验证：吸附弹窗 / 磁吸充电 / 吸附时 NVT 触控板禁用

## P3 — 打磨
- [ ] Pen Pro 2 GATT 特性表比对（btmon 抓包 vs `OemGattProtocolHooks` UUID 清单）
- [ ] 书写振动（OplusTouchNodeManager node38 在本机的可用性）
- [ ] 刷新率投票（笔落屏 120Hz），沿用 fix-module 的显示基线做法，勿动 refresh_rate_config
- [ ] 回归矩阵：重启 / 蓝牙重启 / 反复吸附断开 / 首笔延迟

## 工程项
- [ ] Hook 包名是否改名（当前沿用 com.aclaniakea.lenovopenbridge，改包名需同步 build 脚本/签名/scope）
- [ ] 本机 LSPosed 版本与 lspd 路径同步机制验证（post-fs-data 的 lsposed-path-sync）
- [x] 无线 adb 不稳，调试期改 USB（2026-09-21）
  - 结论：**根因三条，没有一条是 WiFi 本身**。
  - ① USB + WiFi 双在线 → 裸 `adb <cmd>` 报 `error: more than one device/emulator`；
    `build.py:device_online()` 只看 returncode，于是**静默**返回 False，第 4 步
    scope 自检与 `--push` 被悄悄跳过（现象是「自检明明没跑，却显示构建成功」）。
    已修：新增 `device_state()` 返回 `device|multiple|none`，双设备时打印准确诊断
    而非 `no device connected`。
  - ② adb server 陈旧网络视图 → `adb connect` 报 `No route to host`，而同一时刻
    `nc -z <ip> 5555` 成功。重启 server 即解（`adb_wifi.sh` 自动重试一次）。
  - ③ DHCP 换 IP → 不信任缓存，优先从 USB 侧读 `wlan0` 重新发现。
  - 统一入口 `scripts/adb_wifi.sh`（`connect|status|env|off`）。
    `eval "$(scripts/adb_wifi.sh env)"` 导出 `ANDROID_SERIAL` —— adb 原生读取该变量，
    故 `build.py` / `pen_release.py` / `deploy_vector.sh` 等子进程**零代码改动**继承。
  - `scripts/deploy_vector.sh` preflight 错误信息同步区分（原先双设备时误报
    `no device; adb connect first`，把人往 WiFi 方向带偏）。
  - 实测：WiFi 通道 pull 20.3 MB/s；第 4 步自检 PASS（scope 8/8 一致，0.2s）。
  - 设备侧 `persist.adb.tcp.port=5555` 已设 ⇒ 重启后 adbd 直接监听，无需 USB 介入；
    `wifi_sleep_policy=2`（WiFi 永不休眠）也已就位。
  - 遗留：`mDeepEnabled=true`（Doze 开启）。插电时不触发，故当前无碍；拔线 + 息屏
    长时间挂机会限网导致 adb 卡顿/掉线，届时 `su -c 'dumpsys deviceidle disable'`。

## P2.5 充电守护（新增，2026-09-18 实现）
- [x] `module/charge-guard.sh` v2：磁吸观察 + 充满通知 + 兜底断电 + IPeManager 状态修正（已实机起进程验证启动）
- [x] `service.sh` 挂载、`uninstall.sh` 恢复 TX=1
- [x] 充电全周期采样（charge_log.csv 33 样本）：充满判据=online 1→0 边沿 + 涓流保持期分析；ipe_chg 恒 0 实锤 → 判据禁用 ipe_chg，见 docs/p0-recon-20260918.md 充电全周期采样分析
- [ ] 实机验证充满通知（吸附 + 电量 100）闭环
- [x] 补受控实验：确认 `online` 语义 —— **随"笔是否在线圈上"变化**（吸附=1，离开=0，见 `docs/charge_log_20260918.csv` 11:34 边沿），但 **不反映是否在送电**（`cps_wls_en:0` 时仍为 1）。因此它**不能**作为「正在充电」判据；唯一判据是 `tx_status` 的 `cps_wls_en`（2026-09-18）
  - `capacity` 同理：吸附=笔的 SOC，离开=0 —— 是驱动通过带内通信解析出的笔电量，可用作交叉校验
- [x] **`module/pen-revive-guard.sh` v1**（2026-09-21 定位 + 实机部署）—— **方案已证伪，见下条 v2**
  - 初判根因链：笔满电 → 驱动自己关 TX（实测吸附后 15~60s）→ Lenovo 笔失去 Qi 供电
    **自行关机** → 突然掉电不走正常 BLE disconnect → 蓝牙栈 HOGP state **不清零 = 僵尸连接**
    → `monitor_real_bt_state` 以为一切正常，而 `request_pen_connect` 只在 Hall 物理边沿调用
    ⇒ 断连后无人重连。**这条链本身是对的**（后经内核 ASK 包日志坐实）。
  - 对策设想：软件等价的「重新吸附」—— 判失联后写一次 `tx_status=1` 送电唤醒笔。
  - ❌ **证伪**：五组独立实验全部失败（详见 `docs/pen-sleep-death-analysis.md`）。写 `tx_status=1`
    只能产生 Analog Ping，而 Hall 边沿走 `dhall_och1909 → cps_wls_charger` 的**内核内函数调用**
    （等价于 Qi Digital Ping），笔只认后者。连 OEM 原生的 `CONNECT_PENCIL` 通路（实验 C，
    CoreService 确实响应并上报 `charging=1`）都唤不醒它。
- [x] **`module/pen-revive-guard.sh` v2（链路状态守护）**（2026-09-21 静态改造，**实机待验证**）
  - 完整根因链 + 五组证伪实验 + 排查命令：`docs/pen-sleep-death-analysis.md`
  - **删除全部 `tx_status` 写入**，一个字节都不写（继续写只是空载线圈发热）。
  - 改为维护 `settings lenovo_pen_powered_down`：Hall 说笔在吸附位、但 CPS 侧 TX 已关闭
    ≥ `POWERED_DOWN_AFTER_SEC`（默认 90s）⇒ 判定笔已自行关机。
  - 解除条件**只有一个**：笔重新上电（`tx=1` 或 `online=1`）。**笔离开吸附位时不解除** ——
    笔离座本就自行关机，保持标志才诚实；若在 detach 清标志，用户「拿起→放回」时 attach
    边沿会看到 0，重连请求又会被僵尸连接吃掉（那正是要修的 bug）。
    - ⚠️ **2026-09-21 11:18 该前提被用户实测证伪**：「笔离座本就自行关机」不成立 —— 破僵尸后
      笔在**全程未吸附**的情况下恢复了完整书写（`event5` 3822 条压力/倾斜事件，`physical_docked=0`
      恒定 196 采样）。且这个「离座不解除」正是 v0.1.9 要修的自锁环的一半成因，见下方 P2.5-b。
  - 不用 `online` 做判据（与 TX 同秒归零，无独立区分度）；不用 HOGP（僵尸连接就是它不翻转造成的）；
    不用 `lenovo_pen_hardware_battery_last_at`（它由本模块 `monitor_battery_cache` 在
    `connected=1` 时周期刷新，僵尸期间会被自己喂成常青，不具备活性含义）。
  - 开关：`pen-revive.dryrun`（存在=只记录不改 settings）/ `disable-pen-revive`（存在=停摆）。
- [x] **`service.sh` 僵尸连接修复**（2026-09-21，配套 v2，**实机待验证**）
  - `real_bt_connected()` 增加 `lenovo_pen_powered_down=1 → return 1`：让 CPS 侧的确定性事实
    压过蓝牙栈的滞后状态。单点改动同时修好三处 —— `monitor_real_bt_state` 镜像变诚实、
    `monitor_hall_capsule` 吸附边沿能触发重连、`request_pen_connect_bounded` 不再误判「已连接」。
  - 新增 `pen_cps_responsive()` 并用于吸附边沿：`if ! real_bt_connected || ! pen_cps_responsive`
    ⇒ 吸附是全新物理会话，笔没在线圈上真实响应就一律重连，不管蓝牙栈怎么说
    （`request_pen_connect` 自带 8 秒去重，Hall 抖动不会变成重连风暴）。
  - 依据：`service.sh:1667` 注释本就写明 *"the dock-attach edge reconnects it later"* ——
    这一步此前被僵尸连接吃掉，正是「必须再吸一次」的直接机制。
  - ⚠️ **不是本模块引入的**：`docs/p0-recon-20260918.md`（移植第一天）第 79–82 行就记到了
    「充满 → online 归零 → 静止 23 分钟」。charge-guard v2 的 `tx_set 0` 只存在 4 小时
    （`059e018` 11:39 → `d7e8609` 15:36），与现象无关。

## P2.5-b 僵尸连接自锁环：`powered_down` 短路判据把模块自己的自恢复废掉（v0.1.9，2026-09-21）

**现象**（用户 4 步实测）：拿笔书写 ✅ → 重新吸附 → 锁屏静置 2 分钟 → 解锁 → **写不出**，
必须重新吸附才恢复；随后确认**笔全程未吸附**时靠软件破僵尸即可书写。

- [x] **实验 1 完成**（11:12，root，零用户操作）：`DISCONNECT_PENCIL` → `HOGP 2→3→0`（<2s）；
      `CONNECT_PENCIL` → **6 秒内**完整会话重建（`services=12 characteristics=36`、
      固件 `PARKER-V3 V1.26`、序列号 `HVE70MYJ`、notify 回包、`real HID connected`）。
      11:02 的 CONNECT 失败与 11:12 的成功，**唯一差异是中间那次 DISCONNECT**。
- [x] **实验 1b 完成**（11:14–11:18，用户手写）：破僵尸后**不吸附**即可书写，
      `event5` 3822 条（`ABS_PRESSURE`/`ABS_TILT`/`BTN_DIGI` 齐全），
      `physical_docked=0` 恒定 196 个 1 秒采样 ⇒ **L1「笔固件掉电、只能靠线圈唤醒」证伪**。
- [x] **代码级根因定位**（不是猜测，有模块自身日志）：
      `service.sh::real_bt_connected()` 里 `powered_down=1 → return 1` 的短路，
      而 `request_pen_connect_bounded()` 正是拿它当重连成功判据。实测 `pen-bridge.log`：
      ```
      11:12:23 explicit pen reconnect window ended without HOGP   ← 宣告失败
      11:12:23 real BT state mirror connected=0 (was 1)           ← 于是清镜像
      ```
      **而 `dumpsys` 从 11:12:14 起 `hogp connection state=2` 就已成立** —— 连上了却报没连。
      **自锁环**：置位要求 `docked=1`、解除要求回到线圈，而离座分支故意不动标志
      ⇒ 笔带着 `powered_down=1` 离座后该标志**结构上再也无法更新**
      ⇒ 判据恒假 ⇒ 自恢复永不确认 + 镜像永远报「未连接」
      ⇒ 并连带写出 `settings_enable_oppo_pencil=0` / `ipe_pencil_present=0`
      （模块持续向 ColorOS 宣告「笔不存在」）。
- [x] **v0.1.9 修复**（4 处，`sh -n` 通过，构建并推送设备 Download）：
      | 编号 | 改动 |
      |---|---|
      | F-A | 拆出 `bt_stack_hogp_live()`（纯栈读取）；`powered_down` 短路**限定在 `physical_docked=1` 时**生效 |
      | F-B | 新增 `request_pen_reconnect_hard()`：`DISCONNECT` → 轮询等 `HOGP` 落下（≤6s）→ 清自造闩锁 → `CONNECT` |
      | F-C | 离座边沿：`left_powered_down=1`（失败场景指纹）走 F-B，否则维持原普通路径 |
      | F-D | `request_pen_connect_bounded()` 三次普通 `connect` 耗尽后兜底追加 F-B |
      - F-A 修「假阴性」；F-C 修「发错原语」（11:02 其实**发过** connect，只是被僵尸 `not disconnected. state=2` 拒绝）
      - 依据文档：`docs/pen_undock_after_dock_sleep_unusable_20260921.md` §1.6 / §1.6.1 / §1.6.2
- [x] **实验 3 执行（11:31）：❌ 失败 —— 修复未被触发。** 详见
      `docs/pen_exp3_v019_failure_20260921.md`。直接原因：离座边沿（`service.sh:1308`）两个条件
      同时为假 —— `left_powered_down=0`（停充→离座仅 **47 秒** < `POWERED_DOWN_AFTER_SEC=90`）、
      `! real_bt_connected` 恒假（僵尸 `HOGP=2` 全程 163 采样）⇒ **离座零动作**。
- [x] **实验 3b 救助（11:34，root 零操作）**：`DISCONNECT` → `HOGP 2→0`；`CONNECT` → 6 秒内
      真实重建（`GATT_CH_OPEN` + `read 2a19 → battery 100` + 笔 notify 回包 `01 00 01 0d …`）。
- [x] **实验 3c 完成（11:38，用户手写）：✅ 通过** —— 救助后**全程未吸附**书写正常：
      `cap_ev5` 新增 **744 条**笔迹事件（`ABS_PRESSURE` 113 值 / `ABS_TILT` 84 值 /
      `ABS_X`·`ABS_Y` 各 164 唯一值，0→4265 压力曲线），`physical_docked=0` 恒定 301 采样；
      模块侧 `explicit pen reconnect confirmed by HOGP attempt=1`（**F-A 解耦生效，不再假阴性**）。
      ⇒ **笔不需回座唤醒；硬重连有效且充分；缺的只是触发条件。**
      ⇒ 另实测确认：F-B 的 `DISCONNECT` 由 **OEM CoreService 通路**完成即可，
      `run_hidctl`（因 `penhidctl` 崩溃而不可用）**不是阻塞项**。
- [x] **D1a 已实施（v0.1.10，用户 2026-09-21 11:44 选定）** —— 离座边沿改用「笔尖节点短窗口活性」判据。
      改动 5 处：
      | # | 内容 |
      |---|---|
      | 1 | 新增常量 `PEN_TIP_DEVICE_NAME` / `INPUT_DEVICES_FILE` / `PEN_TIP_ALIVE_WINDOW=2` / `PEN_HARD_RECONNECT_DEDUP*` |
      | 2 | 新增 `pen_tip_event_node()`：按**设备名**从 `/proc/bus/input/devices` 解析 evdev 节点 |
      | 3 | 新增 `pen_tip_alive_within()`：**三态**返回（0=有事件／1=零事件／2=判不了） |
      | 4 | 重写 `monitor_hall_capsule()` 离座分支：栈真断 → 直接硬重连；栈报「在」（可能是僵尸）→ 活性判据裁决 |
      | 5 | `request_pen_reconnect_hard()` 增加 10 秒去重（防 Hall 抖动引发重连风暴） |
      - **三态设计理由**：把「工具故障」误当成「休眠」会导致**每次离座都无谓重连**（6 秒不可用），
        所以只有 `getevent` 返回 **124**（超时）才判静默；节点打不开/工具异常一律走 `2`（保守不动）。
      - **设备实测**：`NVTCapacitivePen` 的 evdev 节点 = **`/dev/input/event5`**
        （⚠️ `dumpsys input` 里的 `9: NVTCapacitivePen` 是 **InputDevice id，不是 evdev 节点号**）；
        解析函数输出正确；零事件路径连跑 3 轮稳定 `rc=1` / 耗时 2s；**无 `rc=2` 降级**。
      - 构建：v0.1.10 / versionCode 110，MD5 `af6cf6ba44e34916ce4725f4e988cc4d`（设备侧已核对一致），
        scope 8/8 一致，Hook APK 仍 v4.6.0 未变。
      - ⚠️ **遗留风险（部署后须实测确认）**：`rc=0` 路径（有事件时返回 0）**未能自主验证** ——
        该内核（6.6.82）下 `sendevent` 注入的事件不被广播给其他 evdev 客户端
        （`sendevent rc=0` 但 `getevent` 仍超时），属 `evdev_write()` 的语义限制。
        若此处误判，症状是「**每次拿笔都要等约 6 秒**」且模块日志出现
        `pen tip silent ... assuming sleeping emitter` —— 出现即说明判据有误，需排查。
- [x] **D1a 通路审计 + v0.1.11→v0.1.13 迭代**（2026-09-21 12:1x，实机实测）
      - **新发现（A/B 已证）**：`com.oplus.ipemanager` 只声明 `PARTIALLY_DIRECT_BOOT_AWARE`，
        `CoreService` 自身**不是 direct-boot-aware**；用户处于 `RUNNING_LOCKED` 时 PMS 在
        `resolveService` 阶段就过滤掉它 ⇒ `am startservice` 报
        `Not found; no service started`（rc=255），`cmd package query-services` 返回
        `No services found`。模块自带的 `com.aclaniakea.penhidctl` 同样没有该标记
        ⇒ **该窗口内 OEM 与 HID 两条通路同时不可达**（不是 ROM 的 am 缺陷）。
      - **影响边界（极易被高估，务必记住）**：`RUNNING_LOCKED` 只存在于
        **「开机 → 该次开机首次解锁」**之间。解锁过一次后即使关屏、停在锁屏界面，
        用户仍是 `RUNNING_UNLOCKED` ⇒ **用户复现路径（吸附→锁屏→解锁→取笔）全程解锁态，
        D1a 主路径不受影响**；唯一受影响的是「开机后首次解锁前」这一小段
        （实测 11:49 / 12:09 两次启动的 OEM 会话恢复全程失败；12:03 那次启动的解锁定位于
        12:03:38.5，同一条调用 0.5 秒后即成功，可作对照）。
      - **另一个独立成因**：以 **shell(uid 2000)** 身份调用会被
        `Requires permission com.oplus.permission.safe.IOT` 拒绝；模块以 **root(uid 0)**
        运行不受限 —— 早期用 `adb shell am startservice` 手测总是失败就是此因。
      - **改动**：v0.1.11 引入「不可达就挂起」状态（pending 标记 + 后台等待者）；
        v0.1.12 判据改为「**先尝试、真的投不出去才挂起**」+ `mkdir` 原子锁保证单实例；
        v0.1.13 `request_oem_pen_action()` 三种失败（未解锁／包未登记／权限）一律 `return 1`。
      - **去重时间戳改为「投递成功后才写」** —— 延后不消耗 10 秒去重窗口。
      - **桩测试 5/5 通过**（设备上跑，函数体从 `service.sh` 原样抽取）：
        T1 TTL 放弃 / T2 恢复触发 / T3 单实例去重 / T4 延迟恢复 / T5 持有者死亡接管。
        T3 当场抓出「去重依赖 `/proc/<pid>/cmdline` 匹配脚本名」的脆弱实现（改名即失效），
        已改为 `mkdir` 锁；另纠正了「拿笔尖活性当恢复触发器」的死锁设计
        （休眠的笔尖节点本就零事件，那正是 D1a 判据本身）。
      - 完整证据：`docs/pen_exp3_v019_failure_20260921.md` **§14**。
- [x] **缺陷 B：开机 OEM 会话恢复的 3 次预算在未解锁窗口被烧光**（同批修复，v0.1.13 实测）
      - 旧实现每次失败都占用预算（30 秒间隔），未解锁窗口内 90 秒烧完 ⇒ `exhausted`，
        随后只能「等到下一次真实蓝牙边沿」才恢复 OEM 会话（实测 12:09 那次启动：
        attempt 1/3、2/3 全是 `unlocked=0`）。
      - 修复：预算只记「真投出去」的次数；投递不出去的另计 `oem_recovery_blocked`
        （封顶 40 次 ≈ 20 分钟），真实蓝牙边沿时一并清零。
      - 实测 v0.1.13 启动日志：`OEM haptic recovery not deliverable; channel unreachable,
        budget kept (1/40)` → `(2/40)`，不再出现 `attempt=1/3`。
- [x] **D1a 第二层失效：判据子壳静默死锁**（2026-09-21 12:22 用户复查「还是不行」，实机取证）
      - **现象**：12:21:57 离座边沿，模块日志**整段静默 80 秒以上** —— 判据、重连、日志全无。
        与 11:31 那次表现相同但成因不同：这次是**代码根本没跑完**。
      - **根因**：`$(pen_tip_event_node)` 的子壳永久卡在 `/proc/bus/input/devices` 的
        **无限超时 poll**（`syscall 73 ppoll, nfds=1, tmo_p=NULL`；`fdinfo/0` 读偏移冻结在
        `pos:1`；六次采样一字不变），父壳 `31695` 停在 `pipe_read` 等它，永无结果。
        **`( ... ) &` 的失败是静默的** —— 这正是 D1a 整条被吃掉的原因。
      - **对照**：同一 `while read` 构造在 root shell、`nsenter` 进同一挂载命名空间、
        以及新起的「仿模块 fd 环境」进程里**都能正常跑完 106 行** ⇒ 与运行时状态相关的
        偶发阻塞，**不能靠「复现不出来」排除**。
      - **改动**：v0.1.14 —— ①节点解析改扫 sysfs（`/sys/class/input/input*/name` +
        `inputN/eventM`，彻底不碰 procfs）；②新增 `pen_tip_probe_reap()` 探针看门狗，
        监视循环每秒记账、超 `PEN_TIP_PROBE_MAX=15` 秒收尸并强制重连；③`rc=2`（判不了）
        由「不动」改为「重连」（失败保险向）；④两条通路都登记 PID+起始秒。
        v0.1.15 —— ⑤`request_pen_reconnect_hard` **同时清两个闩锁**（见下条）；
        ⑥探针增加「为什么判不了」的诊断行；⑦节点扫不到时等 1 秒重试一次。
      - **实测**（函数体从 `service.sh` 原样抽取，设备上跑）：节点 → `/dev/input/event5`
        （修正了初版少一层 `input/` 的缺陷）；静默 → `rc=1`/2s；看门狗·健康不杀／
        卡死杀并强制重连／未到上限不动作；强制缺节点 → 1 秒重试 + 诊断 + `rc=2`。
      - **端到端**：v0.1.14 重启后 12:33:00 的离座边沿**第一次留下完整日志链**
        （`pen tip liveness unknown (rc=2); hard reconnecting as fail-safe` → 硬重连成功）。
        核心目标（不再静默）达成。证据见 `docs/pen_exp3_v019_failure_20260921.md` §15。
- [x] **缺陷 C：硬重连「断而不建」，比僵尸连接更糟**（12:33:05 实测抓到，v0.1.15 修复）
      - Hook 的 `IpeManagerHooks` 处理 `DISCONNECT_PENCIL` 时把
        `lenovo_pen_disconnect_requested` 与 `lenovo_pen_user_disconnect_requested`
        **两个闩锁一起置 1**（`CONNECT_PENCIL` 则一起清 0），而硬重连只清了前者
        ⇒ `request_pen_connect` 立刻以 `pen connect skipped: user disconnect choice is active`
        返回：**链被我们亲手断掉又拒绝重建**。这次只因用户 5 秒后又把笔吸回座上
        （吸附边沿会清闩锁）才没暴露成新故障。
      - 修复：硬重连里两个闩锁一起清（用户主动点「断开」造成的闩锁仍在函数开头提前返回，
        不会被误清）。
- [x] **exp6 已重做：用户反馈「一切都正常」，笔确已恢复；残留仅为 13 秒重连窗口**（2026-09-21 13:24–13:30，多路仪器取证）
      - **证据**：笔尖 `event5` 产生 **4674 行**完整 NVTCapacitivePen 报文（`ABS_X/Y` +
        `ABS_DISTANCE` + `ABS_TILT_X/Y` + `BTN_DIGI` DOWN/UP 严格配对），**5 段书写**
        （段 3→段 4 空档 22 秒），`hid9` 同步产出 12 组 `MSC_SCAN 0x619/0x620` 笔键报文，
        `event4` 活跃 673 行 —— 与"画 → 按笔键 → 再画 → 手指划"逐项吻合。
        **笔的物理层、驱动层、输入层全部正常**（与 §13 以来"BLE 活但屏幕零事件"的旧现场相反）。
      - **13:12 那次"还是写不出"被重新定性**：模块当时**做对了每一步**（探针 → fail-safe
        硬重连 → 清双闩锁 → CONNECT），但「取下笔 → 链路可用」间隔
        **13:12:25 → 13:12:38 = 13 秒**，用户在窗口内就验收了。13:12:38 之后
        `pen-bridge.log` 再无任何输出 ⇒ 13:24 的成功书写**全程无模块介入**，是纯物理书写。
      - **`rc=143` 成因定案（D6 关闭）**：不是 getevent 的毛病，是 **`timeout` 自身的
        竞态** —— 同一条命令、同一个节点，13:12 模块记录 `rc=143`（128+SIGTERM），
        13:29 本轮实测得到 `rc=124`（toybox 正常超时码，纯度探针 `timeout 1 sleep 5`
        同样返回 124）。`timeout` 发 SIGTERM 与子进程自退存在赛跑，谁先结算退出状态
        谁生效。旧代码把 143 归入 `*)` 判成「无法判定」，且**决策会被退出码悄悄改写**。
      - **v0.1.16 已实施**：判据改为**读输出而非退出码**（`getevent -l -c 1` + 匹配 `EV_`），
        与工具版本/信号竞态彻底解耦。实机校验：静默分支 8/8 轮正确、零误报。
      - 详见 `docs/pen_exp3_v019_failure_20260921.md` §16。
- [ ] **D5** 停充后周期性极短 TX 脉冲维持笔的发射子系统清醒
      【**唯一能把「长时吸附后写不出」归零的方向；2026-09-21 14:46 已由实验定案为
      残留缺陷的病因**。新证据（详见 `docs/pen_exp3_v019_failure_20260921.md` §17）：
      失败的自变量是**充停后的吸附时长**，不是 `powered_down`——成功 1.9/2.5/6.2 min，
      失败 17 min，刚吸附过则秒级恢复；阈值在 6.2–17 min 之间。
      机制：CPS 驱动 `.ko` 字符串显示 **笔自己通过带内通信 `charging_cmd` 命令驱动
      `close tx`**（非用户态干涉），此后 TX 连续关闭 ~19 分钟无再充活动
      （`pen-revive.log`: `tx off 1129s`），笔即在此期间深休眠。
      两条**可写的** TX 杠杆已探明：`/sys/class/power_supply/cps_wls_tx/online`(`-rw-`)
      与 `wls_tx/cmd`(`-rw-`)；厂商私有 `tx_status`（v2 踩过坑）优先不用。
      先做**零代码脉冲验证**：吸附+息屏，每 60s 脉冲一次 × 20 分钟 → 取下即写。
      同时保留原判据：若 D5 成立，离座分支会走 `pen tip alive after undock; link kept`，
      13 秒窗口一并归零】
- [x] **D8 已结案：原假设被证伪，判据并非空转**（2026-09-21 14:11:39 实测）
      - 原假设是「离座瞬间笔尖必然零事件 ⇒ D1a 探针等价于无条件重连」。**实测推翻**：
        用户在**拿起笔立即书写**时，2 秒探针窗口内捕获到笔尖事件（`event5` 同期 268 行），
        判据走 `ALIVE` 并打印 **`pen tip alive after undock; link kept`** —— **保持链路，
        零延迟可写**。这是 v0.1.10 引入 D1a 以来 `ALIVE` 分支的**首次真实触发**。
      - ⇒ **判据能真实区分两种时序**：「拿起就写」→ `ALIVE` → 不重连；
        「取下搁置后再写」→ `SILENT` → 硬重连（~13 秒）。
        **13 秒窗口因此不是普遍现象**，只在后者出现，而「取下即写」这条最常用路径
        已经是零延迟。
      - **顺带显形的物理事实**：`powered_down=1` 全程为 1 时笔尖仍在发射 ⇒
        `powered_down` 描述的是**充电/BLE 侧**状态，与**笔尖电容发射**是两套独立系统。
        书写不依赖 BLE ⇒ **排查"写不出"时不能拿 `powered_down` 当病灶推理**。
- [x] **v0.1.16 已部署并端到端验证**（2026-09-21 14:09，**热重载，未重启设备**）
      - 部署手法：`mv` **原子替换** `service.sh` + `module.prop` —— **不能用 `cp` 覆盖**：
        shell 是流式读脚本的，`cp` 会截断一个正在被读取的文件，让运行中的实例读到
        错位内容甚至执行垃圾；rename 保留旧 inode 给已打开的 fd，运行实例不受影响。
        之后停掉旧工作实例及其全部子进程、**保留 ppid=1 的 respawn 循环**，
        8 秒内自动以新文件重建（实测 14:09:05 完整启动序列，新工作进程 7310 + guard 7536/7545）。
      - 新版 md5 `7f03a4a1c5acf4931bd24b532475114c`；zip `d4aec284c5ccd97df28492406789a429`
        （Vector scope 自检 8/8 一致）；回滚备份 `*.v0115.bak` 在 `/data/local/tmp`。
- [x] **🛑 模块已从设备上卸载（2026-09-21 15:11，交还系统内置笔桥 inkdye）**
      用户指令：「移除本模块，恢复 inkdye 版本」。执行内容与实测结果：
      - 停止模块运行时：supervisor 3168 + 工作实例 + `charge-guard` / `pen-revive-guard`
        共 **10 个进程**全部清空（先杀 respawn 源头，再清子进程）。
      - `pm enable --user 0 com.inkdye.lenovopentocoloros` → `enabled=1`
        （原为 `3` = DISABLED_USER），且 `disabledComponents` 计数为 0。
      - Vector `modules disable` + `pm uninstall` 配套 Hook APK；`pm uninstall --user 0`
        `com.aclaniakea.penhidctl`；overlay `/system/priv-app/aclpenhid` 随模块消失。
      - 清 `persist.lenovo.penbridge.disabled`（`resetprop -p -d`）+ **34 个 `lenovo_pen*`
        settings 镜像键**；**OEM 的 20 个 `ipe_pencil_*` 键一律未动**（归属判定：
        `lenovo_pen*` 由本模块 `HookUtils.java` / `service.sh` 写入，`ipe_pencil_*` 是原厂）。
      - `ksud module uninstall tb522fu_pen_bridge` + 重启 → 模块目录已删除，
        `ksud module list` 不再出现。
      - **键位覆盖自动回落到 ROM 原版**：`Vendor_17ef_Product_622e.kl` 现为 987 B /
        `2009-01-01` ROM 时间戳、首行是 ROM 自带注释（模块版是 1463 B + 我们的注释）。
      - **全程未写任何 CPS/充电节点**：`tx_status` 仍是 `cps_boost_mode:0, cps_wls_en:0`。
        实测 `uninstall.sh` 里的 `echo 1 > tx_status` **被驱动拒绝**（值未变）⇒
        该行是遗留 no-op；且 `charge-guard` v3 / `pen-revive-guard` v2 本就只读，
        所谓"恢复 TX=1"没有实际对象。
      - 离线备份：`../offline_backups/tb522fu_pen_bridge_backup_20260921_150946.tgz`
        （6.0 MB，md5 `f074e468cfc6efe2295c05b90fd4d8b2`）+ `*_snapshot.txt`；
        重装可直接用 `releases/tb522fu-pen-bridge-v0.1.16.zip`。
      - 重启后复核：loadavg 回落、cpu-1-1-1 `37.8 °C`、笔输入节点
        （`NVTCapacitivePen`→event5、笔 HID→event8/9）全在。
      - **遗留待决**：① `com.oplus.gesture` 仍处禁用 —— 是本模块为"开机稳定性"禁的
        （`service.sh:232`），与笔功能无关，**未擅自恢复**（恢复有 bootloop 风险）；
        ② 便签引擎 `libSuniaEngine.so` 仍是补丁版 `db61d1ff…`，
        **原版本地无源可还原**（ROM 无 com.coloros.note，应用来自 `/data/app`，
        且设备上无 `.guard.bak.*` 备份）。
- [ ] **D9**：给 13 秒窗口加可观测性 —— 在硬重连收敛点打印耗时
      （`hard pen reconnect converged in Ns`），把"能不能写"从用户体感变成日志读数，
      后续优化才有基线。
- [ ] **D11**：笔键按压能否唤醒深休眠的笔？（低成本备选，30 秒可测）
      - 现象线索：2026-09-21 `14:45:33-39`（用户「用笔书写」步骤）`/proc/nvt_pen_diff`
        全程为 **0**；紧接 `14:45:40-42`（用户「按笔键 3 次」步骤）抬到 **762**，
        随后回落 111。提示**笔键可能触发一次笔侧唤醒**。
      - 若成立 ⇒ 可给用户一条不依赖重新吸附的现场自救手法（离座后按笔键再写）；
        更进一步，模块可在离座分支里主动触发一次等价的笔侧唤醒。
- [ ] **D12**：`/proc/nvt_pen_diff` 语义校验（未校验前不得单独作判据）
      - 该节点是**快照型**：裸读返回 `EAGAIN("Try again")`，需带重试才成功；
        且同一值可连续重复十余次（`111 409` 重复 15 次、`655 2685` 重复 11 次），
        **存在"缓存上一帧"的嫌疑**。
      - 更硬的坑：失败态读到 1191/762，成功态读到 655 —— **数值大小与"能否书写"
        不单调对应**，不能直接当"笔在发射"的证据。
      - 可信的次要信号：**读取成败本身**。手指一碰屏幕 IC 即醒，读才从 `fail` 转 `ok`
        （14:45:18 之前 11 次全 fail，触碰后转 ok）。
      - 校验方法：笔远离屏幕 10s → 笔尖贴屏 10s → 再远离 10s，全程高频采样，
        看数值是否跟随。
- [ ] **D1b** GATT 探测【**已降级**：休眠不拆 BLE 承载，read 可能照常成功】
      **D4「监听链路静默」已证伪** —— 正常态亦静默 3.5 分钟（`fe41` 是按需上报，非心跳）。
      复核依据见 `docs/pen_exp3_v019_failure_20260921.md` §13.5 / §13.6。
- [ ] **N1（P1）**：`penhidctl` 100% 崩溃（`Failed to open APK ... I/O error` + NPE `getStringArray` on null，
      由 KSU overlay 提供、App mount ns 不可见）⇒ `run_hidctl` 的 disconnect/connect 通道实际不可用。
      改为 `pm install` 到 `/data` 再 grant；F-B 目前主要靠 OEM CoreService 通路，故 N1 不阻塞本修复。
- [ ] **N6（P1）**：`live uhid link=false` 仍上报 `connected` ⇒ 笔按键/手势（`event8/9`）静默失效。
      本轮实测 `event8/9` 在整个书写期间**均为 0 字节**，与该判断一致（未按笔键，故不能独立定案）。

- [ ] **待验证（设备回归后）**：Hall 驱动 rebind 能否软件化「重新吸附」——
      对 `soc:hall_detect@0` 做 unbind+bind 会让 Hall 驱动重新 probe，可能触发完整的
      「笔来了」通知链。**风险**：失败会让 Hall 吸附检测整体失效（重启可恢复）。
      未验证前**不要**剪进自动守护；命令见 `docs/pen-sleep-death-analysis.md`。
  - `charge_type`：吸附=Trickle、离开=Unknown，是驱动按吸附状态给的档位名，同样不代表"正在充电"
- [x] 实机复核「充满后软件侧是否主动断电」（2026-09-18，电量 100 / 笔在磁吸上）：**有，但当前是我们守护干的，不是驱动**
  - charge-guard.log 两次「driver left TX on at full (100); forced off」分别在吸附后 **t+3s / t+6s**，即我们的守护比驱动先动手；**因此"驱动自己会不会关"至今没有被观察到**（被我们抢先了）
  - 通知层：14:56:59 触发「手写笔已充满，已停止充电」（ColorOS 丢 shell 通知，UI 走 Hook 广播）
  - ⚠️ 守护抢先动手的风险：守护用 **BLE 侧电量**（可能滞后）当判据，驱动用 **带内 `charging_soc`**（直读）。若两者不一致（BLE 仍读 100、笔实际需要补电），守护会**挡住合法补电** → 表现为"笔吸上去不充电"
- [x] **反向工程 CPS 驱动本体**（`/vendor_dlkm/lib/modules/cps_wls_charger.ko`，2026-09-18）——确认充满截止/补充充电是**原厂驱动自带**：读 `hall_status`、带内 ASK/FSK 解析 `pen_type/mac/charging_soc/charging_status/charging_cmd`、`charging_cmd → close tx`、`error int flag → close tx`、`cps_handle_rechg_work`（补充充电）、设备树无任何策略参数。详见 `docs/cps-charger-driver-analysis.md`
- [x] **决定性实验已补（2026-09-21）**：`disable-charge-guard` 状态下让笔保持吸附，确认**驱动会自己关掉 `cps_wls_en`**，且延迟极短（实测吸附后 15~60s）。
  - **更关键的发现**：关 TX 不是驱动自己的判断，**是笔通过带内 ASK 包下的命令** ——
    内核日志 `ask pkt data[2]:1, charging_cmd:1, close tx` + `cps_wls_boost:0`。
    笔报满电（`charging_status:2`）后下令停充，驱动照办，笔随即因失去载波而自行关机。
  - ⇒ 守护的强制写 0 **早已被移除**（charge-guard v3 纯观察者，见提交 `d7e8609`），
    当前方向正确，无需回退。详见 `docs/pen-sleep-death-analysis.md`。
- [x] 实机验证充满通知闭环：守护触发 ✅、兜底断电 ✅；确认 ColorOS 丢弃 shell 通知 → UI 展示移入 P1 Hook APK（SHOW_PENCIL_CAPSULE 广播链路已预留）
- [ ] 实机验证恢复充电通知（电量回落到 <=95）
- [ ] 实机验证 ipe_pencil_charging_state 在低于 100% 吸附时被修正为 1
- [ ] P1 Hook APK 中实现 SHOW_PENCIL_CAPSULE 接收端（弹胶囊/发通知）
- [x] inkdye **默认禁用**（2026-09-18 用户拍板）：`service.sh` 开机 `disable-user` 内置笔桥 `com.inkdye.lenovopentocoloros`，由本模块接管；用户可用 `action.sh enable` 覆盖（写入 `inkdye-enabled.state`）。卸载/panic/boot-guard 会无条件恢复
- [x] **inkdye 落地时机修正**（2026-09-18）：原实现放在 `exec` 之前、service.sh 很早期执行，此时 **PackageManager 尚未就绪**，`pm disable-user` **静默失败**（日志说禁了、`dumpsys` 仍 `enabled=0`）。
  已改为 `exec` 之后的后台函数 `apply_inkdye_state()`：**最多 40×3s 重试 + `pm list packages -d --user 0` 复核状态**，确认后打印。实机验证 `enabled=3`（DISABLED_USER）✅
- [x] **charge-guard 单实例守卫修正**（2026-09-18）：pidfile 在 `/data` 跨重启保留，旧实现只 `kill -0 <old>` → 撞上被复用的 PID 时守护**静默 exit 0**（守护整轮缺席）。
  已改为 boot_id + `/proc/<pid>/cmdline` 双校验，pidfile 记 `pid bootid`；`service.sh` 补启动日志 + 后台 liveness 复核（失败补启一次）。实机验证单实例、日志正常 ✅

### ❌ 实验 3（v0.1.9 部署后真实路径）失败：修复未被触发（2026-09-21 11:31）

完整分析见 `docs/pen_exp3_v019_failure_20260921.md`。

- [x] **部署 + 重启**：v0.1.9（versionCode 109）已装，`system_server stylus hooks installed`，
      scope 8/8 校验通过；开机基线 `powered_down=0 link_connected=1 HOGP=2`（判据已正常）。
- [x] **用户真实路径复现**：吸附(11:27:48) → 锁屏静置(11:28:20–11:30:50) →
      满电停充(11:30:25) → 解锁 → 拿笔(11:31:12) → **写不出**；`event5` 在 11:31:12 后 **0 事件**。
- [x] **直接原因（零动作）**：离座边沿两个条件同时为假 ——
      `left_powered_down=0`（`pen-revive.log`：`tx off 45s/90s`，**未到 90s 阈值**）
      且 `! real_bt_connected` 为假（僵尸 `HOGP=2`）⇒ `pen-bridge.log` 在 11:31:12 后**再无输出**。
- [x] **认知修正 1**：`powered_down=1` **不能**当失败场景指纹 —— 失败只需 47 秒静置，标志需要 90 秒。
- [x] **认知修正 2**：v0.1.8 那次的 `connected=0`（11:00:50）很可能是 `powered_down` 短路写镜像的
      **结果**而非链路断开证据（两场景真实栈状态同为 `HOGP=2`）。
- [x] **认知修正 3**：N3 解耦（F-A）在离座边沿是**负向**的 —— 旧代码靠短路副作用"歪打正着"发过一次
      connect，解耦后判据恒真、连试都不试。⇒ **必须有活性判据补位**。
- [x] **救助实验再次通过**（11:34，零操作）：`DISCONNECT` → `HOGP 2→0`；`CONNECT` → 6 秒内
      真实重建（`GATT_CH_OPEN` + 读写成功 + 笔 notify 回包）⇒ 笔活着，修复动作有效。
- [x] **新缺陷记录**：硬重连后 HID 输入设备**重新枚举**（id 10/11 → 12/13），
      hook 缓存的旧节点失效 ⇒ 全程报 `live uhid link=false`（与 L5 同源）。
- [ ] **采坑待修**：`cap_ev8/9` 采集写死了节点号，重连后会漏测 ⇒ 改按**设备名动态发现** `eventN`。
- [ ] **修复待选（用户要求暂缓）**：主推 **D1a 笔尖活性闭环**（离座后 ~2s 内 `event5` 无事件 ⇒ 硬重连），
      备选 D1b GATT 探测（hook 侧）。见失败分析 §10。

## P1 构建（2026-09-18 完成）
- [x] Hook APK：`releases/PenBridge-Hook-tb522fu-v4.1.3.apk`（DeviceGate=SM8750P/sun，213KB，Xposed API 已正确从 dex 剔除）
- [x] PenHidCtl：`releases/PenHidCtl-tb522fu-1.1.0.apk`（17KB）
- [x] 模块包：`releases/tb522fu-pen-bridge-v0.1.0.zip`（含 charge-guard.sh、service.sh、PenHidCtl priv-app、lsposed-path-sync、hook 副本）
- [x] 构建脚本本地化：build_hook_source/build_penhid（alias/out env 化、libpeninput.so 可选）、build_root（repo 布局适配、去 CPS GPIO、加 charge-guard）
- [x] 本机工具链：/tmp/android-sdk（build-tools android-15 + platform-35），自签 keys/tb522fu.jks（不入库）
- [x] 安装/验证流程文档化：`docs/install-vector-route.md`；产物推送脚本 `scripts/push_to_device.sh`（含 md5 校验）
- [x] 刷入模块 zip + 重启（`ksud module install`；ColorOS 拦截 `adb install`，Hook APK 走 root `pm install`）
- [x] Vector 作用域配置（`vector-cli scope set`，**1 + 7 项**全 user 0）——**框架是 Vector，不是 LSPosed**。⚠️ system_server 用伪包名 `system`（曾误写 `android`，见 P1.5）
- [x] 验证 hook 加载（`vector-cli log cat` 显示 uid 1000/`system` 也加载了本模块）
- [x] 验证 charge-guard 随开机启动、bootfail 计数正常归零
- [ ] 确认 inkdye 被默认禁用后基础书写（NVTCapacitivePen HID）与触觉反馈不受影响（Hook 未生效期间触觉可能空窗，必要时 `action.sh enable` 临时回退）
- [ ] 接入真实手写笔做功能验收（吸附弹窗 / 按键 / 触觉 / 充满闭环）

## P1.6 日志查看控制台 + 日志限流（2026-09-19，模块 v0.1.1）

- [x] 需求：模块要"点开就能看日志"，但不能无限制写日志；管理器**原生控制台**即可（明确不要 WebView）。
- [x] 事实核对（设备实测，详见 `.workbuddy/memory/2026-09-19.md`）：
  - root 管理器是 **ReSukiSU `com.resukisu.resukisu` v4.2.0-rc1**（KernelSU 系），**不是 Magisk**
    （`/data/adb/magisk` 不存在，`ksud 4.2.0-rc1 uapi:2`）。
  - 管理器**每个模块卡片只有一个原生控制台入口**：APK 里只有 `hasActionScript=`（→「操作」按钮，跑 action.sh）
    和 `hasWebUi=`（→「WebUI」按钮）两个开关，`module.prop` 也没有第二个按钮的配置项。
    ⇒ 不用 WebView 的前提下，"新增按钮且不复用原按钮"只有**加伴生模块**一条路；
    用户最终选择**复用现有 `action.sh`**，所以不新增卡片、不新增产物。
  - WebUI 的 JS API 没有剪贴板接口；`cmd clipboard` 在本 ROM 不存在
    （`No shell command implementation.`），`dumpsys clipboard` 也是空 ⇒ 一键复制不做（用户已确认可放弃）。
  - `ksud module action <id>` 存在 ⇒ 控制台输出能在 adb 侧一比一复现，便于验证。
- [x] **写入侧限流**（新增 `module/bin/penlog.sh`）：条数（默认 400 行）+ 字节（128 KB）双上限；
  超限时保留**最近 N 行**并写一行裁剪记录。`charge-guard.sh` 覆盖为 200 行、
  `guard_note_engine.sh`（`/data/local/tmp/note_engine_guard.log`）200 行。
- [x] **硬约束（务必遵守）**：`service.sh` 用 `exec >>"$LOGFILE"` 持有 fd ⇒ 裁剪必须**同 inode 就地回写**，
  **绝不能 `mv` 后重建同名文件**（写会继续落进已 unlink 的旧 inode，表现为"日志停在裁剪那一刻"）。
  原 `trim_log_if_large` 是超 512 KB **整file清空**（现场全丢），现改为保留最近 400 行。
  本地已验证：裁剪后 inode 不变、后续写入继续落盘。
- [x] **读取侧限流**（`action.sh`）：pen-bridge.log 120 行 / charge-guard.log 60 行 /
  note_engine_guard.log 60 行 / Hook 日志（logcat tag `LenovoPenBridge`）200 行；
  新增 `sh action.sh log`（只输出日志段，快速入口）与 `sh action.sh clearlogs`（就地裁到 5 行）。
- [x] 踩坑：**本 ROM 的 logcat 不支持 `-t N` 与 `-s TAG` 同用** —— 两种顺序、root 下都是**空输出**，
  而单独用 `-t N`、单独用 `-s TAG` 都正常。Hook 日志因此改为
  `logcat -d -v time -s TAG | tail -n N`。
- [ ] 实机验收：点模块卡片「操作」按钮，确认日志段渲染正常且行数在预期范围内。

## P1.5 hook 不进 system_server 修复（2026-09-18 实机定位）
- [x] 现象：应用进程有 hook 日志（`WirelessSettings hooks installed` 等），但触觉/笔键/磁吸/输入门控全无效；`grep 'stylus hooks installed'` 恒为 0
- [x] 定位手段：KernelSU logcat 存档 `/data/adb/ksu/log/logcat.log`（跨开机保留，普通 `logcat -d` 已轮转）+ Vector `verbose_*.log` 的 `VectorConfigCache`/`VectorLegacyBridge` 行 + 对 `handleLoadPackage` 插桩
- [x] 根因：模块作用域写成 `android`。Vector 作用域**匹配**用伪包名 `system`，但框架**回调**仍以 `packageName="android"` 回调（故代码 `case "android"` 正确）
- [x] 修复：`scope.list` + `arrays.xml` 的 `android` → `system`；运行时 `vector-cli scope set ... system/0`；移除多余 `android/0`
- [x] 验证：重启后 `system_server stylus hooks installed` 出现，随后 uevent 桥/触觉/输入门控/状态同步全部生效；boot_completed 25s
- [x] 增强：`handleLoadPackage` 早退路径补 `skip <pkg> (gate=…)` 诊断日志（此前静默，是误判"框架没工作"的主因）

### P1.5 复发（2026-09-19）：改包名 = 换模块身份 → scope 又写成 `android`，全部自定义键失效
- [x] 现象：commit `f9fa079`（包名 `com.aclaniakea.lenovopenbridge` → `com.futureharmony.lenovopenbridge`）之后
      **长按 / 捏握 / 书写扩展 / 系统快捷全部无反应**（功能码 101–113 一个不响应）；
      而设置页里 `ipe_pencil_wb_click_long_press=101` / `_squeeze=102` / `_long_click_v2=108` / `_single_click=109` **都还在**
      → 写入侧正常，缺的是**消费端**
- [x] 判据（区分"动作执行失败"与"消费端不存在"）：广播探针 `am broadcast -a …RUN_ACTION --ei code 999`（无害空功能码）
      之后 logcat 连 `debug action refused` 都没有 → 系统侧接收器压根不存在
- [x] A/B 对照（**同机不同次开机**，一次改名前 / 一次改名后）：
  | 检查项 | 改名前开机 21:28（旧包名） | 现在 22:03 开机（新包名） |
  |---|---|---|
  | `handleLoadPackage pkg=android`（系统侧回调） | ✅ 1 次（pid 2291） | ❌ 0 次 |
  | `system_server stylus hooks installed` | ✅ 1 次 | ❌ 0 次 |
  | `PhoneWindowManager interceptKeyBeforeQueueing hooks=1` | ✅ | ❌ |
  | Vector modules log 中 system_server 的 `Loading legacy module` | ✅ 有 pid 2291 | ❌ 无 |
  | 应用侧 hook（ipemanager / 我的设备 / 便签 / 无线 / 截图 / 翻译） | ✅ | ✅ **全好（最易误判成代码 bug）** |
- [x] 根因：**改包名 = Vector 里换了一个全新模块身份**（新 mid=820），作用域为空；装机后重设时写成了 `android`。
      作用域**匹配**必须用伪包名 `system`（框架**回调**仍以 `packageName="android"` 回调，所以代码 `case "android"` 是对的）
      —— 与 09-18 那次（见上一节）**同一个坑**：改名把已修好的状态清零了
- [x] 「enabled ≠ 被注入」：`vector-cli modules ls` 显示 `com.futureharmony.lenovopenbridge … enabled`，
      与是否被注入 system_server **无关**。唯一硬判据仍是 `grep 'system_server stylus hooks installed'`
- [x] 排查路径（可复用，写进 `docs/install-vector-route.md` 第 6 节）：
  - `/data/adb/lspd/log/modules_*.log`（本次）+ `log.old/modules_*.log`（上次开机）→ 搜 `Loading legacy module` 与 system_server pid
  - `/data/adb/ksu/log/logcat.log`（跨开机存档）→ grep `handleLoadPackage pkg=android`、`system_server stylus hooks installed`
  - ⚠️ `adb logcat -d -b all` 此时**已轮转**，早期开机日志只能靠上面两处；`cli log cat` 也只保留注入后的行
- [x] 修复：`scope add com.futureharmony.lenovopenbridge system/0`（**外科式**，不动其它项）+ 重启
- [x] 附带发现：设备侧残留 `android/0`（历史遗留）。`scope set` 是**整表覆盖**，靠人手点/手敲必然复发
- [x] **根治（工具链，本次完成）** —— 单一真源 + 幂等自检，见 `scripts/pen_release.py`：
  - 新增 `scripts/pen_release.py`：产物的「最新」按 **版本** 排序（原先三处 `sorted(glob)[-1]` 是**字典序**，
    `v4.5.10` 会排在 `v4.5.4` 之前）；作用域从 `hook/source/resources/META-INF/xposed/scope.list`
    （APK 的声明源）派生，运行时表不再手敲；解析 adb 路径
  - `build.py` 新增**第 4 步作用域自检**：读运行时 scope 与 `scope.list` 比对 →
    缺 `system/0` 用 `scope add` 补齐 / 多余项用 `scope rm` 清掉 → **复读校验**；无设备自动跳过
    （`--no-device` / `--no-scope-fix` / `--require-device`）。另加断言：**module zip 内嵌的 Hook APK == 最新 APK**
  - `scripts/deploy_vector.sh`：adb 路径解析（原默认 `/opt/homebrew/bin/adb` **本机不存在**）；
    APK 版本地板 v4.5.0（拒绝 pre-rename 包）；安装前校验 APK 内声明的 scope 含 `system`；
    `scope set` 后**回读断言** `system/0` 存在，否则 fail-fast（不再"退出码 0 就当成功"）
  - `scripts/push_to_device.sh`：产物按版本解析（原硬编码 `PenBridge-Hook-tb522fu-v4.1.3.apk` —— **旧包名**，
    推过去装 = 装了个没人 `enable` 的包，正是本次故障的成因）
  - `module/tools/build_root.py`：内嵌 APK 同样按版本取（原来也是字典序）
  - Hook 版本 bump **v4.5.5**：`build_hook_source.py` 改为单点常量 `APK_VERSION`
    （文件名 / `versionName` / `versionCode` 全部派生），不再三处手改

## P1.4 卡死根因修复（2026-09-18 实机定位）
- [x] 现象：hook + 模块同开 → 卡开机动画，system_server 停在 PMS 扫描，`boot_completed` 永不为真
- [x] 根因：`SystemStylusHooks` 中 `SystemServer#startOtherServices`/`#run` 的 after-hook **无 try/catch**，`init()` 异常逃逸进 system_server 启动序列
- [x] 修复：回调全部包 `catch (Throwable)`；`PhoneWindowManager` 回调同样加保护
- [x] 二分验证：仅 KSU 模块 → 正常。⚠️ 注意：当时的"hook + 模块同开 → 正常"结论**无效**——彼时 hook 因作用域写成 `android` 压根没进 system_server（见 P1.5）。修复作用域后重新验证：hook 真正在 system_server 运行 + 模块同开 → `boot_completed` 25s，正常
- [x] 其余踩坑入档：`charge-guard.sh` 权限位漏配、密钥库口令丢失（重建 + `keys/tb522fu.pass`）、`lspd` 库归属 Vector

## P1.5 启动失败自保（2026-09-18 实现）
- [x] `post-fs-data.sh`：`app_process` 调用加 `timeout 20`（无 timeout 时回退后台+轮询），消除唯一会阻塞开机的无界调用
- [x] `customize.sh`：同类调用加超时，避免安装器被挂住；修正过时的 ui_print（不再声称"首次开机禁用 inkdye"）
- [x] boot guard：`post-fs-data.sh` 记账 → `service.sh` 确认 `boot_completed=1` 才清零 → 连续 >3 次失败自动生成 `disable`（计数文件 `/data/adb/tb522fu_pen_bridge.bootfail`）
- [x] `module/panic.sh`：一键恢复（enable inkdye / 杀进程 / tx=1 / 清标记 / 默认停用模块），支持 `--keep`
- [x] `uninstall.sh`：清理 boot-guard 计数与 `disable`，避免重装后立刻熔断
- [x] `docs/install-vector-route.md` 增「启动失败与救援」分层说明（模块自保 + KSU 安全模式 + ksud + Recovery）
- [ ] 实机验证：正常开机计数归零（`cat /data/adb/tb522fu_pen_bridge.bootfail` 应为空/0）— ✅ 2026-09-18 已验证为空
- [ ] 实机验证：人为制造 4 次失败开机 → 确认自动熔断 + inkdye 自动恢复

## P2.6 磁吸胶囊延迟修复（2026-09-18 实机定位 + 验证）
- [x] 现象：磁吸吸附后胶囊要等很久才弹（实测 10~21 秒），有时干脆不弹
- [x] 证据：`pen-bridge.log` 四次吸附全部一致 —— dock 边沿 `trusted=false`（`lenovo_pen_hardware_battery_valid=0`），随后成串 `magnetic capsule delayed: fresh battery sample unavailable`，第 14:45 那次等满 40 次才成功，另两次分别在 20/29 次后 **abandoned**
- [x] 三处成因：
  1. `monitor_hall_capsule` 在吸附边沿**主动**把 `lenovo_pen_hardware_battery_valid` 清 0（避免用上一轮缓存）
  2. `request_pen_capsule()` 硬卡 `valid==1` 才广播
  3. 本机内核侧**没有任何**新鲜电量源：`CPS_UEVENT` 写死为 pineapple 的 `i2c-2/2-0041`（本机不存在），`lenovo_penraw/uevent` 只有 `MAJOR/MINOR/DEVNAME` 无 `LEVEL` → 只能等厂家 BLE 栈首帧 GATT 样本
- [x] 修复 A（核心）：`request_pen_capsule()` 改用 `read_hardware_battery()`——新鲜优先、退化到最后一次真实采样；`valid!=1` 时只记一条日志，不再卡门
- [x] 修复 B：`resolve_cps_nodes()` 按 I2C 地址 `*-0041` 运行时解析 CPS 节点（`/sys/bus/i2c/devices/`），取代写死路径；新增 `read_cps_charging()` 从 `tx_status.cps_wls_en` 取充电真值（语义与 charge-guard.sh 一致）
- [x] 修复 C：`request_pen_capsule_when_ready` 加 worker 标记文件去重 —— 此前吸附边沿 hall 抖动会并存 2~3 个重试循环，日志出现成对重复行
- [x] 回归验证（真实笔，重启后）：4 次吸附延迟分别为 **1s / 0s / 0s / 1s**；其中 1s 那次正是 `trusted=false`（原 21 秒路径）；`delayed` 与 `abandoned` 零出现；成对重复行消失
- [x] 附带收益：`charging` 不再恒 0，改由 `cps_wls_en` 驱动（吸附时 `charging=1`）；`CPS uevent requested` 首次出现，证明路径解析生效
- [ ] 观察项：笔接近满电时驱动会脉冲 `cps_wls_en`（1↔0），`charging` 因此可能轻微抖动（`monitor_charging_cache` 已按变化才发布，实际约每 10s 最多一次）

## P0.7 手势功能对齐设备管理配置（v4.1.11，2026-09-18）

**背景**：v4.1.10 为了「先让它有反应」把 `click()` 的广播目标改成永远包含 `com.oplus.healthservice`，
结果 **4 个手势全部弹转盘**（用户实测「所有都是弹出转盘」），**丢掉了设备管理里配置的原有功能**。
用户明确要求恢复：`呼出调色盘` / `橡皮擦切换` 等。

**根因**：`com.oplus.healthservice` 的 `StylusPressReceiver` **完全忽略 `action` extra**，收到
`PENCIL_SINGLE_CLICK`/`PENCIL_DOUBLE_CLICK` 一律 `onStylusSingleDoubleClick()` → 解锁时弹
`CallRouletteService`（转盘）。所以「发到 healthservice」== 「无条件弹转盘」。

**本机实测的配置值**（`settings list global | grep ipe_pencil`）：
| 键 | 值 | 含义 |
|---|---|---|
| `ipe_pencil_single_click` | **3** | 呼出调色盘（show_color） |
| `ipe_pencil_double_click` | **1** | 橡皮擦切换（switch_eraser） |
| `ipe_pencil_long_click` | 0 | 无 |

枚举（反编译 `com.oplus.ipemanager` 的 `IPESettingManager` + `setting/fragment/p.java`）：
`1=橡皮擦切换 2=切回上一支笔 3=呼出调色盘`（**app 内**由 doodle 引擎
`MODE_TOGGLE_ERASER/MODE_TOGGLE_LAST_PEN/MODE_PICK_COLOR` 处理）、`4=打开转盘`（→ healthservice）、`0=无`。

**修复（v4.1.11 / versionCode 410011 / md5 `02ecc6bd093a84a76390b471a8f0692c`）**：
`SystemStylusHooks.click()` 恢复**按配置值条件路由**——
`i == 4` → 只发 `com.oplus.healthservice`（转盘）；
`i ∈ {1,2,3}` → 发 `{com.coloros.note, com.oplus.screenshot}`（app 内切笔模式，正是设备管理配置的功能）。
⚠️ 这条条件是**承重**的，源码里已写长注释，勿再改成「都发 healthservice」。

**待验证**：重启后用户做单击/双击，确认分别是「调色盘」和「橡皮擦」。
（`dumpsys package` 看不到 `com.coloros.note` 的 manifest 接收器 —— 该类接收器是 doodle 引擎
在画布激活时**动态注册**的，所以清单里没有属正常；`NoteToolkitHooks` 正是挂在
`com.oplusos.vfxsdk.doodleengine.toolkit.Toolkit` 的 `receiverSingleClick/receiverDoubleClick` 上。）

## P0.11 手写笔手势设置页注入扩展功能（v4.2.8，2026-09-19 完成并实机闭环）

**需求**：保留原厂 5 项（关闭 / 当前工具与橡皮擦切换 / 最近工具切换 / 显示颜色盘 / 手写笔轮盘），
在原厂列表下追加「扩展功能」分组，可选自定义动作（截图 / 通知栏 / 返回 / 主屏 / 最近任务 / 手电筒），
**全局单选**（原厂行与扩展行互斥），并追加一行「恢复原厂选项」。

**宿主**：`com.oplus.ipemanager` 的 `btadsorb.setting.activity.PencilGestureSettingActivity`
（`click_type` = `single_click` / `double_click` / `long_click_v2`，分别对应下滑 / 双击 / 上滑），
行控件是 `com.coui.appcompat.preference.COUIMarkPreference`。

**落库键（自建桥接键，不污染 OEM 键）**：`ipe_pencil_wb_click_<click_type>`，
取值 `101..106` = 扩展动作，`-1` = 未选扩展（走原厂键）。消费端在 `SystemStylusHooks`。

**踩过的 4 个坑（都是承重教训，勿回退）**：
1. `hookAll` 只匹配 `getDeclaredMethods()`。混淆后的 `PencilGestureSettingActivity` **没有** override
   `onCreate`/`onResume` → 钩子匹配 0 个方法、**静默失效**。必须挂框架基类 `android.app.Activity`
   再在回调里按类名过滤。
2. `androidx.preference.Preference` **没有** `setChecked`（在 `CheckBoxPreference`/`TwoStatePreference`
   层，dex 反编译 `COUIMarkPreference extends CheckBoxPreference` 证实）。在基类解析 → 第一次调用即
   `NoSuchMethodException` → 整段同步退出，表现为「扩展行可多选、原厂行取消不掉」。
   必须 `setRowChecked()` 沿**行自身**类层级解析，并调 `notifyChanged()` 触发重绘（否则只改数据不重画）。
3. `CheckBoxPreference.onClick` 是 **toggle**（`!isChecked()`）且在监听器**之后**执行 → 点已选中的行会
   「键仍选中、UI 却被取消」。必须在监听器里 `postDelayed(120ms)` 再断言同步一次。
4. OEM 行的 **key 与可见标签是错位的**（如 `item_color_picker` 实际对应「当前工具与橡皮擦切换」）。
   「恢复原厂选项」**必须按可见标题匹配**（关闭0/橡皮1/最近2/色盘3/轮盘4/随心圈5），不能按 key 猜。
   另：本机 `Settings.Global.putString(key, null)` 会写成字面量 `"null"`，删除语义改用写 `-1`。

**导航通路（自动化验证用）**：`ipemanager` 是插件化应用，PMS 里查不到那些 Activity，`am start` 解析不了。
可行路径：`su -c am start -a com.oplus.mydevices.ACTION_DEVICE_CARD_HOME_ACTIVITY`（设备中心）
→ 点手写笔卡片 → 下滑触控条。
**验证方法**：hook 内嵌 `gesture marks sync ... state=[key=checked]` 日志读模型状态 + `screencap` 目检；
`uiautomator` 的 `checked` 属性对 COUIMark 自绘控件**不可靠**，不能作为判据。

**状态**：选中 / 互斥 / 重击保持 / 恢复原厂勾回 / 落键 101 与 -1 全部实机通过。
**遗留**：物理下滑 → 截图动作的端到端（消费端已实现，待用户笔势实测确认）。

## P0.12 书写扩展功能包 + 手势×功能自由组合（v4.3.0–v4.3.9，2026-09-19；UI 链路已实机闭环，物理笔动作待测）

**需求**（用户 2026-09-19）：新增「撤销重做 / 手写浮窗便签 / 圈选翻译 / 圈选 OCR / 翻页」，
并让 **按键（手势槽）和功能项自由组合**。

**动作注册表**（`IpeManagerHooks.GESTURE_CUSTOM_CODES`，101..113 **连续**，label 索引 =
code-101，别破坏）：
| 码 | 功能 | 执行 |
|---|---|---|
| 107/108 | 撤销/重做 | system_server 注入 Ctrl+Z / Ctrl+Y（`injectCombo`：CTRL down → key down/up → CTRL up） |
| 109/110 | 翻页上/下 | **按前台 App 策略**（`PageTurnConfig`，v4.8.6）：水平模拟 / 垂直模拟 / KeyEvent 上下 / KeyEvent 左右；首次在某 App 触发时弹窗选一次并持久化到 `Settings.Global#lenovo_pen_pageturn_map` |
| 111 | 手写便签 | `HandwrittenNoteOverlay`：system_server 直接 addView 的 TYPE_APPLICATION_OVERLAY 全屏手写层（工具条：关闭/撤销/橡皮/清空/4 色/保存），保存走 `HookUtils.publishPng` → `PenShareProvider` → Pictures/PenBridge |
| 112/113 | 圈选识别/翻译 | `LassoSelectOverlay`：透明覆盖层框选 → `android.window.ScreenCapture`（反射，DisplayCaptureArgs.Builder.setSourceCrop）裁剪 → 保存 PNG + 剪贴板（图片）；翻译再弹分享/`ACTION_TRANSLATE`。**OCR 引擎未接**（`tryRecognize` 留 null），DeepThinker 探测未完成 |

**自由组合的两层含义**：
1. 原 3 个 OEM 手势页（下滑/双击/上滑）继续单选绑定 13 个功能中的任意一个（已有能力）。
2. **新开长按/捏握两个槽**（v4.3.5–v4.3.9 重构，用户要求：样式与原厂一致、不用自定义窗口）：
   弹窗面板「长按」「捏握」为独立行（样式复制上滑卡片：背景/内边距/字号/chevron，
   tag=`lenovo_panel_extra`，值 TextView tag=`lenovo_extra_value_<type>`）；点击
   **直接启动系统原生 `PencilGestureSettingActivity`（`click_type=long_press/squeeze`）**，
   页面内自动注入「书写扩展/系统快捷」双分类，单选样式与原厂一致——不再用自建 AlertDialog。
   OEM 内部其实支持这两个槽位（页面标题 OEM 自设，如「轻捏笔身」）。
   写入 `ipe_pencil_wb_click_long_press` / `_squeeze`。消费端 `SystemStylusHooks.extraGestureAction`：
   `>=100` 走 runCustomAction；`1..4` 复用 click() 的承重路由（4→healthservice，1..3→note/screenshot）；
   `0`=关闭；`-1`/`5`=随心圈 collect。

**v4.3.1 实机验证 + 修复（2026-09-19）**：
- 注入 UI 全量呈现：书写扩展 7 项（107-113）+ 系统快捷 6 项（101-106）+ 恢复原厂选项；单选正常
  （`gesture marks sync single_click selected=113`，原厂行全部 false）。
- **修复 veto 范围过宽（v4.3.1）**：原实现拦截页面上所有非 `item_wb_*` 行的 `setChecked(true)`，
  把「手写笔轮盘设置 → 显示工具名称」（`item_wheel_show_tool_name`，重开页面时框架合法恢复 ON）
  也拦成了 OFF。改为只 veto `GESTURE_STOCK_ROW_KEYS` 内的原厂单选行。
- **重开页面持久化验证**：退出→重进，顶部原厂行保持未选、桥接选择保留，无闪烁无 stale 勾选。
- **恢复原厂选项实机验证**：点击后 bridge=-1、全部扩展行 false、原厂「手写笔轮盘」重新选中。

**v4.3.2–v4.3.4 修复（2026-09-19）**：
- **PencilSettingActivity 摘要联动（v4.3.2/3）**：手写笔主页面（非弹窗）每行的 assignment TextView
  也用 `com.oplus.ipemanager:id/assignment` 渲染原厂值，桥接选择后不刷新。修复：面板 assignment
  重写 hook 同时覆盖 `PencilSettingActivity`（onResume 需额外 hook 具体类——**hookAll 只匹配
  declared methods，该 activity 重写了 onResume，基类钩子拦不到**，与 P0.11 同坑）；主页面
  pass 关闭 addExtraPanelRows（弹窗专用），`schedulePanelPass(root, ctx, allowExtraRows)`。
- **桥接键重启丢失（v4.3.4，根因钉死）**：ipemanager app 进程写的 `Settings.Global` 键在本 ROM
  **重启后被 provider 丢弃**（app 写入会话内可读回、重启后变 -1；root `settings put` 写入则保留）。
  修复：system_server 侧注册 `registerBridgeSettingsWriter`（广播
  `com.aclaniakea.lenovopenbridge.WRITE_GESTURE_KEY`，校验 key 前缀 `ipe_pencil_wb_click_`），
  app 侧全部 3 处写入（行监听 / 原厂行点击清除 / 长按捏握对话框）改走
  `persistGestureKey()`：本地 putInt（会话内即时读回）+ 广播镜像（system uid 写入持久）。
  实机验证：广播 → `bridge settings persisted ipe_pencil_wb_click_single_click=113` → 落库。

**注意**：
- 设置页注入为**双分类**：「书写扩展」(107-113) + 「系统快捷」(101-106)，reset 行在系统组末尾。
- 同一文件多个 Edit **并行调用会互相覆盖**（丢更新），必须顺序编辑 —— 本次又踩一次（3 个 Edit 并行，
  2 个静默丢失），已全部改为逐个提交。
- `HandwrittenNoteOverlay.toggle` 复用同一手势做开关（开着时再触发即关闭），防止笔被"锁"在便签层。
- **弹窗面板行卡片在 RecyclerView 内，禁止对其父容器 addView**（v4.3.5 闪退根因：
  `ViewHolder.shouldIgnore()` NPE）。v4.3.6+ 方案：把上滑卡片改为 VERTICAL LinearLayout，
  原内容包成第一个 sub-row，长按/捏握作为同款 sub-row 追加（sub-row 带卡片背景+间隙 margin，
  视觉上仍是独立行）。幂等：`findViewWithTag("lenovo_panel_extra")` 命中时只刷新值标签。
- `readGesturePageType` 白名单必须含 `long_press`/`squeeze`（v4.3.8 修），否则长按/捏握页注入静默失败；
  另有 fallback：从 activity intent 的 `click_type` 读（`gesturePageTypes` map）。

**v4.3.5–v4.3.9 实机验证 + 修复（2026-09-19 下午）**：
- 用户报障：弹窗点上滑触控条打开的是「原始样式的安卓切换」（自建 AlertDialog），且长按/捏握行文字
  与上滑行重叠。根因：v4.3.4 的行注入把行 addView 进了 RecyclerView → 闪退 + 位置错乱。
- v4.3.5：RecyclerView addView → `ViewHolder.shouldIgnore()` NPE 闪退（点击笔设置即崩）。
- v4.3.6/7：改为卡片纵向堆叠方案（上滑卡片 VERTICAL 化），弹窗渲染正常、无重叠、无闪退；
  长按/捏握点击改为启动原生 `PencilGestureSettingActivity`（弃用自建 AlertDialog）。
- v4.3.8：长按页注入静默失败 → `readGesturePageType` 白名单加 `long_press`/`squeeze`
  + intent fallback。实机：长按页出现书写扩展/系统快捷双分类，选「撤销」→
  `ipe_pencil_wb_click_long_press=107` 经 system_server 落库，**重启后保留**。
- v4.3.9：弹窗摘要不刷新 → 幂等早退分支改为刷新 `lenovo_extra_value_<type>` 标签的 TextView。
  实机：长按→撤销、捏握→通知栏 联动正确。
- 13:33 的 3 次 FATAL 是 `com.oplus.gesture` 系统应用开机时序问题（凭据加密存储未解锁），
  与本模块无关。

**v4.4.0 实测四连修（2026-09-19 14:0x 用户报障）**：
1. 长按/捏握/上滑堆叠行缺浅灰卡片层 → `addExtraPanelRows` 样式快照改为向上爬到第一个
   持有背景的祖先（止步 RecyclerView）；全链无背景则合成 `GradientDrawable(0xFFF5F6F7, r=24)`
   兜底，日志记录 bgOwner。
2. 点上滑打开的是捏握页（logcat 实证 14:01:09 `panel extra squeeze`）→ 双保险：
   a) `origRow` 加自有监听，点击时按**可见标题**实时解析槽位（`gestureTypeOfRow`，
   防适配器复用错位），子视图消费点击阻断冒泡；b) onResume 每次从当前 intent 刷新
   `gesturePageTypes`（防 activity 复用残留旧槽位）。
3. 圈选翻译/识别报 "no display token"（Android 16 上 SurfaceControl token 静态方法全返 null，
   logcat 5 次复现）→ token 链升级：SurfaceControl → @hide `Display.getAddress` →
   @hide `DisplayControl`；仍失败则 `ScreenCapture.captureDisplayEx(displayId, args)`
   （token 在 SurfaceFlinger 侧解析），每级失败原因入日志。
4. 手写便签工具栏被状态栏盖住（`FLAG_LAYOUT_IN_SCREEN`+FILL 全屏，y=0 在状态栏下，
   关闭/撤销点不到）→ root 顶部按 `status_bar_height` 加 padding。
- 安装 v4.4.0 后 `killall system_server` 软重启生效（`am crash android` 无效，pid 不变）。
- **待用户解锁实测**：灰层样式、上滑点击、圈选翻译落图、便签关闭按钮。

**v4.4.5–4.4.7 面板样式对齐原厂 + 圈选截屏定案（2026-09-19 16:0x，UI 已实机像素级比对）**：

面板（`addExtraPanelRows` 重写，**已实测闭环**）：
- **真实结构**（uiautomator + 自写 PNG 解码逐像素测得）：RecyclerView 的**一个 item == 一行**，
  **item 自身**携带可见 #353535 卡片（宽 1064–2776 / 高 127–132，padding 40），首个子视图是
  **透明内容层**（外边距 0），右侧箭头是 `AppCompatImageView`（32×63，StateListDrawable），
  行间为 **1px #5D5D5D**、按内容宽内缩的分隔线。
- v4.4.2 的错误：克隆**内容层**几何（40px 外边距）+ 把卡片 drawable 用**模块主题**重刷 →
  得到更窄、更亮（#4A4A4A 而非 #353535）的盒子。
- v4.4.5 起：注入行 = item 的**透明**子视图（同 40px 内缩），卡片层自然长高覆盖；
  箭头**克隆原厂 ImageView 的 drawable**（`findStockArrow`，失败才自绘 `ChevronView`）；
  分隔线颜色/厚度从原厂分隔线子视图 `ColorDrawable` 读出。
- v4.4.7：行高不再取本 item（注入后 132），改为**爬升到 RecyclerView 取同层原生行**最小值
  （127）→ 实测行距 127/128，与原厂 127 一致。
- 实测比对（3840×2560 截图逐像素）：底色 #353535 ✓、左边界 1064 ✓、右边界 2776 ✓、
  分隔线 1px #5D5D5D ✓、箭头在位 ✓、行高 128 vs 127 ✓。

圈选（`LassoSelectOverlay`，**待重启 system_server 实测**）：
- `/system/bin/screencap` **不可用**：system_server 被 SELinux 拒绝 exec（`error=13`），
  同一策略也将废掉 `/system/bin/cp`（保存路径已一并改为进程内 I/O）。
- `SurfaceControl.getInternalDisplayToken/getPhysicalDisplayIds` 在本 ROM **已被移除**
  （NoSuchMethodException）；token 实际来自 @hide `Display.getAddress()`。
- **关键**：本 ROM **没有顶层 `android.window.DisplayCaptureArgs`**（Android 15+ 已并入
  `ScreenCapture` 的**嵌套类**）→ 用 `ScreenCapture$DisplayCaptureArgs$Builder(IBinder)`
  + `setSourceCrop` → `ScreenCapture.captureDisplay(DisplayCaptureArgs)`
  → `ScreenshotHardwareBuffer.asBitmap()`（签名从 `framework.jar` 的 dex 反解确认，
  脚本 `/tmp/dexdump.py`）。
- **v4.4.8 token 修正**（v4.4.5 实测报 `argument 1 has type android.os.IBinder, got
  android.view.DisplayAddress$Physical`）：`Display.getAddress()` **不是** IBinder。
  全量 dex 扫描结论：本 ROM `SurfaceControl` **没有任何 token getter**
  （getInternalDisplayToken / getPhysicalDisplayIds / getPhysicalDisplayToken 全不存在），
  `android.view.DisplayControl` 也不存在；**唯一可用**的是 OPPO 自己的
  `android.hardware.display.OplusDisplayManager.getPhysicalDisplayToken(long)`
  （AIDL `IOplusDisplayManager` 同签名）→ 用 `DisplayAddress$Physical.getPhysicalDisplayId()`
  取 id 再换 token，另加 id 0..3 暴力兜底。
- 抓图前 `Thread.sleep(250)` 等浮层真正下屏，避免把选区框/蒙层拍进去。

**v4.4.9 → v4.5.0 保存路径定案：FUSE 墙 + 委托应用进程代写（2026-09-19 16:2x，应用侧链路已自证）**：

- **`/system/bin/{screencap,cp}` 与 `/storage/emulated/0` 全部被判死刑，但原因此前判错了。**
  dmesg 的 avc 是决定性证据：
  ```
  avc: denied { read write } for comm="binder:23732_2"
    path="/mnt/user/0/emulated/0/Pictures/PenBridge/.pending-*.png"
    dev="fuse" scontext=u:r:system_server:s0
    tcontext=u:object_r:fuse:s0 tclass=file permissive=0
  ```
  → MediaStore 行**建得出来**（`.pending-*` 文件由 MediaProvider uid 10182 落地），
  但随后的「打开流」走 FUSE 视图，而 **system_server 域被 SELinux 禁止读写 fuse 文件**。
  所以这不是"provider 不可用/权限没配"，而是**架构性不可达**：
  system_server 侧 MediaStore 与 `/storage/emulated/0` 永久不可用，任何配置都救不回来。
  （副产物：`Pictures/PenBridge` 下留了 11 个 0 字节 `.pending-*` 垃圾，已清理。）
- 进程归属更正：圈选/便签浮层跑在 **system_server（uid 1000, `u:r:system_server:s0`）**，
  不是 `com.oplus.ipemanager`（那个进程只跑笔的 GATT 侧）。
  system_server 的补充组**含 1023(`media_rw`)**，所以 `/data/media/0/...` 裸目录在 **DAC** 上可写；
  但该目录**模块自己的 app uid 读不到**，拿不到可分享的 Uri，因此不能作为主路径。
- **v4.5.0 修法：写盘这件事交给模块自己的进程做。** 新增 `PenShareProvider`
  （authority `com.aclaniakea.lenovopenbridge.share`，exported + grantUriPermissions）：
  system_server 用 `ContentResolver.call()` 把 PNG 按 **192 KiB 分块**过 binder
  （远低于 1 MiB 事务上限），应用进程（uid 10406，**有自己合法的 FUSE 挂载**）执行
  MediaStore insert + `openOutputStream` + `IS_PENDING=0`，回传
  `content://media/external/images/media/N`；失败则退回自身 cache 并由 `openFile()` 供 Uri；
  两者都失败才退回裸目录 `/data/media/0/Pictures/PenBridge` + media scan。
- **同时修掉一个把整条链路废掉的既有缺陷**：`LassoSelectOverlay.finish()` 原先在
  `uri == null` 时**直接 return**，于是保存一失败，**OCR / 剪贴板 / 分享全都不会执行**。
  现在保存失败也继续走后面的 OCR 与分享分支，只是提示语不同。
- **另修**：`stagePng()` 原先直接 `bmp.compress()`，而 ScreenCapture 返回的是
  `Config.HARDWARE` 位图，`compress()` 读不了 → 统一加 HARDWARE→ARGB_8888 转换
  （`HookUtils.toPngBytes()` / `stagePng()` 都处理）。
- **自证手段（不必占用用户时间）**：provider 的 `call()` 放开 shell(2000)，
  于是可用 CLI 直接验证**生产代码路径**：
  ```
  adb shell content call --uri content://com.aclaniakea.lenovopenbridge.share --method selftest --arg x.png
  → Bundle[{ok=true, uri=content://media/external/images/media/1398, where=mediastore}]
  ```
  并已核对：文件 66 B、魔数 `89 50 4E 47`、`relative_path=Pictures/PenBridge/`、
  `is_pending=0`、`owner_package_name=com.aclaniakea.lenovopenbridge` ⇒ **应用侧写盘链路打通**。
  （provider 另接受 `b64` 载荷别名，因为 `content call` CLI 无法传 `byte[]`。）
- 工具链坑：`build_hook_source.py` 里 aapt2 link 的 `--version-code/--version-name`
  **不生效**，真实版本号由 `AndroidManifest.xml` 决定；脚本内那两个值已一并对齐为 450000/4.5.0。
- 命名坑：模块**包名**是 `com.aclaniakea.lenovopenbridge`（uid 10406），
  `com.aclaniakea.colorosporttuning` 只是 Java **包名**，别拿它查 `dumpsys package`。
- 重载手段：用户已授**永久** shell root → `su -c 'killall system_server'` 软重启有效
  （`uptime` 不清零，仅重启框架；实测注入日志 `system_server stylus hooks installed` 正常）。

**待办**：
- [ ] **v4.5.0 圈选翻译实测**（system_server 已软重启装载新钩子）：期望弹出分享面板，
      图片进剪贴板，文件落到相册 `Pictures/PenBridge`
- [ ] 手写便签保存（同一个 `publishPng`）一并复测
- [ ] **物理笔动作验证**（笔已重连，弹窗摘要联动正常）：实际触发各手势 → 撤销/重做/翻页/便签/圈选
- [ ] 长按/捏握物理手势触发 → extraGestureAction 路由复测（bridge=107 已验证重启存续）
- [ ] 捏握行摘要显示「通知栏」为历史遗留值（旧对话框写入 102），如需可重置
- [ ] Ctrl+Z 在便签 doodle 引擎是否生效待测（不生效则改 hook note 应用内部 undo）
- [ ] DeepThinker / ROM OCR 服务探测 → 接入 `LassoSelectOverlay.tryRecognize`
- [ ] 手写便签的压感宽度、防误触（palm rejection）体验调优
- [ ] PencilSettingActivity 摘要联动需笔连接状态下复验（未连接时页面隐藏手势行）

## P0.13 圈选翻译黑屏：App 走错区域端点（v4.5.3，2026-09-19 完成定位 + 实现）

**现象**：不是截图黑 —— `pen_translate_*.png` 逐像素统计正常（252×181、`uniq=1050`、
平均亮度 62.6）。`TranslatePhotoResultActivity` 起来了、图也 FUSE 打开了，但页面全黑。

**根因**：`com.coloros.translate` 把服务端打到已经退役的域名上。
```
b2/a.k()  SystemProperties "persist.sys.oplus.region"（本机 = CN）
        ↓ utils/x.b()  assets/country_region_mapping.json（{"cn":["CN","OC"],…}）
        ↓ b2/a.l()   switch {cn, us, in, eu}  DEFAULT = "sg"
→ https://aitool-cuiocr-sg.heytapmobi.com/aiendpoints/…   ← DNS 已 NXDOMAIN
```
`b2/a.l()` 兜底分支是 `"sg"`；本机上游没取到值 → 一路落到 sg。
（对照实验：把 `persist.sys.oplus.region` 改成 ZZ 重启 App，结果**依然** sg，
说明不是属性值的问题，是取值链本身没走通。）

**修复**：新增 `TranslateRegionHooks`（作用域只含 `com.coloros.translate`）双保险：
1. 钉住区域：hook `b2/a.l(String,boolean)` → `setResult("cn")`；
   hook `DomainBuilder#getToolboxHostNameByRegion` 把入参改成 `"cn"`，让 App 自选 CN 模板；
2. 兜底改写：hook `okhttp3.Request$Builder#url(...)`，最终 URL 里 `-sg.heytapmobi.com`
   → `-cn.`（App 每版重混淆，这层保证即使 1 全部失效链路仍在）。
目标全部懒加载 + 吞异常，符号改名不会拖垮该进程。**作用域 8 → 9 项**
（`META-INF/xposed/scope.list`、`res/values/arrays.xml`、运行时 `vector-cli scope set`）。

**别再走的弯路（已实证）**：
- ❌ `/system/etc/hosts` 或 DNS 把 sg 域名指到 CN 服务器：证书是通配 `*.heytapmobi.com`，
  TLS 能过（HTTP 404 而非握手失败），但**网关按 HTTP Host 头路由** —— 同一条 POST，
  Host=SG 返 404、Host=CN 返 200。为此曾装过 `tb522fu_translate_hosts` 模块，
  已卸载，`module/system/etc/hosts` 也已删除。
- ❌ 改 `persist.sys.oplus.region`：本来就是 CN，改了不影响。
- ⚠️ 验证必须**解锁进桌面**：该 App 只 partially direct-boot-aware，锁屏时 PMS 会过滤
  它的组件，`am start -n` 报 "Activity class … does not exist"（连 `-n` 显式组件都解析不到），
  adb 侧无法自解锁跑通。

**验证方式**：`settings put global lenovo_pen_debug_actions 1` 后
`am broadcast …--ei code 114`（免画框：直接截固定区域 + 交给翻译），
看 `adb logcat | grep -i aitool-cuiocr-cn` 是否出现 CN 主机、App 是否拿到 200。

**待办**：
- [ ] 解锁后实测：code 114 免交互路径 + 真笔圈选（“设计 Davidson” 到 CN 端点并出结果）
- [ ] CN 端点是否需要 HeyTap 账号登录（目前已知匿名 POST 可返 200，未确认全流程）


## P0.8 便签手写笔记闪退（native，2026-09-18 完成根因定位，**非本模块引起**）

**现象**：`com.coloros.note` 打开手写笔记后进程崩溃；`/data/tombstones/tombstone_15..23` 共 **9 个**
签名**完全一致**（确定性，非竞态）。

**崩溃点（已逐字节还原）**：
```
pc = 0x0  ← blr 到一个 NULL 函数指针；x0 = 0
#01 EglContext3::eglInit()+76            (libSuniaEngine.so，BuildId 1fa3bc13…)
#02 EglContext3::EglContext3(EglContext3*, bool)+68
#03 ToolFactory::ToolFactory()+156
#05 GroupFactoryManager::createFactory()  #06 CanvasManager::CanvasThreadHandlerFunc
#07 ThreadHandler::ThreadFunc             线程名 DefaultDispatch
```
偏移换算：`.text` 的 paddr/vaddr 差 `0x4000` → `pc 0x697cbc` 对应文件偏移 `0x693cbc`。

**完整调用链（r2 反汇编 + 重定位解析得到）**：
```c
void EglContext3::eglInit() {                     // @0x697c70
    EglInit::makeTempEglContext();                // 建临时 EGL 上下文
    glewExperimental = GL_TRUE;
    glewInit();                                   // @0x828bcc (48B)
    EglInit::clearTempEglContext();
    dpy = __eglewGetDisplay(EGL_DEFAULT_DISPLAY); // @0x697cbc  blr → NULL → 崩
}
```
- 崩溃指令 `0x697cbc: blr x8`，`x8 = [[GOT 0xc2a3a8]]`；该 GOT 槽的重定位符号 = **`__eglewGetDisplay`**。
- `glewInit()` 实现：
  ```c
  r = glewContextInit();                 // 取 eglGetProcAddress("glGetString") 后调 glGetString(GL_VERSION)
  if (r != 0) return r;                  // ← 无 current 上下文 → 返回 GLEW_ERROR_NO_GL_VERSION
  dpy = eglGetProcAddress("eglGetCurrentDisplay")();
  return eglewInit(dpy);                 // 只有走到这里才填 __eglew* 全套指针
  ```
- `eglewInit(dpy)` 开头（@0x81d73c）：`eglInitialize/eglQueryString` 经 `eglGetProcAddress` 取到后，
  **`if (eglInitialize(dpy,&maj,&min) != 1) return;`** —— 若 `dpy` 是 `EGL_NO_DISPLAY`（= 本线程无 current 上下文）
  则直接返回，**`__eglewGetDisplay` 保持 NULL**。
- `EglInit::makeTempEglContext()`（@0x69878c）里 `eglCreateContext` 与
  **`eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)` 的返回值都被丢弃**；
  库内只有 `eglGetDisplay/eglInitialize/eglChooseConfig` 三条失败日志字符串，**没有 createContext/makeCurrent 的**。
  ⇒ **只要这两步任一失败，引擎静默地没有 current 上下文，紧接着就空指针崩溃。**

**排除项（都实测过）**：
- ❌ 本模块的 Java hook：把 `com.coloros.note` 从 Vector scope 移除（免重启生效）后**仍崩**。
- ❌ 本模块禁用 `com.inkdye.lenovopentocoloros`：`pm enable` 后**仍崩**。
- ❌ 本模块写属性：全仓无 `resetprop`；`grep Settings.*put*` 只写笔相关键。
- ❌ 应用更新：`com.coloros.note` 16.7.2（`lastUpdateTime=2026-09-12`）与本机 `lastUpdateTime=1970` 的
  ROM 自带版 `/my_stock/del-app/OppoNote2/OppoNote2.apk`，**`glewInit` 与 `eglInit` 崩溃点代码完全相同**，
  两版 `makeTempEglContext` 也都是「不检查 createContext/makeCurrent」→ **回滚应用不能修**。
- ❌ `ro.hardware.chipname`（日志里 `Access denied finding property "ro.hardware.chipname"`）：
  该串只出现在 `/system/lib64/libonnxruntime.so`、`/vendor/lib64/libtensorflowlite_c.so`、
  `/odm/lib64/libancbase_rt_fusion.so`（手写**识别模型**加载器），属**无害回退的红鲱鱼**，与 EGL 无关。
- ❌ 可更新 GPU 驱动：`ro.gfx.driver.1=com.qualcomm.qti.gpudrivers.sun.api35` 是 `vendor/app` 系统应用
  （从未更新），且 `dumpsys package com.coloros.note` 无 `gpuDriverPaths` → 便签**没有** opt-in。

**设备侧实测（本仓库外的临时探针，`app_process` + `android.opengl.EGL14`）**：复刻
`makeTempEglContext` 的完整序列（`eglGetDisplay → eglInitialize → eglChooseConfig(与引擎同一组 attribs) →
eglCreateContext(ES3.0) → eglMakeCurrent(surfaceless) → glGetString`）**在本机全部成功**：
```
1 eglGetDisplay      -> ok        err=0x3000
2 eglInitialize      -> true      EGL 1.5 Android META-EGL
3 eglChooseConfig    -> true n=1  (RGBA8888 / ES2|ES3 / CONFORMANT=ES3 / PBUFFER|WINDOW)
4 eglCreateContext   -> ok
5 eglMakeCurrent(no surface) -> true
6 glGetString(GL_VERSION) -> OpenGL ES 3.2 V@0800.72, Adreno 830
```
驱动能力也齐（`dumpsys SurfaceFlinger`）：**`EGL_KHR_surfaceless_context` 与 `GL_OES_surfaceless_context` 均在**。
⇒ **设备本身完全支持这条路径**，失败只发生在便签进程内部 ⇒ 需**进程内**探针才能定位最后一步。

**下一步（二选一，需用户决定）**：
1. **进程内 EGL 探针（推荐，改动小）**：在 `NoteToolkitHooks` 加一个一次性 `EglDiag`，用
   `persist.lenovo.penbridge.egl_diag=1` 开关，在 `com.coloros.note` 里跑同一组 EGL14 调用并把
   每一步结果写 `/data/local/tmp/note_egl_diag.txt`。若进程内**成功** ⇒ 是 OPPO 应用/引擎自身问题
   （外部不可修，只能换便签或打 .so 补丁）；若进程内**失败** ⇒ 是进程环境问题，接着做第 2 步。
   → **[已实现，v4.1.12]** 见下方「进程内 EGL 探针 v4.1.12」。
2. **框架 A/B**：`touch /data/adb/modules/{zygisk_vector,rezygisk,hma_oss_zygisk}/disable` 后重启，
   再试手写笔记。用于排除「某个 Zygisk/Xposed 框架注入便签进程破坏 native GL 初始化」。
   ⚠️ 会同时停掉笔桥（重启后记得删掉这些 `disable` 文件再重启一次）。

**可能的修法（若确认是引擎自身）**：给 `/data/app/.../com.coloros.note-.../lib/arm64/libSuniaEngine.so`
打补丁（root 可直接覆写该文件，签名校验只针对 APK 安装）——把 `eglInit` 里的
`blr x8`（`0x697cbc`）换成直接 `bl <PLT eglGetDisplay @0xb90c20>`，并让 `glewInit`
无条件走 `eglewInit`（`0x828bd8` 的 `cbz w0` 前插 `mov w0, wzr`）。**风险高、易被应用更新覆盖**，非首选。

### 进程内 EGL 探针 v4.1.12（2026-09-18 实现）

新增 `hook/source/sources/.../NoteEglDiag.java`（一次性、带开关、默认关闭），由
`NoteToolkitHooks` 在两处相位调用：`Application.onCreate` / `MyApplication.onCreate`（相位
`app-oncreate:*`）与 `Toolkit.onAttachedToWindow` / `onResume$paint_intermediate_release`
（相位 `toolkit:*`）。

- **开关**：`settings put global lenovo_penbridge_egl_diag 1`（也接受 `persist.lenovo.penbridge.egl_diag=1`）。
  仅当包名 == `com.coloros.note` 时才生效，避免扰动 `com.oplus.screenshot`。每相位每进程只跑一次。
- **跑什么**：先记录「探针前已 current 的 EGL」状态，再分别在**调用线程**与**另起 worker 线程**上复刻
  `eglGetDisplay → eglInitialize → eglQueryString → eglChooseConfig(PBUFFER_BIT/ES2) →
  eglCreateContext(ES3) → eglMakeCurrent(双 EGL_NO_SURFACE) → glGetString(GL_VERSION)`，逐步骤记录
  `eglGetError()`。分两线程是刻意的——**崩溃发生在非主线程**（`DefaultDispatch`），只跑主线程答不了问题。
- **附带环境取证**：`/proc/self/maps` 里 libEGL/libGLESv2/libglew/libSuniaEngine/libadreno 等映射行、
  `nativeLibraryDir` 列表、dataDir、ABI、fingerprint。**若发现便签自带 `libEGL.so` 遮蔽系统库即为铁证**。
- **落盘**：主 `/data/local/tmp/note_egl_diag.txt`（需 root 预先 `touch` 并 `chmod 666`；SELinux
  `Enforcing` 下 app 写 `shell_data_file` 很可能被拒），镜像 `files/note_egl_diag.txt`
  （`su -c cat /data/data/com.coloros.note/files/note_egl_diag.txt`，一定可写）。
- **判读**：进程内成功 ⇒ OPPO 引擎自身问题（外部不可修）；失败 ⇒ 进程环境问题，转下一步 ①/②。
- 本次探针**不与 EGL 生命周期冲突**：不调用 `eglTerminate`，只销毁自己建的 context。

### 进程内探针**实测结论（2026-09-18，决定性）**：进程内 EGL **完全正常** ⇒ **OPPO 引擎自身问题**

v4.1.12 部署 + 重启 + `cmd activity start-activity -n com.coloros.note/com.nearme.note.main.MainActivity`
起进程后（注：`am start -n` 对该包一律报 "Activity class does not exist"，但
`cmd activity start-activity -n` 与 `monkey -p` 均可正常拉起），探针落盘
`/data/data/com.coloros.note/files/note_egl_diag.txt`（24 KB）。
`/data/local/tmp/...` 那条路**实测 EACCES**（SELinux Enforcing 下 app 写 `shell_data_file` 被拒），
镜像路径按设计生效。

**4 次进程启动 × 2 条线程（主线程 + 另起 worker）= 8/8 全部成功**，逐项一致：
```
eglGetDisplay(DEFAULT)      -> ok        err=EGL_SUCCESS
eglInitialize               -> true v1.5  1.5 Android META-EGL
EGL_KHR_surfaceless_context -> true
eglChooseConfig             -> true n=4
eglCreateContext(ES3)       -> ok
eglMakeCurrent(surfaceless) -> true      err=EGL_SUCCESS
glGetString(GL_VERSION)     -> OpenGL ES 3.2 V@0800.72 (GIT@3967b80d3b, Ica4ce9bea6, 1770914544) (Date:02/12/26)
glGetString(GL_VENDOR)      -> Qualcomm
glGetString(GL_RENDERER)    -> Adreno (TM) 830
glGetError                  -> 0x0
```
覆盖的进程：`com.coloros.note`（主进程，2 次）与 `com.coloros.note:tbl_privileged_process0`
（2 次）—— **两个进程都是 8/8 成功**。

**环境取证（排除掉所有「环境」解释）**：
- `nativeLibraryDir` 里只有 `libsuniabase.so` + `libSuniaEngine.so` 是与图形相关的（共 31 个条目），
  **没有任何自带的 `libEGL.so` / `libGLESv2.so` / GLEW** ⇒ 不存在「便签自带 libEGL 遮蔽系统库」。
- `/proc/self/maps` 里 EGL/GLES 全部来自系统：`/system/lib64/libEGL.so`、
  `/system/lib64/libGLESv2.so`、`/system_ext/lib64/libvulkanextimpl.so`、
  `/vendor/lib64/libadreno_utils.so`、`/system/lib64/libegl_flags.so`、`libui.so`、`libvulkan.so`。
- 探针**运行前**该线程就是干净的 `EGL_NO_DISPLAY / EGL_NO_CONTEXT / EGL_NO_SURFACE`
  （没有「卡住的 current context」「别的 display」这类脏状态）。
- 主线程与后台线程**结果完全相同** ⇒ 不是线程亲和性 / 非主线程限制问题。

⇒ 按既定判读标准：**进程内成功 ⇒ 是 OPPO 应用/引擎自身问题**。
与 P0.8 前面的逐字节分析（`glewInit()` 在有 current context 之前被调用 ⇒ `glewContextInit()` 因
`glGetString(GL_VERSION)` 拿不到版本而返回 `GLEW_ERROR_NO_GL_VERSION` ⇒ `__eglewGetDisplay` 保持 NULL；
而 `makeTempEglContext()` 又把 `eglCreateContext`/`eglMakeCurrent` 的返回值丢掉 ⇒ 失败无声）
**互相印证**：**同一进程、同一线程、同一组调用我们能跑通，引擎自己跑不通 ⇒ 是引擎调用时序/错误处理的问题。**

**因此外部可做的只剩两条**（与前面判断一致）：
1. 换应用（不用 `com.coloros.note` 手写）；
2. 给 `libSuniaEngine.so` 打补丁（见上文「可能的修法」），**风险高、且会被应用更新覆盖**。

**开关现状**：`settings put global lenovo_penbridge_egl_diag 0` —— 已关闭（默认关，避免每次便签进程
启动都写 24 KB）。要再采集（例如抓 `toolkit:*` 相位）：`settings put global lenovo_penbridge_egl_diag 1`，
复现后 `su -c cat /data/data/com.coloros.note/files/note_egl_diag.txt`。
⚠️ 注意 `toolkit:onAttachedToWindow` / `toolkit:onResume$paint_intermediate_release` 两个相位在本次
**没有触发**（只开了便签列表页，没进手写画布），所以「画布阶段是否也正常」仍未被直接观测；
但既然同进程同时刻连 worker 线程都能成功，进一步采集的边际价值很低。

### 决定性加强证据：**同 PID 先通过探针、随后就崩在引擎自己那行**（2026-09-18 18:44–18:45）

原以为「只开了列表页所以引擎没跑到崩点」，实际不然 —— **便签启动后几十秒内自己就崩了**，
而且崩的正是那几个刚跑完探针的进程。`/data/tombstones` 里新出现了两条（重启后写入）：

```
tombstone_25  18:44:14  pid 12039  >>> com.coloros.note <<<
tombstone_26  18:45:52  pid 19643  >>> com.coloros.note <<<
 #00 pc 0x0 <unknown>
 #01 pc 0x697cbc  libSuniaEngine.so (EglContext3::eglInit()+76)     <-- 与之前 9 条**同一偏移**
 #02 pc 0x698024  libSuniaEngine.so (EglContext3::EglContext3(EglContext3*, bool)+68)
```

对照探针日志里的同一个 PID：

| pid | 探针（相位 `app-oncreate`） | 探针结果 | 之后的结局 |
|---|---|---|---|
| **12039** | 18:44:11.737 | 主线程 ✅ + worker ✅ → `GL_VERSION = OpenGL ES 3.2 ... Adreno 830` | 18:44:14 崩在 `EglContext3::eglInit()+76` |
| **19643** | 18:44:22.386 | 主线程 ✅ + worker ✅ → 同上 | 18:45:52 崩在同一偏移 |
| 17286 / 20775 | 18:44:12 / 18:44:23 | 同样 ✅ | （`:tbl_privileged_process0`，未见 tombstone） |

**这是本案最硬的一条证据**：在**同一个进程**里，探针能在 `t=0` 与 `t=+11s` 把
`eglGetDisplay→eglInitialize→eglChooseConfig→eglCreateContext(ES3)→eglMakeCurrent(surfaceless)→
glGetString(GL_VERSION)` 全部跑通（主线程与后台线程都行），**而引擎自己在几十秒后崩在
`blr __eglewGetDisplay`（NULL）**。⇒ 排除「进程环境」「线程亲和性」「驱动能力」「库被遮蔽」
「便签自带 libEGL」等一切环境解释，**只能是 `libSuniaEngine.so` 自己的调用时序 + 丢弃返回值的错误处理**。

**副作用观察**：便签现在**自己就会崩**（不需要用户手写、进列表页几十秒即崩），
所以在这台机器上 `com.coloros.note` 目前基本不可用 —— 与用户的主观感受一致。


## P0.9 `vendor.oplus.hardware.urcc-service` 持续崩溃（2026-09-18 完成根因定位，移植缺件）

**现象**：`/odm/bin/hw/vendor.oplus.hardware.urcc-service` 反复崩、反复被 init 拉起。
今日 `/data/tombstones` 共 **32 条**：urcc **24 条**、`com.coloros.note` 8 条（见 P0.8）、
`system_server` 3 条（见 P0.10）。urcc 的 24 条**签名完全一致**。

**签名**（tombstone_00 为代表）：
```
pid: 1868, tid: 1868, name: UrccMainThread  >>> /odm/bin/hw/vendor.oplus.hardware.urcc-service <<<
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
Cause: null pointer dereference
 #00 pc 0x0 <unknown>                       <-- 调了个空函数指针
 #01 pc 0x58bec /odm/lib64/liburcccore.so (urccResStateRequest+396)
 #02 pc 0x512c  .../urcc-service (Urcc::urccResStateRequest(aidl ...)+96)
 #03 pc 0xd570  /odm/lib64/vendor.oplus.hardware.urcc-V1-ndk.so (IUrcc::onTransact+5144)
 #04 ... libbinder_ndk.so / libbinder.so     <-- 普通 Binder 调用路径
```
崩溃线程既可能是 `UrccMainThread`，也可能是 `binder:<pid>_N` —— 即**任何**走到该 AIDL 调用的
线程都会崩，不是单线程问题。

**逐指令定位**（`liburcccore.so`，md5 `1c160f3dad18a60109b20ca3698449f2`）：
```
0x58bd8  adrp x9, 0x93000
0x58bdc  ldr  x9, [x9, 0x4d8]   ; x9 = UrccCtlServer::uah_lib   (重定位符号 _ZN13UrccCtlServer7uah_libE)
0x58be0  ldr  w20, [x8]         ; 请求 id
0x58be4  ldr  x8, [x9, 0xc0]    ; x8 = uah_lib->[0xc0]   <-- 无 null 检查！
0x58be8  mov  w0, w20
0x58bec  blr  x8                ; <-- pc=0，SIGSEGV
```
槽位映射（由 `UrccCtlServer::loadUahCoreLib()` @0x4d804 的 dlsym 序列解出）：
- `+0x88` = `dlopen("/odm/lib64/libuahcore.so")` 的句柄
- `+0x90` = `uah_init`、`+0xa0` = `uah_request`、`+0xa8` = `uah_get_feature_status`、
  `+0xb0` = `uah_allow`、`+0xb8` = …、**`+0xc0` = `uah_get_mode_status`**、`+0xc8` = `uah_get_feature_status`
- 同一函数里 `+0xc8` 的调用点**有** `cbz x9, <bail>` 保护（0x58b5c），而 `+0xc0` 的调用点**没有**
  —— 上游自己的 null 检查不一致，只有「UAH 库缺失」这种设备才会暴露。

**根因**：**`/odm/lib64/libuahcore.so` 在这台设备上根本不存在**。
```
/odm/lib64/libuahcore.so             MISSING   <-- 铁证
/odm/lib64/liboplus_gpu_utils.so     MISSING   (loadUahCoreLib 也 dlopen 这个)
/vendor/lib64/libpowerhal.so         MISSING   (同上)
/odm/lib64/liburcccore.so            PRESENT
/odm/bin/hw/vendor.oplus.hardware.urcc-service  PRESENT
$ find /odm /vendor /system -iname '*uah*'   ->  只有 liboplus-uah-client.so（不是 core）
```
`dlopen` 失败 ⇒ 句柄 NULL ⇒ 之后所有 `dlsym(NULL, "uah_*")` 全部返回 NULL ⇒ `uah_lib` 表**整表为空**
⇒ 第一次 `UrccResStateRequest` 就撞 `+0xc0`（`uah_get_mode_status`）⇒ 崩。init 拉起后再被调用再崩
⇒ **崩溃循环**。

**为什么这台设备会缺件**：`vendor.oplus.hardware.urcc-service` 是 **OPPO 的性能/调度总控**
（读它的 rc 就明白：它去 `chmod`/写 `/proc/perfmgr/boost_ctrl/eas_ctrl/*`、`/proc/oplus_scheduler/...`、
`/proc/game_opt/...`、`/proc/ufsplus_ctrl/*`、`/sys/devices/platform/soc/soc:oplus-omrg/*`、`oplus_cpu_boost`、
`ufshc` devfreq、`/dev/cpuctl/*` …）。实测这些 **OPPO 专属内核节点全部 MISSING**：
```
/proc/perfmgr/boost_ctrl/eas_ctrl      MISSING
/proc/oplus_scheduler                  MISSING
/proc/game_opt                         MISSING
/proc/ufsplus_ctrl                     MISSING
/sys/devices/platform/soc/soc:oplus-omrg  MISSING
/sys/class/devfreq/kgsl-busmon         PRESENT   (高通自有，与 OPPO 无关)
uname: Linux 6.6.82TB522FU ... aarch64     ro.board.platform=sun  ro.soc.model=SM8750P
```
即：**移植时把 OPPO 的 odm 服务/库搬了过来，但 Lenovo/高通侧的内核接口和 `libuahcore.so` 没跟过来。**
该 HAL 在本机**理论上不可能工作**（它要控的调度节点一个都不在）。它在 `/odm/etc/vintf/manifest/`
里有声明（`vendor.oplus.hardware.urcc-service.xml`），所以框架侧 UAH 会去 bind 它：
`UAH-UahAdaptHelper: getAidlService uah urcc service = vendor.oplus.hardware.urcc.IUrcc$Stub$Proxy@…`
⇒ 调用 ⇒ 崩 ⇒ 循环。

**影响评估**：24 次/约 2 小时，崩溃-重启的耗电/日志噪音是实打实的；功能上因为该 HAL 本来就不可能工作，
**丢掉它不会比现在更差**（UAH 拿不到服务会走降级分支）。**但它不是 note 闪退的原因，也不是 system_server
崩的原因**（签名/调用栈完全无关）。

**可选处置（按推荐度）**：
1. **只做可观测性 + 忽略**：`setprop persist.vendor.oplus.uah.debugenable 1` 前先确认；urcc 崩一次就
   重启一次，代价可接受。**零风险**，先这样跑。
2. **让 init 别再拉起它**：用 KernelSU 模块 overlay `/odm/etc/init/vendor.oplus.hardware.urcc-service.rc`
   把 service 改成 `disabled`（并删掉那些注定失败的 `chmod` 段）。副作用：vintf 里仍声明该 AIDL HAL，
   `init`/`vintf` 可能报 "HAL not found"；需实测框架是否因此降级异常。
3. **补齐缺件**：从**同版本 OPD2409 (ColorOS 16) 的 odm 镜像**里取 `libuahcore.so` +
   `liboplus_gpu_utils.so` + `libpowerhal.so` 放进 `/odm/lib64/`。⚠️ **ABI 必须完全匹配**，
   且这些库还会去碰上面那些不存在的内核节点 —— **不保证能治好，可能只是把 NULL 崩换成别的崩**。
4. **不建议**：给 `liburcccore.so` 打补丁（把 0x58be4 的空槽调用绕开）。治不了根，且该库属于 odm，
   升级即失效。除非 1/2 都被证明不可接受。

**验证已做**：`getenforce=Enforcing`（与 SELinux 无关）；无 dmesg 噪声（init 的报错走 logcat 且已 rollover）。

## P0.10 `system_server` 偶发崩溃（ART 内部 abort，2026-09-18 定位到失败模式，未定因）

今日 3 条 `system_server` tombstone（05 / 12 / 29），**签名一致**：

```
pid: 2271, tid: 3446, name: HeapTaskDaemon   >>> system_server <<<      (05 是 Thread-2, 29 是 HeapTaskDaemon)
signal 11 / 6,  fault addr 0x9 / --------
Abort message: 'Failed to recognize implicit suspend check at 0xaa5e46b4;
                thread state = Runnable; mutator lock shared held = false;
                code ranges = {{0x66800000, 2000000}, {0x62800000, 2000000}, ...
                               {0xaa45c000, 2ea058}, {0x72c5052340, eed0}}'
backtrace:
 #00 libart.so (art::ReferenceMapVisitor<art::RootCallbackVisitor,false>::VisitFrame()+2820)
 #01 libart.so (StackVisitor::WalkStack<...>(bool)+340)
 #02 libart.so (art::Thread::VisitRoots(art::RootVisitor*, art::VisitRootFlags)+1088)
 #03 libart.so (art::gc::collector::MarkCompact::RunPhases()+660)
 #04 libart.so (art::gc::collector::GarbageCollector::Run(...)+328)
 #05 libart.so (art::gc::Heap::CollectGarbageInternal(...)+608)
 #06 libart.so (art::gc::Heap::ConcurrentGC(...)+168)
 #07 libart.so (art::gc::Heap::ConcurrentGCTask::Run(art::Thread*)+76)
 #08 libart.so (art::gc::TaskProcessor::RunAllTasks(art::Thread*)+124)
 #09 /system/framework/arm64/boot-core-libart.oat (art_jni_trampoline+112)
 #10 boot-core-libart.oat (java.lang.Daemons$HeapTaskDaemon.runInternal+184)
```
三条的 abort 文本逐字相同、只有 PC 与 `code ranges` 基址不同（05: `0xa9982548`，12: `0xaa5e46b4`，
29: `0xaa604754`）。**失败模式**：ART 的 **并发 MarkCompact（压缩式）GC** 在
`Thread::VisitRoots → StackVisitor::WalkStack → ReferenceMapVisitor::VisitFrame` 遍历**某个线程**的栈时，
发现该线程 PC 处的指令**不是**它期望的「隐式 suspend check」序列（且该线程还是 `Runnable`、
未持 mutator lock 共享锁），于是 `LOG(FATAL)` 主动 abort。

**注意**：被 abort 的线程（`HeapTaskDaemon`）栈是纯 ART + boot-core-libart.oat —— 出问题的**不是**
崩溃线程本身，而是**被它遍历到的那条线程**。abort 里给出的 PC（如 `0xaa604754`）**落在它自己列出的
code range `{0xaa45c000, 2ea058}` 内**，也就是说「地址在已注册的 oat 代码段里，但那段代码不是
stack map 期望的内容」⇒ 指向 **执行中的代码与 oat/stack map 不一致**。

**两个候选（尚未区分，需 A/B）**：
- **(a) ROM 的 ART/boot image 与运行时不匹配**：这是移植 ROM 最典型的一类问题。三条 tombstone 的
  帧 #09/#10 落在 `/system/framework/arm64/boot-core-libart.oat`，而 abort 的 code range
  `{0xaa45c000, 2ea058}`（约 3MB）大小也吻合 boot image 量级。`/system/framework/arm64/` 下还有
  OPPO 特有的 `boot-QPerformance.*` / `boot-UxPerformance.*`（带 `.fsv_meta` 完整性元数据），
  以及 OPPO 私有 `dalvik.vm.enable_pr_dexopt=true` / `dalvik.vm.appimageformat=lz4` /
  `dalvik.vm.isa.arm64.variant=oryon`。**若 boot.art/oat 与 `libart.so` 不同源，就会出现「代码段对、
  内容不对」**。
- **(b) hook 框架 deopt 打补丁**：Vector 安装 hook 时会对目标方法做 `ThreadList::SuspendAll` + deopt
  （见 P0.5），deopt 会把 oat 里方法入口改成 trampoline / 就地补丁。若补丁与并发压缩 GC 交叠，
  就会让「代码段里 PC 处不是预期的隐式 suspend check」。**本模块确实注入 system_server**
  （这正是笔桥需要的）。

**排除项**：三条 tombstone 里**都没有** urcc 相关帧（P0.9 是独立问题）；崩溃线程栈**没有任何** hook
框架帧（`/data/adb/...`、libzygisk、lspd 只出现在内存映射 dump 里，属正常注入痕迹，**不是**因果证据）。
`grep -lE "/data/adb/(modules|lspatch)|libzygisk|zygiskd|lspd|rezygisk|libhm"` 命中 05/12/15/16/29 ——
这只是 Zygisk 在每个进程都注入，**不能**当罪证。

**下一步（区分 a/b，按代价从小到大）**：
1. **先数频率**：清空 `/data/tombstones`（或记住序号），正常用一天，看 system_server 是否会再崩、
   以及是否**只在装了 hook 模块时**出现。当前 3 次/2h 样本太小。
2. **框架 A/B**：`touch /data/adb/modules/{zygisk_vector,rezygisk,hma_oss_zygisk}/disable` 后重启，
   复现「之前必崩」的场景。这条能一刀切开 (b)。⚠️ 会同时停掉笔桥，记得删掉 `disable` 再重启。
3. **验证 (a)**：比对 `libart.so` 的 BuildId 与 boot image 的编译指纹
   （`strings /system/framework/arm64/boot.oat | grep -i "dex2oat"`、`/system/framework/arm64/boot.art`
   的 `image_checksum`/`oat_checksum` 与 `libart.so` 的 `ArtMethod` 布局版本是否同源）；
   以及 `/data/dalvik-cache/arm64/` 里是否有**别的 ROM 留下的陈旧 odex**（移植后未清 data 的经典坑）。
4. **缓解（若能确认 a）**：避开压缩式 GC（`-Xgc:` 指到非 MarkCompact 的 collector，或调
   `dalvik.vm.gc.*`），使 ART 不走 `MarkCompact::RunPhases` 这条遍历路径。**未验证，先别动**。

**结论**：**已把失败模式钉死在 ART 的 `MarkCompact → Thread::VisitRoots → WalkStack → VisitFrame`
里**（不是 binder、不是 HAL、不是我们的 Java 代码），但 **(a) ROM ART/boot-image 不同源** 与
**(b) hook deopt 补丁** 两种成因**尚未区分**，需按上面 1→2 步做 A/B。**不作任何未经实验的结论。**

---

## P0.14 画布内 107/108 直达撤销/重做（v4.6.0，2026-09-20 已部署实机；接收侧通道实测通过，**待笔实测发送侧**）

**问题（用户报告）**：捏握设为「取消/撤销」在便签**全屏涂绘画布**里实测无反应。

### 定位（只读 + 反汇编，2026-09-19/20）

**第一步：107 与 Ctrl+Z 不是两条路。** `StylusActionDispatcher.undo()` 全文只有两个分支：

```java
if (HandwrittenNoteOverlay.isVisible()) { HandwrittenNoteOverlay.performUndo(); return; }  // 模块自绘浮层，不走键盘
sendKeyCombination(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_Z);                         // ← 否则就是注入 Ctrl+Z
```

⇒ 动作码 107 的**实现**就是「注入 Ctrl+Z」，所以「画布里 107 没用」与「画布里 Ctrl+Z 没用」是
**同一件事的两种说法**，一次失败、不是两次。`redo()` 同理（`Ctrl+Shift+Z` + 25ms 后 `Ctrl+Y`）。
L3 实机注入（`input keycombination 113 54`）与模块内注入走同一条
`InputManager.injectInputEvent`，可互为忠实替身；框架侧 `KEYLOG_PhoneWindowManagerExtImpl`
证实注入带正确的 `META_CTRL_ON|META_CTRL_LEFT_ON`，**注入侧无问题**。

**第二步：画布为什么接不住 —— 该界面一个按键回调都没有。** 反汇编 `classes2.dex` 逐层点数：

| 类 | 成员数 | 按键相关成员 |
|---|---|---|
| `com.nearme.note.paint.NewPaintActivity` | 16 | **0** |
| `com.nearme.note.paint.NewPaintFragment` | 480 | **0**（`KEY_*` 全是 Intent extra 常量名） |
| `com.oplus.richtext.editor.view.CoverPaintView` | 188 | **0** |
| `com.oplusos.vfxsdk.doodleengine.PaintView` | 370 | **0** |

`PaintView extends FrameLayout`（`CoverPaintView extends PaintView`），两者都不覆写
`dispatchKeyEvent`/`onKeyDown`/`onKeyUp`/`onKeyPreIme` ⇒ 注入的键送到焦点窗口后**被丢弃**。
对比：便签 WebView 编辑器有键入口（`WVJBWebView.onKeyPreIme` → `WVNoteViewEditFragment` →
`undoEvent()`），这就是「同一个应用，不同界面结论相反」的原因。**不是路由错，是压根没有接收方。**

**第三步：找到真正的 worker。** 标题栏 ↺ 按钮的处理器就是答案：

```
NewPaintFragment.initTitleBar$lambda$63(NewPaintFragment, View)   ← 撤销按钮 OnClickListener
  → MultiClickFilter.isEnabled(...)                              ← 防连点
  → com.oplusos.vfxsdk.doodleengine.PaintView.undo:()V           ← 真正的撤销
```

而 `NewPaintFragment.enablePaintUndoManager(Z)` 把同一个 `paintView` 包了一层干净的公开门面：

```
com.nearme.note.paint.NewPaintEditPresenter   (PUBLIC FINAL, implements oplus...container.api.d)
  private final CoverPaintView paintView;
  public void    undo()  { paintView.undo(); }   // ()V，access 0x0001 PUBLIC
  public void    redo()  { paintView.redo(); }   // ()V，access 0x0001 PUBLIC
  public boolean canUndo();                       // ()Z
  public boolean canRedo();                       // ()Z
```

### 实现（v4.6.0）

1. **新类** `hook/source/sources/com/aclaniakea/colorosporttuning/CanvasPaintHooks.java`
   （`final`、包内可见，与 `NoteToolkitHooks` 同级；ADR-001 平铺包布局）。
   - 注入点：`NewPaintFragment.onStart` 登记偏好「哪个画布在屏」、`onStop` / `onDestroyView` 反注册
     （`NewPaintFragment` **没有覆写 `onResume`**，所以用 onStart/onStop；两者都是该类**自己声明**的方法，
     `HookUtils.hookAll` 能直接命中）。
   - 登记用 `WeakReference`（照抄 `NoteToolkitHooks.active` 的写法），泄漏的画布不会吊住 Activity。
   - 命令接收：动态注册 `CANVAS_UNDO` / `CANVAS_REDO` 两个 action 的 receiver；
     `FLAG_RECEIVER_NOT_EXPORTED` **不可用**（发送方在 system_server，uid 不同），
     故用 `RECEIVER_EXPORTED` + 发送侧 `setPackage("com.coloros.note")` + 模块私有 action 名收敛可达面。
   - 反射容错：先取声明字段 `newPaintPresenter`；R8 改名时退化为「按声明类型找唯一字段」，再不行就记日志放弃。
   - 接收体跑在 `ContractProbe.executeGuarded` 下（失败 3 次熔断 → 静默 no-op，绝不把异常抛进便签主线程）；
     另有 250ms 同向去重窗口（对照 `NoteToolkitHooks.dispatch` 的 150ms 去重）。
2. **常量** `PenBridgeConstants.CANVAS_UNDO` / `CANVAS_REDO`
   （`com.futureharmony.lenovopenbridge.action.CANVAS_*`；**不设 `_LEGACY` 双胞胎**：发送方与接收方同属一个 APK，
   不存在混版本窗口）。
3. **接线** `UiWorkingSetPrefetch` 的 `case "com.coloros.note"` 增加 `CanvasPaintHooks.install(...)`。
4. **路由** `StylusActionDispatcher.undo()/redo()`：在注入按键**之前**加一句
   `requestCanvasUndo(context, ...)`。发送用 `sendBroadcastAsUser(..., UserHandle.ALL, ...)` + 回退到
   `sendBroadcast`（同 `SystemStylusHooks.sendAll` 的理由：system_server 直接 sendBroadcast 只覆盖发送用户），
   并加 `FLAG_RECEIVER_FOREGROUND` 把延迟压进手势可接受区间。

### 为什么两条路不会互相打架（关键安全论证）

命令只有满足「某个已登记的画布片段 `isAdded() && isResumed()`」才会被消费。该判据精确等价于
「全屏画布正在前台」，因为 `NewPaintFragment` 的全部外部引用者都在 `com.nearme.note.paint.*`
（`NewPaintActivity` / `QuickPaintActivity` 用 `instance-of` + `check-cast` 挂载，
其余是画布自己的辅助类与 lambda）；`MainActivity`、`NoteDetailFragment`、
`NoteDetailPaintManager`、`coverdoodle.CoverDoodlePresenter`（双栏封面涂绘走这条）**完全不引用它**。

| 场景 | 命令 | 注入按键 | 净效果 |
|---|---|---|---|
| 全屏画布 | ✅ 生效（直达 worker） | 惰性（无接收方） | **1 次撤销** |
| WebView 编辑器 | ❌ 无画布 → 记日志忽略 | ✅ 生效 | **1 次撤销** |
| 其它应用 | ❌ 广播不达 / 无画布 | ✅ 生效 | **1 次撤销** |

⇒ 两侧都不需要知道前台是谁，也不存在「同一手势撤两次」。

### 已知边界

- ~~只解决 **107 / 108**。109/110（翻页）在画布里仍是「滑动 + PAGE_UP/DOWN」，画布同样不吃键 —— 未处理。~~
  **（2026-09-23 已处理）** 109/110 改为按前台 App 策略：画布类 App 可为其单独选「KeyEvent 上下/左右」
  或保持模拟滑动，不再写死。见顶部「P0.13 按应用翻页策略」段。
- 画布内的**取消语义**依赖画布自身 undo 栈；栈空时 `PaintView.undo()` 自己 no-op（应用会打
  `mUndoList is empty, cannot to previous step`），模块不额外拦截。

### 部署与验证结果（2026-09-20 00:14–00:16 实机执行）

**已部署** ✅ —— `scripts/deploy_vector.sh --no-reboot` → 手动 reboot：

| 项 | 结果 |
|---|---|
| 设备侧 APK versionName | `4.6.0` / versionCode `460000`（原 v4.5.5） |
| 设备侧 `base.apk` md5 | `1499ea0b3142e892c3800316e262917d`（与本地产物**逐字节一致**） |
| Vector 模块状态 | `enabled`（uid 10408） |
| 运行时 scope | `system` + 7 app，与 `scope.list` 完全一致，脚本回读断言 `system/0 present ✓` |
| 开机 | `sys.boot_completed=1`，t+25s，**未卡开机**；无 `.bootfail` 文件 |
| system_server 注入 | `handleLoadPackage pkg=android` → `install deferred by 2500ms` → `stylus hooks installed (startOtherServices=1 run=1)` ✓ |
| 便签进程注入 | `CanvasPaintHooks: canvas undo/redo bridge installed` + `undo/redo receiver registered` ✓ |

**接收侧通道已端到端验证** ✅（用 root 广播替代笔，绕开「必须动手写」的限制）：

```
adb shell su -c 'am broadcast -a com.futureharmony.lenovopenbridge.action.CANVAS_UNDO -p com.coloros.note'
```

当时画布（`NewPaintActivity`）确在前台、画布上已有两笔。**一次广播 → 恰好一次处理**：

```
main proc  : CanvasPaintHooks: canvas undo invoked=true canUndo=true canRedo=true
sub  proc  : CanvasPaintHooks: no resumed canvas on screen, undo left to the key path
```

⇒ 三件事一次坐实：① 广播链路（action 名 / `RECEIVER_EXPORTED` / `setPackage` 定向）真的通；
② `newPaintPresenter` 反射取字段 + `undo()` 反射调用**成功**（`invoked=true`）；
③ 「双进程注册」不会双撤 —— 子进程 `com.coloros.note:tbl_privileged_process0` 也注册了 receiver，
但它的画布登记表为空，命中 `no resumed canvas` 分支后静默返回（见下「已知边界」）。

### 剩余验证步骤（需实机手写笔，人工/助手均未执行）

1. 便签 → 涂鸦笔记 → **全屏画布** → 画两笔 → **捏握**（设为撤销）→ 期望最后一笔消失。
   这条才真正覆盖**发送侧**（`StylusActionDispatcher.undo()` → `requestCanvasUndo()`）。
2. 同一条笔记改在 **WebView 正文编辑器**里捏握 → 仍应生效（回归项：不能因本次改动变差）。
3. 日志观察点（`sh action.sh log` 或 `logcat -s LenovoPenBridge`）：与上面两条一致。
4. 回退：装回 v4.5.5 即可（本改动不写任何持久状态，无迁移问题）。

### 已知边界（实测确认，非缺陷）

- **receiver 在便签的每个进程各注册一份**（主进程 + `com.coloros.note:tbl_privileged_process0`）。
  一次广播会投递到两个进程，但只有真正持有前台画布的主进程会动作，子进程走 `no resumed canvas` 静默返回，
  **净效果仍是 1 次撤销**（上面实测：1 次广播 → 主进程 1 次 `invoked` + 子进程 1 次 no-op）。
  若要消掉这条无谓投递，可在注册前判断进程名，只留主进程 —— 但当前行为正确，未改。
- ~~只解决 **107 / 108**。109/110（翻页）在画布里仍是「滑动 + PAGE_UP/DOWN」，画布同样不吃键 —— 未处理。~~
  **（2026-09-23 已处理）** 109/110 改为按前台 App 策略：画布类 App 可为其单独选「KeyEvent 上下/左右」
  或保持模拟滑动，不再写死。见顶部「P0.13 按应用翻页策略」段。
- 画布内的**取消语义**依赖画布自身 undo 栈；栈空时 `PaintView.undo()` 自己 no-op（应用会打
  `mUndoList is empty, cannot to previous step`），模块不额外拦截。


### ⚠️ 构建不可字节复现（实测，2026-09-20）

同一个源码连跑两次 `python3 build.py`，产出的 APK **md5 不同**。已验证差异是**良性的**：

| 检查项 | 结果 |
|---|---|
| `classes.dex` | **逐字节相同**（两次 md5 均为 `567313c5338ebd75bf98709b81ee6aa6`，319580 B） |
| zip 条目列表 + 各条目大小 | 完全一致 |
| 逐条目内容 md5 | **全部一致**（含 `META-INF` 里所有文件） |
| 差异位置 | 仅 APK Signing Block（v2/v3 签名） |

⇒ `apksigner` 的签名块不可复现（签名随机化），**条目内容才是真值**。后果：

- **不要把 md5 当作产物身份**。本文件此前记的 `c70331e7…` 在重跑构建后变成 `1499ea0b…`，
  两次功能完全等价。要认身份请比对 `classes.dex` 的 md5。
- `releases/*.md5.txt` 由 `build.py` 每次重写，只能用于**同一份文件**的传输校验
  （`push_to_device.sh` 的用途），不能用来判断「本地这份是不是那次发布的那份」。

