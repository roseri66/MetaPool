# adapter-redisson 设计决策

> 2.1 P1 的第二个适配器：把 Redisson 分布式锁纳入 MetaPool 治理面（类型 `lock`）。
> 完成于 2026-08-12。前置阅读：[`metapool-2.1-capabilities.md`](metapool-2.1-capabilities.md)（`DistributedLock` 接口设计与 5 条拍板决策）。

本文件记录**实现期做出的取舍及其理由**。接口本身的决策在上面那份文档里，这里只写「把它落到 Redisson 上时，遇到了什么、选了什么、放弃了什么」。

---

## 一、为什么这个适配器排在 commons-pool2 前面

原路线图的建议顺序是 commons-pool2 → jdk-executor → redisson。实际执行时**两次都把「验证新接口」排在了「低风险对称抄写」前面**：

| | 挂的能力接口 | 该接口此前的实现数 | 风险性质 |
|---|---|---|---|
| commons-pool2 | `Pool<T>` | 1（HikariCP，已验证两遍） | 实现风险，低 |
| jdk-executor | `ManagedExecutor` | **0** | **设计风险**，未知 |
| redisson | `DistributedLock` | **0** | **设计风险**，未知 |

判断依据一句话：**接口设计的风险远大于适配器实现的风险，应当先暴露。**
抽象抽错了要改接口（影响所有实现方、且 `metapool-common` 是已发布的公开契约层）；适配器写错了只影响一个模块。所以先做那个能证伪抽象的。

commons-pool2 属于「补齐谱系」，风险最低，放最后做也不会有惊喜。

---

## 二、核心取舍：必填 `leaseTime` 关掉了 Redisson 的看门狗

### 事实

Redisson 的 `RLock` 有一个**看门狗（watchdog）**机制：调用不带 lease 的 `tryLock(waitTime, unit)` 时，后台线程会在租约过半时自动续期，只要持有者进程活着，锁就不会过期。

但 `tryLock(waitTime, leaseTime, unit)` **一旦显式传入 leaseTime，看门狗就不再介入**。

而 `DistributedLock` 的接口契约（拍板决策 ②）规定 `leaseTime` **必填**。两者相乘的结论是：

> **在 MetaPool 下，Redisson 的看门狗永远不会启用。**

### 这个代价有多真实

业务执行超过租约 → 锁自动释放 → 另一个进程拿到同一把锁 → **两个线程同时在临界区**。而决策 ③ 又明确不提供 fencing token（各后端并非都能提供），所以下游存储**无法识别并拒绝**「过期持有者」的迟到写入。

这正是 Martin Kleppmann 批评「基于超时的分布式锁」时描述的场景，不是理论风险。

### 为什么仍然选择必填

反过来看，如果允许不填 lease 以启用看门狗，代价是另一头的：**持有者进程崩溃（或被 kill -9、或 GC 长停顿到心跳断掉）时，锁不再续期但也没有明确的过期点**，最坏情况是死锁到 Redis 键被人工清理。

两害相权：

| 方案 | 失败模式 | 谁能发现 | 能否兜底 |
|---|---|---|---|
| 必填租约（选中） | 业务超时 → 锁被抢 | **能**：`totalLeaseExpired` 指标会涨 | 能：调大 lease / 缩短临界区 |
| 看门狗自动续期 | 进程崩溃 → 锁悬挂 | 难：要去翻 Redis 键 | 难：只能人工介入 |

选中的方案，**失败是可观测、可调、可预期的**；另一个方案的失败是静默的。治理面的第一价值就是"让状态可见"，所以选前者。

### 怎么处理这个代价

**没有藏起来，做了三件事**：

1. **写进适配器类注释**，含规避建议（租约设为业务耗时 3~5 倍、临界区不做无界 IO、正确性要求极强时改用存储层互斥）。
2. **写成一条测试**：`leaseExpiry_releasesLock_evenWhileHolderStillThinksItHoldsIt`。
   它演示的就是"锁被别人抢走"这个场景本身。写成测试意味着这个代价**可见，且不会被后来者无意改掉**。
3. **给它一个指标**：`metapool.lock.lease.expired.total`。这条曲线持续上涨就是"租约配短了"的直接信号。

> 面试可讲：**知道自己的设计牺牲了什么，把它写下来、测出来、量出来。**
> 三者缺一，"我们考虑过这个问题"就只是嘴上说说。

---

## 三、这个适配器刻意不实现 `Tunable`

Redisson 锁**没有有意义的运行时可调参数**——`waitTime` / `leaseTime` 是**每次调用传入**的，不是配置项。

为了让治理面"看起来完整"而硬凑一个可调参数（比如把某个默认值做成可调），是在制造假象：控制面显示"这个资源可以热调"，实际调了什么也不影响正在进行的加锁。

所以它只实现 `ManagedResource` + `DistributedLock`。

**这是好事，不是缺项。** 它证明「可选能力接口」这套设计在正常工作：谁有谁实现，没有就不实现。有一条测试专门守着：

```java
assertFalse(lock instanceof Tunable,
        "Redisson 锁无运行时可调参数，不应为了「显得完整」而实现 Tunable");
```

对照 1.0 的做法——那时是**先定义一个大接口，再逼所有资源实现，实现不了就抛 `UnsupportedOperationException`**（台账 P-07）。现在是反过来的：**资源有什么能力就声明什么能力**。

---

## 四、`LockHandle` 的两个实现约束

### 1. 凭证不可跨线程

Redisson 的 `RLock` 是**线程绑定**的：`unlock()` 在非持有线程上调用会抛 `IllegalMonitorStateException`。

因此 `LockHandle` **必须在获取它的那个线程上 `close()`**。已写进类注释。try-with-resources 天然满足，所以推荐用法本身就规避了这个坑。

### 2. `close()` 绝不抛异常

契约要求幂等且静默。实现上三层保护：

```java
if (!closed.compareAndSet(false, true)) return;     // ① 重复 close 直接返回
if (lock.isHeldByCurrentThread()) lock.unlock();    // ② 只解自己的锁
catch (IllegalMonitorStateException e) { ... }      // ③ 后端拒绝时也不抛
```

理由：`close()` 最常出现在 try-with-resources / `finally` 里。**在那里抛异常会掩盖业务的原始异常**——排查时看到的是"解锁失败"，真正的业务错误却不见了。

### 3. 互斥测试必须用另一个线程

写测试时踩到的：Redisson 的 `RLock` **可重入**，同一线程再次 `tryLock` 同一个键会直接成功，**测不出互斥**。必须起一个新线程去竞争。

顺带说明：可重入是 Redisson **自身**的行为，`DistributedLock` 契约**不承诺**它（决策 ③：基于 `SETNX` 的实现就不可重入，写进统一接口必然逼出 `UnsupportedOperationException`）。

---

## 五、`totalLeaseExpired` 是近似值，并且说明了口径

真正的租约到期发生在 **Redis 侧**，进程内观测不到。

本地能观测到的最接近信号是：`close()` 时发现该锁已不再由本线程持有。就用它计数，**并在 javadoc 写明这是近似口径、会低估**（拿到锁后从未 `close` 的情况数不到）。

这条呼应台账 **P-12** 的教训（当时 `PoolStats.totalReleased` 因为归还走 `Connection.close()` 而恒为 0）——那次的结论是"凡归还由底层对象自己完成的，适配器别自作多情记计数器"。这里的区别是：我们确实有一个本地可观测的近似信号，那就**用它，但把口径说清楚**，而不是假装精确。

---

## 六、停机时不强解本进程持有的锁

`stop(graceful)` **刻意不去解掉仍被持有的锁**——那些锁对应的业务可能还在临界区里跑，替它解锁等于亲手制造并发。

未释放的锁交给**租约**自然过期。这正好是"`leaseTime` 必填"的另一处兑现：**没有租约的分布式锁，持有者进程一消失就是永久死锁。**

停机时若仍有持有中的锁，记一条 WARN——通常说明停机窗口短于业务临界区，是运维该知道的事。

---

## 七、依赖卫生

Redisson **不在** `spring-boot-dependencies` 里，所以在根 pom 自设版本（`3.35.0`）。这不违反台账 P-16——P-16 说的是"**Boot BOM 已管的坐标**别自设版本"。

但 Redisson 会传递 netty 与 jackson，**那两族恰好是 Boot BOM 管着的**。已实测核对最终版本一致，无 P-16 式的模块错配：

```
io.netty:*                    4.1.118.Final   （8 个模块全部一致）
com.fasterxml.jackson.core:*  2.18.2          （annotations / core / databind 一致）
```

**升级 Redisson 时必须重跑 `mvn dependency:tree` 复核这两族**，别让它把 Boot 管的版本拽偏。

---

## 八、测试拆分

| 文件 | 需要 Docker | 覆盖 |
|---|---|---|
| `RedissonLockAdapterTest` | ❌ | 能力隔离（非 `Pool`、非 `Tunable`）、配置 fail-fast、未启动行为、生命周期 `synchronized`、指标绑定、工厂解析、SPI 可发现性 |
| `RedissonLockAdapterRedisTest` | ✅ | 真加锁、互斥、复用、凭证幂等、**租约到期**、指标真实变化、重启 |

与 `HikariAdapterPostgresTest` 同构：`@Testcontainers(disabledWithoutDocker = true)`，无 Docker 自动跳过而不是构建失败。

**为什么必须拆**：互斥、租约到期、凭证幂等这三件事**不接真后端根本验不了**，而它们恰恰是 `DistributedLock` 最核心的语义。用 mock 验它们等于自己骗自己。

---

## 九、examples 为什么没默认开启锁

示例应用的定位是**开箱即跑**：H2 能内嵌，Redis 不能。默认开启会让没装 Redis 的人一启动就 fail-fast 报错。

因此在 `application.yml` 里以**注释形式**给出完整配置 + 一行 `docker run` 命令。想试的人两步就能跑起来，不想试的人不受影响。
