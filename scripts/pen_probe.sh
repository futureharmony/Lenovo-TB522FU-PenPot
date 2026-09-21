#!/system/bin/sh
# 一次性笔状态探针（打印一行）。纯诊断，不属于模块发布内容。
TX=/sys/bus/i2c/devices/11-0041/tx_status
t=$(date '+%T')
h=$(cat /sys/devices/virtual/hall/och1909/hall3 2>/dev/null); h=${h##* }
tx=$(cat "$TX" 2>/dev/null | tr -d ' ' | grep -o 'cps_wls_en:[01]' | cut -d: -f2)
on=$(cat /sys/class/power_supply/cps_wls_tx/online 2>/dev/null)
cap=$(cat /sys/class/power_supply/cps_wls_tx/capacity 2>/dev/null)
le=$(dumpsys bluetooth_manager 2>/dev/null | grep -c 'LE:Y.*Lenovo Tab Pen')
echo "  $t hall=$h tx=$tx online=$on cap=$cap penLE_acl=$le"
