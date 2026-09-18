#!/system/bin/sh
# Synthesize a REAL touch-strip gesture at the hardware level.
#
# The pen's "Consumer Control" HID interface reports each consumer usage as:
#     EV_MSC MSC_SCAN <hid-usage>   -> InputReader stores it as mCurrentHidUsage
#     EV_KEY KEY_UNKNOWN <down/up>  -> KeyboardInputMapper maps usage->keycode
#                                      via the "key usage" lines in
#                                      Vendor_17ef_Product_622e.kl (F1/F2/F3)
# so writing the same sequence with sendevent exercises the *entire* input
# stack (EventHub -> InputReader -> PhoneWindowManager) exactly like the pen.
#
# usage: pen_gesture.sh <node> <hid-usage-hex>
#   single click : 0c0614   (-> F1 / keycode 131)
#   slide down   : 0c0612   (-> F1 / keycode 131)
#   slide up     : 0c0613   (-> F1 / keycode 131)
#   two-click    : 0c0601   (-> F2 / keycode 132)
#   long press   : 0c0611   (-> F3 / keycode 133)

NODE=$1
USAGE=$2
if [ -z "$NODE" ] || [ -z "$USAGE" ]; then
    echo "usage: $0 <dev-node> <hid-usage-hex>"
    exit 1
fi

echo "sending usage 0x$USAGE to $NODE"

# MSC_SCAN carries the HID usage; must precede the EV_KEY.
sendevent "$NODE" 4 4 "$USAGE"
# key down
sendevent "$NODE" 1 240 1
sendevent "$NODE" 0 0 0
sleep 0.03
# key up
sendevent "$NODE" 1 240 0
sendevent "$NODE" 0 0 0

echo "sent"
