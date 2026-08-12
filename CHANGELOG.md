# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [未发布] 2.3.0

无破坏性变更。

### 新增

- **`metapool-adapter-lettuce`**：把 Lettuce 的 Redis 连接纳入治理（类型 `redis`）。核心零改动。
  设计全文见 [`docs/design/adapter-lettuce.md`](docs/design/adapter-lettuce.md)。

  🎯 **它刻意不实现 `Pool`。** Lettuce 是单连接多路复用——一个连接天然线程安全、被所有线程共享、
  命令在其上流水线化，**没有「借出/归还」这回事**。项目里已有两个 `Pool` 实现，于是
  「别的 adapter 都实现了 Pool，这个也该实现」听起来很自然，**但那是用一致性绑架语义**，
  与 1.0 把所有资源硬塞进 `acquire/release` 同源（P-07）。

  区别在于这次的错误会**很不明显**：假的 `Pool` 实现能跑、测试也会绿，直到某人真按池语义去用它
  （比如在"借来"的连接上跑 `MULTI/EXEC` 事务，却发现别人的命令混了进来）。
  业务改用 `LettuceAdapter.connection()` 拿原生连接——那个耦合本来就存在（发 Redis 命令必然用
  Lettuce 的 API），套一层 `Pool` 不解耦任何东西，只会多一层假抽象。

  **但它实现 `Tunable`**（`command-timeout` 运行时真可写），而 Redisson 适配器不实现——
  同一条判据（有没有真参数），两个相反结论。

- **starter 支持 `metapool.redis.*` YAML 声明。**

- **`ConfigValues.duration(key, raw)`**（`metapool-common` 的 `spi` 包）：适配器工厂共用的
  duration 解析。此前在四个工厂里各有一份**完全相同**的实现，现收敛到一处 ——
  它同时是 RULES §3.2 的一个落点（非法配置必须报 `MetaPoolConfigException`，
  不能漏出裸的 `NumberFormatException`）。参数化 `key` 以保留各调用点原有的错误消息；
  **刻意不校验正负**，因为负值在某些底层库里有确定含义（Commons Pool2 的负 `max-wait` = 无限等待），
  解析器只管「能不能读懂」，不管「合不合理」。纯新增 API，无破坏性。

### 行为说明

- **`health()` 的 DEGRADED 表示「正在自动重连」，不是故障。** Lettuce 默认自动重连，
  连接短暂断开时报 DOWN 会造成误报警。判据与「线程池饱和 / 连接池借满不等于故障」同源。
- **指标只报连接层事件**（open / connects / disconnects / exceptions）。业务直接用原生 API 发命令，
  适配器观测不到命令量，就不去猜（坑 P-12）。其中 `disconnects` 最有价值：
  Lettuce 的自动重连会把网络抖动**掩盖掉**，业务侧只觉得「偶尔慢一下」，
  不埋这条曲线没人看得见。

---

## [2.2.0] — 2026-08-12

无破坏性变更。本版不加适配器 —— 2.1 已证明的三件事（治理五类异构资源 / 能力接口经得起实现检验 /
扩展点是真的），加第六个适配器一件也不会加强。这一版补短板，见
[`docs/design/roadmap-2.2.md`](docs/design/roadmap-2.2.md)。

### 修复

- 🔴 **一个资源的 `health()` 抛异常，不再击穿整个聚合健康。**
  此前 `DefaultResourceManager.health()` 直接传播异常，于是一个行为不端的适配器能让整个
  `/actuator/health` 报错 —— 而聚合健康正是**出事时**要看的东西，最需要它的时刻恰好不可用，
  且运维看到的是一个与真实故障无关的异常。现在抛异常或返回 `null` 的资源一律计为 **DOWN
  并在 detail 里点名**，其余资源照常参与聚合。（台账 P-23）

  契约本就要求 `health()` 不抛异常，但契约管不住第三方 adapter —— 而 2.1 起 SPI 扩展是明确
  鼓励的（`metapool.resources.<type>` 就是为它开的口子），等于自己开了口子却没防住。

### 新增

- **examples 增加故障演示端点**（仅 examples，不在任何发布构件中）：
  `POST /demo/saturate/{name}?seconds=N` 故意把某个被治理资源打饱和，
  `POST /demo/release/{name}` 释放，`GET /demo/saturation` 查看当前状态。
  用来现场演示「出问题 → 治理面看得见 → 不重启热调救回来」这条链路。
  它按**能力接口**分派（`ManagedExecutor` / `Pool<T>` / `RateLimiter`），不认类型字符串。

### 测试

- `metapool-core` 新增 `ControlPlaneFailurePathTest`（16 条），专覆盖**故障路径**：
  停机时某资源抛异常其余照停、启动回滚异常不掩盖首因、聚合健康对行为不端资源免疫、
  同名并发注册只成功一次、只读快照、以及两个容易踩的语义（启动后再注册不会被自动启动、
  close 后 start 会重启）。core 的测试数 6 → 22。
- 基线 125 → **144 通过 + 0 跳过**。

---

## [2.1.0] — 2026-08-12

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

- **`metapool-adapter-redisson`**：把 Redisson 分布式锁纳入治理（类型 `lock`）。
  `DistributedLock` 的第一个实现。同样是**核心零改动**。
  设计取舍全文见 [`docs/design/adapter-redisson.md`](docs/design/adapter-redisson.md)。
  **它刻意不实现 `Tunable`** —— Redisson 锁的 `waitTime` / `leaseTime` 是每次调用传入的，
  没有运行时可调参数。可选能力谁有谁实现，不为「显得完整」硬凑（有测试守着）。

- **`metapool-adapter-commons-pool2`**：把 Commons Pool2 通用对象池纳入治理（类型 `object`）。
  核心零改动。这是继 HikariCP 之后第二个**真·池**（实现 `Pool<T>`）。
  两点与 Hikari 不同，都写进了 javadoc：
  - **它没有「自带工厂」** —— Commons Pool2 不知道怎么造 `T`，必须由使用方提供
    `PooledObjectFactory`。因此编程式接入为首选；YAML 走 `factory-class`（要求无参构造，
    构造期 fail-fast 校验类存在 / 确实实现该接口 / 有无参构造）。保留这条路是为了不破坏
    SPI 对称性 —— 否则 `create()` 只能抛异常，这个适配器就成了二等公民。
  - 🎯 **`borrow(Duration)` 在这里是真超时**：Commons Pool2 原生支持 `borrowObject(Duration)`，
    确实按传入值限时；而 `HikariAdapter` 只能以配置项 `connectionTimeout` 为界、参数仅作提示。
    同一接口方法两种语义强度，两边 javadoc 均已写明。这也顺带兑现了 2.1 路线图 P2 里
    「`Pool.borrow(Duration)` 真超时」那条待办（在本适配器上天然成立）。

- **starter 支持 `metapool.executors.*` / `metapool.locks.*` / `metapool.objects.*` YAML 声明**，
  示例应用默认纳管 datasource + rate-limiter + executor 三类；
  锁需要外部 Redis，故在 `application.yml` 中以注释形式给出完整配置（demo 保持开箱即跑）。

### 行为说明（新资源类型自带的边界）

- **线程池饱和时 `RejectedExecutionException` 原样透传**，不包装成 `MetaPoolException`。
  该异常类型本身是生态契约的一部分（`CompletableFuture`、Spring `@Async` 都按它做处理），
  包装即破坏互操作。MetaPool **自己**产生的错误仍是 `MetaPoolException` + `ErrorCode`。
- **`queue-capacity` 默认无界**（与 `Executors.newFixedThreadPool` 一致），
  此时 `maximum-pool-size` **不生效** —— 这是 JDK `ThreadPoolExecutor` 的既有行为
  （线程数到 core 后任务只进队列），MetaPool 不替它做决定，但在适配器类注释中明确写出。
  要让 max 生效必须配有界队列。
- **`queue-capacity` 不可热调**：JDK 队列容量构造时确定，放进白名单等于承诺一件底层做不到的事。

- 🔴 **分布式锁：必填 `leaseTime` 会关掉 Redisson 的看门狗**，即锁**不会自动续期**。
  这是 `DistributedLock` 契约「`leaseTime` 必填」的直接后果，**不是 bug**。
  代价是：业务执行超过租约时锁会被释放、另一进程可拿到同一把锁，
  而本契约又不提供 fencing token，下游无法拒绝过期持有者的迟到写入。
  **换来的是**：持有者进程崩溃时锁一定会过期（不会永久死锁），且失败是**可观测的**——
  `metapool.lock.lease.expired.total` 持续上涨即提示 `leaseTime` 配短了。
  规避建议（租约设为业务耗时 3~5 倍、临界区不做无界 IO、强正确性场景改用存储层互斥）
  与完整论证见适配器类注释与设计文档。该行为有专门的测试演示，以防被无意改掉。

### 文档

- 踩坑台账新增 P-19（两个 size setter 各自校验 `core<=max`，逐个应用会炸在中间态）、
  P-20（`SynchronousQueue.remainingCapacity()` 恒为 0，只看队列会误判健康）、
  P-21（有状态资源在缓存复用的 Spring 上下文里造成顺序依赖的假失败）、
  P-22（Testcontainers 默认的 Docker API 版本被新引擎拒绝，集成测试被**静默跳过**而构建仍是绿的）。
- 修正 RULES 两处与实际不符：commit 尾注规则（2026-07-29 已全历史清除 AI 尾注）、
  P-03 的 maven-enforcer 根治项（早已落地，此前仍标「待办」）。

### 构建

- **修复：Testcontainers 集成测试在新版 Docker 上被静默跳过。** Testcontainers 内置的 docker-java
  默认按 Docker API `1.32` 发请求，而 Docker Engine 29.x 的最低 API 版本是 `1.40`，`/info` 直接
  返回 400；Testcontainers 把它理解成「本机没有 Docker」而跳过全部集成测试，**构建依然 BUILD SUCCESS**。
  父 pom 的 surefire 现显式传 `api.version`（取 `1.40`——新引擎的下限，同时在老引擎支持范围内，两头兼容）。
  修复后 8 条集成测试（PG 1 条 + Redis 7 条）在本机真跑通过，基线 84+8跳过 → **92 全通过**。
  仅影响测试执行，不改变任何发布物。

### 配置

- **新增通用分段 `metapool.resources.<类型>.<名称>`，类型任意。**
  此前配置绑定为每个内置类型硬编码一个 Map 字段（`datasources` / `rate-limiters` / …），
  于是**第三方经 SPI 扩展的资源类型根本没法用 YAML 声明**——而 `type()` 用 String 而非 enum
  的本意恰恰是不挡第三方扩展，配置层把这个口子堵掉了一半。现在补上了：

  ```yaml
  metapool:
    resources:
      datasource:            # 内置类型也能这么写
        reporting:
          jdbc-url: jdbc:postgresql://.../report
      my-custom-type:        # 第三方 adapter 的类型，SPI 发现即可用
        whatever:
          some-native-key: 42
  ```

  **具名分段一个都没废弃**，2.0.x 的配置无需任何改动即可升级；两种写法可自由混用。
  唯一约束是资源名全局唯一——同名在两处出现会**启动即失败，并指出是哪两段撞了**
  （控制面自带的重名拒绝只能说「名字撞了」，说不出出处，而混用时这正是最容易犯的错）。
  未知类型同样启动即失败，并列出实际可用的类型。

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
