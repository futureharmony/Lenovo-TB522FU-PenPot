# AGENTS.md
- 单 readme.md；任务进展回写 TODO.md（完成后打勾并注明日期）。
- 上游 ../coloros-pad-fixes 只读，移植差异全部落在本仓库并在 readme「已完成的移植改动」登记。
- 入口脚本：构建用 module/tools/build_root.py 与 hook/tools/build_hook_source.py；设备侦察用 scripts/recon_sysfs.sh。
- 硬件路径（Hall/CPS/GPIO 线号）禁止沿用 TB710FU 数值，必须以本机侦察结果为准。
- 破坏性验证前先确认模块可回退（inkdye enable 恢复路径必须始终可用）。
