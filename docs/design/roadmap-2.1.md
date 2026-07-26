# MetaPool 2.1 路线图（下个版本能做的）

> 承接 [`metapool-2.0-roadmap.md`](./metapool-2.0-roadmap.md)。2.0 已发布 Maven Central；`main` 现为 `2.1.0-SNAPSHOT`。
> 原则不变：**动手写实现前，接口签名先经设计确认**（尤其新增能力接口时）。
> 每项标注真实必要性——不为堆功能而堆功能。

---

## 2.1 主题

**把「治理控制面」从 2 种资源做深，扩到覆盖常见资源全谱；同时补上工程基建（CI）。**
2.0 用 HikariCP + Bucket4j 验证了抽象普适（一个是池、一个不是池）。2.1 的核心工作是**横向复制这个模式**，
外加一件 2.0 欠的账：持续集成。

---

## P0 — 该做（有明确价值，且是「作品集完整度」的短板）

### 1. GitHub Actions CI ✅ 已完成（2026-07-26）

- **构建 CI**（[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)）：`push(main)` / `pull_request` /
  手动触发，matrix `ubuntu-latest` + `windows-latest` × JDK 17，跑 `mvn -B clean verify`；
  失败上传 surefire 报告；同分支新 push 自动取消旧构建；纯文档改动不触发。
  README 徽章已从**硬编码的假徽章**（`badge/build-passing`，链接指向 `.`）换成真实 workflow badge。
- **实测收益不止「把本地结果搬上去」**：ubuntu runner 自带 Docker，因此
  `HikariAdapterPostgresTest`（Testcontainers PostgreSQL）在 CI 上**真实执行**了 5.476s ——
  该用例自写出来一直因本地无 Docker 而跳过，这是它第一次真正跑过。
  首次运行结果：ubuntu **38 通过 / 0 跳过**，windows 37 通过 / 1 跳过（无 Docker，符合 §4.3 不阻塞构建）。
- **发布 workflow**（[`.github/workflows/release.yml`](../../.github/workflows/release.yml)）：
  按本节原定的「避免误发」原则，**只允许 `workflow_dispatch` 手动触发**，不挂 `v*` 标签触发
  ——标签是发布**结果**的记录，不该当扳机。带 `dry_run` 输入且**默认为 true**：先演练（完整签名打包但不上传），
  验证 Secrets/GPG/javadoc 都正常后再关掉演练真发。父 pom 的 `autoPublish=false` 保留了
  「到 Central 门户人工点 Publish」这道闸门；workflow 也**不会**自动改版本提交、不会打 tag。
  所需 Secrets 见 [`../PUBLISHING.md`](../PUBLISHING.md)。
- **jacoco 覆盖率上报**：原计划的这一项**暂不做**。接 Codecov 之类要引入外部账号 + token，
  与 §1.5「优先简单稳定、拒绝为显高级而加」冲突；覆盖率数字对本项目的可信度提升也远不如
  「两个平台真实跑通 + PG 容器真实验证」。等真有需要（如多人协作看回归）再议。

### 2. 补齐能力接口：`DistributedLock` / `ManagedExecutor` ✅ 已完成（2026-07-26）

- **设计**：[`metapool-2.1-capabilities.md`](metapool-2.1-capabilities.md)（已确认），含 P-07 自查、
  每个决策的「为什么/不用会怎样」，以及 5 项拍板结果。
- **已落地**（`metapool-common`，签名 + Javadoc，零实现）：
  `capability/DistributedLock`、`capability/LockHandle`、`capability/ManagedExecutor`、
  `stats/LockStats`、`stats/ExecutorStats`。
- **三条定型决策**：
  1. 锁发放**持有凭证**而非 `unlock(key)` —— 后者无法判断调用方是否持有者，会导致「租约到期后
     解了别人的锁」这个经典事故；凭证 `extends AutoCloseable`，try-with-resources 即正确用法。
  2. `ManagedExecutor extends Executor` 但**不 extends `ExecutorService`** —— 后者带 `shutdown()`，
     会开出绕过控制面的第二个停机入口。
  3. 线程池饱和**透传** `RejectedExecutionException` 而非包装 —— 「别在接口层发明第二套饱和语义」。
     已在 RULES §3.2 记为明示例外并划清反向边界。
- **守护**：`CapabilityIsolationTest` 用反射断言两个新接口都不继承 `Pool`、`ManagedExecutor` 不继承
  `ExecutorService`、`DistributedLock` 没有 `unlock` 方法 —— 让违反变成红灯而非 review 的运气。
- **核心零改动**：`ResourceTypes.EXECUTOR` / `LOCK` 常量早已存在，`DefaultResourceManager` 只认
  `ManagedResource` + `instanceof Tunable`，无需认识新接口。

---

## P1 — 横向扩展适配器（2.1 的主体工作，逐个独立交付）

每个 adapter 与 2.0 的 hikari / bucket4j 对称：新模块 + `ResourceAdapterFactory` + SPI 注册 + 测试。核心零改动。

| 适配器 | 底层库 | 能力接口 | 备注 / 风险 |
|---|---|---|---|
| `adapter-lettuce`（redis） | Lettuce | `Pool<StatefulConnection>` | Lettuce 单连接多路复用，"池"语义需想清楚是否真需要池化；也可只做治理不做 Pool |
| `adapter-commons-pool2`（object） | Commons Pool2 | `Pool<T>` | 最直接对称 hikari，低风险，适合先做 |
| `adapter-jdk-executor`（executor） | JDK ThreadPoolExecutor | `ManagedExecutor`（见 P0#2） | 线程池不是池，验证 `ManagedExecutor` 抽象；tune 核心/最大线程数 |
| `adapter-redisson`（lock） | Redisson | `DistributedLock`（见 P0#2） | 依赖 Redis，测试用 Testcontainers Redis；看门狗续期语义 |
| `adapter-netty`（memory） | Netty `PooledByteBufAllocator` | `Pool<ByteBuf>` 或自定义 | 堆外内存，度量口径与释放安全性需谨慎 |

**建议顺序**：commons-pool2（最对称、最低风险）→ jdk-executor（验证 `ManagedExecutor`）→ redisson（验证 `DistributedLock`）→ lettuce → netty。

**每落一个 adapter 同步做**：examples 里纳管它、Grafana 看板加对应面板、README 模块表更新。

---

## P2 — 治理能力增强（有价值但非必需，按需取舍）

| 项 | 做什么 | 为什么 / 判断 |
|---|---|---|
| Metrics 生命周期 | `stop()` 时从 registry 注销自己的 meter | 2.0 现状：停机后 gauge 仍在，读到 0（已 null-safe）。清理更干净，但非 bug。低优先。 |
| Tracing | 接 Micrometer Observation，给 borrow / tryAcquire 埋 span | 有价值，但只有在有链路追踪后端时才有意义。可选。 |
| `Pool.borrow(Duration)` 真超时 | 让 Hikari 适配器真正按传入 timeout 限时（当前以配置 connectionTimeout 为界，已文档说明） | 修掉 2.0 记录的阻抗。中等价值。 |
| Health 细节 | Actuator `/health` 暴露每个资源的 per-resource 明细 | 运维友好。低成本。 |
| 注解式配置 | `@MetaPoolDataSource` 之类 | 2.0 判为「YAML 够用」。除非有明确诉求，**不做**（避免过度设计）。 |

---

## 明确暂不做（延续 2.0 的克制）

- ❌ 微服务 / MQ / 分布式注册中心 / 远程配置中心
- 🅿️ AI 跨池诊断：可作为最后的**可选 SPI 演示**（`metapool-spi-ai` 重建为真接入 SpringAI 的示例），但绝不作为核心；地基稳、adapter 谱系完整后再议。

---

## 版本收尾约定

- 每个 adapter 或 P0 项完成即可作为一次小版本（`2.1.0` 打包一批，或 `2.1.x` 滚动）。
- 发布沿用 `mvn -Prelease clean deploy`（见 [`../PUBLISHING.md`](../PUBLISHING.md)），JDK 17，Central token + GPG。
- 发布后推进到下一个 `-SNAPSHOT`。
