# 联想手写笔桥接 — Root 模块

模块 ID：`lenovo_pen_bridge`，作者 ACLaniakea。版本以 `module.prop` 与 release 为准（本文不再跟版本号）。

- Hall/CPS/BLE 状态监控与真实磁吸胶囊广播；
- 开机按已绑定手写笔地址调用原厂 CoreService `CONNECT_PENCIL`，不以磁吸为前提；
- 设置页断开调用 `DISCONNECT_PENCIL`，由配套 Hook 执行真实 GATT 断开；
- `PenHidCtl.apk`（priv-app，无启动器）HID 控制辅助；
- 不监听屏幕状态、不做唤醒回放、**不做任何充电/TX/GPIO/休眠控制**——
  官方 ZUXOS 取证（`docs/pen_official_rom_verdict_20260921.md`）证明
  「充满断电 → 笔深休眠 → 重新吸附唤醒」是官方驱动+笔固件的完整行为链，
  官方系统层没有保持 TX/唤醒笔的逻辑；模块对该链路只读不写（v0.1.17）；
- 模块内含与独立安装包同签名的 Hook 副本，只用于 LSPosed 的早期稳定读取；不执行 `pm install`、不覆盖 `/data/app`。

作用域：`com.oplus.ipemanager`、`com.heytap.mydevices` 等（见 `scope.list`），由用户手动勾选。

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
