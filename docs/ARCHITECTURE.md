# 架构（各部分职责与契约）

本文件描述仓库的**静态结构**（每个目录/子系统是什么、边界在哪、依赖谁）与**运行链路**（一次刷入到功能生效
经过了哪些代码）。目标与进度见 [STATUS.md](STATUS.md)，长期方向见 [ROADMAP.md](ROADMAP.md)。

---

## 1. 全景

```
                ┌────────────────────── 构建期（GitHub Actions） ──────────────────────┐
DDK 镜像 ──► build-lkm        产出  <kmi>_kernelsu.ko ×8      ┐
susfs4ksu ─► build-susfs-gki  产出  Image.gz / AnyKernel3 ×7  │ 内核侧产物
susfs LKM ─► build-susfs-lkm  产出  susfs_guard_lkm-.ko ×8    ┘（兜底）
third_party/TEESimulator-RS ─┐
third_party/tricky-addon-enh ─┴─► build-builtin-modules.sh ─► userspace/ksud/builtin/**/bin
                                                              │
                                        build-ksud ───────────┴─► ksud（RustEmbed 打包内置模块 + .ko）
                                                              │
                                        build-manager ────────┴─► APK（libxeccli.so = ksud）
                └──────────────────────────────────────────────────────────────────────┘

                ┌────────────────────── 运行期（设备） ───────────────────────┐
boot → ksuinit → kernelsu.ko 装载 → ksud init <阶段> → 内置模块落盘 → sepolicy
     → post-fs-data / post-mount / service / boot-completed 阶段脚本
     → TEE 引擎 + TA 守护启动 → keybox 校验/刷新 → bootloader 状态伪装
                └────────────────────────────────────────────────────────────┘
```

---

## 2. 目录职责

| 路径 | 是什么 | 边界 / 不该做什么 |
|---|---|---|
| `kernel/` | KernelSU 内核模块（Kbuild），含 `CONFIG_KSU_SUSFS` 集成层 | 只写内核侧；UAPI 结构体改动必须同步三副本 |
| `userspace/ksud/` | Rust 用户态：supercall 客户端、模块管理、boot 阶段、`builtin`、`late_load`、boot_patch | 不直接改内核策略；所有特权操作走 supercall 或既有钩子 |
| `userspace/ksud/builtin/` | **内置模块的源目录**（脚本/配置/NOTICE），不含 GPL 二进制 | 二进制只能由 CI 编出；此目录禁止提交 `*.so`/`.dex`/可执行文件 |
| `userspace/ksuinit/` | 早启动装载器（`kernelsu.ko`）| 不承载功能逻辑 |
| `userspace/meta-overlayfs/` | 上游的元模块（OverlayFS）| 未改动 |
| `manager/` | Kotlin + Compose 管理器；`jniLibs/libxeccli.so` 就是 ksud | 不重复实现内核侧逻辑；UI 只调用 ksud/JNI |
| `uapi/` + 两份镜像 | supercall/ioctl/结构体契约 | 三份必须逐字节一致 |
| `third_party/` | 三棵 vendored 上游源码树（TEE 引擎、TA 守护、SUSFS LKM）| 只允许"上游原样 + UPSTREAM.md 里登记的改动" |
| `feature/` | `feature/tee/`：T1/T2 的验收规格与 bash 测试（46 例）| 不随产品打包 |
| `scripts/` | 质量门禁（`check_quality.py` + baseline）、辅助脚本 | 门禁不得被绕过 |
| `js/`, `website/` | 上游 WebUI 包与文档站 | 本仓不部署文档站 |
| `.github/workflows/` | 15 条流水线（见 §3）| 数量上限 15，新增必须先删 |
| `docs/` | 本套文档 | — |

---

## 3. 交付链路（谁产出什么）

| 工作流 | 触发 | 产物 | 说明 |
|---|---|---|---|
| `build-lkm.yml` (+`ddk-lkm.yml`) | push/手动 | `<kmi>_kernelsu.ko` ×8（含 6.18）| 软加载（LKM）路线的内核模块 |
| `build-susfs-gki.yml` | 手动 | `Image.gz-<kmi>` + AnyKernel3 卡刷包 ×7 | **SUSFS 编进内核**的主路线 |
| `build-susfs-lkm.yml` | push(`third_party/susfs4ksu-lkm/**`)/手动/被 manager 调用 | `susfs_guard_lkm-<kmi>.ko` + 每 KMI 模块 zip | 兜底路线（无法刷内核 / 6.18）|
| `ksud.yml` | 被 `build-manager` 调用 | `ksud`（aarch64/x86_64/riscv64）| `pack_lkm`/`pack_ksuinit`/`pack_susfs` 控制内嵌载荷 |
| `ksud-extra.yml` | 被调用 | host 版 ksud ×5 | 桌面端 CLI |
| `ksuinit.yml` | 被调用 | `ksuinit` ×3 | 早启动装载器 |
| `build-manager.yml` | push/PR/被调用 | **APK**（`libxeccli.so` + 内置模块）| 会串起 lkm/ksuinit/susfs/ksud 全部 |
| `release.yml` | 手动/被调用 | Release 资产 | 版本号来自 `version.properties`（见 §5）|
| `guards.yml` | push/PR | — | `scripts/check_quality.py`（8 道门禁）|
| `clippy.yml` / `rustfmt.yml` / `shellcheck.yml` / `clang-format.yml` | push/PR | — | 语言级门禁（clang-format 固定 18.1.8）|
| `npm.yml` | push/PR(`js/**`) | — | 上游 JS 包测试 |

---

## 4. 运行时链路（一次开机）

1. `ksuinit` 早启动装载 `kernelsu.ko`（或在软加载路线由 `ksud late-load` 装载）。
2. `ksud` 以 init 事件被调用，`init_event::on_post_fs_data()`：
   - `builtin::install_builtin_modules()` → 把 `builtin/tricky_store`、`builtin/TA_enhanced` 落盘到
     `/data/adb/ksu/builtin/<id>/`（内容来自 ksud 内嵌资源，**不经过模块体系**）；
   - 加载模块与内置模块的 `sepolicy.rule`；
   - 依次执行 `post-fs-data` → `post-mount`（元模块优先）→ `service` → `boot-completed` 阶段脚本；
   - 若内核未内置 SUSFS 且 APK 带了载荷，则 `late_load::load_susfs_lkm_for_current_kmi()`。
3. 内置阶段的脚本链：
   - `TA_enhanced/post-fs-data.sh` → `common/bootstate.sh::bootstate_spoof_lock`（**回锁欺骗**）；
   - `tricky_store/service.sh` → 启动 TEE 引擎（`inject`/`supervisor`/`classes.dex`）；
   - `TA_enhanced/common/keybox.sh` → keybox 拉取/校验/回滚（**可信密钥**）。
4. 管理器通过 `ksud`/JNI 读写状态，不再重复实现上述逻辑。

---

## 5. 关键契约（改之前必须读）

1. **KMI 命名**：`android<release>-<kernel>`（如 `android15-6.6`）。上游 `tools/pack_module.py` 与
   模块脚本都按这个形状解析，不能改。
2. **内核模块文件名**：`{kmi}_kernelsu.ko`（ksud 按此查表）、`{kmi}_susfs_guard_lkm.ko`（装载器按此查表，
   CI 会把 `susfs_guard_lkm-{kmi}.ko` 重命名后再内嵌）。
3. **内置模块目录布局**：`builtin/<module_id>/` + 可选 `post-fs-data.sh`/`service.sh`/`boot-completed.sh`/
   `post-mount.sh`/`sepolicy.rule`/`system.prop`/`initrc/`；`module_id` 即目录名。
4. **UAPI 三副本**：`uapi/`、`kernel/include/uapi/`、`manager/app/src/main/cpp/uapi/` 逐字节一致。
5. **supercall/ioctl**：编号与结构体布局只允许**追加**，不允许改语义或重排字段。
6. **版本号**：`version.properties` 是唯一真源（`managerVersionCode`/`managerVersionName` 由 Gradle 读取）。
7. **路径常量**：`/data/adb/ksu/builtin`（内置模块）、`/data/adb/ksu/bin`（二进制与 `.ko`）、
   `/proc/susfs_*`（SUSFS 控制面）— 脚本与 Rust 侧都硬编码这些值，改动要全局搜。

---

## 6. SUSFS 的两条路线与边界

| | GKI 内置（主）| LKM 兜底 |
|---|---|---|
| 工作流 | `build-susfs-gki.yml` | `build-susfs-lkm.yml` |
| 形态 | SUSFS 编进 `Image.gz`，刷内核即生效 | 独立 `.ko`，由 `ksud` 落盘 `insmod` / 内存装载 |
| 覆盖 | 7 个 KMI（12-5.10 … 16-6.12）| 8 个 KMI（含 17-6.18）|
| 前置 | 能刷内核 | 无需刷内核，但设备上要有该 `.ko`（内嵌或下载）|
| 内核侧代码 | `CONFIG_KSU_SUSFS=y` → `kernel/` 的 1567 行集成层生效 | 集成层不参与编译（配置关闭）|
| 用户态 | `ksud` 走 supercall 驱动 | `ksud` 写 `/proc/susfs_*` 驱动 |

两条路线的**用户态语义相同**，但内核 ABI 由各自上游决定，不能混用（GKI 用 susfs4ksu 的 pin，LKM 用
susfs4ksu-for-lkm 的 ABI）。

---

## 7. 子系统关键文件

| 子系统 | 关键文件 |
|---|---|
| supercall 路由 | `kernel/supercall/dispatch.c`（`ksu_handle_sys_reboot`）、`kernel/supercall/supercall.c` |
| su 兼容 | `kernel/feature/sucompat.c`、`kernel/hook/setuid_hook.c`、`kernel/runtime/ksud_integration.c` |
| SELinux | `kernel/selinux/{selinux,rules}.c`、`kernel/feature/selinux_hide.c` |
| 内置模块（用户态）| `userspace/ksud/src/{assets,builtin,init_event}.rs` |
| 软加载 / SUSFS 装载 | `userspace/ksud/src/late_load.rs` |
| 启动补丁 | `userspace/ksud/src/boot_patch.rs` |
| 特权客户端 | `userspace/ksud/src/ksucalls.rs` |
| 管理器入口 | `manager/app/src/main/java/me/weishu/kernelsu/ui/MainActivity.kt`、`ui/navigation3/Routes.kt` |
| 验收规格 | `feature/tee/{bootstate,selfcheck,target-gen,keybox-health}.sh` + `feature/tee/test/` |
| 质量门禁 | `scripts/check_quality.py`、`scripts/quality-baseline.json` |
