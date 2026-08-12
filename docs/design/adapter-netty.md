# adapter-netty 设计决策：实现 `Pool`，但 `release` 的语义更强

> 2.4 的适配器，也是适配器谱系的收官。完成于 2026-08-13。
> 与它成对照的是 [`adapter-lettuce.md`](adapter-lettuce.md)——那个**不**实现 `Pool`，这个实现。
> **两份文档合起来才是完整的判据。**

---

## 一、和 lettuce 那次相反的结论，凭什么？

上一个适配器（Lettuce）我拒绝实现 `Pool`，理由是「单连接多路复用**没有借还这回事**」。
现在 Netty 的 `ByteBuf` 也不是普通池化对象，为什么这次反而实现？

**判据是一句话：**

> **「语义更强」和「语义不存在」是两回事。**
> 前者可以映射并在文档里注明，后者只能靠撒谎才能映射。

- Lettuce：`borrow()` 该返回什么？同一个共享连接。`release()` 做什么？什么也不做。
  —— 这个动作**根本不存在**，硬做出来就是假的。
- Netty：`borrow()` 真的分配一块内存，`release()` 真的把它交还回去。
  动作**确实存在**，只是 release 的语义比「还给池」更强——它是**引用计数减一**。

映射一个语义更强的动作，代价是「必须写清差别」；映射一个不存在的动作，代价是「契约在撒谎」。
前者可付，后者不可付。

---

## 二、差别到底在哪

| | HikariCP / Commons Pool2 | Netty `ByteBuf` |
|---|---|---|
| `release()` 的含义 | 还给池 | **引用计数减一**，到 0 才真回池 |
| 忘了 release | 池耗尽 → 阻塞报错，**能发现** | **堆外内存泄漏**，GC 不管，OOM 之前无声无息 |
| 能被多方持有吗 | 不能 | 能（`retain()`） |

第二行是这个适配器存在的真正理由，见第四节。

### 落到代码上

`release()` 的实现只做三件事：`refCnt() <= 0` 就静默忽略并记 WARN（契约要求，否则 Netty 的
`IllegalReferenceCountException` 会在使用方的 `finally` 里掩盖业务原始异常）、否则减一、
计数。**不做任何"帮你还干净"的聪明事。**

对应测试 `release_decrementsRefCount_itDoesNotSimplyReturnToPool`：
`retain()` 之后要 release **两次**才归零。写成测试，是为了让这个差别不被当成 bug 改掉。

---

## 三、`borrow(Duration)` 的第三种语义

本项目到此，同一个 `Pool.borrow(Duration)` 有了**三种**语义强度：

| 适配器 | 语义 |
|---|---|
| `CommonsPool2Adapter` | **真超时**，按传入值限时（Pool2 原生支持） |
| `HikariAdapter` | 以配置的 `connectionTimeout` 为界，参数**仅作提示** |
| **`NettyByteBufAdapter`** | **无等待语义，参数被忽略** |

第三种的理由：内存分配**不排队**——要么立刻成功，要么直接抛 `OutOfMemoryError`，
不存在「等一会儿就有了」的情形，因此没有可以限时的等待。

**三者都不违反契约**（契约是「最多等这么久」的上界，上界更严不算违约），
但调用方需要知道差别，所以**三边 javadoc 互相点名写明**。

> 这件事本身值得记住：**一个接口有多个实现时，"都符合契约"不等于"行为一样"。**
> 契约划的是底线，不是行为的全部。差异该写在文档里，而不是留给使用方去踩。

---

## 四、🎯 治理面的真价值：让堆外泄漏在当天可见

这才是这个适配器值得做的理由，而不是「补齐谱系」。

堆外内存泄漏平时**完全不可见**——它不占堆，GC 日志里看不到，`jmap` 也不直观，
**不 OOM 就没人知道**。等到 OOM 时，现场已经离事故原因很远了。

导出的指标：

| 指标 | 价值 |
|---|---|
| `metapool.memory.allocated.total` | 累计借出 |
| `metapool.memory.released.total` | 累计释放 |
| `metapool.memory.outstanding` | 当前未释放数 |
| `metapool.memory.leaked.total` | 停机时仍未释放的数量 |
| `metapool.memory.used.direct.bytes` / `.heap.bytes` | 来自 Netty 自身的用量统计 |

**把 allocated 与 released 两条曲线并排画出来，一分叉就说明有人忘了 release。**
泄漏在发生的当天就能看见，而不是三周后 OOM 时。

> 这与 lettuce 适配器导出「断连次数」是同一个主题：
> **底层库为了好用而隐藏的东西，治理面负责让它重新可见。**
> Netty 隐藏的是「你忘了释放」，Lettuce 隐藏的是「网络在抖」。

---

## 五、两个刻意的「不做」

### 停机时不替调用方 release

若停机时仍有未释放的 buf：记 WARN、计入 `leaked.total`，**但绝不替它 release**。

那块内存可能正被别处使用（`retain()` 过），强行释放会造成 **use-after-free**——
**比泄漏更危险**。泄漏只是浪费内存，use-after-free 是数据损坏甚至崩溃。

有测试坐实：`stop_countsLeaksButNeverReleasesOnYourBehalf` 断言停机后 `refCnt` 仍为 1。

### 健康只有 UP / DOWN，没有 DEGRADED

内存分配器**不存在「饱和但仍在工作」这个中间态**——它要么分配成功，要么直接 OOM。

别的适配器有 DEGRADED（线程池饱和、连接池借满有人等、Redis 正在重连），但那是因为它们
**真的有**那个状态。为了「看起来一致」而硬造三态，就是在治理面上制造假象。

**泄漏该看指标，不该被伪装成健康状态。** 有测试：借 50 个不还，健康仍是 UP。

---

## 六、统计口径必须写清，否则会被误读

`PoolStats` 有五个字段，这里只有两个是实数：

- `active` —— 本适配器借出、尚未经本适配器 release 的数量。**是近似值**：
  调用方 `retain()` 后由别处 release 的，我们观测不到（坑 P-12 的同类问题：观测不到的事不硬记）。
- `idle` —— **恒为 0**。Netty 的池按 arena/chunk 组织，没有「可数的空闲对象」这个概念，
  硬编一个数字只会是假的。空闲内存量请看 `used.*.bytes` 指标。
- `pending` —— **恒为 0**。分配不排队。

两个恒为 0 的字段有专门的测试钉住（`poolStats_idleAndPendingAreAlwaysZero_byDesign`），
**防止后来者觉得"这里是不是漏实现了"而去补一个编造的数字。**

---

## 七、依赖：netty 版本一个字都不许自设

`netty-buffer` 的版本**完全交给** `spring-boot-dependencies` 里的 `netty-bom`：

- 自设版本 → redisson / lettuce 也在传递 netty，三者会错配（P-16 的原型）
- 在 `dependencyManagement` 里补一条**不带 version** 的条目 → 那不是「沿用 BOM」，
  而是把 BOM 管的版本覆盖成空，构建直接报 `'dependencies.dependency.version' is missing`
  （2026-08-13 实际踩过）

正确做法：根 pom 的 `dependencyManagement` 里**什么都不写**，子模块直接声明
`io.netty:netty-buffer` 不带 version。实测三者同为 `4.1.118.Final`。

---

## 八、测试

25 条，**不需要 Docker**（全在内存里跑）。重点不在「能不能借到 buf」，而在
**引用计数语义与普通池的差别有没有被如实对待**：

- `release_decrementsRefCount_itDoesNotSimplyReturnToPool` —— retain 后要 release 两次
- `stop_countsLeaksButNeverReleasesOnYourBehalf` —— 停机不替你释放
- `health_hasNoDegradedState_becauseAllocationEitherSucceedsOrThrows` —— 借 50 不还仍 UP
- `borrowWithTimeout_ignoresTheTimeout_becauseAllocationNeverQueues` —— 第三种语义
- `poolStats_idleAndPendingAreAlwaysZero_byDesign` —— 两个 0 是刻意的
