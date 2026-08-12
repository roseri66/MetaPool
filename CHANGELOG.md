# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [未发布] 2.1.0

无破坏性变更：`metapool-common` 已有接口与值对象的签名与 2.0.1 完全兼容，
2.0.x 的 YAML 配置无需改动即可升级。

### 新增

- **`DistributedLock` / `LockHandle` / `ManagedExecutor` 能力接口**（`metapool-common`）。
  设计与五条拍板决策见 [`docs/design/metapool-2.1-capabilities.md`](docs/design/metapool-2.1-capabilities.md)。
  其中两条最值得知道：
  - **锁只发放持有凭证，不提供 `unlock(key)`** —— 后者无法判断调用方是否持有者，
    会导致「租约到期后解了别人的锁」。凭证 `extends AutoCloseable`，try-with-resources 即正确用法。
  - **`ManagedExecutor extends Executor` 但刻意不 extends `ExecutorService`** ——
    后者带 `shutdown()`，等于给业务代码开了一个绕过控制面的第二停机入口。

- **`metapool-adapter-jdk-executor`**：把 JDK `ThreadPoolExecutor` 纳入治理（类型 `executor`）。
  `ManagedExecutor` 的第一个实现，四个能力全部落地：生命周期（三段式优雅停机）、
  统一 tag 指标（`metapool.executor.*`）、`ManagedExecutor`、以及经 JDK 原生 setter
  热调 `core-pool-size` / `maximum-pool-size`。
  **加入它时 `metapool-core` 与 `metapool-common` 零改动** —— SPI 扩展点的可核验证据。

- **starter 支持 `metapool.executors.*` YAML 声明**，示例应用同时纳管
  datasource + rate-limiter + executor 三类资源。

### 行为说明（新资源类型自带的边界）

- **线程池饱和时 `RejectedExecutionException` 原样透传**，不包装成 `MetaPoolException`。
  该异常类型本身是生态契约的一部分（`CompletableFuture`、Spring `@Async` 都按它做处理），
  包装即破坏互操作。MetaPool **自己**产生的错误仍是 `MetaPoolException` + `ErrorCode`。
- **`queue-capacity` 默认无界**（与 `Executors.newFixedThreadPool` 一致），
  此时 `maximum-pool-size` **不生效** —— 这是 JDK `ThreadPoolExecutor` 的既有行为
  （线程数到 core 后任务只进队列），MetaPool 不替它做决定，但在适配器类注释中明确写出。
  要让 max 生效必须配有界队列。
- **`queue-capacity` 不可热调**：JDK 队列容量构造时确定，放进白名单等于承诺一件底层做不到的事。

### 文档

- 踩坑台账新增 P-19（两个 size setter 各自校验 `core<=max`，逐个应用会炸在中间态）、
  P-20（`SynchronousQueue.remainingCapacity()` 恒为 0，只看队列会误判健康）、
  P-21（有状态资源在缓存复用的 Spring 上下文里造成顺序依赖的假失败）。
- 修正 RULES 两处与实际不符：commit 尾注规则（2026-07-29 已全历史清除 AI 尾注）、
  P-03 的 maven-enforcer 根治项（早已落地，此前仍标「待办」）。

### 已知局限

- 配置绑定为每个内置类型硬编码一个 Map 字段（`datasources` / `rate-limiters` / `executors`），
  因此**第三方经 SPI 扩展的资源类型暂时无法用 YAML 声明**，只能编程式接入。
  `type()` 用 String 而非 enum 的本意恰恰是不挡第三方扩展，配置层把这个口子堵回去了一半。
  通用化方案（`metapool.resources.<type>.<name>`）属公开配置面变更，待专门设计。

---

## [2.0.1] — 2026-07-26

**补丁版本。强烈建议所有 2.0.0 使用方升级** —— 2.0.0 的 `metapool-spring-starter`
会静默关掉使用方应用的全部日志（详见下方 🔴）。

无 API 变更：`metapool-common` 的所有接口与值对象签名与 2.0.0 完全兼容。

### 🔴 必须升级的原因

- **`metapool-spring-starter` 不再打包 `logback-spring.xml`**。
  2.0.0 把该文件打进了 jar 根目录，Spring Boot 的 `LogbackLoggingSystem` 会把它当成
  **使用方应用自己的**日志配置并覆盖 Boot 默认值；而它的 appender 全部包在
  `<springProfile name="dev|prod">` 里，因此：

  | 使用方情形 | 2.0.0 的后果 |
  |---|---|
  | 未激活 dev/prod（默认） | **应用全部日志静默消失**，含 MetaPool 自身的治理审计流水 |
  | 激活 `dev` | 整个应用 root logger 被强制拉到 DEBUG |
  | 激活 `prod` | 应用全部日志被改道 `logs/metapool.log`，控制台输出消失 |

  2.0.1 删除该文件，使用方恢复自己的日志配置。（台账 P-08）

### 修复

- **`Bucket4jAdapter.stop()` 补 `synchronized`**：此前与 `start()` 并发时会丢 stop ——
  `stop()` 已返回而限流器仍在放行流量。（P-09）
- **`start()` 中途失败现在会回滚**：多资源场景下若第 2 个资源启动失败，第 1 个已启动的
  连接池此前会泄漏到 JVM 结束（Spring 拿不到 bean，`destroyMethod` 永不执行）。
  现改为逆序停掉已启动者后重抛原异常。（P-11）
- **`PoolStats.totalReleased` 不再恒为 0**：累计计数改为只统计 `Pool` 能力路径
  （`borrow`/`release`），两者可对账；原生 `getConnection()` + `Connection.close()`
  的流量不计入（适配器无从观测归还）。瞬时口径请用
  `metapool.datasource.connections.*` 指标，两种用法都涵盖。（P-12）
- **`HikariAdapter` 调参后重启不再丢**：生效值现在会回写内部 `HikariConfig`，
  `stop()` → `start()` 后保留调参结果，与 `Bucket4jAdapter` 行为一致
  （此前 Hikari 会静默退回原始池大小）。（P-15）
- **优雅停机窗口内的报错语义**：新增 `ErrorCode.SHUTTING_DOWN`（`POOL-005`），
  drain 期间的 `borrow()` 不再报 `INTERNAL` + "not started"。
- **配置异常统一**：`limit-for-period` 非数字时不再漏出裸 `NumberFormatException`；
  `tryAcquire(permits)` 的非正数校验不再漏出裸 `IllegalArgumentException`
  —— 两者现在都是 `MetaPoolException` 体系。
- **`bindTo` 可绑定到多个 `MeterRegistry`**：移除自持的 "已绑定" 标志，此前它会让
  第二个 registry 静默拿不到任何指标（幂等本就由 Micrometer 保证）。（P-14）
- `ResourceManager.resources()` 现在真的返回只读视图（此前 javadoc 承诺只读但返回可变 List）。

### ⚠️ 行为变更（唯一一处）

- **`tunable` 白名单里不支持的 key 现在启动即失败**，抛 `MetaPoolConfigException`，
  而不是等到调参时才返回 `rejected`。

  ```yaml
  metapool:
    datasources:
      main:
        tunable: [maximum-poolsize]   # 漏了连字符 → 2.0.1 起启动即报错
  ```

  各 adapter 支持的 key：
  - `datasource`（Hikari）：`maximum-pool-size`、`connection-timeout`
  - `rate-limiter`（Bucket4j）：`limit-for-period`

  这些配置在 2.0.0 下**本来也没生效过**（调参时会被拒），此次改为 fail-fast 是为了
  让问题在启动时而非生产调参时暴露。升级前请检查 `tunable:` 声明拼写。（P-13）

### 构建与工程

- 修正 `metapool-spring-starter` 重复声明 adapter 依赖的问题 —— 此前后一条
  `test` scope 会静默覆盖前一条的 `<optional>true</optional>`，且触发 Maven
  `must be unique` 告警。（P-10）
- 对齐 Micrometer 各模块版本（此前 `micrometer-core` 1.14.4 对
  `micrometer-registry-prometheus` 1.14.5，Micrometer 要求同版本）。（P-16）
- 迁移 Bucket4j 已过时的 `Bandwidth.classic` / `Refill.greedy` 到
  `Bandwidth.builder().refillGreedy(...)`，构建 deprecation 告警清零。
- `metapool-examples` 补上 `repackage` execution，`mvn package` 现在产出可执行 jar。（P-18）
- 清理根 pom 中 1.0 遗留的 dependencyManagement（lettuce / commons-pool2 / guava / redisson）。

### 测试

- 基线 **25 → 37** 个测试通过（+1 个 Testcontainers PostgreSQL 用例在无 Docker 时自动跳过）。
- 新增 `ActuatorSurfaceTest`：`/actuator/metapool` 的 list/tune 与 health 指示器此前零测试。
- 新增 `StarterPackagingTest`：断言 starter 不再打包任何会劫持使用方的日志配置。
- `ExampleApplicationTest` 的限流断言去时序化（此前精确断言绑死在 200ms 补令牌周期上）。

### 文档

- README / examples 配置补上安全提示：`POST /actuator/metapool/{name}` 是变更接口，
  Actuator 默认无认证，生产须加保护或绑内网管理端口。
- `docs/RULES.md` 台账新增 P-08 ~ P-18，并提炼出 3 条新构建规则。

---

## [2.0.0] — 2026-07-25

首个 Maven Central 版本。从 1.0 的「自研 7 类资源池」转向**资源治理控制面**：
纳管 HikariCP / Bucket4j 等成熟库，统一生命周期 / 可观测 / 动态调参 / 优雅停机。

- 治理契约 `ManagedResource`（`ManagedLifecycle` + `MetricsSource`）人人实现；
  功能性 API 下沉为可选能力接口 `Pool<T>` / `RateLimiter` / `Tunable`，**编译期隔离**，
  根除 1.0 用单一 `ResourceLifecycle<T>` 硬套所有资源导致的里氏替换破坏。
- 控制面 `ResourceManager`：注册表 + 顺序启停 + 聚合健康 + 调参路由，进程内对象。
- 扩展走 JDK `ServiceLoader`（`ResourceAdapterFactory`，type → factory），核心零改动。
- 统一指标 tag `metapool.resource` / `metapool.type` —— 一个 Grafana 看板同时观察
  连接池与限流器。
- Spring Boot starter：YAML 声明式纳管 + `/actuator/metapool` 运行时热调。
- 治理开销实测：限流 +1.75%，连接借还 ~20ns 常量（对真实 PostgreSQL < 0.1%）。
