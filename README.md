# MetaPool — Java 资源治理控制面

**简体中文** · [English](README.en.md)

[![Maven Central](https://img.shields.io/maven-central/v/io.github.roseri66/metapool-spring-starter?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.roseri66/metapool-spring-starter)
[![JDK](https://img.shields.io/badge/JDK-17-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-brightgreen)](https://spring.io/projects/spring-boot)
[![CI](https://github.com/roseri66/MetaPool/actions/workflows/ci.yml/badge.svg)](https://github.com/roseri66/MetaPool/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-green)](LICENSE)

> **MetaPool 不重造连接池——它把 HikariCP、Bucket4j 等异构资源管理器纳入一套统一的
> 生命周期、可观测性、动态调参与优雅停机门面。**
>
> 一个应用里往往同时有连接池、限流器、线程池、分布式锁……每一种都有各自的 API、配置风格和监控方式。
> MetaPool 是它们之上的一层**治理控制面（Resource Governance Control Plane）**：统一的是「治理」，
> 而不是「用法」——底层该用 HikariCP 就用 HikariCP，MetaPool 只负责把它们统一管起来。

> ⚠️ **关于本仓库的演进**：早期版本（1.0）曾尝试「自研 7 类资源池」，这是一条错误的路——每个自研池都打不过
> 对应的成熟专用件，且用一个 `acquire/release` 接口硬套所有资源导致里氏替换破坏。2.0 转向「治理成熟库」。
> 完整的架构评审与重构决策见 [`docs/design/metapool-2.0.md`](docs/design/metapool-2.0.md)。

---

## 它解决什么问题

一个典型 Java 应用的资源管理是碎片化的：

| 资源 | 常见选型 | 配置方式 | 监控方式 |
|---|---|---|---|
| 数据库连接池 | HikariCP | `spring.datasource.hikari.*` | HikariCP 自有 MBean |
| 限流 | Bucket4j / Resilience4j | 硬编码 | 各库各异 |
| 线程池 | JDK ThreadPoolExecutor | `new` / `@Bean` | 自行埋点 |
| 分布式锁 | Redisson | `Config` 硬编码 | 各库各异 |

**N 种资源 = N 套 API + N 种配置 + N 种监控（或没有）。** 出问题时你得同时看 Hikari MBean、限流计数、线程栈——每层都是孤岛。MetaPool 把这层「治理」统一起来。

## MetaPool 的答案：统一治理，而非统一用法

```
                    +------------------------------+
                    |       ResourceManager        |  控制面：注册表 + 编排
                    |   register / start / close   |  统一 metrics / health / tune
                    +--------------+---------------+
                                   |  同构地纳管 N 个异构资源
                    +--------------v---------------+
                    |       ManagedResource        |  统一治理契约（所有资源都实现）
                    |   + ManagedLifecycle         |  start / stop(graceful) / health
                    |   + MetricsSource            |  bindTo(MeterRegistry) 统一 tag
                    +--------------+---------------+
                                   |  可选能力：谁有谁实现，编译期隔离
                                   |  不会出现 UnsupportedOperationException
      +-------------------+--------+----------+-----------------------+
      v                   v                   v                       v
   Tunable             Pool<T>           RateLimiter        (Lock / Executor ...)
  动态调参            borrow / release       tryAcquire               后续扩展
      |
      v
   +----------------------------------------------+
   |  ResourceAdapterFactory (SPI extension pt.)  |  类路径多一个 adapter jar
   |  HikariAdapter / Bucket4jAdapter / ...       |  = 多支持一种资源，核心零改动
   +----------------------------------------------+
```

**核心一刀**：把功能性 API（`borrow/release`、`tryAcquire`、`lock/unlock`）从统一契约里剥离为**可选能力接口**，各资源只实现自己那个。连接池实现 `Pool`，限流器实现 `RateLimiter`，谁都不用假装实现不属于自己的方法——`Bucket4jAdapter` 甚至在编译期就无法被 `instanceof Pool`。

---

## 快速开始

### 方式一：Spring Boot（YAML 声明式，推荐）

```xml
<!-- 可选：import BOM 统一对齐版本，则下方无需再写 version -->
<dependency>
    <groupId>io.github.roseri66</groupId>
    <artifactId>metapool-spring-starter</artifactId>
    <version>2.0.1</version>
</dependency>
<!-- 按需引入所用资源类型的 adapter（SPI 自动发现） -->
<dependency>
    <groupId>io.github.roseri66</groupId>
    <artifactId>metapool-adapter-hikari</artifactId>
    <version>2.0.1</version>
</dependency>
```

```yaml
metapool:
  datasources:
    main:
      jdbc-url: jdbc:postgresql://localhost:5432/app
      username: app
      maximum-pool-size: 20        # 直通 HikariCP，不发明第二套参数名
      tunable: [maximum-pool-size, connection-timeout]   # 声明可运行时热调的白名单
  rate-limiters:
    order-api:
      limit-for-period: 100        # 直通 Bucket4j
      refill-period: 1s
      tunable: [limit-for-period]
```

启动后：所有资源自动被治理，指标注册到 Micrometer，`/actuator/metapool` 可查可调，容器关闭时逆序优雅停机。

### 方式二：编程式（非 Spring）

```java
ResourceManager metaPool = MetaPool.create();
metaPool.register(HikariAdapter.from(hikariConfig).named("main").build());
metaPool.register(Bucket4jAdapter.builder()
        .named("order-api").limitForPeriod(100).refillPeriod(Duration.ofSeconds(1)).build());

metaPool.bindMetrics(meterRegistry);
metaPool.start();                        // 按注册顺序启动

// ... 业务里正常使用底层原生 API ...
metaPool.close();                        // 逆序优雅停机（drain）
```

---

## 统一可观测 + 动态调参（头牌能力）

**一个 `MeterRegistry` 里，连接池与限流器的指标共存、统一 tag** —— 一个 Grafana 看板看到所有资源：

```
metapool.datasource.connections.active{metapool.resource="main", metapool.type="datasource"}
metapool.ratelimiter.available.tokens{metapool.resource="order-api", metapool.type="rate-limiter"}
```

**运行时不停机调参**，经 Actuator 端点：

```bash
# 查看所有被治理资源
GET  /actuator/metapool

# 把连接池上限从 20 热调到 40，不重启
POST /actuator/metapool/main   {"key": "maximum-pool-size", "value": "40"}
```

底层：HikariCP 走 `HikariConfigMXBean`，Bucket4j 走 `replaceConfiguration`——MetaPool 统一成一个 `apply(patch)` 门面，仅允许白名单参数，带审计。白名单里写了不支持的 key 会在**启动时**就报错，而不是等到调参时才拒。

> ⚠️ **生产安全**：`POST /actuator/metapool/{name}` 是**变更接口**。Actuator 端点默认不带认证，
> 请务必用 Spring Security 保护 management 端口，或只把它绑到内网管理端口
> （`management.server.port` + `management.server.address`）。示例应用为了开箱即跑没有加认证，
> **不要直接照搬到生产**。

### 本地起监控栈（Prometheus + Grafana）

```bash
mvn -pl metapool-examples spring-boot:run      # 示例应用，暴露 /actuator/prometheus
docker compose -f deploy/docker-compose.dev.yml up -d   # Prometheus + Grafana + AlertManager
# Grafana http://localhost:3000 (admin/admin) → 首页即 "MetaPool — Resource Governance Overview"
```

预置看板 [`deploy/grafana/dashboards/metapool-overview.json`](deploy/grafana/dashboards/metapool-overview.json)
在**同一屏**展示连接池状态（active/idle/pending）与限流器（可用令牌 / 放行·拒绝速率），
指标名与告警规则见 [`deploy/`](deploy)。

---

## 核心抽象

| 抽象 | 职责 | 为什么需要 |
|---|---|---|
| `ManagedResource` | 治理身份（name/type）+ 组合下列两项 | 让控制面同构纳管异构资源 |
| `ManagedLifecycle` | start / stop(graceful) / health | 所有资源真正共有的能力（重构支点） |
| `MetricsSource` | bindTo(MeterRegistry) 统一 tag | 「一个看板看全部」的技术地基 |
| `Tunable`（可选） | 白名单动态调参 | 不停机治理 |
| `Pool<T>` / `RateLimiter`（可选） | 各资源的原生能力 | 能力隔离，根除 LSP 破坏 |
| `ResourceManager` | 注册表 + 编排 + 聚合 health | 治理是横切的，需中心编排者 |
| `ResourceAdapterFactory` | SPI 扩展点 | 加一种资源 = 写一个 adapter |

设计全文（含每个抽象「不用会怎样」的论证）：[`docs/design/metapool-2.0.md`](docs/design/metapool-2.0.md)。

---

## 模块

| 模块 | 职责 |
|---|---|
| `metapool-common` | 纯契约层：治理抽象 + 能力接口 + 控制面接口 + SPI + 值对象（仅依赖 micrometer-core / slf4j） |
| `metapool-core` | 控制面实现：`DefaultResourceManager` + `ResourceAdapterLoader` + `MetaPool` 入口 |
| `metapool-adapter-hikari` | 把 HikariCP 纳入治理（`datasource`） |
| `metapool-adapter-bucket4j` | 把 Bucket4j 纳入治理（`rate-limiter`，非池资源） |
| `metapool-adapter-jdk-executor` | 把 JDK `ThreadPoolExecutor` 纳入治理（`executor`，非池资源） |
| `metapool-adapter-redisson` | 把 Redisson 分布式锁纳入治理（`lock`，非池资源，**不实现 `Tunable`**） |
| `metapool-spring-starter` | Spring Boot 自动装配 + Actuator health/tune 端点 |

## 扩展新资源类型

实现 `ResourceAdapterFactory`，经 `META-INF/services` 注册，类路径多一个 jar 即多支持一种资源，核心零改动。

**这不是宣传语，是可核验的事实**：加入 `metapool-adapter-jdk-executor` 时，`metapool-core` 与 `metapool-common` **零改动**（`git show 8685ee3 --stat` 可查）。

规划中的适配器：Commons-Pool2（object）、Lettuce（redis）、Netty（memory）。

**能力接口是可选的，也可核验**：`metapool-adapter-redisson` **不实现 `Tunable`** —— Redisson 锁的
`waitTime` / `leaseTime` 是每次调用传入的，没有运行时可调参数，于是就不实现。谁有谁实现，
不为「显得完整」硬凑。这与 1.0 「定义大接口 → 逼所有资源实现 → 实现不了就抛
`UnsupportedOperationException`」恰好相反。

---

## 构建

```bash
mvn clean test      # JDK 17+，全模块编译 + 测试
```

## 项目状态与路线图

| Milestone | 内容 | 状态 |
|---|---|:--:|
| M0 | 架构清场（砍自研池/AI/Agent 负债） | ✅ |
| M1 | 核心契约（治理 + 能力隔离） | ✅ |
| M2 | HikariCP 适配器 | ✅ |
| M3 | Bucket4j 适配器 + 控制面 + starter | ✅ |
| M4 | 文档 / examples / JMH benchmark / Testcontainers | ✅ |
| 2.1 P0 | GitHub Actions CI + `DistributedLock` / `ManagedExecutor` 能力接口 | ✅ |
| 2.1 P1 | `executor`（JDK 线程池）+ `lock`（Redisson）适配器已落地；object / redis / memory 待做（见 [2.1 路线图](docs/design/roadmap-2.1.md)） | 🚧 |
| 发布 | BOM + `io.github.roseri66` groupId + Central `release` profile | ✅ 已发布 `2.0.1`（流程见 [`docs/PUBLISHING.md`](docs/PUBLISHING.md)） |
| CI | GitHub Actions：ubuntu + windows × JDK 17；手动触发的发布 workflow | ✅ |

## 这个项目是什么 / 不是什么

- ✅ **是**：一个进程内的资源治理门面，统一异构资源的生命周期、可观测、动态调参。
- ❌ **不是**：又一个连接池实现（它包装成熟件，不与之竞争）。
- ❌ **不是**：分布式系统——控制面就是一个 `Map` + 编排逻辑，不含 MQ / 注册中心 / 微服务。

---

## License

[Apache License 2.0](LICENSE) · 100% OSI 开源依赖，零付费。
