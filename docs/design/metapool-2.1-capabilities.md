# 2.1 能力接口设计：`DistributedLock` / `ManagedExecutor`

> **状态：✅ 全部落地（2026-08-12）。** 签名于 2026-07-26 进入 `metapool-common`；
> `ManagedExecutor` 由 [`metapool-adapter-jdk-executor`](../../metapool-adapter-jdk-executor) 实现，
> `DistributedLock` 由 [`metapool-adapter-redisson`](../../metapool-adapter-redisson) 实现，
> 均随 **2.1.0** 发布 Maven Central。
>
> **两个接口的实现都没有被迫抛 `UnsupportedOperationException`** —— 这是本设计成立的判据，
> 也是台账 P-07 那条教训被真正兑现的证据。落地时的取舍见
> [`adapter-redisson.md`](adapter-redisson.md) 与 [`roadmap-2.1.md`](roadmap-2.1.md) 的完成记录。
> 依据路线图 [`roadmap-2.1.md`](roadmap-2.1.md) P0#2 —— 这是 2.1 唯一必须先设计确认的一步。
> 规范依据：RULES §1.1（代码前先确认设计）、§1.6（每个设计回答"为什么需要/不用会怎样"）、
> §2.2（能力接口必须编译期隔离，禁止 `UnsupportedOperationException`）、台账 P-07。

---

## 0. 先做 P-07 自查

台账 P-07 写着「任何新抽象都要先自查是否在重犯」。1.0 恰恰是在**这两类资源**上翻的车：

| 1.0 的错误 | 根因 | 2.1 必须怎么避开 |
|---|---|---|
| `ThreadResourcePool.acquire()` → `throw UnsupportedOperationException` | 线程池被硬塞进 `Pool` 语义。**线程池不是池**——你不"借出一个线程用完还回来"，你是**提交任务**。 | `ManagedExecutor` **绝不 extends `Pool`**，签名里不出现 borrow/release |
| `SmartReentrantLock implements ResourceLifecycle<Boolean>` → 锁被建模成「借出一个 Boolean」 | 为了套统一接口，把"是否拿到锁"扭曲成"借出的资源对象" | `DistributedLock` 独立成能力接口，返回**持有凭证**而非 Boolean |

**本设计的自查结论**：两个接口都只描述该类资源**真实具备**的语义，且都不进入 `ManagedResource` 的必实现集合——
它们是可选能力，`instanceof` 探测。任何一个 adapter 都不需要为了"统一"而抛 `UnsupportedOperationException`。

---

## 1. `DistributedLock`

### 1.1 为什么需要它 / 不用会怎样

**为什么**：分布式锁是"非池、非限流"的第三类资源，用来验证治理抽象对更多形态成立。它同样需要治理——
生命周期（Redisson 客户端的启停）、可观测（持锁数、获取失败率、锁等待耗时）、优雅停机（停机前释放本进程持有的锁）。

**不用会怎样**：接 Redisson 时无处安放它的功能性 API。要么塞进 `Pool<RLock>`（重蹈 1.0 覆辙），
要么只做治理不给功能面（用户被迫绕过 MetaPool 直接拿 `RedissonClient`，治理就漏了）。

### 1.2 关键建模决策：**发放持有凭证，而不是 `lock(key)` / `unlock(key)`**

这是本设计最重要的一刀。

```java
// ❌ 方案 A（否决）：键式
boolean lock(String key, Duration wait, Duration lease);
void unlock(String key);
```

否决理由:`unlock(key)` **无法判断调用方是不是持有者**。典型事故:线程 1 拿到锁但业务超时、
租约到期锁已被线程 2 获得,此时线程 1 走到 `finally { unlock(key) }` —— **把别人的锁解了**。
这是分布式锁最经典的 bug,接口不该让它容易发生。

```java
// ✅ 方案 B（推荐）：凭证式
Optional<LockHandle> tryLock(String key, Duration waitTime, Duration leaseTime) throws InterruptedException;
```

凭证携带持有者身份,释放只能通过凭证,天然杜绝上述事故;且 `LockHandle extends AutoCloseable`,
try-with-resources 就是正确用法。

### 1.3 签名

```java
package com.metapool.common.capability;

/**
 * 分布式锁能力 —— 仅锁类资源实现（如 Redisson 适配器）。
 *
 * <p>本接口<b>不实现</b> {@link Pool}：锁不是"借出一个对象再归还"，而是"在一段租约内独占一个键"。
 * 1.0 曾把锁建模成 {@code ResourceLifecycle<Boolean>}（借出一个 Boolean），这是被明确否决的做法。
 *
 * <h3>线程安全</h3>
 * <p>所有方法必须支持多线程并发调用。
 */
public interface DistributedLock {

    /**
     * 尝试获取 {@code key} 上的锁。
     *
     * @param waitTime  最多等待多久；{@link Duration#ZERO} 表示不等待，立即返回
     * @param leaseTime 租约时长——持有超过它，锁自动释放，防止持有者崩溃导致死锁
     * @return 获取成功返回持有凭证；在 waitTime 内未获得返回 {@link Optional#empty()}
     */
    Optional<LockHandle> tryLock(String key, Duration waitTime, Duration leaseTime)
            throws InterruptedException;

    /** 当前锁资源统计快照。 */
    LockStats lockStats();
}

/**
 * 锁持有凭证。释放锁的<b>唯一</b>途径，从而杜绝"解了别人的锁"。
 *
 * <p>实现 {@link AutoCloseable}，推荐 try-with-resources：
 * <pre>{@code
 * Optional<LockHandle> h = lock.tryLock("order:123", Duration.ofSeconds(3), Duration.ofSeconds(30));
 * if (h.isEmpty()) { return REJECTED; }
 * try (LockHandle held = h.get()) {
 *     // 临界区
 * }   // 自动释放
 * }</pre>
 */
public interface LockHandle extends AutoCloseable {

    /** 本凭证对应的锁键。 */
    String key();

    /**
     * 本凭证是否仍然有效。
     *
     * <p><b>诚实说明</b>：这是<em>本地视角</em>的尽力而为判断（未 close 且租约未到期）。
     * 分布式环境下它<b>不能</b>保证远端仍认为你持有——网络分区、时钟漂移都会让判断失真。
     * 需要强正确性请用带 fencing token 的方案（见 §1.5"明确不做"）。
     */
    boolean isHeld();

    /** 释放锁。<b>幂等</b>；已释放或租约已过期时静默返回，不抛异常。 */
    @Override
    void close();
}
```

配套值对象（`metapool-common/stats`）：

```java
/**
 * @param heldByThisProcess 本进程当前持有的锁数量
 * @param totalAcquired     累计获取成功次数
 * @param totalTimeout      累计因 waitTime 耗尽而失败的次数
 * @param totalLeaseExpired 累计租约到期被动释放的次数（该指标偏高说明 leaseTime 配短了）
 */
public record LockStats(int heldByThisProcess, long totalAcquired,
                        long totalTimeout, long totalLeaseExpired) { }
```

### 1.4 为什么 `waitTime` / `leaseTime` 都是必填参数

没有提供 `lock(key)`（无限等待）重载,是刻意的:分布式环境下无限等待几乎总是 bug 的温床
(调用方以为最多卡几秒,实际卡到线程池耗尽)。**强制调用方对"等多久"和"最多持有多久"表态**,
比给一个方便但危险的默认值更负责。

`leaseTime` 同理:没有租约的分布式锁,一旦持有者进程崩溃就永久死锁。

### 1.5 明确**不做**的（连同理由）

| 不做 | 理由 |
|---|---|
| 可重入计数（`holdCount()`） | Redisson 的 `RLock` 可重入，但基于 `SETNX` 的简单实现不可重入。放进统一接口就会有 adapter 无法履约 → P-07 复发。**MetaPool 不承诺可重入**；底层若可重入是它自己的行为，由 adapter 文档说明。 |
| Fencing token（`long fencingToken()`） | ZooKeeper/Curator 能提供单调递增 token，Redis 系普遍不能（Redlock 的正确性长期有争议）。放进接口 = 逼 Redisson adapter 抛 `UnsupportedOperationException`。**若将来需要，另立 `FencedLock extends DistributedLock` 可选子接口**，谁能提供谁实现——这正是 2.0 能力隔离范式的复用。 |
| 看门狗自动续约 | 这是**配置**不是 API。Redisson 传 `leaseTime` 即关闭看门狗、不传则自动续约。MetaPool 侧统一为"显式 leaseTime"，adapter 可提供 `auto-renew: true` 配置项。 |
| 公平锁 / 读写锁 / 联锁 | 库特有能力，留给具体 adapter 的原生 API，不进统一契约（同 2.0 对 `Pool` 的处理）。 |
| `unlock(String key)` | 见 §1.2，接口层面就杜绝。 |

### 1.6 治理落点

- **类型**：`ResourceTypes.LOCK`（`"lock"`，常量已存在，核心零改动）
- **指标**：`metapool.lock.held`（gauge）、`metapool.lock.acquired.total`、`metapool.lock.timeout.total`、
  `metapool.lock.wait`（timer），统一 tag `metapool.resource` / `metapool.type`
- **health**：后端可达 = UP；获取超时率异常升高 = DEGRADED；客户端未启动/已关闭 = DOWN
- **stop(graceful)**：先停止接受新的 `tryLock`，在 graceful 期内等待本进程已持有的锁被释放，
  超时后关闭底层客户端（与 `HikariAdapter` 的 drain 同构）
- **Tunable**：暂不提供（`waitTime`/`leaseTime` 是每次调用的参数，不是资源级配置）

---

## 2. `ManagedExecutor`

### 2.1 为什么需要它 / 不用会怎样

**为什么**：线程池是最常见、也最缺治理的资源——绝大多数应用 `new ThreadPoolExecutor(...)` 之后
既无指标也无优雅停机。纳入治理后可以"一个看板看到连接池 + 限流 + 线程池",并支持运行时调核心线程数。

**不用会怎样**：线程池只能各自 `@Bean` 裸建，队列堆积、拒绝次数无人可见，停机时任务被强杀。

### 2.2 关键决策：**线程池不是池**

1.0 在这里翻过车（`ThreadResourcePool.acquire()` 抛异常）。根因是把"提交任务"错当成"借出资源"。

```java
// ❌ 1.0：线程池被迫实现 Pool
Thread t = threadPool.acquire();   // 语义不成立
// ✅ 2.1：线程池就是提交任务
executor.execute(() -> doWork());
```

因此 `ManagedExecutor` **不 extends `Pool`**，签名里没有 borrow/release。

### 2.3 关键决策：extends `java.util.concurrent.Executor`，但**不** extends `ExecutorService`

```java
public interface ManagedExecutor extends java.util.concurrent.Executor { ... }
```

**extends `Executor` 的理由**：`Executor` 只有一个 `execute(Runnable)`，是 JDK 的通用最小契约。
继承它意味着 `ManagedExecutor` 可以直接传给任何接受 `Executor` 的 API
（`CompletableFuture.supplyAsync(sup, executor)`、Spring 的 `@Async` 等），**零适配成本**。

**不 extends `ExecutorService` 的理由**（重要）：`ExecutorService` 带着
`shutdown()` / `shutdownNow()` / `awaitTermination()` / `isShutdown()` / `isTerminated()`。
把它们暴露出去，用户就能**绕过控制面直接关掉资源**——治理面立刻出现一个洞：
控制面以为资源在跑，实际已被业务代码关停。生命周期必须**只有一个入口**，即 `ManagedLifecycle.stop(graceful)`。
（这也解释了为什么不能简单地让 adapter `implements ExecutorService`。）

### 2.4 签名

```java
package com.metapool.common.capability;

/**
 * 线程池能力 —— 仅执行器类资源实现（如 JDK {@code ThreadPoolExecutor} 适配器）。
 *
 * <p><b>线程池不是池</b>：它<b>不实现</b> {@link Pool}——你不"借出一个线程用完归还"，你是提交任务。
 * 1.0 曾让线程池实现 {@code acquire()} 并直接抛 {@code UnsupportedOperationException}，
 * 这是本项目最重要的一条反面教材（台账 P-07）。
 *
 * <p>本接口<b>刻意不继承</b> {@link java.util.concurrent.ExecutorService}：那会把
 * {@code shutdown()} 暴露给业务代码，使其绕过控制面关停资源。停机只有一个入口：
 * {@link com.metapool.common.resource.ManagedLifecycle#stop(Duration)}。
 */
public interface ManagedExecutor extends java.util.concurrent.Executor {

    /**
     * 提交无返回值任务。
     *
     * <p>资源未启动或已停机时抛 {@link com.metapool.common.exception.MetaPoolException}；
     * 线程池饱和且拒绝策略为 abort 时，透传 JDK 的
     * {@link java.util.concurrent.RejectedExecutionException}（见 §2.6）。
     */
    @Override
    void execute(Runnable task);

    /** 提交有返回值任务。 */
    <T> CompletableFuture<T> submit(Callable<T> task);

    /**
     * 取底层原生 {@code ExecutorService}，用于本接口未覆盖的高级用法
     * （{@code invokeAll} / {@code invokeAny} / 自定义 {@code ThreadFactory} 等）。
     *
     * <p>⚠️ <b>契约</b>：调用方<b>不得</b>在返回对象上调用 {@code shutdown()} / {@code shutdownNow()}。
     * 停机由控制面负责；擅自关停会使控制面状态与实际不符。
     */
    ExecutorService unwrap();

    /** 当前执行器统计快照。 */
    ExecutorStats executorStats();
}
```

配套值对象：

```java
/**
 * @param activeCount        正在执行任务的线程数
 * @param poolSize           当前线程数
 * @param corePoolSize       核心线程数（可经 Tunable 热调）
 * @param maximumPoolSize    最大线程数（可经 Tunable 热调）
 * @param queueSize          队列中等待的任务数
 * @param queueRemainingCapacity 队列剩余容量（有界队列才有意义，无界返回 Integer.MAX_VALUE）
 * @param completedTaskCount 累计完成任务数
 * @param rejectedCount      累计被拒绝的任务数（MetaPool 侧计数，JDK 不提供）
 */
public record ExecutorStats(int activeCount, int poolSize, int corePoolSize, int maximumPoolSize,
                            int queueSize, int queueRemainingCapacity,
                            long completedTaskCount, long rejectedCount) { }
```

### 2.5 为什么 `submit` 返回 `CompletableFuture` 而不是 `Future`

`Future` 只能阻塞 `get()`，无法组合。`CompletableFuture` 是 `Future` 的子类型，
调用方想当 `Future` 用完全兼容，想链式组合也支持——**严格更强,无损失**。

### 2.6 已定：拒绝时**透传** `RejectedExecutionException`

**决定（2026-07-26）：透传，不包装。**

RULES §3.2 要求「异常统一继承 `MetaPoolException` 并携带 `ErrorCode`」。但线程池饱和时，
JDK 的既定契约是抛 `RejectedExecutionException`，而且 `CompletableFuture`、Spring `@Async`
等基础设施**会按这个类型做处理**。包装成 `MetaPoolException` 会破坏这些互操作。

| 方案 | 优点 | 缺点 |
|---|---|---|
| **A（推荐）**：透传 JDK 的 `RejectedExecutionException` | 与 JDK/Spring 生态互操作完好；符合"统一治理不统一用法" | 与 §3.2 字面冲突，需在 RULES 里记一条例外 |
| B：包装成 `MetaPoolException(POOL-006)` | 严格符合 §3.2 | 破坏互操作；调用方 catch `RejectedExecutionException` 会失效 |

**采纳 A。** 用户裁定的理由比"互操作"更根本：

> **「别在接口层发明第二套饱和语义。」**

也就是说，问题不只是 catch 不到，而是 MetaPool 不该为一个**已有既定含义**的概念再造一套词汇——
这与 §2.1「统一的是治理，不是用法」同源。RULES §3.2 已据此补上明示例外，并划清反向边界：
MetaPool **自己**产生的错误（未启动、配置非法、调参被拒）仍必须是 `MetaPoolException` + `ErrorCode`，
透传只适用于「底层库在其既定语义下抛出的异常」。

### 2.7 治理落点

- **类型**：`ResourceTypes.EXECUTOR`（`"executor"`，常量已存在，核心零改动）
- **指标**：`metapool.executor.active`、`.pool.size`、`.queue.size`、`.completed.total`、`.rejected.total`
- **health**：正常 UP；队列使用率超阈值或近期有拒绝 = DEGRADED；未启动/已终止 = DOWN
- **stop(graceful)**：`shutdown()` → `awaitTermination(graceful)` → 超时则 `shutdownNow()`。
  这正是 `ManagedLifecycle.stop(Duration)` 语义的天然映射
- **Tunable**：`core-pool-size`、`maximum-pool-size`、`keep-alive`
  （JDK `ThreadPoolExecutor` 原生支持运行时 setter，与 Hikari 的 `HikariConfigMXBean` 同构）

---

## 3. 对既有代码的影响

**核心零改动**（§2.8 的验证）：

- 两个常量 `ResourceTypes.EXECUTOR` / `LOCK` **已存在**，无需新增
- 两个接口是**新增文件**，放 `metapool-common/capability/`，不改动任何现有接口
- `DefaultResourceManager` 不需要认识它们——它只认 `ManagedResource` + `instanceof Tunable`
- `MetaPoolEndpoint` 的 `list()` 会自动列出新资源（它按 `type()` / `health()` 输出，与具体能力无关）

唯一需要评估的：`metapool-common` 会因 `ManagedExecutor` 引入 `java.util.concurrent` 依赖——
JDK 内置，不违反 §2.4「只依赖 micrometer-core / slf4j」。

---

## 4. 拍板结果（2026-07-26 全部确认）

1. ✅ **锁：凭证式**（§1.2）—— 代价是调用方要写 `Optional` 解包，换来接口层杜绝误解他人的锁
2. ✅ **锁：可重入与 fencing token 都不进统一接口**（§1.5）—— 将来用 `FencedLock` 子接口扩展
3. ✅ **执行器：extends `Executor`，不 extends `ExecutorService`**（§2.3）—— 不能有第二个停机入口
4. ✅ **执行器：提供 `unwrap()`**（与 `HikariAdapter.getConnection()` 一致），靠 javadoc 契约约束不许调
   `shutdown()`。未采用"屏蔽 shutdown 的包装视图"——更安全但更"魔法"，与 §1.5 简单优先有张力
5. ✅ **执行器：拒绝时透传 `RejectedExecutionException`**（§2.6）—— RULES §3.2 已补明示例外

**已落地**：5 个文件进 `metapool-common`（两个能力接口 + `LockHandle` + 两个 stats record）
+ `CapabilityIsolationTest` 结构性守护。核心零改动，`mvn clean verify` 绿。

**下一步适配器顺序**：先 `commons-pool2` adapter（最对称、验证不了新接口但风险最低），
再 `jdk-executor`（验证 `ManagedExecutor`），最后 `redisson`（验证 `DistributedLock`，需 Testcontainers Redis）。
