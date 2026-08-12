# MetaPool 2.0 需求与工程约定

> 📌 **历史文档**：本文件是 2.0 时期的规划记录，**刻意不随后续版本改写** —— 它记录的是当时怎么想的，改了就失去价值。
> 当前状态请看 [`roadmap-2.1.md`](roadmap-2.1.md) 与仓库根的 `CHANGELOG.md`。

> 本文以 2.0「资源治理控制面」的视角，收敛并取代 1.0 的 BRD/PRD/需求范围/项目 Rules 等规划文档。
> 架构与技术选型见 [`metapool-2.0.md`](./metapool-2.0.md)；模块拆分与里程碑见 [`metapool-2.0-roadmap.md`](./metapool-2.0-roadmap.md)。

---

## 1. 业务目标

一个 Java 应用里往往同时存在连接池、限流器、线程池、分布式锁等多类资源，每类都有各自的 API、配置风格、
监控方式——碎片化导致**难观测、难统一治理、运维成本高**。

MetaPool 2.0 的目标：**不重造这些资源，而是在它们之上提供一层统一的治理控制面**，做到
- 统一的**生命周期**（启动 / 优雅停机 / 健康）
- 统一的**可观测**（Micrometer 统一 tag，一个看板看全部）
- 统一的**运行时动态调参**（不重启改参数）
- 统一的**扩展机制**（SPI，加一种资源 = 写一个 adapter）

> 与 1.0 的根本差异：1.0 试图「自研 7 类资源池」，方向错误（打不过专用件 + 用一个接口硬套导致 LSP 破坏）。
> 2.0 转向「治理成熟库」——统一的是治理，不是用法。

## 2. 目标用户

| 角色 | 诉求 | 用法 |
|---|---|---|
| Java 开发 | 统一接入多类资源，少写监控/生命周期样板 | spring-starter + YAML，或编程式 `MetaPool.create()` |
| 应用运维 | 一屏看全部资源指标、动态调参、优雅停机 | Grafana 看板 + `/actuator/metapool` |
| 架构/性能 | 治理层开销可控、可扩展 | JMH benchmark + SPI adapter |

## 3. 范围

**范围内（2.0 已交付）**
- 6 个核心治理抽象（见 [metapool-2.0.md §3](./metapool-2.0.md)）
- 2 个生产级适配器：HikariCP（datasource）、Bucket4j（rate-limiter）
- 控制面 `DefaultResourceManager` + SPI 加载器
- Spring Boot Starter + Actuator health/tune 端点
- 可观测（统一 tag 指标 + Grafana 看板）、可运行 examples、JMH benchmark、Testcontainers 集成测试
- Maven Central 发布（`io.github.roseri66`，BOM + release profile）

**范围外 / 明确不做（非目标）**
- ❌ 自研任何连接池/限流器（包装成熟库，不与之竞争）
- ❌ 微服务 / MQ / 分布式注册中心（控制面就是进程内对象）
- ❌ 远程配置中心（进程内 `Tunable` 足够）
- ❌ AI 诊断作为头牌（可作为最后的可选 SPI 演示，非核心）

**后续扩展**：redis(Lettuce) / object(Commons-Pool2) / executor(JDK) / lock(Redisson) / memory(Netty) 各写一个 adapter。

## 4. 功能需求概要

| 能力 | 说明 | 落点 |
|---|---|---|
| 统一生命周期 | start / stop(graceful drain) / health | `ManagedLifecycle` |
| 统一可观测 | bindTo(MeterRegistry) + 统一 tag | `MetricsSource` |
| 动态调参 | 白名单热调，Actuator 端点 | `Tunable` + `/actuator/metapool` |
| 池化能力 | borrow/release（仅池类资源） | `Pool<T>` |
| 限流能力 | tryAcquire（仅限流类资源） | `RateLimiter` |
| 控制面编排 | 注册 / 顺序启停 / 聚合健康 | `ResourceManager` |
| 扩展 | type→factory 的 SPI 发现 | `ResourceAdapterFactory` |

## 5. 非功能需求

- **治理开销**：治理层相对裸用底层库的额外开销应极小。实测为几十纳秒常量级，对真实后端 < 0.1%（见 [benchmarks.md](../benchmarks.md)）。
- **正确性**：能力隔离，编译期杜绝「假装实现不属于自己的能力」（如限流器不实现 `Pool`）。
- **兼容**：JDK 17+，Spring Boot 3.x。
- **可用**：优雅停机 drain 在用资源；无 Docker 环境集成测试自动跳过不阻塞构建。

## 6. 工程约定（2.0 仍沿用）

- 包命名 `com.metapool.{module}`；发布 groupId `io.github.roseri66`。
- `metapool-common` 为**纯契约层**，仅依赖 micrometer-core / slf4j，不含任何第三方实现。
- 异常统一继承 `MetaPoolException` 并携带 `ErrorCode`（格式 `POOL-NNN`）。
- 配置**不可变 + 启动即校验（fail-fast）**；参数直通底层库原生命名，不发明第二套。
- 每个抽象/设计都回答「为什么需要它？不用有什么问题？」。
- 按 Milestone 推进，先设计确认再实现。
