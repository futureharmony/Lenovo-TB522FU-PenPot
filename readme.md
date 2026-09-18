# tb522fu-pen-port — 联想手写笔 TB522FU 彻底适配

把 coloros-pad-fixes 的 `lenovo_pen_bridge`（ACLaniakea，v4.1.3，面向 TB710FU / SM8650Q / pineapple）移植到 **Lenovo TB522FU**（`sun` / SM8750P / 骁龙 8 Elite，运行移植版 ColorOS）。

## 结构

```
module/       KernelSU/Magisk Root 模块（含禁用 inkdye 逻辑）
hook/         LSPosed Hook 源码（com.aclaniakea.lenovopenbridge）
penhidctl/    priv-app HID 控制器源码
scripts/      设备侦察/构建辅助脚本
docs/         分析与验证记录
```

## 安装/回退

- 刷入模块 → 首次开机自动 `pm disable-user com.inkdye.lenovopentocoloros`（系统内置笔桥，避免双桥打架）。
- 卸载模块 → 自动 `pm enable` 恢复 inkdye，随时可回退。
- 手动恢复：`su -c 'pm enable com.inkdye.lenovopentocoloros'`。

## 已完成的移植改动

1. `hook/.../DeviceGate.java`：SM8650Q/PINEAPPLE → SM8750P/SUN
2. `module/service.sh`：Root 侧设备门同步改；新增 inkdye 禁用/恢复逻辑
3. `module/module.prop`、`customize.sh`：模块 ID 改为 `tb522fu_pen_bridge`，避免与原模块冲突

## 待验证 / 进行中

见 `TODO.md`。最关键卡点：**Hall/CPS 硬件节点**（TB710FU 的 `pen1_hall/pen2_hall`、CPS I2C/GPIO 在 TB522FU 均不存在，需先跑 `scripts/recon_sysfs.sh` 找替代节点）。

上游与背景分析：
- 上游仓库：`../coloros-pad-fixes`（仅读，不改动）
- 方案对比：`../pen-bridge-comparison.md`
- 难度评估：`../pen-bridge-porting-difficulty.md`
