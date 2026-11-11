# 发展方向（Roadmap）

> 这是**方向**，不是承诺清单；每项的完成度以 [STATUS.md](STATUS.md) 为准。
> 排序原则：先把"一次刷入就能用"的主链路钉死，再谈体验与生态。

---

## 近期（首版发布前后）

| 项 | 为什么现在做 | 完成判据 |
|---|---|---|
| GKI SUSFS 内核 7 个 KMI 全绿 | 主路线；SUSFS 只有编进内核才算"刷一次就有" | 7 个 `Image.gz` + AnyKernel3 包产出，每个都有 arm64 magic / gzip 断言通过 |
| 首版 APK 发布 | 用户可见的第一个产物 | `build-manager` 绿 + artifact 核对（内置引擎、keybox 代次、`libxeccli.so`）|
| 真机验收（T1/T2）| CI 证明不了设备行为 | `feature/tee/test/run*.sh` 在真机全绿；`/proc/susfs_*` 可读；bootstate 三键一致 |
| 清掉 5 项技术债（D1–D5）| 会误导用户 / 泄露凭据 / 逻辑漂移 | D1 删除旧仓下载路径；D2 凭据移出代码；D3 合并或删除 `feature/tee` 并行实现；D4 加 uapi 守卫进 `guards`；D5 保持整洁 |
| keybox 轮换告警 | 叶子寿命只有 ~2 周，到期即红 | 构建日志 + 管理器都能看到"剩余天数"，并提示上游是否换代 |

## 中期（首版稳定后）

1. **管理器 UI 补齐**：TEE 引擎状态卡（版本/root 类型/剩余天数）、SUSFS 状态卡（版本/variant/特性开关）、
   内核包下载与安装（按当前 KMI 自动选包，失败回滚）。
2. **内核包分发**：GKI 内核按 KMI 发布到自有直链 + Release，管理器内一键下载/校验/安装；
   站点接入新仓（现在是旧仓流水线）。
3. **SUSFS 配置面**：把 `sus_path`/`sus_kstat`/`uname`/open_redirect 等做成模板（"隐藏 root 痕迹"预设），
   避免每个用户手写规则；LKM 兜底路线复用同一套模板。
4. **可观测性**：统一的"健康检查"入口（keybox 链、bootstate、SUSFS、引擎存活），把
   `feature/tee/*` 的诊断能力从测试脚本提升为产品功能。
5. **上游同步机制**：把"跟随 tiann/KernelSU main"变成可重复流程（三方合并 + 冲突清单 + 门禁），
   降低长期维护成本。

## 远期

| 项 | 说明 |
|---|---|
| `android17-6.18` 的全量支持 | 等 susfs4ksu 出 `gki-android17-6.18` 分支；在此之前用 LKM 兜底 |
| 可复现构建 | 固定工具链版本、记录镜像 digest、产物可复算（当前已对 DDK/镜像做 digest 钉死）|
| 签名与发布分离 | 发布签名密钥独立于构建；提供可验证的产物清单（sha256 + 来源说明）|
| 文档站 | 把 `docs/` 发布成站点（当前只在仓库内） |
| 多语言 | 上游的 `docs/README_*.md` 保持同步；本项目自身文档先中文，再按需翻译 |

---

## 不做的方向（防止范围蔓延）

- **不追 KPM/APatch 那套内核补丁引擎**：与本仓 LKM + GKI 双路线冲突，收益低、维护成本高。
- **不做"通用伪装框架"**：只解决本项目声明的四个目标（密钥 / 回锁 / SUSFS / 检测对抗），
  不承接"改任何属性/隐藏任何应用"的万能需求。
- **不引入 AGPL/无许可证上游**：见 [RED-LINES.md](RED-LINES.md) 第 1 条。
- **不做常态化 `setenforce 0` / permissive**：见 [RED-LINES.md](RED-LINES.md) 第 10 条。
