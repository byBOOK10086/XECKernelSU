# 状态（已完成 / 进行中 / 未完成）

> 更新基准：2026-11-12。证据列给出的是**可复核的东西**（工作流名、commit、CI 日志里的实测数字、文件），
> 不是"应该没问题"。没有证据的一律落在「未验证」区。

---

## 1. 四个产品目标

| 目标 | 状态 | 证据 |
|---|---|---|
| **最新可信密钥（TEE）** | ✅ 构建链成立，⚠️ 时效受上游约束 | `build-builtin-modules.sh` 在本仓首跑成功：`classes.dex` 2,459,568 B、`libTEESimulator.so` 206,624 B、`ta-enhanced` 4,725,800 B、`resetprop-rs` 331,912 B；keybox 刷新日志：`DeviceID=Yurikey68`、`candidate leaf valid until Nov 13 16:32:13 2026 GMT`、`bundled keybox already up to date` |
| **回锁欺骗** | ✅ 脚本链完整并在开机执行 | `builtin/TA_enhanced/post-fs-data.sh` → `common/bootstate.sh::bootstate_spoof_lock`；`post-mount.sh` / `prop.sh` 同源调用；`init_event.rs` 的 post-fs-data/post-mount 阶段执行顺序已在架构文档 §4 固定 |
| **SUSFS** | 🟡 主路线编译中；兜底路线已全绿 | 主路线：`build-susfs-gki.yml`（恢复自 `f43afc53`）在 `android12-5.10` 上已过"应用 50_ 补丁 + olddefconfig"，正在 `Build Image.gz`。兜底：`build-susfs-lkm.yml` 8/8 KMI + 打包全绿（`.ko` 3.1–7.8 MB/KMI，单 KMI 模块 zip 1.33 MB）|
| **鸭子 / 春秋对抗** | 🟡 TEE 层已覆盖；隐藏类待 SUSFS 生效 | 引擎侧 11 个补丁文件（含春秋覆盖、错误回包顺序、缓存失效）已编入；SUSFS 系隐藏（`sus_path`/`uname`/SELinux hide）要等 GKI 内核或 LKM 装载后才可用 |

---

## 2. 基础设施

| 项 | 状态 | 证据 |
|---|---|---|
| 上游源码 vendor（三棵树） | ✅ | `third_party/TEESimulator-RS`（v6.0.1-307 / `6d241e56`）、`third_party/tricky-addon-enhanced`（v5.53.1 / `5c5e4975`）、`third_party/susfs4ksu-lkm`（`f95ae4c4`），各带 `LICENSE` + `UPSTREAM.md`/`VENDORED.md` |
| 内置模块由源码现编（不再提交 GPL 二进制） | ✅ | aarch64 ksud 构建日志：`==> Building ta-enhanced + resetprop-rs`、`==> Building TEESimulator-RS engine (Gradle zipRelease)`、`==> Built-in module artifacts:` 全部列出 |
| 内置模块机制（落盘/阶段脚本/sepolicy） | ✅ | `userspace/ksud/src/{builtin,assets,init_event}.rs`；`ROOT = /data/adb/ksu/builtin` |
| SUSFS 用户态装载器 | ✅ | `late_load.rs`：asset → 落盘 → `debug::insmod`（符号解析）→ 纯内存兜底；`init_event` 在 post-fs-data 调用 |
| 质量门禁 | ✅ | `guards` / `Clippy` / `Rustfmt` / `ShellCheck` 在 `38c9f58`、`6ac03fb` 等提交上全绿；`scripts/check_quality.py` 本地绿 |
| 工作流数量上限 | ✅ | 15 个（恢复 `build-susfs-gki.yml` 时删掉 `deploy-website.yml`）|
| 文档 | 🟡 本批新增 | `README.md`、`docs/{UPSTREAMS,ARCHITECTURE,STATUS,ROADMAP,RED-LINES}.md` |

---

## 3. 进行中（有 run 在跑）

| 项 | 起点 | 现状 |
|---|---|---|
| APK 首版 | `build-manager` @ `adb1b4a` | 排队中（macos host ksud 占住 runner）；已修：根 `Cargo.toml` 的 `workspace.exclude`、Gradle wrapper 校验和（62→64 位）|
| GKI SUSFS 内核（android12-5.10）| `38c9f588` | 编译 `Image.gz` 中；已过补丁应用与 `CONFIG_KSU_SUSFS=y` 配置 |
| 其余 6 个 KMI 的 GKI 内核 | — | 等 12-5.10 出结果后并行派发 |

---

## 4. 未完成

| # | 项 | 说明 |
|---|---|---|
| 1 | GKI 内核 7 个 KMI 全绿 | 主路线当前仅验证到"能进编译"；`kernel/` 的 1567 行 SUSFS 集成层此前从未以 `CONFIG_KSU_SUSFS=y` 编译过 |
| 2 | `android17-6.18` 的 SUSFS | susfs4ksu 无 `gki-android17-6.18` 分支 → 该 KMI 只能走 LKM 兜底 |
| 3 | 真机验证 | 无设备接入；T1（密钥链）/T2（bootstate 实读）/`/proc/susfs_*` 均未经真机确认 |
| 4 | 管理器 UI：TEE / SUSFS 状态卡 | 目前只有 JS/内置脚本层，管理器没有对应页面 |
| 5 | 站点/发版接入新仓 | 现有站点流水线只认旧仓 `XECKernelPro`；首版先走 GitHub Release + OpenList 直链 |
| 6 | uapi 三副本守卫 | 旧仓的 `check_uapi_mirror.py` 未搬入；当前靠人工保证逐字节一致 |

### 4.1 已登记的技术债（**本轮按指示先不动**）

| # | 问题 | 影响 |
|---|---|---|
| D1 | `ExternalModulesScreen.kt` 仍从旧仓 release 下载 TEE/TA 模块 zip | 点击会拉到旧仓产物；新仓这两个已是内置 |
| D2 | `RemoteUploader.kt` 硬编码公开仓内的上传账号口令 | 凭据泄露（写权限、限定 `/uploads`）|
| D3 | `feature/tee/*` 是一套不随产品发布的并行实现（仅 `README`/`quality-baseline` 引用）| 与 `builtin/TA_enhanced/common/*` 逻辑重复，存在漂移风险 |
| D4 | `uapi/` 三副本无自动守卫 | 同上第 6 项 |
| D5 | 仓库整洁度 | 1413 文件 / 16.9 MB；仅 3 个 >1 MB 文件（busybox ×3，上游自带）；无构建垃圾、无孤儿 Rust 模块 |

---

## 5. 已知风险与限制（发布说明必须带上）

1. **keybox 时效**：内置箱 `Yurikey68` 叶子 **2026-11-13 16:32:13 UTC** 到期。到期后上游若未换代，
   TEE 卡会变红；运行时拉取可缓解，但设备需联网。
2. **首版无真机验证**：CI 只能证明"编译/打包/门禁"成立，不能证明设备上 TEE/bootstate/SUSFS 的实际表现。
3. **SUSFS 时效**：GKI 路线以 susfs4ksu 的 per-KMI pin 为准（见 [UPSTREAMS.md](UPSTREAMS.md) §2.1），
   上游若改 ABI，必须重新钉 SHA 并与 `ksud` 的 verb 集合对齐。
4. **体积**：`ksud-aarch64` 产物 23.3 MB（含两套内置引擎）；SUSFS `.ko` 每 KMI 3.1–7.8 MB，**不进 APK**。
