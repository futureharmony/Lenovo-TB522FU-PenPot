#!/system/bin/sh
# Enumerate input event nodes so we can pick the Lenovo Consumer Control node.
for i in 0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
    n=$(cat /sys/class/input/event$i/device/name 2>/dev/null)
    if [ -n "$n" ]; then
        echo "event$i = $n"
    fi
done
