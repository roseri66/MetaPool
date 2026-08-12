# adapter-lettuce 设计决策：一个**不实现 `Pool`** 的 redis 适配器

> 2.3 的适配器。完成于 2026-08-13。
> **本文件的主体不是"怎么实现"，而是"为什么它不该实现 `Pool`"** —— 那才是这个适配器的价值所在。

---

## 一、问题：一个看起来理所当然的答案

MetaPool 到这时已经有两个 `Pool<T>` 实现（HikariCP、Commons Pool2）。现在要加 Redis 适配器，
最自然的想法是：

> 「连接嘛，当然是池化。实现 `Pool<StatefulRedisConnection>`，和另外两个对称。」

**这个想法是错的**，而且错得很隐蔽 —— 因为它听起来非常合理。

---

## 二、Lettuce 是单连接多路复用

一个 `StatefulRedisConnection`：

- **天然线程安全**，可以被应用里所有线程共享
- 命令在同一条 TCP 连接上**流水线化**（pipelining），并发不靠多连接靠多路复用
- Lettuce 官方文档的建议就是**共享一个连接**，而不是池化

所以它**没有「借出 / 归还」这回事**。你不会从里面"借一个连接"，用完"还回去" ——
所有人本来就在用同一个。

> 强行套上 `Pool` 语义，等于**凭空发明一个不存在的生命周期**。
> `borrow()` 返回什么？同一个共享连接。`release()` 做什么？什么也不做。
> 一个 borrow 恒返回同一对象、release 恒为空操作的"池"，**是在说谎**。

---

## 三、为什么这个判断值钱：它和 P-07 是同一个错误

台账 P-07 记的是 1.0 的致命错误：用一个 `ResourceLifecycle<T>{acquire();release();}` 硬套所有资源，
结果线程池的 `acquire()` 只能 `throw new UnsupportedOperationException()`。

那次的错误很明显 —— 因为线程池和"池"的语义差距太大，抛异常抛得很难看。

**这次的错误会很不明显**：

| | 1.0 的错 | 这次可能犯的错 |
|---|---|---|
| 表现 | `acquire()` 抛 `UnsupportedOperationException` | `borrow()` 正常返回，`release()` 正常返回 |
| 会被发现吗 | 会，一跑就炸 | **不会**，测试全绿、功能正常 |
| 危害 | 立刻暴露 | 契约在撒谎：调用方以为拿到的是独占资源，实际是共享的 |

**这才是它更危险的地方。** 一个抛异常的假实现会被立刻发现；一个"能跑但语义是假的"实现会一直活下去，
直到某个人真的按 `Pool` 的语义去用它 —— 比如在 `borrow()` 出来的连接上执行 `MULTI/EXEC` 事务，
然后发现另一个线程的命令混进了自己的事务块。

### 一句话判据

> **不能因为「别的 adapter 都实现了 `Pool`」就让它也实现。那是用一致性绑架语义。**

可选能力接口的全部意义就在这里：**没有的能力就不声明。**
这条在 `Bucket4jAdapter`（限流不是池）和 `RedissonLockAdapter`（锁不是池、且无可调参数所以连
`Tunable` 都不实现）上已经用过两次，这是第三次 —— 也是最不明显的一次。

---

## 四、那业务怎么用？

调 `LettuceAdapter.connection()` 拿原生连接：

```java
LettuceAdapter cache = (LettuceAdapter) metaPool.get("cache");
cache.connection().sync().set("k", "v");
```

**必然会被追问的一句：这不就耦合到具体 adapter 了吗？**

答：**那个耦合本来就存在。** 业务要发 Redis 命令，必然要用 Lettuce 的 `RedisCommands` API
（`sync()` / `async()` / `reactive()`）。套一层 `Pool<StatefulRedisConnection>` **不会解耦任何东西** ——
`borrow()` 返回的还是 Lettuce 的类型，业务照样要 import Lettuce。

> 一层不解耦任何东西、还把语义说错的抽象，是**净负债**。

### 什么时候才真的需要池化 Redis 连接

确实存在需要**独占连接**的场景：

- 阻塞命令（`BLPOP` / `BRPOP` / `XREAD BLOCK`）—— 会占住连接
- 事务（`MULTI` / `EXEC`）—— 需要连接独占，否则别人的命令会混进事务块
- 需要独占的 pipeline 批处理

这些场景 Lettuce 自己提供了 `ConnectionPoolSupport`（内部就是 Commons Pool2）。

**但那是使用方按场景做的选择，不该由治理层替所有人预先决定。**
默认路径应该是"共享一个连接"，因为那覆盖 95% 的用法且性能更好。

---

## 五、它**实现** `Tunable` —— 与 Redisson 适配器恰好相反

`command-timeout` 是运行时真可写的（`StatefulConnection.setTimeout(Duration)`，已用 `javap` 核实），
线上 Redis 变慢时能不重启地放宽。所以本适配器实现 `Tunable`。

而 `metapool-adapter-redisson` **没有**任何运行时可调参数（`waitTime` / `leaseTime` 是每次调用传入的），
所以它不实现 `Tunable`。

> **同一条判据（有没有真参数），两个相反结论。**
> 这正是可选能力接口在正常工作的样子 —— 判据是稳定的，结论随资源而变。

不进白名单的两个：`uri`（换地址等于换资源，不是调参）、`auto-reconnect`
（`ClientOptions` 构造期设置，运行时改不了 —— **底层做不到的事不进白名单**，与 jdk-executor 的
`queue-capacity` 同理）。

---

## 六、健康三态：`DEGRADED` 表示"正在自动重连"

```
未启动 / 客户端已关                    → DOWN
连接不 open，且 auto-reconnect 开着     → DEGRADED   ← 不是 DOWN
连接不 open，且 auto-reconnect 关着     → DOWN
PING 通                                → UP
PING 抛异常                            → DOWN
```

**关键是把「正在自愈」和「已经坏了」分开。** Lettuce 默认自动重连，短暂断开时 `isOpen()` 为 false，
但客户端正在恢复 —— 这时报 DOWN 会造成误报警，而误报警多了就没人看告警了。

判据与线程池「饱和不等于故障」、连接池「借满不等于故障」同源：
**资源处于非理想状态 ≠ 资源坏了。**

---

## 七、指标：只报**我们真观测得到**的东西

业务直接用原生 API 发命令，**适配器观测不到命令量**。所以这里不去猜、不去估
（坑 P-12 的教训：观测不到就别记计数器）。

能观测到的是连接层事件，经 `RedisConnectionStateListener`：

| 指标 | 价值 |
|---|---|
| `metapool.redis.connection.open` | 1/0 |
| `metapool.redis.connects.total` | 含**重连**次数，正常应稳定不涨 |
| `metapool.redis.disconnects.total` | 🎯 **这条持续上涨 = 网络在抖** |
| `metapool.redis.exceptions.total` | 连接层异常 |

**断连计数是这个适配器最有价值的产出。** Lettuce 的自动重连把网络抖动**掩盖掉了** ——
业务侧只觉得"偶尔慢一下"，没有任何人知道底下在反复重连。把它变成一条曲线，
就把一个原本不可见的问题变成了可见的。

> 这其实是"治理"这件事的一个缩影：**底层库为了好用而隐藏的东西，治理面要负责让它重新可见。**

---

## 八、一处顺手挡住的配置陷阱

`auto-reconnect` 如果直接用 `Boolean.parseBoolean` 解析，`"yes"` 会**静默变成 `false`** ——
使用方以为开着自动重连，实际关着，而且**只有在网络真抖动时才会发现**。

所以只接受 `true` / `false`，其余一律 fail-fast 报错。有测试守着。

---

## 九、测试

| 文件 | 需要 Docker | 覆盖 |
|---|---|---|
| `LettuceAdapterTest`（16 条） | ❌ | **不实现 `Pool` 的断言**、实现 `Tunable` 的断言、配置 fail-fast、未启动行为、生命周期 `synchronized`、指标绑定、工厂解析、SPI 可发现性 |
| `LettuceAdapterRedisTest`（7 条） | ✅ | 真 PING、**共享连接是同一对象**、共享连接并发安全、热调超时并跨重启、重启建新连接、连接事件计数 |

其中两条是本适配器的主张的直接证据：

- `redisConnection_isNotAPool_becauseMultiplexingHasNoBorrowSemantics` —— 写成测试，
  是为了让这个决定**不会被后来者"顺手补全"**
- `connection_isSharedNotPooled` —— 断言两次 `connection()` 返回 `assertSame`，
  **这就是"没有借还语义"的实证**

---

## 十、✅ 顺带还掉的一笔技术债：`parseDuration` 已抽取

写这个适配器时，`parseDuration` 成了**第四处**完全相同的实现
（bucket4j / jdk-executor / commons-pool2 / lettuce）。当初立的约定是「第三个 adapter
再需要就抽取」—— 已经超了一处。

已抽到 `metapool-common` 的 `com.metapool.common.spi.ConfigValues.duration(key, raw)`：

- **参数化 `key`** 是为了保留各调用点原有的错误消息（`invalid keep-alive '...'` /
  `invalid max-wait '...'`）——抽取不该让报错变得更含糊。
- **刻意不校验正负**：负值在 Commons Pool2 里有确定含义（负的 `max-wait` = 无限等待）。
  **解析器只管「能不能读懂」，不管「合不合理」** —— 是否允许负数属于各适配器自己的语义。
- 四份重复的测试也一并删掉，共用逻辑只在 `ConfigValuesTest` 一处保证正确性。

> 为什么之前一直没抽：它是**已发布公开契约层**加 API，属于要单独拍板的事，
> 不该在适配器提交里顺手做。等到攒够四处、边际收益明显盖过成本时再做，是刻意的。
