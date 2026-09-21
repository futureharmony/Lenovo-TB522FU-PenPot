# PenProbe — 笔 GATT 探针（实验工具，2026-09-21）

广播 + 前台服务驱动的 BLE GATT 探针，用于对深睡笔做下行命令实验。
背景与结论见 `docs/pen_wake_experiment_E0_E4_20260921.md`。

## 构建（本机，无需 Gradle）
```
javac -source 11 -target 11 -cp $SDK/platforms/android-35/android.jar -d build src/com/exp/penprobe/*.java
$BT/d8 --min-api 31 --output dexout build/com/exp/penprobe/*.class
aapt2 link -I $SDK/platforms/android-35/android.jar --manifest AndroidManifest.xml -o probe-unsigned.apk
zip -qj probe-unsigned.apk dexout/classes.dex
apksigner sign --ks debug.keystore --ks-pass pass:android --ks-key-alias dbg --out probe.apk probe-unsigned.apk
```
（改 AndroidManifest.xml 后必须重跑 aapt2 link，否则装的是旧 manifest。）

## 安装与控制（设备）
```
adb push probe.apk /data/local/tmp/ && adb shell pm install -r -t /data/local/tmp/probe.apk
adb shell su -c 'pm grant com.exp.penprobe android.permission.BLUETOOTH_CONNECT; pm grant com.exp.penprobe android.permission.ACCESS_FINE_LOCATION'
# 操作一律走 su + 显式组件（shell 广播被 ColorOS 拦截）：
adb shell su -c 'am start-foreground-service -n com.exp.penprobe/.ProbeService -a penprobe.CONNECT --es address DC:EB:4D:06:77:DD'
adb shell su -c 'am start-foreground-service -n com.exp.penprobe/.ProbeService -a penprobe.WRITE --es uuid 00000008 --es hex 010503000000'
adb shell su -c 'am start-foreground-service -n com.exp.penprobe/.ProbeService -a penprobe.STOP'
adb shell logcat -d | grep PenProbe
```
操作码：CONNECT / WRITE(uuid,hex) / READ(uuid) / LIST / DISCONNECT / STOP。
WRITE 在链路失效时自动重连（4s 后重试）。日志 tag `PenProbe`。
