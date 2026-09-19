# 圈选翻译黑屏：`com.coloros.translate` 走错区域端点（2026-09-19）

## 1. 现象

手写笔圈选 → 选「翻译」→ ColorOS 翻译的结果页**整屏纯黑**（`screencap` 采样为单一
`#000000`，100%），既不报错也不出内容。用户原话："翻译界面黑屏。"

## 2. 结论（先给答案）

不是我们截图的问题，也不是分享通道的问题：我们交给 App 的 PNG 完全正常
（`pen_translate_*.png`，有内容、平均亮度 62.8）。黑屏是因为 **App 把服务端打成
已经不存在的域名**，拿什么都没有。

```
b2/a.k()  SystemProperties "persist.sys.oplus.region"  ← 设备上是 CN
        ↓ utils/x.b()  assets/country_region_mapping.json  →  没产出 cn
        ↓ b2/a.l()   switch {cn, us, in, eu}  DEFAULT = "sg"
→ https://aitool-cuiocr-sg.heytapmobi.com/aiendpoints/...
  ↑ 这个域名在 DNS 上已经消失
```

`b2/a.l()` 的最后一道 switch **只有 cn/us/in/eu 四条命中分支**，其余（含上游取值失败）
一律落到 default `"sg"`。本机这条链没能产出 `"CN"`，于是所有请求都指向 sg。

## 3. 排查过程（可复现）

| 步骤 | 手段 | 结果 |
|---|---|---|
| 截图是不是黑的？ | 拉出 `pen_translate_*.png` 逐像素统计 | 252×181、`uniq=1050`、平均亮度 62.6 —— 图是好的 |
| App 到底请示了谁？ | logcat 看 `OppoTranslation.NetworkPerformance` | `aitool-cuiocr-sg.heytapmobi.com` + `UnknownHostException` |
| 域名死了还是只是本机 DNS 坏了？ | 在 Mac 上 `dig` | Mac 上也 **NXDOMAIN** —— 域名本身退役了 |
| 换 DNS/改 hosts 能不能救？ | `curl --resolve SG名字:CN_IP` | TLS 握手 OK（证书是通配 `*.heytapmobi.com`），但返回 **404** |
| 为什么 404？ | 同一路径换成 CN 主机名再发一次 | **200** —— 网关按 **HTTP Host 头**路由，靠重定向 DNS 无效 |
| 上游为什么要 sg？ | 反编译 `b2/a.k()`、`x.a/x.b/c`、对照 `assets/country_region_mapping.json` | `{"cn":["CN","OC"], ...}`，表里 CN 只映射 cn；可见是没有取到值导致空列表，最后由 `b2/a.l()` 兜底成 sg |
| 改 `persist.sys.oplus.region` 有用吗？ | `setprop ... ZZ` 后重启 App 再试 | **没用**，依旧 sg → 不是属性值的问题，取值链本身没走通 |

## 4. 修复

新增 `TranslateRegionHooks`（作用域限定 `com.coloros.translate`），双保险：

1. **钉住区域**：hook `b2.a.l(String, boolean)` 直接 `setResult("cn")`；同时 hook
   `com.oplus.aiunit.translation.utils.DomainBuilder#getToolboxHostNameByRegion`
   把入参改成 `"cn"`，让 App 自己选 CN 模板。
2. **兜底改写**：hook `okhttp3.Request$Builder#url(...)`，任何含 `-sg.heytapmobi.com`
   的最终 URL 都换成 `-cn.`。App 每次发版都会重混淆一遍类名方法名，这层即使 1 全部失效
   也能保住链路。

所有目标都懒加载 + 吞异常：新版里符号改名不会拖垮该进程的其他功能。

作用域同步加到 8 → **9 项**（`META-INF/xposed/scope.list`、`res/values/arrays.xml`、
运行时 `vector-cli scope set`）。

## 5. 走过的弯路（别再试）

- ❌ **`/system/etc/hosts` 或 DNS 把 sg 域名指到 CN 服务器**：TLS 能过，但网关按 Host
  头路由，结果全是 404。为此曾经装过一个 `tb522fu_translate_hosts` 模块，已卸载，
  `module/system/etc/hosts` 也已删除。
- ❌ **改 `persist.sys.oplus.region`**：属性本来就是 CN，改了也不影响结果。

## 6. 验证方法

```sh
adb shell settings put global lenovo_pen_debug_actions 1
# 免交互：直接截固定区域并交给翻译（不需要用笔画）
adb shell am broadcast -a com.aclaniakea.lenovopenbridge.RUN_ACTION --ei code 114
# 看路由是否已经是 CN
adb logcat -d | grep -iE "TranslateRegionHooks|MenuRepository|NetworkPerformance"
adb logcat -d | grep -i "aitool-cuiocr-cn"
```

⚠️ **必须在解锁且已进桌面时做**：这个 App 只 partially direct-boot-aware，设备锁屏时
PackageManager 会过滤掉它的组件，`am start -n` 会报
"Activity class ... does not exist"，adb 侧无法自解锁验证。
