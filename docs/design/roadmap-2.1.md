# MetaPool 2.1 路线图（下个版本能做的）

> 承接 [`metapool-2.0-roadmap.md`](./metapool-2.0-roadmap.md)。2.0 已发布 Maven Central；`main` 现为 `2.1.0-SNAPSHOT`。
> 原则不变：**动手写实现前，接口签名先经设计确认**（尤其新增能力接口时）。
> 每项标注真实必要性——不为堆功能而堆功能。

---

## 2.1 主题

**把「治理控制面」从 2 种资源做深，扩到覆盖常见资源全谱；同时补上工程基建（CI）。**
2.0 用 HikariCP + Bucket4j 验证了抽象普适（一个是池、一个不是池）。2.1 的核心工作是**横向复制这个模式**，
外加一件 2.0 欠的账：持续集成。

---

## P0 — 该做（有明确价值，且是「作品集完整度」的短板）

### 1. GitHub Actions CI ✅ 已完成（2026-07-26）

- **构建 CI**（[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)）：`push(main)` / `pull_request` /
  手动触发，matrix `ubuntu-latest` + `windows-latest` × JDK 17，跑 `mvn -B clean verify`；
  失败上传 surefire 报告；同分支新 push 自动取消旧构建；纯文档改动不触发。
  README 徽章已从**硬编码的假徽章**（`badge/build-passing`，链接指向 `.`）换成真实 workflow badge。
- **实测收益不止「把本地结果搬上去」**：ubuntu runner 自带 Docker，因此
  `HikariAdapterPostgresTest`（Testcontainers PostgreSQL）在 CI 上**真实执行**了 5.476s ——
  该用例自写出来一直因本地无 Docker 而跳过，这是它第一次真正跑过。
  首次运行结果：ubuntu **38 通过 / 0 跳过**，windows 37 通过 / 1 跳过（无 Docker，符合 §4.3 不阻塞构建）。
- **发布 workflow**（[`.github/workflows/release.yml`](../../.github/workflows/release.yml)）：
  按本节原定的「避免误发」原则，**只允许 `workflow_dispatch` 手动触发**，不挂 `v*` 标签触发
  ——标签是发布**结果**的记录，不该当扳机。带 `dry_run` 输入且**默认为 true**：先演练（完整签名打包但不上传），
  验证 Secrets/GPG/javadoc 都正常后再关掉演练真发。父 pom 的 `autoPublish=false` 保留了
  「到 Central 门户人工点 Publish」这道闸门；workflow 也**不会**自动改版本提交、不会打 tag。
  所需 Secrets 见 [`../PUBLISHING.md`](../PUBLISHING.md)。
- **jacoco 覆盖率上报**：原计划的这一项**暂不做**。接 Codecov 之类要引入外部账号 + token，
  与 §1.5「优先简单稳定、拒绝为显高级而加」冲突；覆盖率数字对本项目的可信度提升也远不如
  「两个平台真实跑通 + PG 容器真实验证」。等真有需要（如多人协作看回归）再议。

### 2. 补齐能力接口：`DistributedLock` / `ManagedExecutor` ✅ 已完成（2026-07-26）

- **设计**：[`metapool-2.1-capabilities.md`](metapool-2.1-capabilities.md)（已确认），含 P-07 自查、
  每个决策的「为什么/不用会怎样」，以及 5 项拍板结果。
- **已落地**（`metapool-common`，签名 + Javadoc，零实现）：
  `capability/DistributedLock`、`capability/LockHandle`、`capability/ManagedExecutor`、
  `stats/LockStats`、`stats/ExecutorStats`。
- **三条定型决策**：
  1. 锁发放**持有凭证**而非 `unlock(key)` —— 后者无法判断调用方是否持有者，会导致「租约到期后
     解了别人的锁」这个经典事故；凭证 `extends AutoCloseable`，try-with-resources 即正确用法。
  2. `ManagedExecutor extends Executor` 但**不 extends `ExecutorService`** —— 后者带 `shutdown()`，
     会开出绕过控制面的第二个停机入口。
  3. 线程池饱和**透传** `RejectedExecutionException` 而非包装 —— 「别在接口层发明第二套饱和语义」。
     已在 RULES §3.2 记为明示例外并划清反向边界。
- **守护**：`CapabilityIsolationTest` 用反射断言两个新接口都不继承 `Pool`、`ManagedExecutor` 不继承
  `ExecutorService`、`DistributedLock` 没有 `unlock` 方法 —— 让违反变成红灯而非 review 的运气。
- **核心零改动**：`ResourceTypes.EXECUTOR` / `LOCK` 常量早已存在，`DefaultResourceManager` 只认
  `ManagedResource` + `instanceof Tunable`，无需认识新接口。

---

## P1 — 横向扩展适配器（2.1 的主体工作，逐个独立交付）

每个 adapter 与 2.0 的 hikari / bucket4j 对称：新模块 + `ResourceAdapterFactory` + SPI 注册 + 测试。核心零改动。

| 适配器 | 底层库 | 能力接口 | 备注 / 风险 |
|---|---|---|---|
| `adapter-lettuce`（redis） | Lettuce | `Pool<StatefulConnection>` | Lettuce 单连接多路复用，"池"语义需想清楚是否真需要池化；也可只做治理不做 Pool |
| ~~`adapter-commons-pool2`（object）~~ | Commons Pool2 | `Pool<T>` | ✅ **已完成 2026-08-12** |
| ~~`adapter-jdk-executor`（executor）~~ | JDK ThreadPoolExecutor | `ManagedExecutor` | ✅ **已完成 2026-08-12** |
| ~~`adapter-redisson`（lock）~~ | Redisson | `DistributedLock` | ✅ **已完成 2026-08-12**，取舍见 [`adapter-redisson.md`](adapter-redisson.md) |
| `adapter-netty`（memory） | Netty `PooledByteBufAllocator` | `Pool<ByteBuf>` 或自定义 | 堆外内存，度量口径与释放安全性需谨慎 |

**原建议顺序**：commons-pool2（最对称、最低风险）→ jdk-executor → redisson → lettuce → netty。

**实际执行时调整为先做 jdk-executor，理由记录在此**：commons-pool2 挂的是 `Pool<T>`，
已被 HikariCP 验证过两遍，做完只能证明「会照抄」；jdk-executor 挂的才是 P0 新定义、
零实现的 `ManagedExecutor`。**接口设计的风险远大于适配器实现的风险，应当先暴露。**
附带好处：JDK 自带，无外部依赖、无需 Docker，本机即可跑全绿。

### ✅ `adapter-jdk-executor` 完成记录（2026-08-12）

- **结论：`ManagedExecutor` 抽对了** —— `metapool-core` 与 `metapool-common` 零改动
  （RULES §2.8 的验收线）。四个能力全部落地，27 条测试。
- 实现中确立的三条边界，后续 adapter 可直接沿用：
  1. `submit()` 用 `execute` 而非 `CompletableFuture.supplyAsync` —— 饱和被拒时异常必须
     **同步抛出**，而不是变成异常完成的 future。「没受理」和「受理了但失败了」是两件事。
  2. `unwrap()` 维持返回裸 `ExecutorService`，**不包防护代理**。那个代理自己就是 P-07 复发
     （是个 `ExecutorService` 却在核心方法上抛异常）。真正的边界是**默认路径 vs 逃生舱**。
  3. 底层运行时改不了的参数（如队列容量）**不进 `Tunable` 白名单** —— 放进去等于承诺一件
     底层做不到的事，是 P-07 的思路错误换到调参这一面。
- 新增台账 P-19 / P-20 / P-21。

### ✅ `adapter-redisson` 完成记录（2026-08-12）

- **结论：`DistributedLock` 抽对了** —— 核心零改动，21 条测试（14 本地 + 7 需 Docker）。
  2.1 定义的两个新能力接口至此**都有了真实现**，没有一个被迫抛 `UnsupportedOperationException`。
- **核心取舍**：契约要求 `leaseTime` 必填 ⇒ Redisson **看门狗永远不启用** ⇒
  业务超时会被抢锁，且无 fencing token 兜底。选它是因为**失败可观测、可调、可预期**，
  而看门狗方案的失败（进程崩溃后锁悬挂）是静默的。代价已写进类注释 + 专门的测试 + 独立指标。
- **它不实现 `Tunable`** —— 没有运行时可调参数就不实现，可选能力接口正常工作的证据。
- 完整论证见 [`adapter-redisson.md`](adapter-redisson.md)（**面试素材**：为什么先做它、
  牺牲了什么、怎么让代价可见）。

### ✅ `adapter-commons-pool2` 完成记录（2026-08-12）

低风险的对称实现，但落地时暴露了两个**它和 Hikari 不同**的点，都值得记住：

1. **通用对象池没有「自带工厂」**。Hikari 给个 JDBC URL 就知道怎么造 `Connection`；
   Commons Pool2 不知道怎么造 `T`，必须拿到使用方的 `PooledObjectFactory`，而工厂对象
   写不进 YAML。若不给 `factory-class` 反射路径，`ResourceAdapterFactory.create()` 就只能
   一律抛异常，SPI 对称性（§2.8）当场破掉。**拍板：做 `factory-class`**，三项校验构造期
   fail-fast（类存在 / 确实实现该接口 / 有无参构造），错误消息里同时给出「改用编程式」的出路。
   反射只用于按名实例化一个类，不涉及改字段。

2. 🎯 **`borrow(Duration)` 在这里是真超时**，而 Hikari 只能以配置项为界、参数仅作提示。
   同一个接口方法在两个实现上语义强弱不同，**两边 javadoc 都写清了**。
   这不是 LSP 破坏——契约本就是「最多等这么久」的上界语义，Hikari 给出的上界更严；
   但调用方若指望「传 5s 就能等 5s」，在 Hikari 上会失望，所以必须写明。
   顺带兑现了本文件 P2 里「`Pool.borrow(Duration)` 真超时」那条待办（在本适配器上天然成立）。

另外**对照 P-19 得到一条反向结论**：Commons Pool2 的四个容量 setter **互不校验**
（`minIdle > maxIdle` 也照单全收），所以这里**不需要** jdk-executor 那种按方向排序的逻辑。
该结论有测试坐实（`tune_minIdleAboveMaxIdle_isAcceptedByPool2_noOrderingHazard`），不凭印象。

「真超时」那条测试已用探针验证是承重的：把 `borrowObject(timeout)` 换成 `borrowObject()` 后，
用例立刻以「实测只等了 211ms（配置值）而非传入的 1500ms」失败。

**每落一个 adapter 同步做**：examples 里纳管它、Grafana 看板加对应面板、README 模块表更新（两版）。

---

## P2 — 治理能力增强（有价值但非必需，按需取舍）

| 项 | 做什么 | 为什么 / 判断 |
|---|---|---|
| Metrics 生命周期 | `stop()` 时从 registry 注销自己的 meter | 2.0 现状：停机后 gauge 仍在，读到 0（已 null-safe）。清理更干净，但非 bug。低优先。 |
| Tracing | 接 Micrometer Observation，给 borrow / tryAcquire 埋 span | 有价值，但只有在有链路追踪后端时才有意义。可选。 |
| `Pool.borrow(Duration)` 真超时 | 让 Hikari 适配器真正按传入 timeout 限时（当前以配置 connectionTimeout 为界，已文档说明） | 修掉 2.0 记录的阻抗。中等价值。 |
| Health 细节 | Actuator `/health` 暴露每个资源的 per-resource 明细 | 运维友好。低成本。 |
| 注解式配置 | `@MetaPoolDataSource` 之类 | 2.0 判为「YAML 够用」。除非有明确诉求，**不做**（避免过度设计）。 |

---

## 明确暂不做（延续 2.0 的克制）

- ❌ 微服务 / MQ / 分布式注册中心 / 远程配置中心
- 🅿️ AI 跨池诊断：可作为最后的**可选 SPI 演示**（`metapool-spi-ai` 重建为真接入 SpringAI 的示例），但绝不作为核心；地基稳、adapter 谱系完整后再议。

---

## 版本收尾约定

- 每个 adapter 或 P0 项完成即可作为一次小版本（`2.1.0` 打包一批，或 `2.1.x` 滚动）。
- 发布沿用 `mvn -Prelease clean deploy`（见 [`../PUBLISHING.md`](../PUBLISHING.md)），JDK 17，Central token + GPG。
- 发布后推进到下一个 `-SNAPSHOT`。
