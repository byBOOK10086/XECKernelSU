<div align="center">

# 开发红线 · Red Lines

**12 条不可协商的规则。违反其中任何一条的改动，无论功能多好都不得合并。**

[← 返回 README](../README.md) · [上游声明](UPSTREAMS.md) · [架构](ARCHITECTURE.md) · [进度](STATUS.md) · [路线](ROADMAP.md)

</div>

---

## 速查

| # | 红线 | 严重度 |
|:--:|---|:--:|
| 1 | AGPL / 无许可证上游，一律不留、不编、不分发 | 🔴 阻断 |
| 2 | 仓库里不得出现由 GPL 源码编出的预编译二进制 | 🔴 阻断 |
| 3 | 功能不得退化成"再装一个模块" | 🔴 阻断 |
| 4 | 包名身份必须唯一 | 🔴 阻断 |
| 5 | UAPI 三副本逐字节一致，且只允许追加 | 🔴 阻断 |
| 6 | 第三方必须钉死版本，禁止跟分支头 | 🟠 高 |
| 7 | 密钥、口令、token 不入库 | 🔴 阻断 |
| 8 | 没有证据，不写"已修复 / 已支持" | 🟠 高 |
| 9 | 门禁不可绕过 | 🔴 阻断 |
| 10 | 不碰 SELinux 的常态化降级 | 🔴 阻断 |
| 11 | 不破坏上游同步性 | 🟠 高 |
| 12 | 诚实标注"验证边界" | 🟠 高 |

---

## 1 · 许可证：AGPL / 无许可证上游一律拒绝 🔴

**为什么**：本项目以 GPL-2.0（内核）+ GPL-3.0（用户态）分发。引入更强 copyleft 或"无授权"代码，
整个产物即不可分发。历史裁决：宁可少一个功能。

**怎么判**：新增 `third_party/` 目录必须在 PR 附上游 `LICENSE` 与当前许可证名。
AGPL / 无 LICENSE / "仅研究用途"自定义条款 → 拒。只作行为参考、不引代码是允许的（需在
[UPSTREAMS §5](UPSTREAMS.md#5-明确不使用的上游) 记明）。

---

## 2 · GPL 合规：不提交预编译二进制 🔴

**为什么**：`userspace/ksud/builtin/` 会被 RustEmbed 打进 ksud 再进 APK；提交二进制等于只发二进制不发源码。

**怎么判**：`builtin/**` 只允许脚本 / 配置 / NOTICE。`*.so`、`*.dex`、可执行文件一律拒。
产物由 `.github/scripts/build-builtin-modules.sh` 在 CI 从 `third_party/` 现编。

---

## 3 · "一次刷入"不得退化 🔴

**为什么**：产品定义就是"装管理器 + 刷对应 KMI 内核 = 全都有"。

**怎么判**：TEE 引擎、TA 守护、bootstate、SUSFS 必须由内置模块或内核镜像提供。
新增"用户手动安装"步骤需单独审批，并在 [STATUS §4](STATUS.md#4-未完成--技术债) 登记例外。

---

## 4 · 包名身份唯一 🔴

**为什么**：历史上错配过一次——`su_daemon` 认不出管理器、首授权死锁、`boot_patch` 备份目录建不出来。

**怎么判**：改包名时同时检查 `manager/app/build.gradle.kts`、`userspace/ksud/src/lib.rs`（烘入常量）、
`userspace/ksud/builtin/**/*.sh`、target 列表；`scripts/check_package_identity.py` 存在时必须绿。

---

## 5 · UAPI 三副本 + 只允许追加 🔴

**为什么**：内核、ksud、管理器 JNI 各读一份 `uapi/*.h`，不一致 = 运行时静默错位。

**怎么判**：`uapi/`、`kernel/include/uapi/`、`manager/app/src/main/cpp/uapi/` 三份 hash 相同；
supercall / ioctl **编号与结构体布局只允许追加**，禁止改语义、重排字段、复用旧编号。

---

## 6 · 第三方必须钉死版本 🟠

**为什么**：`susfs4ksu` 的 ABI 与 `ksud` 的 verb 一一对应；引擎换行为会静默改变检测结果。

**怎么判**：`third_party/` 用 tag/commit（写在 `UPSTREAM.md`）；上游补丁用 per-KMI SHA；
容器镜像用 `@sha256:`；构建取的 Gradle 发行包校验 sha256。

---

## 7 · 凭据不入库 🔴

**为什么**：公开仓库里的凭据等于已泄露。

**怎么判**：diff 出现 `ghp_` / `github_pat_` / `BEGIN … PRIVATE KEY`（公开 keybox 除外）以外的凭据 → 拒。
已知例外（**待清理 D2**）：`RemoteUploader.kt` 内的上传账号口令。

---

## 8 · 没有证据，不写"已修复 / 已支持" 🟠

**为什么**：CI 绿 ≠ 语义对；编译过 ≠ 设备上生效。

**怎么判**：功能声明必须附证据（工作流名 + 提交 + 关键日志行，或真机命令输出）。
未验证项必须出现在 [STATUS §5](STATUS.md#5-已知风险与限制发布说明必须带上)。

---

## 9 · 门禁不可绕过 🔴

**为什么**：门禁是唯一能挡住"悄悄变差"的机制。

**怎么判**：`check_quality.py` 8 道门禁、`clippy`（`-Dwarnings` + pedantic）、`rustfmt`、`shellcheck`、
`clang-format`（固定 **18.1.8**）必须绿。文件大小棘轮（`MAX_LINES = 400`）只允许**一次性、有记录的预算重钉**
（写进 [QUALITY-GATES](QUALITY-GATES.md) 与 commit message）。工作流上限 15，新增先删。

---

## 10 · 不碰 SELinux 常态化降级 🔴

**为什么**：`setenforce 0` / permissive 既危险又是检测面。

**怎么判**：不得新增"关掉/放宽 SELinux"的逻辑。需要绕过策略时走既有钩子
（`selinux_hide.c`、SUSFS avc spoof、`ksu_handle_*` 分流），并说明它为何等价于合法状态。

---

## 11 · 不破坏上游同步性 🟠

**为什么**：长期维护成本取决于能否继续吃上游 KernelSU 的修复。

**怎么判**：fork 差异集中在 `third_party/`、`builtin/`、SUSFS 集成层、CI；大规模重写上游通用逻辑
（尤其 `manager/`、`kernel/`）需在 PR 说明收益与未来同步方案。

---

## 12 · 诚实标注验证边界 🟠

**为什么**：用户依赖发布说明判断能不能刷。

**怎么判**：每个 Release 必须写清哪些结论来自 CI、哪些来自真机；没验证的写"未验证"，不能省略。

---

## Review 检查清单

复制到 PR 描述里逐条打勾：

```markdown
- [ ] 1 许可证：新增 third_party 附 LICENSE，非 AGPL / 非无证
- [ ] 2 无预编译 GPL 二进制（builtin/** 只有脚本/配置）
- [ ] 3 未引入"再装一个模块"的步骤（或已在 STATUS 登记例外）
- [ ] 4 包名身份一致（gradle / ksud 烘入 / 脚本 / target）
- [ ] 5 UAPI 三副本 hash 相同，只追加不重排
- [ ] 6 第三方 pin 到 commit/tag/digest
- [ ] 7 diff 无凭据
- [ ] 8 功能声明带证据（CI 工作流 + 日志行 / 真机输出）
- [ ] 9 门禁全绿，未放宽棘轮与工作流上限
- [ ] 10 未触碰 SELinux 降级
- [ ] 11 差异范围未破坏上游同步性
- [ ] 12 Release 说明标注了验证边界
```

---

## 违规处理

| 阶段 | 处理 |
|---|---|
| Review 发现 | **拒绝合并**，并在 PR 引用本文件条目编号 |
| 已进主干 | 登记到 [STATUS §4](STATUS.md#4-未完成--技术债)，下一个发布前修掉 |
| 同类重复出现 | 把检查**自动化**（进 `check_quality.py` 或语言门禁），不靠记忆 |
