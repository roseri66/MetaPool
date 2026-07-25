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

### 1. GitHub Actions CI
- **做什么**：`push` / `pull_request` 触发 `mvn clean verify`（JDK 17，多 OS 可选）；`v*` 标签触发 `mvn -Prelease deploy`（用 Secrets 存 Central token + GPG）。接入 jacoco 覆盖率上报。
- **为什么**：现在「全绿」只有本地证据。公开仓库有 CI 徽章 = 可信度显著提升，也防回归。**这是 2.0 唯一明显欠缺的工程基建。**
- **前置**：无。GPG/token 走 GitHub Secrets，不落仓库。
- **风险**：低。发布 job 建议手动触发（workflow_dispatch）而非全自动，避免误发。

### 2. 补齐能力接口：`DistributedLock` / `ManagedExecutor`
- **做什么**：2.0 里为 YAGNI 只定义了 `Pool` / `RateLimiter`。要接入锁和线程池，先在 `metapool-common` 定义这两个能力接口（签名 + Javadoc，零实现）。
- **为什么**：它们是下面 #3 里 lock / executor 适配器的前提。
- **前置**：**接口签名需先确认**（这是 2.1 唯一必须先设计确认的一步）。
- **风险**：中。锁的语义（可重入 / 超时 / 看门狗续期）差异大，接口要抽得住 Redisson 又不过度。建议参考 2.0 对 `Pool` 的做法：只抽最小公共面，库特有能力留给具体 adapter。

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
