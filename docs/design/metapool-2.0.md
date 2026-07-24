# MetaPool 2.0 — 核心抽象设计文档

> 状态：设计确认通过（2026-07）· 作者：架构评审
> 定位转向：从「自研 7 类资源池」→「资源治理控制面（纳管成熟库）」

---

## 0. 背景与方向转弯

MetaPool 1.0 忠实实现了原始 BRD/PRD 的方向——**自研 7 类工业级资源池**（thread / db / redis / object /
memory / rate-limit / lock）。但这个方向在架构上是死路：

- 每个自研池都打不过对应的专用成熟件（HikariCP / Redisson / Bucket4j / Netty …），压测必输；
- 为了「统一」，用一个 `ResourceLifecycle<T>{ acquire(); release(T); }` 接口硬套 7 类资源，导致
  **里氏替换原则（LSP）系统性破坏**：
  - `ThreadResourcePool.acquire()` → `throw UnsupportedOperationException`
  - `TokenBucketRateLimiter.acquire(timeout, unit)` → 忽略超时参数
  - `SmartReentrantLock implements ResourceLifecycle<Boolean>` → 锁被建模成「借出一个 Boolean」
- 又额外堆了 AI 诊断 SPI、ByteBuddy 字节码 Agent、自建 Prometheus Server 等过度设计。

**2.0 的方向**：不再自研池，而是把 HikariCP / Bucket4j / Lettuce / Commons-Pool2 / Netty / Redisson / JDK
等**异构成熟资源管理器**纳入一套统一的**治理面**。

**一句话定位**：

> MetaPool 是 Java 应用的**资源治理控制面（Resource Governance Control Plane）**——它不重造连接池，而是把
> 异构资源管理器纳入一套统一的**生命周期、可观测性、动态调参、优雅停机**门面。

---

## 1. 设计哲学

> **统一的是「治理」，不是「用法」。**
>
> 生命周期、可观测、动态调参、健康检查——这四件事对所有资源同构，必须统一；
> 而「怎么用」（连接池 borrow/return、限流 tryAcquire、锁 lock/unlock）各资源天生不同，**绝不强行统一**。

这一条直接根除了 1.0 的 LSP 灾难：功能性 API 下沉为**能力接口**，各资源只实现自己那一个；
治理能力上浮为**统一契约**，所有资源都实现。

三个已确认的边界决策：

| 决策项 | 结论 |
|---|---|
| 纳管范围 | 先做深 2 种（DB=包 HikariCP、限流=包 Bucket4j），验证抽象后横向扩 |
| 头牌亮点 | 统一可观测 + 控制面（动态调参） |
| 明确不做 | 微服务 / MQ / DDD / 远程配置中心 / AI 头牌化 |

---

## 2. Developer Experience（先定「怎么用」）

### 2.1 引入依赖（一个 starter + BOM 统一版本）

```xml
<!-- 作品集项目 groupId 建议 io.github.<用户名>，否则无法发布 Maven Central -->
<dependency>
  <groupId>io.github.roseri66</groupId>
  <artifactId>metapool-spring-boot-starter</artifactId>
  <version>2.0.0</version>
</dependency>
```

### 2.2 创建被治理资源

**方式 A — YAML（Spring 用户，主推）**

参数**直通底层库原生命名**，不发明第二套参数名；仅额外加治理字段 `tunable`。

```yaml
metapool:
  datasources:
    main:
      jdbc-url: jdbc:postgresql://localhost:5432/app
      username: app
      maximum-pool-size: 20          # 直通 HikariCP
      tunable: [maximum-pool-size, connection-timeout]   # 声明可运行时热调的参数白名单
  rate-limiters:
    order-api:
      limit-for-period: 100          # 直通 Bucket4j
      refill-period: 1s
      tunable: [limit-for-period]
```

**方式 B — 编程式（非 Spring / 库用户）**

```java
MetaPool metaPool = MetaPool.create();
metaPool.register(HikariAdapter.from(hikariConfig).named("main"));
metaPool.register(Bucket4jAdapter.from(limitConfig).named("order-api"));
metaPool.start();               // 统一启动（按注册顺序）
// ... 业务里正常使用底层原生 API ...
metaPool.close();               // 统一优雅停机（逆序 drain）
```

### 2.3 默认配置原则

- **合理默认**：直接继承底层库久经生产的默认值，MetaPool 不覆盖，除非有治理理由。
- **启动即校验（fail-fast）**：非法组合（如 `max < min`）立即抛 `MetaPoolConfigException`，绝不带病运行。
- **配置不可变**：构建后不可变；运行时调参走 `Tunable` 显式通道，而非偷改字段。修掉 1.0 `PoolConfig`
  可变 setter + 无校验的缺陷。

---

## 3. 六个核心抽象

```
                     ┌─────────────────────────────┐
                     │        ResourceManager       │  ← 控制面（注册表 + 编排）
                     │   register / start / close   │
                     │   metrics / health / tune    │
                     └──────────────┬──────────────┘
                                    │ 管理 N 个
                     ┌──────────────▼──────────────┐
                     │       ManagedResource        │  ← 治理身份（所有资源必实现）
                     │   name() type()              │
   统一治理契约 ────▶│   + ManagedLifecycle         │  ← 统一：start/stop/health/drain
   （同构）          │   + MetricsSource            │  ← 统一：吐指标（头牌亮点）
                     │   + Tunable                  │  ← 统一：热调参（头牌亮点）
                     └──────────────┬──────────────┘
                                    │ 各资源"额外"实现自己的能力接口（不统一）
        ┌───────────────┬──────────┴─────────┬──────────────────┐
        ▼               ▼                     ▼                  ▼
   Pool<T>         RateLimiter            DistributedLock     ManagedExecutor
  borrow/return   tryAcquire()           lock()/unlock()     execute()/submit()
  (db/redis/obj)  (rate-limit)           (lock)              (thread)
        │
   ┌────▼────────────────────────────────┐
   │  ResourceAdapter  (SPI 扩展点)       │  ← 把任意成熟库纳入治理
   │  HikariAdapter / Bucket4jAdapter ... │
   └──────────────────────────────────────┘
```

每个抽象都回答：**为什么需要它？不用它有什么问题？**

### 3.1 `ManagedResource` — 治理身份

- **是什么**：所有被纳管资源的公共根。只承载治理身份：`name()`（全局唯一）、`type()`。
  组合下面三个治理能力，**不含任何 acquire/release**。
- **为什么需要**：控制面要能把异构资源当同一种东西去枚举、监控、停机。
- **不用会怎样**：回到 1.0 的碎片化——每种资源一套监控/停机代码，正是 BRD 要消灭的痛点。

### 3.2 `ManagedLifecycle` — 统一生命周期（重构支点）

- **是什么**：`start()` / `stop(Duration graceful)` / `HealthStatus health()`。`stop` 带优雅期，
  实现真正的 drain。
- **为什么需要**：这才是所有资源**真正共有**的东西——都能启动、优雅关闭、有健康状态。
- **不用会怎样**：这正是 1.0 用 `ResourceLifecycle<T>` 试图统一、却错误地把 `acquire/release`
  一起塞进来的地方。把功能性 API 剥离出去，剩下的生命周期才是干净的统一契约。**这是整个重构最核心的一刀。**
- **修掉的缺陷**：1.0 `destroy()` 强杀 active 资源；2.0 `stop(graceful)` 先 drain 等归还。

### 3.3 `MetricsSource` — 统一可观测（🎯头牌亮点）

- **是什么**：`void bindTo(MeterRegistry registry)`。每个资源把指标注册到 Micrometer，
  打统一 tag：`metapool.resource=main`、`metapool.type=datasource`。
- **为什么需要**：头牌故事「一个 Grafana 看板同时看连接池/限流/线程池」。统一 tag 规范是技术地基。
- **不用会怎样**：各库指标 tag 各异，Grafana 拼不到一起，「统一可观测」是空话。
- **关键决策**：**删除 1.0 的 ByteBuddy Agent 和自建 Prometheus Server**。包装成熟库后，
  HikariCP/Bucket4j 本身暴露 Micrometer 指标，只需 `bindTo` + 统一 tag，无需字节码插桩。
  （Agent 仅在纳管「改不了源码的第三方池」时才有价值，非头牌主线，封存。）

### 3.4 `Tunable` — 运行时动态调参（🎯头牌亮点）

- **是什么**：`Set<String> tunableKeys()` + `TuneResult apply(Map<String,Object> patch)`。
  只允许调 YAML 里声明过的 `tunable` 白名单参数。
- **为什么需要**：控制面「不停机治理」的杀手锏——运行时把 `maximum-pool-size` 从 20 调到 40 不重启。
  HikariCP 原生支持 `HikariConfigMXBean` 热调，Bucket4j 可重建 bucket，统一成一个 `apply(patch)` 门面 +
  Actuator 端点。
- **不用会怎样**：失去 BRD 场景 3（不停机调参），头牌故事弱一半。
- **安全边界**：白名单 + 校验 + 审计日志，绝不允许任意反射改字段。

### 3.5 `ResourceManager`（即 `MetaPool`）— 控制面核心

- **是什么**：注册表 + 编排器。`register` / `start` / `close` / `get(name)` / `resources()` /
  全局 `bindMetrics(registry)` / 全局 `health()` / `tune(name, patch)`。用户与 Actuator 端点的唯一入口。
- **为什么需要**：治理是横切的——要按依赖顺序启动、逆序优雅停机、聚合全局健康，需要中心编排者。
- **不用会怎样**：用户自己手撸每个资源的启停顺序和监控绑定，「统一」退化成「一堆各自为政的适配器」。
- **边界纪律**：这是一个**进程内对象**（`ConcurrentHashMap<String, ManagedResource>` + 编排逻辑），
  **不是**微服务、MQ、注册中心。绝不引入任何分布式基础设施。

### 3.6 `ResourceAdapter` — 纳管新资源的 SPI 扩展点

- **是什么**：把「某个成熟库」接入治理面的适配器契约。`HikariAdapter` 知道怎么从 HikariCP 读指标、
  怎么热调、怎么优雅关闭。通过 SPI 注册，第三方能自写 adapter 纳管新资源。
- **为什么需要**：这是「横向扩到 7 种」的机制——加一种资源 = 写一个 adapter，不动核心。
- **不用会怎样**：每加一种资源都要改核心代码，扩展性为零。
- **修掉 1.0 SPI 缺陷**：`ExtensionLoader` 每次 `getExtension` 都新建实例、无缓存、无命名；
  2.0 adapter 按 `type` 命名 + 缓存 + 可发现。

### 3.7 能力接口隔离（修 LSP 的落点）

`Pool<T>`（borrow/return）、`RateLimiter`（tryAcquire）、`DistributedLock`（lock/unlock）、
`ManagedExecutor`（execute）是**并列的能力接口**，各资源**只实现自己那个**。
DataSource 实现 `Pool`，RateLimiter 实现 `RateLimiter`，谁都不用假装实现不属于自己的方法——
**再也不会有 `throw UnsupportedOperationException`**。

---

## 4. 两个试点验证（抽象不漏证明）

| | 试点 1：DB 连接池 | 试点 2：限流器 |
|---|---|---|
| 底层成熟库 | **HikariCP** | **Bucket4j** |
| Adapter | `HikariAdapter` | `Bucket4jAdapter` |
| 是不是「池」 | ✅ 实现 `Pool<Connection>` | ❌ **不**实现 Pool，实现 `RateLimiter` |
| 功能 API | `getConnection()`（直通 Hikari） | `tryConsume(1)`（直通 Bucket4j） |
| Lifecycle | start=建池 / stop=drain 等连接归还 | start=建 bucket / stop=即时 |
| Metrics | Hikari 指标 → 打统一 tag | Bucket4j 指标 → 打统一 tag |
| Tunable | `maximum-pool-size`（via MXBean） | `limit-for-period`（重建 bucket） |

**结论**：一个是池、一个不是池，都能干净挂上 `ManagedResource + Lifecycle + Metrics + Tunable`，
功能 API 各走各的。新抽象经受住了 1.0 最尴尬的两个 case。

---

## 5. 模块结构 2.0（存活 / 改造 / 删除）

| 1.0 模块 | 2.0 处置 | 理由 |
|---|---|---|
| `metapool-common` | ♻️ 改造：只留纯契约（6 核心抽象 + 值对象），实现移出 | common 应是契约库，非实现库 |
| `metapool-pool-db` | ♻️ 改造 → `metapool-adapter-hikari`：删自研池，包 HikariCP | 试点 1 |
| `metapool-pool-rate-limit` | ♻️ 改造 → `metapool-adapter-bucket4j`：删自研令牌桶，包 Bucket4j | 试点 2 |
| `metapool-pool-redis/object/memory/thread/lock` | 🅿️ 暂缓（扩展阶段各写 adapter：Lettuce/Commons-Pool2/Netty/JDK/Redisson） | 先做深 2 种 |
| `metapool-spring-starter` | ♻️ 保留改造：自动装配 + tunable 端点 + health（变主角） | 头牌控制面入口 |
| `metapool-agent-core` | ❌ 删除（封存为可选特性） | 包装成熟库后字节码插桩无必要 |
| `metapool-spi-ai` | ❌ 删除 | 过度设计，地基未稳先做 AI 是负债 |
| `metapool-spi-alert` | ❌ 删除 | 告警交给 Prometheus AlertManager |

净效果：**12 模块 → 约 5 个**（common + starter + 2 adapter + examples/demo）。

---

## 6. Engineering Features 裁决

| 特性 | 裁决 | 理由 |
|---|---|---|
| 生命周期管理 | ✅ 核心 | 统一契约的根 |
| 优雅停机（drain） | ✅ 核心 | 修 1.0 强杀缺陷 |
| Metrics（Micrometer） | ✅ 头牌 | 复用底层库指标 |
| Health check | ✅ 核心 | Actuator 天然对接 |
| 运行时动态调参 | ✅ 头牌 | 控制面杀手锏 |
| 自动资源回收 | ⚪ 底层库已做 | HikariCP 自带 |
| Configuration（不可变+校验） | ✅ 核心 | 修 1.0 可变 setter |
| Annotation API | ⚪ YAML 够用 | 避免过度设计 |
| Tracing | 🅿️ 暂缓 | Micrometer Observation 后续接 |
| Benchmark（JMH） | ✅ 换目标 | 测「治理开销」而非「池速度」（损耗 ≤ 5%） |
| Configuration center（远程） | ❌ 不做 | 进程内 Tunable 足够 |
| AI 诊断 | 🅿️ 最后锥子 | 地基稳后作可选 SPI 演示 |
| MQ / 微服务 / DDD | ❌ 坚决不引入 | 无任何理由 |

---

## 7. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-07 | 初版：方向从自研池转向治理控制面；确认 6 核心抽象；确认试点 = HikariCP + Bucket4j |
