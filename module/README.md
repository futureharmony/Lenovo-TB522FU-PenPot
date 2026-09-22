# 联想手写笔桥接 — Root 模块

模块 ID：`lenovo_pen_bridge`，作者 ACLaniakea。版本以 `module.prop` 与 release 为准（本文不再跟版本号）。

- Hall/CPS/BLE 状态监控与真实磁吸胶囊广播；
- 开机按已绑定手写笔地址调用原厂 CoreService `CONNECT_PENCIL`，不以磁吸为前提；
- 设置页断开调用 `DISCONNECT_PENCIL`，由配套 Hook 执行真实 GATT 断开；
- 不监听屏幕状态、不做唤醒回放、**不做任何充电/TX/GPIO/休眠控制**——
  官方 ZUXOS 取证（`docs/pen_official_rom_verdict_20260921.md`）证明
  「充满断电 → 笔深休眠 → 重新吸附唤醒」是官方驱动+笔固件的完整行为链，
  官方系统层没有保持 TX/唤醒笔的逻辑；模块对该链路只读不写（v0.1.17）；
- **v0.1.23 起不再尝试"深睡笔软件唤醒"**：曾有的唤醒守护与配套 priv-app
  `PenHidCtl` 已整段下线。三层实测结论写在 `service.sh` 的
  「深睡唤醒守护：已整段移除」注释块里 —— 唯一能奏效的手段是重启整片蓝牙，
  既非单设备、又非原厂行为。**睡死的笔请重新吸附一次。**
- 模块内含与独立安装包同签名的 Hook 副本，只用于 LSPosed 的早期稳定读取；不执行 `pm install`、不覆盖 `/data/app`。

作用域：`com.oplus.ipemanager`、`com.heytap.mydevices` 等（见 `scope.list`），由用户手动勾选。

## 代码结构

`service.sh` 只负责配置与调用编排，函数定义按职责放在 `lib/` 下（由主文件以 `.` 加载）：

| 文件 | 职责 |
|---|---|
| `lib/core.sh` | 日志（`log` / `log_err`）、settings 读写（`get_global` / `put_global` / `put_global_diff`）、不 fork 的等待（`nap_init` / `sleep_sec`）、日志裁剪收口 |
| `lib/pen_id.sh` | 笔 MAC 规范化与绑定记录发现 |
| `lib/pen_hw.sh` | Hall / 电量 / 充电 / CPS 的**只读**真值读取 |
| `lib/pen_link.sh` | OEM 连接动作投递与链路真值判据 |
| `lib/pen_ui.sh` | 状态镜像与磁吸胶囊发布 |
| `lib/boot.sh` | 启动期需重试到确认生效的动作（inkdye / 稳定性 / 权限 / 首次连接） |
| `lib/monitors.sh` | 五条常驻监视循环（永不返回，由主文件以 `&` 启动） |

⚠️ 改代码前必须知道的三条：

1. **`source` 必须早于任何 `monitor_* &`**。子 shell 在 fork 那一刻复制父进程的符号表，
   晚 source 会让监视循环**静默** `command not found` —— 不报错、不退出，只是循环体
   里什么都不发生。新增库文件时同步改 `service.sh` 顶部的加载列表。
2. **库文件里不要写 `exit`**。唯一例外是 `core.sh` 中 `sleep_sec` 的忙等兜底出口，
   它本来就必须退掉主进程。库里的未预期 `exit` 会直接终止整个服务。
3. **`log()` 只写 stdout**。被 `$(...)` 捕获的纯读取函数（`read_hall_state`、
   `read_hardware_battery`、`resolve_pen_mac` 等）里必须用 `log_err()`（`>&2`），
   否则日志会混进返回值。`lib/pen_hw.sh` 里因此**一个 `log` 调用都没有**。

`lib/` 在构建白名单（`tools/build_root.py`）中以**目录**形式声明，新增 `.sh` 会自动
打包进模块 zip；但手工 `cp` 单文件到设备调试时，要连 `lib/` 整个目录一起推。

## 日志与控制台

在管理器里点模块卡片的「操作」按钮 = 跑 `action.sh`，输出到**原生控制台**（本模块不用 WebUI）。

- 上半页：模块状态、笔硬件与连接信息、当前手势配置、Hook 状态；
- 下半页：**最近日志片段** —— `pen-bridge.log` 最近 120 行、
  `note_engine_guard.log` 60 行、Hook 日志（logcat tag `LenovoPenBridge`）200 行。

快捷入口（adb / Termux 同样可用）：

```
su -c 'sh /data/adb/modules/tb522fu_pen_bridge/action.sh log'        # 只输出日志段
su -c 'sh /data/adb/modules/tb522fu_pen_bridge/action.sh clearlogs'  # 就地裁到最近 5 行
ksud module action tb522fu_pen_bridge                                # 等价于点「操作」按钮
```

日志上限由 `bin/penlog.sh` 统一负责（条数 + 字节双上限，超限**保留最近 N 行**）：

| 文件 | 条数上限 | 字节上限 |
|---|---|---|
| `pen-bridge.log` | 400 行（另加 512 KB 硬顶） | 512 KB |
| `/data/local/tmp/note_engine_guard.log` | 200 行 | 128 KB |

控制台读取侧也截断（见上），否则一次 dump 上万行会把控制台刷爆且无法回翻。

⚠️ **改日志相关的代码务必注意**：裁剪是**同 inode 就地回写**。`service.sh` 用
`exec >>"$LOGFILE"` 持有该文件 fd，所以实现里**不能 `mv` 日志再重建同名文件**
（后续写入会落进已 unlink 的旧 inode，表现为"日志停在裁剪那一刻"）。
新增日志写入点时统一走 `penlog_append`（自带时间戳 + 自检裁剪）。
