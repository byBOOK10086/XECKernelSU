# XECKernelSU

KernelSU 的分支：**一次刷入**同时得到三件事——可信密钥（内置 TEE 引擎）、回锁欺骗（bootloader 状态伪装）、
SUSFS（内核内置，不是外挂模块）。

本仓库是一个从零重建的工作树（不是旧仓 `XECKernelPro` 的直接续写），保留上游 KernelSU 的骨架与 UAPI
契约，差异集中在 `third_party/`、`userspace/ksud/builtin/`、`kernel/` 的 SUSFS 集成，以及构建流水线。

## 上游声明

本项目的绝大部分地基来自开源社区。完整清单（项目 / 用途 / 固定版本 / 许可证 / 我们改了什么 / 链接）见
**[docs/UPSTREAMS.md](docs/UPSTREAMS.md)**，机器可读的索引见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

一句话概览：

| 上游 | 作用 | 许可证 |
|---|---|---|
| [tiann/KernelSU](https://github.com/tiann/KernelSU) | 整个骨架：内核模块、ksud、管理器 | GPL-3.0（kernel 目录 GPL-2.0）|
| [simonpunk/susfs4ksu](https://gitlab.com/simonpunk/susfs4ksu) | SUSFS 内核实现与 hook 点 | GPL-2.0 |
| [inforcqb/susfs4ksu-for-lkm](https://github.com/inforcqb/susfs4ksu-for-lkm) | SUSFS 可加载模块（兜底路线） | GPL-2.0 |
| [Enginex0/TEESimulator-RS](https://github.com/Enginex0/TEESimulator-RS) | TEE/密钥证明模拟引擎 | GPL-3.0 |
| [Enginex0/tricky-addon-enhanced](https://github.com/Enginex0/tricky-addon-enhanced) | ta-enhanced 守护：keybox 拉取/校验、bootstate | GPL-3.0 |
| [JingMatrix/LSPlt](https://github.com/JingMatrix/LSPlt) | PLT hook（随引擎编入 `.so`） | LGPL-3.0 |
| [Enginex0/resetprop-rs](https://github.com/Enginex0/resetprop-rs) | 属性读写 CLI | MIT |

**不进仓的上游**：AGPL-3.0 或无许可证的项目一律不 vendor、不编译、不分发（历史裁决，见
[docs/RED-LINES.md](docs/RED-LINES.md)）。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 各目录/子系统的职责、边界、依赖方向与关键契约 |
| [docs/STATUS.md](docs/STATUS.md) | 已完成 / 进行中 / 未完成，逐项带证据 |
| [docs/ROADMAP.md](docs/ROADMAP.md) | 发展方向（近期 / 中期 / 远期）|
| [docs/RED-LINES.md](docs/RED-LINES.md) | 开发红线：不可协商的技术与合规约束 |
| [docs/UPSTREAMS.md](docs/UPSTREAMS.md) | 上游声明（含固定版本、改动表、更新端点）|
| [docs/QUALITY-GATES.md](docs/QUALITY-GATES.md) | 本地/CI 门禁与预算棘轮规则 |
| [AGENTS.md](AGENTS.md) | 给自动化代理的仓库操作约定 |

## 刷入方式（概览）

1. 装管理器 APK（内含 `ksud` 与两个内置引擎）；
2. 刷对应 KMI 的内核包（内核里已编入 SUSFS）；
3. 首次开机：`/data/adb/ksu/builtin/` 自动落盘 → TEE 引擎与 TA 守护启动 → keybox 校验/刷新 →
   bootloader 状态伪装生效。

具体命令与 KMI 对应关系见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) 的「交付链路」一节。

## 许可证

本仓库整体 GPL-3.0（见 `LICENSE`）；`kernel/` 目录为 GPL-2.0；`third_party/` 内各子树保留其原始许可证。
