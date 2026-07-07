# SmartPool — 智能多资源池统一管理系统

[![JDK](https://img.shields.io/badge/JDK-17.0.14-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.3-brightgreen)](https://spring.io/projects/spring-boot)
[![Maven](https://img.shields.io/badge/Maven-3.9-blue)](https://maven.apache.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-green)](LICENSE)
[![Tests](https://img.shields.io/badge/Tests-261%20passed-brightgreen)](.)
[![Coverage](https://img.shields.io/badge/Coverage-20%2F20%20Specs%20Complete-success)](.)

> **一站式资源池管理平台** — 自研 7 类资源池 + Java Agent 可观测 + Prometheus/Grafana 监控大盘 + Docker Compose 一键部署。
>
> 对标组件：JDK ThreadPoolExecutor · HikariCP · Jedis/Lettuce · Apache Commons Pool2
>
> **零付费依赖 · 100% OSI 开源协议 · 单机即可运行**

---

## 目录

- [架构总览](#架构总览)
- [模块清单](#模块清单)
- [快速开始](#快速开始)
- [配置参考](#配置参考)
- [可观测性](#可观测性)
- [性能基准](#性能基准)
- [开发指南](#开发指南)
- [项目状态](#项目状态)

---

## 架构总览

```
                          ┌──────────────────────────────────────┐
                          │     ☁  Business Application           │
                          │   (Spring Boot 3.4 / JDK 17)          │
                          └──────────────────┬───────────────────┘
                                             │ 引入 spring-starter
                                             │ 零代码侵入
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
| **common 零外部依赖** | smartpool-common 不含 Lombok/Slf4j，不污染下游 |
| **V1 仅 SPI 接口** | AI 诊断 & 告警渠道仅定义契约，不挤占 30 天工期 |

---

## 模块清单

| 模块 | Spec | 说明 | 测试 |
|------|:--:|------|:--:|
| `smartpool-common` | 01~05 | 生命周期接口、抽象基类、SPI、异常体系、枚举 | 26 |
| `smartpool-pool-thread` | 06 | 自研线程池（3 种拒绝策略、队列可观测、动态调参） | 17 |
| `smartpool-pool-db` | 07 | PostgreSQL 连接池（连接验证、泄露检测、慢连接剔除） | 14 |
| `smartpool-pool-redis` | 08 | Redis 连接池（PING 验证、密码认证、自动恢复） | 12 |
| `smartpool-pool-object` | 09 | 通用对象池（FIFO/LIFO、泛型工厂、驱逐策略） | 17 |
| `smartpool-pool-memory` | 10 | 内存资源池（堆内分页、堆外预留、硬限制） | 26 |
| `smartpool-pool-rate-limit` | 11 | 令牌桶限流器（预热机制、动态调参） | 10 |
| `smartpool-pool-lock` | 12 | 分布式锁（可重入、自动续期、Redis 故障降级） | 29 |
| `smartpool-agent-core` | 13~14 | ByteBuddy 字节码拦截 + Prometheus 指标暴露 | 51 |
| `smartpool-spring-starter` | 16 | Spring Boot 自动装配（HealthIndicator、Actuator、全局异常处理） | 22 |
| `smartpool-spi-ai` | 17 | AI 诊断 SPI 接口（V1 仅接口定义） | 28 |
| `smartpool-spi-alert` | 18 | 告警渠道 SPI 接口（V1 仅接口定义） | 9 |
| `deploy/` | 15/19/20 | Docker Compose ×2 + Grafana ×3 + Prometheus 告警规则 ×6 | — |

---

## 快速开始

### 环境要求

- **JDK 17+**
- **Maven 3.9+**
- **Docker Desktop** (用于中间件)

### 1. 构建项目

```bash
git clone <repo-url> smartpool
cd smartpool

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

### 3. 使用 SmartPool

#### 方式一：Spring Boot Starter（推荐）

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.smartpool</groupId>
    <artifactId>smartpool-spring-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.smartpool</groupId>
    <artifactId>smartpool-pool-thread</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```yaml
# application.yml — 按需配置即可
smartpool:
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

#### 方式二：直接使用池模块

```java
// 自研线程池 — 独立使用
ThreadPoolConfig config = new ThreadPoolConfig();
config.setCorePoolSize(10);
config.setMaxPoolSize(50);

SmartThreadPoolExecutor pool = new SmartThreadPoolExecutor(config);

pool.execute(() -> System.out.println("Hello SmartPool!"));
pool.stats();  // PoolStats{activeCount=1, ...}
pool.shutdown();
```

#### 方式三：Java Agent（可观测）

```bash
# 挂载 Agent，自动采集指标
java -javaagent:smartpool-agent-core.jar=port=9100 -jar your-app.jar

# 查看指标
curl http://localhost:9100/actuator/prometheus
```

---

## 配置参考

完整配置参见 `smartpool-spring-starter/src/main/resources/application-smartpool.yml`。

```yaml
smartpool:
  # ── 线程池 ──
  thread:
    core-pool-size: 10
    max-pool-size: 50
    keep-alive-seconds: 60
    queue-capacity: 1000
    rejected-policy: CALLER_RUNS          # ABORT | CALLER_RUNS | DISCARD
    thread-name-prefix: smartpool-worker-

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
| 线程池 | 6 | `smartpool_thread_*` | `_active_count`, `_pool_size`, `_queue_size`, `_completed_total`, `_rejected_total`, `_queue_wait_seconds` |
| 数据库连接池 | 6 | `smartpool_db_*` | `_active_connections`, `_idle_connections`, `_pending_requests`, `_connection_timeout_total`, `_connection_leak_total`, `_acquire_wait_seconds` |
| Redis 连接池 | 6 | `smartpool_redis_*` | 同上结构 |
| 限流器 | 3 | `smartpool_rate_*` | `_pass_total`, `_reject_total`, `_available_permits` |
| 分布式锁 | 4 | `smartpool_lock_*` | `_acquire_total`, `_timeout_total`, `_hold_seconds`, `_contention_count` |
| 内存池 | 3 | `smartpool_memory_*` | `_used_bytes`, `_max_bytes`, `_allocation_total` |

### Grafana Dashboard

| Dashboard | 面板数 | 访问方式 |
|-----------|:---:|------|
| **线程池监控** | 8 面板 | Grafana → Dashboards → SmartPool — 线程池监控 |
| **连接池监控** | 8 面板 | Grafana → Dashboards → SmartPool — 连接池监控 |
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

## 性能基准

> JMH 1.37 · JDK 17 · 3 warmup + 5 measurement iterations · Mode: Throughput (ops/s)

### 线程池 — SmartThreadPoolExecutor vs JDK ThreadPoolExecutor

| Benchmark | SmartPool (ops/s) | JDK (ops/s) | SmartPool/JDK |
|-----------|------------------:|------------:|:--:|
| 单线程 execute | 47,765,739 | 99,527,889 | 48% |
| 4 线程 execute | 30,792,174 | 59,704,503 | **52%** |

> 双检锁优化消除热路径锁竞争，单线程场景下吞吐量达 JDK 的 48%。4 线程竞争场景下 SmartPool 保持稳定的线性扩展。

### 对象池 — GenericObjectPool

| Benchmark | Throughput (ops/s) |
|-----------|-------------------:|
| FIFO 单线程 | 12,432,499 |
| FIFO 4 线程 | 9,056,830 |
| LIFO 单线程 | 11,613,901 |
| LIFO 4 线程 | 7,800,524 |

> FIFO 比 LIFO 快 ~7%；4 线程竞争下吞吐量约为单线程的 68%。

### 限流器 — TokenBucketRateLimiter

| Benchmark | Throughput (ops/s) |
|-----------|-------------------:|
| 不限流 单线程 | 26,629,764 |
| 不限流 4 线程 | 13,119,638 |
| 1 万 QPS 单线程 | 26,708,279 |
| 1 万 QPS 4 线程 | 12,998,543 |

> 高/低 QPS 配置下吞吐量差异极小（< 0.5%），synchronized 竞争为主要瓶颈，4 线程时吞吐量约为单线程的 49%。

### 内存池 — MemoryPool

| Benchmark | Throughput (ops/s) |
|-----------|-------------------:|
| alloc/dealloc 单线程 | 16,354,306 |
| alloc/dealloc 4 线程 | 10,090,605 |

> 单线程 ~16.4M ops/s，4 线程竞争下保持 ~10.1M ops/s，内存页借还路径健康。

### DB/Redis 连接池 — 高并发压力测试

| 模块 | 测试条件 | 结果 |
|------|---------|:--:|
| DB Pool | 50 线程 × 200 次借还 | ✅ 无死锁、无泄露 |
| Redis Pool | 50 线程 × 200 次借还 | ✅ 无死锁、无泄露 |

### 运行基准测试

```bash
# 设置 JDK 17
export JAVA_HOME="/path/to/jdk-17"

# 方式一：运行全部 4 个基准（逐个执行）
mvn test-compile -DskipTests

# 线程池基准
mvn -pl smartpool-pool-thread -am test-compile exec:java \
  -Dexec.mainClass="com.smartpool.pool.thread.ThreadPoolBenchmark" \
  -Dexec.classpathScope=test

# 对象池基准
mvn -pl smartpool-pool-object -am test-compile exec:java \
  -Dexec.mainClass="com.smartpool.pool.object.ObjectPoolBenchmark" \
  -Dexec.classpathScope=test

# 限流器基准
mvn -pl smartpool-pool-rate-limit -am test-compile exec:java \
  -Dexec.mainClass="com.smartpool.pool.rate.limit.RateLimiterBenchmark" \
  -Dexec.classpathScope=test

# 内存池基准
mvn -pl smartpool-pool-memory -am test-compile exec:java \
  -Dexec.mainClass="com.smartpool.pool.memory.MemoryPoolBenchmark" \
  -Dexec.classpathScope=test
```

---

## 开发指南

### 工程结构

```
smartpool/
├── pom.xml                          # 根 POM（dependencyManagement）
├── smartpool-common/                # 基础设施层（零外部依赖）
├── smartpool-pool-thread/           # 线程池
├── smartpool-pool-db/               # 数据库连接池
├── smartpool-pool-redis/            # Redis 连接池
├── smartpool-pool-object/           # 通用对象池
├── smartpool-pool-memory/           # 内存资源池
├── smartpool-pool-rate-limit/       # 限流器
├── smartpool-pool-lock/             # 分布式锁
├── smartpool-agent-core/            # Java Agent（独立 JAR）
├── smartpool-spring-starter/        # Spring Boot Starter
├── smartpool-spi-ai/                # AI 诊断 SPI（V1 仅接口）
├── smartpool-spi-alert/             # 告警渠道 SPI（V1 仅接口）
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

- **包命名**：`com.smartpool.{module}`
- **common 零外部依赖**：不含 Lombok/Slf4j
- **异常码格式**：`{域}-{三位数字}`，如 `POOL-001`
- **资源池状态守卫**：必须覆盖 NEW/INITIALIZING 等初始化前状态
- **AutoConfiguration 禁止 @ComponentScan**：避免无条件扫描导致上下文初始化失败

详见：[项目 Rules 规范文档](../项目%20Rules%20规范文档.md)（18 条规则）

### 开发流程

```bash
# 1. 阅读 Rules + Spec + 编码规范
# 2. 编码实现

# 3. 全量测试
export JAVA_HOME="/path/to/jdk-17"
mvn test

# 4. 更新文档（Spec 状态 + Rules + CONTEXT_SUMMARY）
```

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

*Made with ❤️ by SmartPool Team · 2026*
