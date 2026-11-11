# 开发红线（不可协商）

违反以下任何一条的改动，无论功能多好都**不得合并**。每条都附"为什么"和"怎么判"，便于 review 时直接引用。

---

### 1. 许可证：AGPL-3.0 或无许可证的上游，一律不留、不编、不分发

**为什么**：本项目以 GPL-2.0（内核）/ GPL-3.0（用户态）分发；引入更强 copyleft 或"无授权"代码会让整个
产物不可分发。历史裁决：宁可少一个功能，也不引入这类上游。
**怎么判**：新增 `third_party/` 目录必须在 PR 里附上游 `LICENSE` 与当前许可证名称；AGPL / 无 LICENSE /
"仅研究用途"的自定义条款 → 拒。只作行为参考、不引入代码，是允许的（需在 [UPSTREAMS.md](UPSTREAMS.md) 记明）。

### 2. GPL 合规：仓库里不得出现"由 GPL 源码编出的预编译二进制"

**为什么**：`userspace/ksud/builtin/` 会被 RustEmbed 打进 ksud 再进 APK；把二进制提交进仓等于只发二进制不发源码，
违反 GPL 的对应源码要求。
**怎么判**：`userspace/ksud/builtin/**` 只允许脚本/配置/NOTICE；`*.so`、`*.dex`、可执行文件一律拒。
产物由 `.github/scripts/build-builtin-modules.sh` 在 CI 里从 `third_party/` 现编。

### 3. "一次刷入"：功能不得退化成"再装一个模块"

**为什么**：产品定义就是"装管理器 + 刷对应 KMI 内核 = 全都有"。要用户额外装模块/开开关，等于没做完。
**怎么判**：TEE 引擎、TA 守护、bootstate、SUSFS 必须由内置模块机制或内核镜像提供；新增依赖"用户手动安装"
的步骤需要单独审批，并在 [STATUS.md](STATUS.md) 登记例外（如 LKM 兜底路线）。

### 4. 包名身份唯一：`applicationId`、ksud 烘入的包名、脚本里的包名必须是同一个值

**为什么**：历史上错配过一次——`su_daemon` 认不出管理器、首授权死锁、`boot_patch` 备份目录建不出来。
**怎么判**：改包名时同时检查 `manager/app/build.gradle.kts`、`userspace/ksud/src/lib.rs`（烘入常量）、
`userspace/ksud/builtin/**/*.sh`、`ts` 侧 target 列表；`scripts/check_package_identity.py` 存在时必须绿。

### 5. UAPI 三副本逐字节一致，且只允许追加

**为什么**：内核、ksud、管理器 JNI 各读一份 `uapi/*.h`；不一致 = 运行时静默错位。
**怎么判**：`uapi/`、`kernel/include/uapi/`、`manager/app/src/main/cpp/uapi/` 三份 hash 必须相同；
supercall/ioctl **编号与结构体布局只允许追加**，禁止改语义、重排字段、复用旧编号。

### 6. 第三方必须钉死版本，禁止跟分支头

**为什么**：susfs4ksu 的 ABI 与 `ksud` 的 verb 集合一一对应；引擎换了行为会静默改变检测结果。
**怎么判**：`third_party/` 用 tag/commit（写在 `UPSTREAM.md`）；上游补丁用 per-KMI SHA（`build-susfs-gki.yml`
的 `PINNED_SHA` 表）；容器镜像用 `@sha256:` digest；构建取的 Gradle 发行包必须校验 sha256。

### 7. 密钥、口令、token 不入库

**为什么**：公开仓库里的凭据等于已泄露。
**怎么判**：diff 里出现 `ghp_`/`github_pat_`/`BEGIN ... PRIVATE KEY`（除公开 keybox）以外的凭据 → 拒。
已知例外（**待清理债 D2**）：`manager/.../RemoteUploader.kt` 内的上传账号口令，见 [STATUS.md](STATUS.md) §4.1。

### 8. 没有证据，不写"已修复/已支持"

**为什么**：CI 绿 ≠ 语义对；编译过 ≠ 设备上生效。
**怎么判**：功能声明必须附证据——CI 工作流名 + 提交 + 关键日志行，或真机命令输出。
未验证项必须在 [STATUS.md](STATUS.md)「未验证 / 已知风险」里列出来。

### 9. 门禁不可绕过

**为什么**：质量门禁是唯一能挡住"悄悄变差"的机制。
**怎么判**：`scripts/check_quality.py` 的 8 道门禁、`clippy`（`-Dwarnings` + pedantic）、`rustfmt`、
`shellcheck`、`clang-format`（固定 **18.1.8**）必须绿。文件大小棘轮（`MAX_LINES = 400`）只允许
**一次性的、有记录的预算重钉**（写进 `docs/QUALITY-GATES.md` 与 commit message），不允许逐次放宽。
工作流数量上限 15，新增必须先删。

### 10. 不碰 SELinux 的常态化降级

**为什么**：`setenforce 0` / permissive 是自曝式的，既危险又是检测面。
**怎么判**：不得新增"把 SELinux 关掉/放宽到 permissive"的逻辑；需要绕过策略时，走既有钩子
（`selinux_hide.c`、SUSFS 的 avc spoof、`ksu_handle_*` 分流），并说明它为什么等价于合法状态。

### 11. 不破坏上游同步性

**为什么**：长期维护成本取决于能否继续吃上游 KernelSU 的修复。
**怎么判**：fork 差异应集中在 `third_party/`、`userspace/ksud/builtin/`、SUSFS 集成层、CI；
大规模重写上游文件（尤其 `manager/` 与 `kernel/` 的通用逻辑）需要在 PR 里说明收益与未来同步方案。

### 12. 诚实标注"验证边界"

**为什么**：用户依赖发布说明判断能不能刷。
**怎么判**：每个 Release 必须写清：哪些结论来自 CI（编译/打包/门禁），哪些来自真机；
没验证的要写"未验证"，不能省略。

---

## 违规处理

1. review 发现即**拒绝合并**，并在 PR 里引用本文件条目编号；
2. 已经进主干的，登记到 [STATUS.md](STATUS.md) §4.1（技术债），并在下一个发布前修掉；
3. 同一类问题重复出现，需要把检查**自动化**（加进 `scripts/check_quality.py` 或对应的语言门禁），
   而不是靠记忆。
