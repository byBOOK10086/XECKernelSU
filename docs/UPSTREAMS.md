# 上游声明（Upstreams）

本文件是**对外声明**：说明本仓库建立在哪些开源项目之上、各自固定在哪一版、我们改了什么、以及哪些东西
我们明确**不使用**。机器可读的索引见 [`../THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)；
每棵 vendored 子树内部还有更细的 `UPSTREAM.md` / `VENDORED.md`（逐文件改动表）。

---

## 1. 骨架：tiann/KernelSU

| 项 | 值 |
|---|---|
| 仓库 | <https://github.com/tiann/KernelSU> |
| 用途 | 整个工程骨架：内核模块（`kernel/`）、`ksud`（`userspace/ksud/`）、管理器（`manager/`）、UAPI、`ksuinit` |
| 追踪方式 | 跟随上游 `main`（不做逐提交 rebase；fork 差异集中在 `third_party/`、`builtin/`、SUSFS 集成与 CI）|
| 许可证 | 根 `LICENSE` = GPL-3.0；`kernel/` = GPL-2.0（上游同款拆分）|
| 我们的改动 | `kernel/` 增加 `CONFIG_KSU_SUSFS` 集成（见 §2）；`userspace/ksud/` 增加内置模块机制与 SUSFS 装载（见 §3.3/§3.4）；`manager/` 增加主题/壁纸/终端/工具箱/隐身入口等页面；`.github/workflows/` 重写 |

KernelSU 的 UAPI（`uapi/*.h`）在本仓库有三份副本（`uapi/`、`kernel/include/uapi/`、
`manager/app/src/main/cpp/uapi/`），必须逐字节一致，见 [RED-LINES.md](RED-LINES.md) 第 5 条。

---

## 2. SUSFS 的两条上游线

### 2.1 susfs4ksu（内核内置实现）

| 项 | 值 |
|---|---|
| 仓库 | <https://gitlab.com/simonpunk/susfs4ksu>（镜像：<https://github.com/ShirkNeko/susfs4ksu>）|
| 用途 | SUSFS 的内核实现本体：`fs/susfs.c`、`include/linux/susfs*.h`，以及 `50_add_susfs_in_gki-<KMI>.patch` 里的 VFS/exec/stat/reboot/input 挂点 |
| 许可证 | GPL-2.0 |
| 固定版本 | 每个 KMI 分支一个提交（**不许跟分支头**，内核 ABI 与用户态 verb 集合一一对应）：<br>`android12-5.10` = `5436438b5cc77f146e04f71070da153dabe17a9d`<br>`android13-5.10` = `1bea9e8fb4a64920f068e5da43f9751dfe601199`<br>`android13-5.15` = `fe943d15ec6a1891f7a360bf210589bdffe4e318`<br>`android14-5.15` = `10751e3c83f6e63f886cd2af937fd0a34463824b`<br>`android14-6.1` = `69b8e734920b8f9ed885ab8d112969719698e242`<br>`android15-6.6` = `ec03d0c73b821afa1d5037d6d493216f59744fcb`<br>`android16-6.12` = `395a8c0c88d348e9fe7c14f66a456fde4653287c` |
| 我们的改动 | 不改上游代码；补丁在 `.github/workflows/build-susfs-gki.yml` 里按 KMI 应用，然后与本仓 `kernel/`（`CONFIG_KSU_SUSFS=y`）一起编译 |
| 已知缺口 | 上游尚无 `gki-android17-6.18` 分支 → 6.18 只能走 §2.2 的可加载模块兜底 |

### 2.2 susfs4ksu-for-lkm（可加载模块，兜底路线）

| 项 | 值 |
|---|---|
| 仓库 | <https://github.com/inforcqb/susfs4ksu-for-lkm> |
| 位置 | `third_party/susfs4ksu-lkm/`（129 文件，GPL-2.0，`LICENSE` 同目录）|
| 固定版本 | commit `f95ae4c4c7df7050b2e13bc5d26403e5e0786d00`（依赖注入说明见 `VENDORED.md` / `PROVENANCE.txt`）|
| 用途 | 无法刷内核的机型 / 6.18：把 SUSFS 作为 `.ko` 装载，暴露同一套 `/proc/susfs_*` 控制面 |
| 它自己声明的上游 | [susfs4ksu](https://gitlab.com/simonpunk/susfs4ksu)（功能与钩子）、[SukiSU-Ultra](https://github.com/SukiSU-Ultra/SukiSU-Ultra)（`patch_memory` / `lsm_hook` / `symbol_resolver`）、[KallRecon](https://github.com/Dere3046/KallRecon) |
| 我们的改动 | 树内零改动；`.github/` 不 vendor，用本仓 `build-susfs-lkm.yml` 按 digest 钉死的 DDK 镜像编译 |

---

## 3. 可信密钥链（TEE）

### 3.1 Enginex0/TEESimulator-RS

| 项 | 值 |
|---|---|
| 仓库 | <https://github.com/Enginex0/TEESimulator-RS> |
| 位置 | `third_party/TEESimulator-RS/`（230 文件）|
| 固定版本 | tag `v6.0.1-307`（versionCode 307），commit `6d241e56d6e8146cd67ed4ac3dadc9c632969549` |
| 许可证 | GPL-3.0（含 LGPL-3.0 的 LSPlt、Apache-2.0 的 AOSP 头、GPL-2.0 的 Linux UAPI 头）|
| 用途 | 编译成内置 `tricky_store` 模块的引擎：`classes.dex`、`libTEESimulator.so`、`libcertgen.so`、`inject`、`supervisor` |
| 我们的改动 | 11 个文件（keystore 错误回包顺序、KeyMint 拒绝时伪造 key、春秋检查覆盖、TEE 响应缓存失效、升级时换引擎、gradle wrapper 超时），逐条列在 `third_party/TEESimulator-RS/UPSTREAM.md` |
| 更新端点 | <https://raw.githubusercontent.com/Enginex0/TEESimulator-RS/main/module/update.json> |
| 技术渊源 | 源自 [5ec1cff/TrickyStore](https://github.com/5ec1cff/TrickyStore)（GPL-3.0）|

### 3.2 Enginex0/tricky-addon-enhanced

| 项 | 值 |
|---|---|
| 仓库 | <https://github.com/Enginex0/tricky-addon-enhanced> |
| 位置 | `third_party/tricky-addon-enhanced/`（70 文件：`rust/` + `external/resetprop-rs/`）|
| 固定版本 | tag `v5.53.1`（versionCode 50058），commit `5c5e49751dd844ea446c7250f09082dc61489f81` |
| 许可证 | GPL-3.0 |
| 用途 | 编译成内置 `TA_enhanced` 模块的守护进程 `ta-enhanced` 与 `resetprop-rs` CLI |
| 我们的改动 | 让守护进程适配"内置模块"形态（内置引擎目录发现、Google RKP EC 信任锚 + SEC1 私钥解析 + 全链有效期、props/security-patch 变体、包枚举、region、冲突检测、状态任务），按区域列表见 `third_party/tricky-addon-enhanced/UPSTREAM.md` |
| 更新端点 | <https://raw.githubusercontent.com/Enginex0/tricky-addon-enhanced/main/update.json> |

### 3.3 Enginex0/resetprop-rs

| 项 | 值 |
|---|---|
| 仓库 | <https://github.com/Enginex0/resetprop-rs> |
| 位置 | `third_party/tricky-addon-enhanced/external/resetprop-rs/`（固化为普通文件，不走 submodule）|
| 固定版本 | commit `4646d28a2de49c139025abc5f6d0357cd863320d` |
| 许可证 | MIT |
| 说明 | 上游 `v5.53.1` 已移除该 submodule；我们保留快照，因为内置模块仍需独立的 `resetprop-rs` |

### 3.4 JingMatrix/LSPlt

| 项 | 值 |
|---|---|
| 仓库 | <https://github.com/JingMatrix/LSPlt> |
| 位置 | `third_party/TEESimulator-RS/app/src/main/cpp/external/LSPlt/` |
| 固定版本 | `3e29437f037cb7d2b9fbb459dcf162f6b8d1d926`（TEESimulator-RS 在该 tag 的 submodule 指针）|
| 许可证 | LGPL-3.0（完整源码与构建配方随仓提供，满足 LGPL 要求）|

### 3.5 数据与 keybox

- **keybox 数据**：构建时由 `.github/scripts/refresh-keybox.sh` 从
  [Yurii0307/yurikey](https://github.com/Yurii0307/yurikey) 拉取公开箱子（base64 源 + 明文镜像），
  解出来校验结构/有效期/代次后才写入 `userspace/ksud/builtin/tricky_store/keybox.xml`；拿不到就保留仓内副本。
  运行时 `TA_enhanced` 还会自行拉取/校验（双源 + 镜像）。
- **target 列表**：`builtin/TA_enhanced/common/` 下的基线/镜像列表参考
  [KOWX712/Tricky-Addon-Update-Target-List](https://github.com/KOWX712/Tricky-Addon-Update-Target-List)。

---

## 4. 随上游保留但由 CI/依赖系统提供的组件

| 组件 | 来源 | 许可证 | 说明 |
|---|---|---|---|
| busybox | 随 KernelSU 上游（`userspace/ksud/bin/*/busybox`）| GPL-2.0 | 未修改，用于模块脚本与下载兜底 |
| ksuinit | 本仓 `userspace/ksuinit`（源自 KernelSU）| GPL-3.0 | 早启动装载 `kernelsu.ko` |
| miuix / Compose / Kotlin | Gradle 依赖（不 vendor）| Apache-2.0 等 | 管理器 UI |
| DDK 镜像 | `ghcr.io/ylarod/ddk-min:<kmi>-20260828@sha256:…` | — | 内核编译环境，全部按 digest 钉死 |

---

## 5. 明确**不使用**的上游

| 项目 | 原因 |
|---|---|
| 上游改为 AGPL-3.0 的项目 | 许可证强度超出本项目可接受范围（用户裁决：不留、不编、不分发）|
| 无许可证的项目（如部分 TrickyStore 衍生工程）| 无授权即不可分发 |
| OhMyKeymint（AGPL-3.0）| 同上；只作行为参考，不引入代码 |
| 旧仓 `XECKernelPro` 的 KPM/APatch 相关模块 | 与本仓形态无关，且部分许可证不合规 |

---

## 6. 我们的原创部分

以下内容不是来自上游（或在上游基础上重写）：

1. `kernel/` 的 SUSFS 集成层（`CONFIG_KSU_SUSFS`）：supercall 路由（`ksu_handle_sys_reboot`）、
   sucompat/setuid/fstat 钩子分流、静态 key、`selinux_hide.c`、`susfs_set_batch_sid` 等。
2. `userspace/ksud/` 的内置模块机制：`assets.rs`（RustEmbed）、`builtin.rs`（落盘/校验/sepolicy/阶段脚本）、
   `init_event.rs` 的阶段接线。
3. `late_load.rs` 的 SUSFS 装载器（`.ko` 落盘 → `insmod` → 纯内存兜底）。
4. `userspace/ksud/builtin/**` 的模块脚本改造（keybox、bootstate、一致性检查等）。
5. `feature/tee/` 的 T1/T2 验收规格与测试。
6. `.github/workflows/` 全部 15 条流水线、`scripts/` 的质量门禁与辅助脚本。
7. `manager/` 的主题商城、壁纸、终端、工具箱、隐身入口等页面。
8. 本 `docs/` 目录。

---

## 7. 声明与联系

- 所有对上游的修改都按 GPL-3.0 §5(a) 在对应 `UPSTREAM.md` 中标注（文件、改动、日期、原因）。
- 如你是上游作者并认为本仓库的署名/改动标注有遗漏，请开 issue，我们会立即修正。
- 本项目与 Google、小米、KernelSU 官方、SUSFS 作者均无隶属关系；所有商标归其各自所有者。
