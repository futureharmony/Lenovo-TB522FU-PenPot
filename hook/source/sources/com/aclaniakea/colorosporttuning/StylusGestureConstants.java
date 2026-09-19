package com.aclaniakea.colorosporttuning;

/**
 * Shared gesture constants and labels for the stylus bridge ecosystem.
 */
public final class StylusGestureConstants {
    private StylusGestureConstants() { }

    public static final String GESTURE_ACTIVITY_CLASS =
            "com.oplus.ipemanager.btadsorb.setting.activity.PencilGestureSettingActivity";

    public static final int[] CUSTOM_CODES = {
            101, 102, 103, 104, 105, 106,            // 系统快捷
            107, 108, 109, 110, 111, 112, 113       // 书写扩展
    };

    public static final String[] CUSTOM_LABELS = {
            "截图", "通知栏", "返回", "主屏", "最近任务", "手电筒",
            "撤销", "重做", "翻页上", "翻页下",
            "手写便签", "圈选识别", "圈选翻译"
    };

    public static final String[] CUSTOM_SUMMARIES = {
            "截取当前屏幕并进入编辑",
            "展开通知面板",
            "模拟返回键",
            "回到桌面",
            "打开最近任务",
            "切换手电筒",
            "向焦点应用发送 Ctrl+Z",
            "向焦点应用发送 Ctrl+Y",
            "向上翻页（阅读/演示）",
            "向下翻页（阅读/演示）",
            "呼出全屏手写浮窗，随手记一笔",
            "用笔圈选屏幕区域并提取文字",
            "用笔圈选屏幕区域并翻译"
    };

    public static final int[] GROUP_WRITING = {6, 7, 8, 9, 10, 11, 12};
    public static final int[] GROUP_SYSTEM = {0, 1, 2, 3, 4, 5};

    public static final String[] STOCK_ROW_KEYS = {
            "item_item_close", "item_erase", "item_recent_tool",
            "item_color_picker", "item_global_wheel", "item_smart_collect"
    };

    public static final java.util.Map<String, Integer> STOCK_LABEL_VALUES =
            new java.util.HashMap<>();
    static {
        STOCK_LABEL_VALUES.put("关闭", 0);
        STOCK_LABEL_VALUES.put("当前工具与橡皮擦切换", 1);
        STOCK_LABEL_VALUES.put("最近工具切换", 2);
        STOCK_LABEL_VALUES.put("显示颜色盘", 3);
        STOCK_LABEL_VALUES.put("手写笔轮盘", 4);
        STOCK_LABEL_VALUES.put("随心圈", 5);
    }

    public static String gestureWbKey(String clickType) {
        return "ipe_pencil_wb_click_" + clickType;
    }

    public static String titleToGestureType(String txt) {
        if ("下滑触控条".equals(txt)) return "single_click";
        if ("双击触控条".equals(txt)) return "double_click";
        if ("上滑触控条".equals(txt)) return "long_click_v2";
        return null;
    }

    public static String getCustomLabel(int code) {
        if (code >= 101 && code <= 100 + CUSTOM_LABELS.length) {
            return CUSTOM_LABELS[code - 101];
        }
        return null;
    }
}
