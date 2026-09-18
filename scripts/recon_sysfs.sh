#!/system/bin/sh
# TB522FU 笔硬件节点侦察脚本（root）
# 用法: adb push recon_sysfs.sh /data/local/tmp/ && adb shell su -c 'sh /data/local/tmp/recon_sysfs.sh'
# 目标：找到 TB710FU 上 pen1_hall/pen2_hall、CPS I2C、lenovo_penraw uevent 的 TB522FU 等价物
OUT=/data/local/tmp/pen_recon.txt
{
echo "=== 1. sysfs virtual 中含 pen/hall/stylus/lenovo/cps 的目录 ==="
ls /sys/devices/virtual/ | grep -iE 'pen|hall|stylus|lenovo|cps|nvt'
for d in $(ls -d /sys/devices/virtual/* 2>/dev/null); do
  case "$d" in
    *pen*|*hall*|*stylus*|*lenovo*|*cps*|*nvt*)
      echo "--- $d"; ls "$d" 2>/dev/null | head -20 ;;
  esac
done

echo "=== 2. 全 sysfs 文件名含 hall 的节点 ==="
find /sys/devices -name '*hall*' 2>/dev/null

echo "=== 3. I2C 设备清单（找充电/笔芯片）==="
for b in /sys/bus/i2c/devices/*; do
  [ -e "$b/name" ] && echo "$b: $(cat $b/name 2>/dev/null)"
done

echo "=== 4. platform 驱动含 pen/lenovo/nvt ==="
find /sys/devices/platform -maxdepth 3 -iname '*pen*' -o -maxdepth 3 -iname '*lenovo*' -o -maxdepth 3 -iname '*nvt*' 2>/dev/null | head -30

echo "=== 5. input 设备与 event 映射 ==="
cat /proc/bus/input/devices | grep -A4 -iE 'pen|stylus'

echo "=== 6. uevent 观察线索：kernel log 中 PEN/hall 关键词 ==="
dmesg | grep -iE 'pen|hall|cps|nvt' | tail -40

echo "=== 7. proc 笔相关节点 ==="
ls /proc/ | grep -iE 'pen|hall|stylus'

echo "=== 8. GPIO 控制器清单（为 CPS 上电重定向准备）==="
cat /sys/kernel/debug/gpio 2>/dev/null | grep -iE 'gpiochip|hall|pen|cps' | head -30

echo "=== 9. 相关属性 ==="
getprop | grep -iE 'pencil|pen|stylus|nvt|touch'
} >"$OUT" 2>&1
echo "done -> $OUT"
