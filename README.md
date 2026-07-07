# MetaPool — 智能多资源池统一管理系统

[![JDK](https://img.shields.io/badge/JDK-17.0.14-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.3-brightgreen)](https://spring.io/projects/spring-boot)
[![Maven](https://img.shields.io/badge/Maven-3.9-blue)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green)](LICENSE)
[![Tests](https://img.shields.io/badge/Tests-261%20passed-brightgreen)](.)
[![Specs](https://img.shields.io/badge/Specs-20%2F20%20Complete-success)](.)

> **一站式资源池集成平台** — 7 类异构资源池统一抽象、统一配置、统一监控。
>
> 不是又一个池化框架，而是一套**集成方案**：当你需要在项目里同时管理线程池、数据库连接池、Redis 连接池、限流器、分布式锁、内存池和对象池时，不必分别引入 HikariCP、Jedis、Commons Pool2、Guava RateLimiter、Redisson 并各自配置运维——MetaPool 用一个抽象层把它们统一起来，一套 YAML 配置全部生效，一个 Grafana 大盘看到所有资源。
>
> **零付费依赖 · 100% OSI 开源协议 · Spring Boot Starter 开箱即用**

---

## 目录

- [为什么做 MetaPool —— 集成设计的由来](#为什么做-metapool--集成设计的由来)
- [集成架构详解](#集成架构详解)
- [架构总览](#架构总览)
- [模块清单](#模块清单)
- [快速开始](#快速开始)
- [配置参考](#配置参考)
- [可观测性](#可观测性)
- [开发指南](#开发指南)
- [项目状态](#项目状态)

---

## 为什么做 MetaPool —— 集成设计的由来

### 现实：碎片化的资源管理

在一个典型的 Java 后端项目中，你至少需要管理三类资源：

| 资源类型 | 通常选型 | 配置方式 | 监控方式 |
|---------|---------|---------|---------|
| 线程池 | JDK ThreadPoolExecutor | 硬编码 `new` 或 `@Bean` | jstack / 自行埋点 |
| 数据库连接池 | HikariCP | `spring.datasource.hikari.*` | HikariCP 自有 MBean |
| Redis 连接池 | Jedis / Lettuce | `JedisPoolConfig` 硬编码 | 无内置监控 |
| 限流器 | Guava RateLimiter | 硬编码 `RateLimiter.create(n)` | 无内置监控 |
| 分布式锁 | Redisson | `Config` 对象硬编码 | 无内置监控 |
| 通用对象池 | Commons Pool2 | `GenericObjectPoolConfig` | 无内置监控 |
| 内存池 | 自行实现 ByteBuffer 管理 | 无统一方案 | 无内置监控 |

结果：**7 种组件 = 7 套 API + 7 种配置风格 + 7 种监控方式（或没有）**。出问题时得同时查 jstack、Hikari MBean、Redis 连接数、Guava 限流计数——每一层都是独立的孤岛。

### MetaPool 的集成思路

MetaPool 的核心命题是：**能不能用一套抽象，把所有这些资源统一管理起来？**

答案是三层集成架构，自下而上：

```
业务代码层     @Autowired SmartThreadPoolExecutor pool;
                  @Autowired DbConnectionPool pool;
                  @Autowired RedisConnectionPool pool;
                  @Autowired TokenBucketRateLimiter limiter;
                  @Autowired SmartReentrantLock lock;
                      ↑ 全部来自 metapool-spring-starter 自动装配
                      ↑ 全部遵循 ResourceLifecycle<T> 统一接口

统一配置层     metapool:
                thread:    core-pool-size: 10  ...
                db:        max-pool-size: 20   ...
                redis:     max-pool-size: 20   ...
                rate-limit: permits-per-second: 1000 ...
                lock:      default-ttl-seconds: 30 ...
                object:    lifo: true ...
                memory:    max-direct-memory-mb: 256 ...
                      ↑ 一个 YAML 命名空间，全部配置到位

统一可观测层   Agent 字节码拦截 → Micrometer → /actuator/prometheus
                      ↑                    ↑
              28 个 Prometheus 指标  ←  Prometheus → Grafana 3 大盘 → AlertManager 6 告警规则
                      ↑
              一个端点暴露全部 7 类资源的运行状态
```

**设计哲学**：
- **抽象先行**：先定义 `ResourceLifecycle<T>` 接口和 `AbstractResourcePool` 模板方法基类，再让每类资源池去继承，保证行为一致性
- **零横向依赖**：每个 `pool-*` 模块只依赖 `metapool-common`，互不引用——你只用线程池就不必引入 Redis 客户端
- **Agent 纵向切面**：Java Agent (`-javaagent`) 是独立进程挂载，不侵入任何业务代码，与核心池化层完全解耦
- **SPI 向前兼容**：AI 诊断和告警渠道先定义接口，确保后续接入时核心逻辑零改动

---

## 集成架构详解

### 第一层：统一抽象 — `ResourceLifecycle<T>` + `AbstractResourcePool`

所有 7 类资源池实现同一个接口：

```java
public interface ResourceLifecycle<T> {
    void    init();                              // 初始化池
    T       acquire();                           // 获取资源（阻塞）
    T       acquire(long timeout, TimeUnit unit); // 获取资源（带超时）
    void    release(T resource);                  // 归还资源
    void    destroy();                            // 销毁池
    PoolStats stats();                            // 运行时统计快照
}
```

`AbstractResourcePool<T>` 提供 6 个模板方法钩子，子类只需覆写自己关心的部分：

| 钩子方法 | 调用时机 | 默认行为 |
|---------|---------|---------|
| `createResource()` | 池需要创建新资源 | 子类必须覆写 |
| `destroyResource(T)` | 资源被淘汰/销毁 | 子类必须覆写 |
| `validateResource(T)` | 资源借出前 | 返回 true |
| `onResourceAcquired(T)` | 资源借出后 | 指标采集 |
| `onResourceReleased(T)` | 资源归还后 | 指标采集 |
| `onResourceLeaked(T)` | 检测到泄露 | 告警触发 |

内置能力（所有子类自动继承）：
- 空闲资源定时回收 (`idleTimeoutSeconds`)
- 资源借出超时泄露检测 (`leakDetectionThresholdSeconds`)
- 线程安全的并发 acquire/release
- 优雅关闭 (`awaitTermination` 等待已借出资源归还)

**这意味着什么？** 实现一个新的资源池类型，只需覆写两个方法（`createResource` + `destroyResource`），就能获得：并发控制、空闲回收、泄露检测、指标采集、配置绑定、健康检查——全部从基类继承，无需重写。

### 第二层：模块隔离 — 零横向依赖

```
                    metapool-common (零外部依赖)
                           │
       ┌───────┬───────┬───┼───┬───────┬───────┐
       ▼       ▼       ▼   │   ▼       ▼       ▼
    thread    db    redis   │  object  memory  rate  lock
       │       │       │    │   │       │       │    │
       └───────┴───────┴────┼───┴───────┴───────┴────┘
                            │
               每个 pool-* 模块只依赖 common
               模块间零引用，按需单独引入
```

实际 pom.xml 中的体现：

```xml
<!-- 只需要线程池 + 限流器？引入这两个即可 -->
<dependency>
    <groupId>com.metapool</groupId>
    <artifactId>metapool-pool-thread</artifactId>
</dependency>
<dependency>
    <groupId>com.metapool</groupId>
    <artifactId>metapool-pool-rate-limit</artifactId>
</dependency>
<!-- 不会被迫引入 Redis 客户端、PG 驱动或其他不相关依赖 -->
```

### 第三层：Spring Boot Starter — 自动装配与配置绑定

这是集成体验的核心。`metapool-spring-starter` 通过 `@ConditionalOnClass` 实现按需装配：

```java
// 伪代码示意：Starter 内部逻辑
@Configuration
@ConditionalOnClass(SmartThreadPoolExecutor.class)  // classpath 有线程池 → 自动装配
public class ThreadPoolAutoConfiguration {
    @Bean
    @ConfigurationProperties(prefix = "metapool.thread")
    public ThreadPoolConfig threadPoolConfig() { ... }

    @Bean
    public SmartThreadPoolExecutor threadPool(ThreadPoolConfig config) { ... }
}

// 同理：DbPoolAutoConfiguration、RedisPoolAutoConfiguration、
//       RateLimitAutoConfiguration、LockAutoConfiguration……
// 你引入哪个模块，哪个就自动生效
```

接入方视角只有三步：

```yaml
# 1. application.yml — 一个命名空间配置全部资源
metapool:
  thread:
    core-pool-size: 10
    max-pool-size: 50
  db:
    max-pool-size: 20
  rate-limit:
    permits-per-second: 1000
```

```java
// 2. 业务代码 — 直接注入，类型安全
@RestController
public class OrderController {

    @Autowired
    private SmartThreadPoolExecutor threadPool;   // 自研线程池

    @Autowired
    private DbConnectionPool dbPool;               // 自研连接池

    @Autowired
    private TokenBucketRateLimiter rateLimiter;    // 自研限流器

    @PostMapping("/order")
    public ApiResult<String> createOrder() {
        if (!rateLimiter.tryAcquire()) {
            return ApiResult.fail(ErrorCode.RATE_LIMITED);
        }
        threadPool.execute(() -> processOrder());
        return ApiResult.success("ok");
    }
}
```

```bash
# 3. 启动中间件（可选，不启用对应模块则不需要）
docker compose -f deploy/docker-compose.dev.yml up -d
```

### 第四层：Agent 纵向切面 — 零侵入可观测

这是集成方案中最关键的一层。传统方式下，要监控 7 类资源需要：
- 线程池 → 自己埋点或引入 Micrometer ThreadPoolTaskExecutor metrics
- HikariCP → 读 HikariCP 自有 MBean
- Redis → 读 Lettuce 自有 metrics（如果有的话）
- 限流器 → 自己埋 Counter
- 分布式锁 → 自己埋 Timer + Counter
- ……

MetaPool 的做法：**一个 Agent JAR 解决全部**。

```bash
# 启动时挂载，零业务代码改动
java -javaagent:metapool-agent-core.jar=port=9100 -jar your-app.jar

# 全部指标在一个端点
curl http://localhost:9100/actuator/prometheus
```

Agent 通过 ByteBuddy 拦截 6 类关键方法，自动采集指标：

| 拦截目标 | 拦截方法 | 自动采集指标数 |
|---------|---------|:--:|
| `SmartThreadPoolExecutor` | `execute` / `submit` / `beforeExecute` / `afterExecute` | 6 |
| `AbstractResourcePool` (DB) | `acquire` / `release` | 6 |
| `AbstractResourcePool` (Redis) | `acquire` / `release` | 6 |
| `RateLimiter` | `acquire` | 3 |
| `Lock` | `tryLock` / `unlock` | 4 |
| `MemoryPool` | `allocate` / `deallocate` | 3 |

Agent 与业务完全解耦：
- Agent 是独立 JAR，`-javaagent` 进程级挂载，不修改任何业务代码
- Agent 拦截范围精确限定 `com.metapool.*`，不扫描全量类
- 移除 JVM 参数即可卸载，不影响业务系统正常启动

### 第五层：监控闭环 — Prometheus + Grafana + AlertManager

```
MetaPool App (Agent 挂载)
    │
    │  HTTP Pull (每 15s)
    ▼
Prometheus Server (指标存储 + 告警规则评估)
    │                    │
    ▼                    ▼
Grafana (3 Dashboard)   AlertManager (6 告警规则)
    │                    │
    ▼                    ▼
线程池 / 连接池 /         PoolExhausted / ResourceLeak
限流 / 锁 / 内存          ConnectionTimeout / HighQueueDepth
可视化面板                ThreadPoolRejection / LockHighContention
```

这一切通过一个 Docker Compose 命令部署：

```bash
docker compose -f deploy/docker-compose.dev.yml up -d
# 自动拉起：PostgreSQL 17.4 + Redis 7.2.8 + Prometheus 3.2.0
#           + AlertManager 0.28.0 + Grafana 11.6.0
```

### 第六层：SPI 向前兼容 — 预留未来集成点

V1 已经定义好了 AI 诊断和告警渠道的 SPI 接口，后续接入只需：

```
metapool-spi-ai       →  AiDiagnosisService    (诊断接口)
                          AiTuningAdvisor        (调优建议接口)
                          AiChatService          (运维问答接口)

metapool-spi-alert    →  AlertChannel           (告警渠道接口)
                          支持：钉钉 / 企业微信 / 飞书 / 邮件
```

实现一个 `AlertChannel` 接口，放入 classpath，`ExtensionLoader` 自动发现并加载——核心池化代码零改动。

### 集成价值总结

| 维度 | 传统方案（碎片化） | MetaPool 集成方案 |
|------|------------------|-------------------|
| 配置 | 7 套独立配置，分散在 YAML / 硬编码 / XML | 1 个命名空间 `metapool.*`，全部 YAML |
| API | 7 套不同 API（`execute()` / `getConnection()` / `acquire()` / `tryAcquire()`…） | 统一 `ResourceLifecycle<T>` 接口 |
| 监控 | 无统一方案，部分组件无内置监控 | 28 个 Prometheus 指标 + 3 个 Grafana 大盘 |
| 告警 | 需自行实现 | 6 条开箱即用 Prometheus Alert 规则 |
| 运维 | 每类资源单独排查 | 全局健康 Dashboard 一眼定位 |
| 扩展 | 新增资源类型需从零搭建 | 继承 `AbstractResourcePool`，覆写 2 个方法即可 |
| 依赖 | 7 个独立组件，版本冲突风险 | 统一 BOM 管理，版本锁定 |

---

## 架构总览

```
                          ┌──────────────────────────────────────┐
                          │     ☁  Business Application           │
                          │   (Spring Boot 3.4 / JDK 17)          │
                          └──────────────────┬───────────────────┘
                                             │ 引入 spring-starter
                                             │ 零代码侵入，自动装配
                                             ▼
┌────────────────────────────────────────────────────────────────────┐
│                        接入层 (Access Layer)                         │
│  ┌──────────────────────┐  ┌──────────────────────────────────┐   │
│  │   spring-starter     │  │  AutoConfiguration ×8            │   │
│  │   统一装配入口         │  │  按需激活: @ConditionalOnClass    │   │
│  └──────────────────────┘  └──────────────────────────────────┘   │
├────────────────────────────────────────────────────────────────────┤
│                      核心池化层 (Resource Pool Layer)               │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌─────────┐ │
│  │  thread  │ │   db     │ │  redis   │ │  object  │ │ memory  │ │
│  │  线程池  │ │  PG连接池 │ │ Redis池  │ │ 通用对象池│ │ 内存池  │ │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬─────┘ └────┬────┘ │
│  ┌────┴────────────┴──────────────────────────────────────┴────┐  │
│  │                    rate-limit (限流器)                        │  │
│  │                    lock (分布式锁)                             │  │
│  └──────────────────────────────────────────────────────────────┘  │
│        ▶ 全部继承 AbstractResourcePool · 模块间零横向依赖             │
├────────────────────────────────────────────────────────────────────┤
│                   基础设施层 (Infrastructure Layer)                  │
│  ┌──────────────────────┐  ┌──────────────────────────────────┐   │
│  │   common-core        │  │   SPI Extensions                 │   │
│  │   生命周期接口         │  │   ExtensionLoader + @SPI        │   │
│  │   AbstractPool       │  │   异常体系 · 字典枚举             │   │
│  └──────────────────────┘  └──────────────────────────────────┘   │
├────────────────────────────────────────────────────────────────────┤
│                         ║ Agent 纵向切面 ║                           │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │  -javaagent:agent-core.jar  ·  ByteBuddy 1.15.10             │  │
│  │  字节码拦截 → 指标采集 → /actuator/prometheus                 │  │
│  └──────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────┘
                                     │
                                     ▼
┌────────────────────────────────────────────────────────────────────┐
│                    外部中间件 (Docker Compose 一键部署)               │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌─────────┐ │
│  │PostgreSQL│ │  Redis   │ │Prometheus│ │AlertMgr │ │ Grafana │ │
│  │  17.4    │ │  7.2.8   │ │  3.2.0   │ │ 0.28.0  │ │ 11.6.0  │ │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └─────────┘ │
└────────────────────────────────────────────────────────────────────┘
```

### 关键设计决策

| 决策 | 说明 |
|------|------|
| **Agent 独立进程挂载** | `-javaagent` 零业务代码侵入，与核心池化层解耦 |
| **模块间零横向依赖** | 7 类资源池平铺，业务方按需引用，不强制全量引入 |
| **common 零外部依赖** | metapool-common 不含 Lombok/Slf4j，不污染下游 |
| **V1 仅 SPI 接口** | AI 诊断 & 告警渠道仅定义契约，保证向前兼容 |

---

## 模块清单

| 模块 | Spec | 说明 | 测试 |
|------|:--:|------|:--:|
| `metapool-common` | 01~05 | 生命周期接口、抽象基类、SPI、异常体系、枚举 | 26 |
| `metapool-pool-thread` | 06 | 自研线程池（3 种拒绝策略、队列可观测、动态调参） | 17 |
| `metapool-pool-db` | 07 | PostgreSQL 连接池（连接验证、泄露检测、慢连接剔除） | 14 |
| `metapool-pool-redis` | 08 | Redis 连接池（PING 验证、密码认证、自动恢复） | 12 |
| `metapool-pool-object` | 09 | 通用对象池（FIFO/LIFO、泛型工厂、驱逐策略） | 17 |
| `metapool-pool-memory` | 10 | 内存资源池（堆内分页、堆外预留、硬限制） | 26 |
| `metapool-pool-rate-limit` | 11 | 令牌桶限流器（预热机制、动态调参） | 10 |
| `metapool-pool-lock` | 12 | 分布式锁（可重入、自动续期、Redis 故障降级） | 29 |
| `metapool-agent-core` | 13~14 | ByteBuddy 字节码拦截 + Prometheus 指标暴露 | 51 |
| `metapool-spring-starter` | 16 | Spring Boot 自动装配（HealthIndicator、Actuator、全局异常处理） | 22 |
| `metapool-spi-ai` | 17 | AI 诊断 SPI 接口（V1 仅接口定义） | 28 |
| `metapool-spi-alert` | 18 | 告警渠道 SPI 接口（V1 仅接口定义） | 9 |
| `deploy/` | 15/19/20 | Docker Compose ×2 + Grafana ×3 + Prometheus 告警规则 ×6 | — |

---

## 快速开始

### 环境要求

- **JDK 17+**
- **Maven 3.9+**
- **Docker Desktop** (用于中间件)

### 1. 构建项目

```bash
git clone <repo-url> metapool
cd metapool

# 设置 JDK 17
export JAVA_HOME="/path/to/jdk-17"

# 编译 + 测试
mvn clean test
```

### 2. 启动中间件（Docker Compose）

```bash
# 开发环境：仅启动 PostgreSQL + Redis + Prometheus + Grafana + AlertManager
docker compose -f deploy/docker-compose.dev.yml up -d

# 验证所有服务健康
docker ps
curl http://localhost:9090/-/healthy   # Prometheus
curl http://localhost:3000/api/health  # Grafana
```

### 3. 使用 MetaPool

#### 方式一：Spring Boot Starter（推荐）

```xml
<!-- pom.xml — 按需引入，引入哪个装配哪个 -->
<dependency>
    <groupId>com.metapool</groupId>
    <artifactId>metapool-spring-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.metapool</groupId>
    <artifactId>metapool-pool-thread</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<!-- 其他池模块按需添加：pool-db / pool-redis / pool-rate-limit / pool-lock / ... -->
```

```yaml
# application.yml — 一个命名空间配置全部
metapool:
  thread:
    core-pool-size: 10
    max-pool-size: 50
    queue-capacity: 1000
```

```java
// 业务代码 — 零侵入，自动装配
@RestController
public class TaskController {

    @Autowired
    private SmartThreadPoolExecutor threadPool;

    @PostMapping("/task")
    public ApiResult<String> submit() {
        threadPool.execute(() -> doWork());
        return ApiResult.success("ok");
    }
}
```

#### 方式二：直接使用池模块（不依赖 Spring）

```java
// 自研线程池 — 独立使用
ThreadPoolConfig config = new ThreadPoolConfig();
config.setCorePoolSize(10);
config.setMaxPoolSize(50);

SmartThreadPoolExecutor pool = new SmartThreadPoolExecutor(config);

pool.execute(() -> System.out.println("Hello MetaPool!"));
pool.stats();  // PoolStats{activeCount=1, ...}
pool.shutdown();
```

#### 方式三：Java Agent（可观测）

```bash
# 挂载 Agent，自动采集全部 7 类资源池指标
java -javaagent:metapool-agent-core.jar=port=9100 -jar your-app.jar

# 查看全部指标
curl http://localhost:9100/actuator/prometheus
```

---

## 配置参考

完整配置参见 `metapool-spring-starter/src/main/resources/application-metapool.yml`。

```yaml
metapool:
  # ── 线程池 ──
  thread:
    core-pool-size: 10
    max-pool-size: 50
    keep-alive-seconds: 60
    queue-capacity: 1000
    rejected-policy: CALLER_RUNS          # ABORT | CALLER_RUNS | DISCARD
    thread-name-prefix: metapool-worker-

  # ── 数据库连接池 ──
  db:
    min-idle: 5
    max-pool-size: 20
    max-lifetime-minutes: 30
    idle-timeout-seconds: 600
    connection-timeout-seconds: 30
    validation-timeout-seconds: 5
    leak-detection-threshold-seconds: 60

  # ── Redis 连接池 ──
  redis:
    min-idle: 5
    max-pool-size: 20
    connection-timeout-seconds: 10

  # ── 限流器 ──
  rate-limit:
    algorithm: TOKEN_BUCKET
    permits-per-second: 1000
    warm-up-seconds: 10

  # ── 分布式锁 ──
  lock:
    default-ttl-seconds: 30
    renewal-interval-seconds: 10
    max-retry-count: 3
    retry-interval-millis: 100

  # ── 对象池 ──
  object:
    min-idle: 2
    max-pool-size: 32
    lifo: true

  # ── 内存池 ──
  memory:
    max-direct-memory-mb: 256
    page-size-kb: 64
```

---

## 可观测性

### 指标全景（28 个 Prometheus 指标）

| 资源池 | 指标数 | 前缀 | 示例 |
|--------|:---:|------|------|
| 线程池 | 6 | `metapool_thread_*` | `_active_count`, `_pool_size`, `_queue_size`, `_completed_total`, `_rejected_total`, `_queue_wait_seconds` |
| 数据库连接池 | 6 | `metapool_db_*` | `_active_connections`, `_idle_connections`, `_pending_requests`, `_connection_timeout_total`, `_connection_leak_total`, `_acquire_wait_seconds` |
| Redis 连接池 | 6 | `metapool_redis_*` | 同上结构 |
| 限流器 | 3 | `metapool_rate_*` | `_pass_total`, `_reject_total`, `_available_permits` |
| 分布式锁 | 4 | `metapool_lock_*` | `_acquire_total`, `_timeout_total`, `_hold_seconds`, `_contention_count` |
| 内存池 | 3 | `metapool_memory_*` | `_used_bytes`, `_max_bytes`, `_allocation_total` |

### Grafana Dashboard

| Dashboard | 面板数 | 访问方式 |
|-----------|:---:|------|
| **线程池监控** | 8 面板 | Grafana → Dashboards → MetaPool — 线程池监控 |
| **连接池监控** | 8 面板 | Grafana → Dashboards → MetaPool — 连接池监控 |
| **全局健康总览** | 16 面板 | Grafana 默认首页，全池概览 + 告警汇总 |

```bash
# Grafana 访问
open http://localhost:3000
# 用户名: admin  密码: admin
```

### Prometheus 告警规则（6 条）

| 告警 | 触发条件 | 级别 |
|------|---------|:--:|
| `PoolExhausted` | pending 请求 > 100 持续 1m | **Critical** |
| `ResourceLeakDetected` | leak 计数 > 0 | Warning |
| `ConnectionTimeout` | 5m 内超时 > 10 次 | **Critical** |
| `HighQueueDepth` | 队列使用率 > 90% | Warning |
| `ThreadPoolRejection` | 5m 内拒绝 > 100 次 | **Critical** |
| `LockHighContention` | 等待线程 > 10 持续 2m | Warning |

---

## 开发指南

### 工程结构

```
metapool/
├── pom.xml                          # 根 POM（dependencyManagement）
├── metapool-common/                # 基础设施层（零外部依赖）
├── metapool-pool-thread/           # 线程池
├── metapool-pool-db/               # 数据库连接池
├── metapool-pool-redis/            # Redis 连接池
├── metapool-pool-object/           # 通用对象池
├── metapool-pool-memory/           # 内存资源池
├── metapool-pool-rate-limit/       # 限流器
├── metapool-pool-lock/             # 分布式锁
├── metapool-agent-core/            # Java Agent（独立 JAR）
├── metapool-spring-starter/        # Spring Boot Starter
├── metapool-spi-ai/                # AI 诊断 SPI（V1 仅接口）
├── metapool-spi-alert/             # 告警渠道 SPI（V1 仅接口）
├── deploy/                          # Docker Compose + 监控配置
│   ├── docker-compose.dev.yml
│   ├── docker-compose.prod.yml
│   ├── .env
│   ├── prometheus/
│   ├── alertmanager/
│   └── grafana/
└── docs/                            # 项目文档（上级目录）
```

### 编码规范

项目遵循 **Alibaba Java Guide**，额外规范：

- **包命名**：`com.metapool.{module}`
- **common 零外部依赖**：不含 Lombok/Slf4j
- **异常码格式**：`{域}-{三位数字}`，如 `POOL-001`
- **资源池状态守卫**：必须覆盖 NEW/INITIALIZING 等初始化前状态
- **AutoConfiguration 禁止 @ComponentScan**：避免无条件扫描导致上下文初始化失败

详见：[项目 Rules 规范文档](../项目%20Rules%20规范文档.md)（18 条规则）

### 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| JDK | 17.0.14 (LTS) | 编译 + 运行时 |
| Spring Boot | 3.4.3 | 应用框架 + Starter |
| ByteBuddy | 1.15.10 | Java Agent 字节码增强 |
| Micrometer | 1.14.5 | Prometheus 指标门面 |
| Lettuce | 6.3.2 | Redis 客户端 |
| PostgreSQL JDBC | 42.7.5 | PG 数据库驱动 |
| JMH | 1.37 | 性能基准测试 |
| JUnit 5 | 5.11.4 | 单元测试 |
| Testcontainers | 1.20.6 | 集成测试 |

所有依赖 **100% OSI 认证开源协议**，零付费依赖。

---

## 项目状态

| 维度 | 状态 |
|------|:--:|
| Spec 完成度 | **20/20** ✅ |
| 测试用例 | 261 passed, 0 failed |
| JMH 基准 | 4 模块（ThreadPool, ObjectPool, RateLimiter, MemoryPool） |
| 可观测 | Prometheus 28 指标 + Grafana 3 Dashboard + 6 告警规则 |
| 部署 | Docker Compose dev + prod 一键部署 |

---

## License

[Apache License 2.0](LICENSE)

---

*Made with ❤️ by [roseri66](https://github.com/roseri66) · 2026*
