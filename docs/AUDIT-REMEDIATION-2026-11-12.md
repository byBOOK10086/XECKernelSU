# 2026-11-12 审计修复对账

修复基线：main 本地 `074792d`，version.properties 保持 30001。本文对应《XECKernelSU 代码审计与修复方案》，记录源码修复及实际验证边界。

| 原问题 | 处理与语义 |
|---|---|
| P0-1 文件修补变刷写 | installBoot 仅 bootUri == null 才生成 -f；选择文件时只允许 ANDROID!/VNDRBOOT，错误输入返回失败，绝不改为本机安装。所有临时文件在 finally 清理。 |
| P1-1 GKI 版本判断 | 使用 major/minor 判断，不使用 sublevel 判断；增加 4.19/5.4/5.10/6.x 回归测试。外来镜像的 KMI 不再回退为本机 KMI。 |
| P1-2 bootstate | 按用户既定需求保留默认开启；纠正文档，仅替换已存在属性与参数，保留 bootconfig 引号，不改 ADB/debug 属性。挂载幂等实现必须只处理本组件拥有的 overlay。 |
| P1-3 UAPI 分叉 | 删除未实现的 exec policy、per-UID UTS、superkey native/JNI/Store/UI；普通 root profile 和 SUSFS 保留。Manager 直接 include 根 uapi，删除私有六份头文件与旧 prctl 回退。 |
| P1-4 root 静默降级 | 特权 shell 必须确认 uid=0，否则明确抛出 NoRootException；普通 shell 只提供无特权元数据。主线程只读取缓存并调度探测；失败候选关闭。修复 UI 调用方的 IO 与失败提示。外部 su 仅用于已有 root 环境中的安装，不作为本项目内核运行证据。 |
| P1-5 默认分区 | 无特权调用 boot-info default-partition，合法结果优先；失败保守回退 boot，不根据 uname 猜 init_boot。 |
| P2-1/2 Kbuild | 删除无消费者 git/fetch 版本计算；按模块根路径读取 version.properties；缺失、格式错误、重复值直接停止构建。 |
| P2-3 | 删除无消费者 MINIMAL_SUPPORTED_KERNEL。 |
| P2-4/5/6 | 删除 forceFlash 空操作、合并 token 校验、合并重复 reboot helper。 |
| P2-7 | module ID 参数统一 shell quoting。 |
| P2-8/9/10 | URI 输入与输出流 use 关闭；临时文件唯一命名；错误/成功均 finally 清理，不重复持有同一镜像别名。下载通道增加 use 关闭。 |
| P2-11/17 | 清理本轮修改涉及的孤儿注释与历史叙事，保留说明不变量的注释；未对全仓进行无关格式重写。 |
| P2-12/13 | boot-patch-v2 保留并明确 experimental；Debug::Test 仅 debug 构建存在，开发者文档归位。 |
| P2-14 | UMOUNT ADD/DEL 对空串返回 EINVAL、超长路径返回 ENAMETOOLONG，保留用户拷贝的原始错误码，不截断路径。 |
| P2-15 | 移除 Wno-int-conversion；完整内核编译结果单列，不以静态检查代替。 |
| P2-16 | 更新 AGENTS 中实际源码路径和 libxeccli.so 名称。审计所述驱动名方向相反：内核实际为 ksu_driver，Manager 的 xecpro_driver 才是错误副本。 |
| §5 质量基线 | 不提高任何文件预算；加入对 git 基准版本的预算单调性检查，CI 比较 push 前或 PR base，防止随实现一起放宽预算。 |
| §6 OTA | bootctl 子命令与槽位分 argv，检查 get/set 退出码及槽位只能为 0/1，失败不得返回成功。 |
| §6 boot-txn | 当前 CLI 无此命令，移除清理 helper、状态、按钮及回调。 |
| §6 驱动名、用户追加授权问题 | 精确匹配 anon_inode:[ksu_driver]，禁止匹配 scoped su descriptor；授权写入检查返回并回读 allow_su。 |
| 用户追加首页状态/模式 | 识别本项目驱动后显示“正在运行”；右下角显示内核 GET_INFO 的 LKM/GKI。root 刷新期间发生的新事件排队补刷新，避免旧状态滞留。 |

## 验证边界

- 静态契约守卫与纯函数测试仅证明其覆盖的契约；编译通过不等于设备功能验证。
- 本轮不进行手机安装、分区写入、重启或授权实测。尚未提供无 root instrumentation 与分区哈希对照证据。
- bootstate 的 proc 文件 overlay 仍会出现在 mountinfo 中；不能据此宣称对所有检测不可见。
- 默认开启遵循用户明确要求，未采纳审计中改默认关闭或强制与 TEE 互锁的产品行为建议。

## 检查结果

| 检查 | 实际结果 |
|---|---|
| Manager compileReleaseKotlin | 通过；最终合并后复跑通过。 |
| Manager assembleRelease / arm64 JNI | 通过；JNI 使用根 UAPI 头文件。 |
| scripts/check_quality.py | 通过；包含审计契约和预算单调性检查，未提高预算。 |
| git diff --check | 通过。 |
| bootstate 离线回归 | 75 项通过，覆盖已有属性、ADB 保持、引号、幂等、挂载所有权及失败路径。 |
| TEE 离线回归 | 21 项通过。 |
| Rust 独立纯函数测试 | 5 项通过，覆盖 GKI 版本判断和 boot 参数重写。 |
| cargo fmt / 格式检查 | 通过。 |
| cargo ndk -t arm64-v8a check / clippy | 便携 LLVM-MinGW + nightly GNU 宿主配置下已通过。默认 MSVC 环境缺少 link.exe；通过进程局部配置解决，未安装全局工具。 |
| ksud Android release | 通过；API31/NDK29，嵌入当前源码构建的七项 TEE 载荷、静态 ksuinit 和修复后的 bootstate。 |
| 静态 ksuinit | 通过；AArch64 ELF EXEC，无解释器及动态节，16 KiB LOAD 对齐。 |
| 内置 TEE 载荷 | 七项均从当前 vendored 源码构建并校验 AArch64/dex 及 SHA256，未复用旧上游成品。 |
| 完整内核构建 | 未验证；本机没有可用的 Linux 内核构建环境。已启用 -Werror=int-conversion，后续构建必须处理真实类型错误。 |
| 设备验证 | 未执行；未安装、刷写或重启手机。 |

2026-11-13 后续构建已补齐宿主工具链，生成当前源码 daemon 并替换旧载荷，重建 Manager 后使用与此前 v30001 相同的 release 证书签名。为保证可核对性，打包保留已经由 Rust release strip 的 daemon 字节，最终 APK 内的 libxeccli.so 必须与本次构建逐字节一致。daemon 版本改为读取根 version.properties，内置刷新标记提升，确保同为 30001 时修复后的内置树也能刷新。

本次 APK 不包含新编译的各 KMI kernelsu.ko；完整内核修改仍需 Linux/CI 构建。它可用于已有兼容内核环境的 Manager/daemon 更新，不能据此宣称具备完整的全 KMI 直接安装载荷。设备授权、首页状态及文件修补不写分区的行为仍未实测。


## 2026-11-13 本地构建产物

- APK：`_scratch/XECKernel_Pro_v30001-audit-fixed.apk`，12,356,068 字节；包名 www.xeckernel.pro，versionCode 30001，versionName v30001。
- APK SHA256：`18bccaff37bf8700c82b529bac2c3c253bfa72b0abeffae62872674af25e4a38`。
- release 证书 SHA256：`8f983688359382f2e75dd72cd59398d7a0c45ed22dd1410138b34b5321a05819`；apksigner verify 通过，与此前 v30001 一致。
- 包内 daemon 与当前 release 构建逐字节一致，SHA256：`889888043563e2ece4f4346476d531d2c87d7f7e549f1999176c5c079c08202d`。
- 第三方载荷构建来源和哈希记录在 `_scratch/builtin-stage/BUILD-PROVENANCE.md` 与 `payload-sha256.json`；APK 核验记录在同目录 `_scratch/XECKernel_Pro_v30001-audit-fixed.build.json`。
- 仍未构建各 KMI 内核模块，未进行设备实测。

