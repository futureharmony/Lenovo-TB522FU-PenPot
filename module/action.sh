#!/system/bin/sh
# Lenovo TB522FU 手写笔生态增强模块 - 管理器操作与热控制脚本
# ------------------------------------------------------------
MODDIR=${0%/*}
LOG="$MODDIR/pen-bridge.log"
INKDYE_PKG="com.inkdye.lenovopentocoloros"
DISABLE_FLAG="$MODDIR/disable"

# 日志上限 helper（条数 + 字节双上限，同 inode 就地裁剪，见 bin/penlog.sh）。
# 老版本安装里可能还没有 bin/penlog.sh，所以必须有降级兜底 —— 否则一次
# "command not found" 就会让 action.sh 在半路静默中断，控制台只剩半页。
PENLOG_MAX_LINES=400
if [ -f "$MODDIR/bin/penlog.sh" ]; then
    . "$MODDIR/bin/penlog.sh"
fi
if ! type penlog_append >/dev/null 2>&1; then
    penlog_append() { printf '[%s] %s\n' "$(date '+%F %T')" "$*" >>"$1"; }
fi
if ! type penlog_trim >/dev/null 2>&1; then
    penlog_trim() { :; }
fi
if ! type penlog_caps >/dev/null 2>&1; then
    penlog_caps() { echo "${PENLOG_MAX_LINES:-400} 行"; }
fi

# 动作代号转中文
format_action() {
    case "$1" in
        101) echo "区域截屏" ;;
        102) echo "展开通知栏" ;;
        103) echo "模拟返回键" ;;
        104) echo "回到桌面" ;;
        105) echo "最近任务" ;;
        106) echo "切换手电筒" ;;
        107) echo "撤销 (Ctrl+Z)" ;;
        108) echo "重做 (Ctrl+Shift+Z / Ctrl+Y)" ;;
        109) echo "翻页上 (向上翻页)" ;;
        110) echo "翻页下 (向下翻页)" ;;
        111) echo "手写便签浮窗" ;;
        112) echo "圈选文字识别 (OCR提取)" ;;
        113) echo "圈选屏幕翻译" ;;
        0)   echo "关闭 (无动作)" ;;
        1)   echo "笔刷与橡皮擦切换" ;;
        2)   echo "最近工具切换" ;;
        3)   echo "呼出调色盘" ;;
        4)   echo "手写笔工具轮盘" ;;
        5)   echo "随心圈" ;;
        -1|"") echo "系统默认设置" ;;
        *)   echo "自定义动作 ($1)" ;;
    esac
}

# 彻底免重启禁用模块
do_disable() {
    echo ""
    echo "=============================================="
    echo "         正在执行【免重启彻底禁用模块】..."
    echo "=============================================="

    # 1. 创建标准模块禁用标记
    touch "$DISABLE_FLAG"

    # 2. 设置全局运行时禁用属性 (LSPosed Hook 极速热透传)
    setprop persist.lenovo.penbridge.disabled 1

    # 3. 清理服务锁：各监视循环轮询 disable 标记，会自行退出
    rm -f "$MODDIR/.service.lock"
    echo "  [√] 后台监视循环将随 disable 标记自行退出"

    # 4. 恢复系统旧版笔桥 (若设备中存在)
    if pm list packages | grep -q "$INKDYE_PKG"; then
        pm enable --user 0 "$INKDYE_PKG" >/dev/null 2>&1
        echo "  [√] 系统内置笔桥 (inkdye) 已恢复启用接管"
    fi

    echo "  [√] Hook 核心拦截已即时转为纯透传模式"
    echo "  [√] 模块标记已写入: $DISABLE_FLAG"
    echo ""
    echo "【处理完毕】模块已彻底停止介入，全程免重启即刻生效！"
    echo "（如需彻底从内存中卸载Hook注入，可执行快速软重启）"
    echo "=============================================="
    penlog_append "$LOG" "module disabled via action.sh"
}

# 彻底免重启启用模块
do_enable() {
    echo ""
    echo "=============================================="
    echo "         正在执行【免重启彻底启用模块】..."
    echo "=============================================="

    # 1. 移除标准模块禁用标记
    rm -f "$DISABLE_FLAG"

    # 2. 恢复全局运行时属性
    setprop persist.lenovo.penbridge.disabled 0

    # 3. 禁用系统旧版笔桥，防止冲突
    if pm list packages | grep -q "$INKDYE_PKG"; then
        pm disable-user --user 0 "$INKDYE_PKG" >/dev/null 2>&1
        echo "  [√] 系统内置旧版笔桥已禁用 (模块独占接管)"
    fi

    echo "  [√] Hook 核心拦截已恢复实时生效"
    echo "  [√] 模块标记已清除"
    echo ""
    echo "【处理完毕】手写笔增强系统已恢复全面接管，全程免重启！"
    echo "=============================================="
    penlog_append "$LOG" "module enabled via action.sh"
}

# 快速软重启 (仅重启 Android 框架与 Zygote，无需关机硬件)
do_soft_reboot() {
    echo ""
    echo "=============================================="
    echo "正在执行【快速软重启】(约5秒重载系统框架)..."
    echo "=============================================="
    sleep 1
    setprop ctl.restart zygote
}

# ----------------- 最近日志（原生控制台输出） --------------------------------
# 管理器控制台没有滚动、没有搜索、也没有剪贴板接口，所以这里只输出【尾部片段】。
# 上限是"两侧一起守"的：
#   写入侧 —— bin/penlog.sh 保证文件不会无限增长（条数 + 字节双上限）；
#   读取侧 —— 这里的 tail / logcat -t 截断，否则一次 dump 上万行会把控制台刷爆，
#             而且控制台不能往回翻，前面的内容等于白输出。
LOG_TAIL_MAIN=${LOG_TAIL_MAIN:-120}
LOG_TAIL_GUARD=${LOG_TAIL_GUARD:-60}
LOG_TAIL_HOOK=${LOG_TAIL_HOOK:-200}
NOTE_LOG="/data/local/tmp/note_engine_guard.log"
HOOK_TAG="LenovoPenBridge"

log_section() {  # log_section <标题> <文件> <行数>
    echo "──── $1（最近 $3 行）────"
    if [ -s "$2" ]; then
        tail -n "$3" "$2" 2>/dev/null
    else
        echo "  （无内容 / 文件不存在）"
    fi
    echo ""
}

do_logs() {
    echo "=============================================="
    echo "      Lenovo Tab Pen Pro · 最近日志片段      "
    echo "=============================================="
    echo "采集时间  : $(date '+%F %T')"
    echo "开机时长  : $(cut -d' ' -f1 /proc/uptime 2>/dev/null)s"
    echo "写入侧上限: $(penlog_caps)"
    echo "读取侧上限: 本页最多 $((LOG_TAIL_MAIN + LOG_TAIL_GUARD + LOG_TAIL_HOOK)) 行"
    echo ""
    log_section "pen-bridge.log · 模块主日志" "$LOG" "$LOG_TAIL_MAIN"
    log_section "note_engine_guard.log · 便签引擎守卫" "$NOTE_LOG" "$LOG_TAIL_GUARD"

    echo "──── Hook 日志 · logcat -s $HOOK_TAG（最近 $LOG_TAIL_HOOK 行）────"
    # ⚠️ 本 ROM 的 logcat【不支持 `-t N` 与 `-s TAG` 同用】：两者一起给会静默返回空。
    #    实测（Android 16 / ColorOS）：`logcat -d -s X -t 3` 与 `logcat -d -t 3 -s X`
    #    都是空输出，而 `-t 3` 单独、`-s X` 单独都正常，root 下也一样。
    #    所以这里改成"先按 tag 过滤，再管道 tail"截断。
    hook_dump=$(logcat -d -v time -s "$HOOK_TAG" 2>/dev/null | tail -n "$LOG_TAIL_HOOK")
    if [ -n "$hook_dump" ]; then
        printf '%s\n' "$hook_dump"
    else
        echo "  （本次开机暂无 Hook 日志：system_server 侧未注入，或缓冲区已轮转）"
    fi
    echo ""
    echo "=============================================="
    echo "Hook 日志只落在 logcat（环形缓冲自带上限）；"
    echo "文件日志超限时保留最近 $(penlog_caps)，不整file清空。"
    echo "=============================================="
}

# 手动把文件日志裁到很小（清屏重来用）。注意不能删文件：service.sh 用
# exec >> 持有同一个 inode，删了以后写入会落进已 unlink 的旧 inode。
do_clear_logs() {
    for f in "$LOG" "$LOG.1" "$NOTE_LOG"; do
        [ -f "$f" ] || continue
        penlog_trim "$f" 5 1024
        echo "已裁剪: $f（保留最近 5 行）"
    done
}

# 命令行参数快捷入口
case "$1" in
    enable|on|1)
        do_enable
        exit 0
        ;;
    disable|off|0)
        do_disable
        exit 0
        ;;
    toggle)
        if [ -f "$DISABLE_FLAG" ]; then do_enable; else do_disable; fi
        exit 0
        ;;
    reboot|soft_reboot)
        do_soft_reboot
        exit 0
        ;;
    log|logs)
        do_logs
        exit 0
        ;;
    clearlogs|clear-logs)
        do_clear_logs
        exit 0
        ;;
esac

# ----------------- 状态数据采集 -----------------
# 硬件与电量
HALL_RAW=$(cat /sys/devices/virtual/hall/och1909/hall3 2>/dev/null)
TX_RAW=$(cat /sys/bus/i2c/devices/11-0041/tx_status 2>/dev/null)
PEN_NAME=$(settings get global ipe_pencil_bt_device_name 2>/dev/null)
[ -z "$PEN_NAME" ] && PEN_NAME="Lenovo Tab Pen Pro"
PEN_MAC=$(settings get global ipe_pencil_mac_addr 2>/dev/null)
[ -z "$PEN_MAC" ] && PEN_MAC="未记录"
PEN_FW=$(settings get global ipe_pencil_fw 2>/dev/null)
[ -z "$PEN_FW" ] && PEN_FW="未知"

# 磁吸与充电
if echo "$HALL_RAW" | grep -q "value = 0"; then
    DOCK_STATUS="已吸附于平板顶部 (磁吸附着)"
else
    DOCK_STATUS="已从平板取下 (手持使用中)"
fi

CHG_STATE=$(settings get global ipe_pencil_charging_state 2>/dev/null)
if [ "$CHG_STATE" = "1" ] || echo "$TX_RAW" | grep -q "cps_wls_en:1"; then
    CHARGE_STATUS="正在无线充电中 ⚡"
else
    CHARGE_STATUS="未在充电 / 电量已满"
fi

BATTERY=$(settings get global ipe_pencil_battery_level 2>/dev/null)
[ -z "$BATTERY" ] || [ "$BATTERY" = "null" ] && BATTERY="--"

# 蓝牙与输入节点
CONN_STATE=$(settings get global ipe_pencil_connect_state 2>/dev/null)
if [ "$CONN_STATE" = "2" ]; then
    BLUETOOTH_STATUS="已连接 (BLE 正常通信)"
elif [ "$CONN_STATE" = "1" ]; then
    BLUETOOTH_STATUS="正在连接中..."
else
    BLUETOOTH_STATUS="未连接 / 休眠"
fi

UHID_DEV=$(grep -sl "Lenovo Tab Pen Pro" /sys/class/input/input*/name 2>/dev/null | head -1)
if [ -n "$UHID_DEV" ]; then
    UHID_NAME=$(cat "$UHID_DEV" 2>/dev/null)
    UHID_STATUS="已绑定虚拟输入设备 ($UHID_NAME)"
else
    UHID_STATUS="待命 (有按键输入时自动激活)"
fi

# 手势配置读取
ACT_SQUEEZE=$(settings get global ipe_pencil_wb_click_squeeze 2>/dev/null)
ACT_LONG=$(settings get global ipe_pencil_wb_click_long_press 2>/dev/null)
ACT_UP=$(settings get global ipe_pencil_wb_click_long_click_v2 2>/dev/null)
ACT_DOWN=$(settings get global ipe_pencil_wb_click_single_click 2>/dev/null)
ACT_DOUBLE=$(settings get global ipe_pencil_wb_click_double_click 2>/dev/null)
[ "$ACT_DOUBLE" = "-1" ] && ACT_DOUBLE=$(settings get global ipe_pencil_double_click 2>/dev/null)

# 系统与后台模块
LSP_STATUS="未安装"
[ -d /data/adb/lspd ] && LSP_STATUS="正常运行 (LSPosed/JingMatrix)"

HOOK_PATH=$(pm path com.futureharmony.lenovopenbridge 2>/dev/null | head -1)
[ -z "$HOOK_PATH" ] && HOOK_PATH=$(pm path com.aclaniakea.lenovopenbridge 2>/dev/null | head -1)
if [ -n "$HOOK_PATH" ]; then
    HOOK_STATUS="已安装并加载核心 Hook"
else
    HOOK_STATUS="未检测到 Hook APK，请检查安装"
fi

if [ -f "$DISABLE_FLAG" ]; then
    MODULE_STATE="【已禁用 (DISABLED)】"
else
    MODULE_STATE="【已启用 (ACTIVE)】"
fi

# ----------------- 格式化输出 -----------------
echo "=============================================="
echo "      Lenovo Tab Pen Pro 手写笔增强系统      "
echo "=============================================="
echo "当前模块状态: $MODULE_STATE"
echo ""
echo "【触控笔硬件与连接】"
echo "  • 设备型号: $PEN_NAME"
echo "  • 蓝牙地址: $PEN_MAC"
echo "  • 固件版本: $PEN_FW"
echo "  • 连接状态: $BLUETOOTH_STATUS"
echo "  • 剩余电量: ${BATTERY}%"
echo "  • 磁吸状态: $DOCK_STATUS"
echo "  • 充电状态: $CHARGE_STATUS"
echo "  • 输入设备: $UHID_STATUS"
echo ""
echo "【当前生效手势配置】"
echo "  • 侧键轻捏 (短按 <0.5秒) : $(format_action "$ACT_SQUEEZE")"
echo "  • 侧键长按 (长捏 ≥0.5秒) : $(format_action "$ACT_LONG") (带笔身震动)"
echo "  • 触控条上滑              : $(format_action "$ACT_UP")"
echo "  • 触控条下滑              : $(format_action "$ACT_DOWN")"
echo "  • 触控条双击              : $(format_action "$ACT_DOUBLE")"
echo ""
echo "【核心服务与后台状态】"
echo "  • LSPosed 框架        : $LSP_STATUS"
echo "  • 笔桥 Hook 核心支持库 : $HOOK_STATUS"
echo "=============================================="
echo ""
echo "【快捷操作说明】"
echo "  • 只看日志     : sh $0 log (本页尾部即最近日志，也可只看日志段)"
echo "  • 裁剪日志     : sh $0 clearlogs (保留最近 5 行，不删文件)"
echo "  • 切换启用/禁用 : sh $0 toggle (免重启即刻生效)"
  echo "  • 快速软重启   : sh $0 reboot (约5秒重载系统框架)"
  echo "  • 睡死的笔     : 重新吸附一次即可恢复（官方固件自身的唤醒方式；"
  echo "                   v0.1.23 起本模块不再尝试软件唤醒）"
echo "  • 手势热配置   : 设置 -> 设备空间 -> 触控笔卡片"
echo "=============================================="
echo ""

do_logs
