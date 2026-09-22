#!/system/bin/sh

ui_print "- 联想手写笔桥接 Root 服务（futureharmony 定制版）"
ui_print "- 开机按已绑定手写笔地址直接调用原厂 CoreService BLE 连接"
ui_print "- 设置页断开同时执行原厂 CoreService、GATT/HID 实际断开"
ui_print "- 统一 Hall/CPS/BLE 硬件充电状态与 ColorOS 设置页热切换"
ui_print "- 已移除自定义 CPS 内核模块，避免异常重启"
ui_print "- 真实 Hall 磁吸弹窗由本模块直接驱动"
ui_print "- 磁吸仅控制弹窗与充电状态显示，蓝牙连接不依赖 Hall"
ui_print "- 支持切换到其他已绑定蓝牙地址的手写笔"
ui_print "- 设备空间手写笔存在状态仅跟随真实蓝牙连接"
ui_print "- 不做任何充电/TX/GPIO/休眠控制：官方没有的逻辑一律不加"
ui_print "- 连接状态只接受真实 ACL/GATT/Hall/CPS 事件，拒绝强制已连接回放"
ui_print "- 刷新率策略统一绑定：笔场景锁 120Hz，其余场景最高 144Hz"
ui_print "- 内置签名 Hook 副本并固定 LSPosed 早期路径，消除冷启动随机路径竞态"
ui_print "- TB522FU (sun/SM8750P) 移植版"
ui_print "- 默认禁用系统内置笔桥 com.inkdye.lenovopentocoloros，由本模块接管"
ui_print "- 想回退内置笔桥：KSU/Magisk 管理器「执行」按钮 → sh action.sh enable"
ui_print "- 切换指令：sh action.sh enable|disable|toggle（默认 disable）"
ui_print "- 启动自保：连续 3 次开机失败将自动停用本模块"
ui_print "- 一键救援：sh panic.sh（恢复状态并停用模块）"
ui_print "- 卸载本模块会无条件恢复 inkdye，并自动卸载配套 Hook APK"
ui_print "- 睡死的笔请重新吸附一次：官方固件自身就是这样唤醒的（v0.1.23 起"
ui_print "  不再尝试软件唤醒，见 service.sh「深睡唤醒守护：已整段移除」）"

# Clean legacy paths
rm -rf "$MODPATH/system/priv-app/lenovopenbridge" \
       "$MODPATH/system/system_ext/priv-app/lenovopenbridge" 2>/dev/null
rm -f "$MODPATH/system/etc/permissions/privapp-permissions-com.aclaniakea.lenovopenbridge.xml" \
      "$MODPATH/system/system_ext/etc/permissions/privapp-permissions-com.aclaniakea.lenovopenbridge.xml" \
      "$MODPATH/system/etc/permissions/privapp-permissions-com.futureharmony.lenovopenbridge.xml" 2>/dev/null

# --- 自动同步安装/更新配套 LSPosed Hook APK ---
if [ -f "$MODPATH/hook/PenBridge-Hook.apk" ]; then
    ui_print "- 正在自动安装配套 LSPosed Hook APK..."
    if pm install -r "$MODPATH/hook/PenBridge-Hook.apk" >/dev/null 2>&1; then
        ui_print "- 配套 Hook APK 安装成功 (com.futureharmony.lenovopenbridge)"
    else
        ui_print "! 提示: Hook APK 未能即时安装（如在 Recovery 下），将在开机时由 service.sh 自动安装"
    fi
fi

# Clean stale PenHidCtl state left by older builds. v0.1.23 removed the
# deep-sleep wake guard together with its helper priv-app.
rm -rf "$MODPATH/system/priv-app/aclpenhid" 2>/dev/null
rm -f "$MODPATH/system/etc/permissions/privapp-permissions-com.aclaniakea.penhidctl.xml" 2>/dev/null
rm -f "$MODPATH/pen-wake-guard.state" "$MODPATH/pen-wake-arm.state" \
      "$MODPATH/pen-wake.last" "$MODPATH/pen-wake-now" 2>/dev/null

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/panic.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
[ -f "$MODPATH/bin/lsposed-path-sync.jar" ] && set_perm "$MODPATH/bin/lsposed-path-sync.jar" 0 0 0644
[ -f "$MODPATH/hook/PenBridge-Hook.apk" ] && set_perm "$MODPATH/hook/PenBridge-Hook.apk" 0 0 0644

# NOTE: the LSPosed path-sync below is DISABLED on Vector: the lspd config db
# is Vector's live database (API 102, different schema) and writing it with
# LsposedPathSync risks corrupting Vector's module table. Module enabling and
# scoping are done through `vector-cli` instead. The block only runs if the
# user explicitly opts back in by creating $MODPATH/enable-lsposed-path-sync.
if [ -f "$MODPATH/enable-lsposed-path-sync" ] && \
        [ -f /data/adb/lspd/config/modules_config.db ] && \
        [ -f "$MODPATH/bin/lsposed-path-sync.jar" ] && \
        [ -f "$MODPATH/hook/PenBridge-Hook.apk" ]; then
    chcon u:object_r:system_file:s0 "$MODPATH/bin/lsposed-path-sync.jar" \
        "$MODPATH/hook/PenBridge-Hook.apk" 2>/dev/null
    # 加超时：安装器里 app_process 卡住会挂住整个 KSU 安装流程。
    if command -v timeout >/dev/null 2>&1; then
        SYNC_RUN="timeout 20"
    else
        SYNC_RUN=""
    fi
    $SYNC_RUN env CLASSPATH="$MODPATH/bin/lsposed-path-sync.jar" app_process /system/bin \
        com.aclaniakea.tools.LsposedPathSync \
        /data/adb/lspd/config/modules_config.db \
        "$MODPATH/hook/PenBridge-Hook.apk" \
        com.futureharmony.lenovopenbridge \
        system com.coloros.note com.oplus.exsystemservice \
        com.heytap.mydevices com.oplus.ipemanager \
        com.oplus.wirelesssettings com.oplus.screenshot com.coloros.translate >/dev/null 2>&1 && \
        ui_print "- Pen Hook 路径与原厂作用域已固定，system_server 冷启动直接加载"
fi

# Remove the legacy kernel bridge from an existing module update as well as
# from a fresh package. The normal CPS/Hall and BLE paths remain intact.
rm -f "$MODPATH/kernel/lenovo_pen_cps_bridge.ko" 2>/dev/null
rmdir "$MODPATH/kernel" 2>/dev/null
