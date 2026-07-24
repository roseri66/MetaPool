# MetaPool 2.0 — Implementation Roadmap（Phase 3）

> 配套设计文档：[metapool-2.0.md](./metapool-2.0.md)
> 开发方式：按真实开源项目流程分 Milestone 推进，**每个 Milestone 完成并验收后再进下一个**。
> 纪律：任何实现代码开工前，接口签名先经设计确认（工作原则 5）。

**进度：M0 ✅ · M1 ✅ · M2 ✅ · M3 ✅ · M4 ✅（README/examples/JMH benchmark/Testcontainers PG 均完成）**
（M3 实施中新增 `metapool-core` 模块承载控制面实现，使 `metapool-common` 保持纯契约——此决策已并入下方 M3。）

---

## Milestone 0 — 架构调整（清场）

**目标**：砍掉死模块，落盘设计，让仓库回到「干净可演进」的状态。

| 项 | 内容 |
|---|---|
| 改哪些模块 | 删除 `metapool-agent-core` / `metapool-spi-ai` / `metapool-spi-alert`；父 `pom.xml` 移除对应 `<module>` 与 byte-buddy 依赖管理；落盘 `docs/design/*` |
| 为什么 | 死模块是过度设计负债；包装成熟库后字节码 Agent 无必要；先清场再重建，避免在旧地基上刷漆 |
| 风险 | 低。删除在 git 可恢复；已验证无存活模块依赖这 3 个 |
| 验收标准 | ① 3 模块从磁盘与父 pom 移除；② `mvn -q validate` 通过；③ 设计文档 + 路线图落盘；④ 在 `redesign/metapool-2.0` 分支，`main` 不受影响 |

**状态**：✅ 本次执行

---

## Milestone 1 — 核心 API 重构（只签名，不实现）

**目标**：在 `metapool-common` 定义 2.0 的 6 个核心抽象 + 能力接口，纯接口 + Javadoc，零实现。

| 项 | 内容 |
|---|---|
| 改哪些模块 | `metapool-common`（清空 1.0 的 `AbstractResourcePool` 等实现，替换为契约）|
| 交付物 | `ManagedResource` / `ManagedLifecycle` / `MetricsSource` / `Tunable` / `ResourceManager` / `ResourceAdapter` + 能力接口 `Pool<T>` / `RateLimiter` / `DistributedLock` / `ManagedExecutor` + 值对象 `HealthStatus` / `TuneResult` / `ResourceType` |
| 为什么 | 6 抽象是全项目地基；先冻结契约，adapter 才能并行开发 |
| 风险 | 中。抽象切错会波及全部 adapter → **本 Milestone 的接口签名必须先经你确认再落地** |
| 验收标准 | ① 全部为接口/记录类，无实现逻辑；② 每个方法有 Javadoc 说明语义 + 线程安全约束；③ `common` 零第三方实现依赖（仅 micrometer-core、slf4j-api）；④ 编译通过 |

---

## Milestone 2 — 第一个生产级 Adapter（HikariCP）

**目标**：`metapool-adapter-hikari` 打通「注册 → 启动 → 取连接 → 指标 → 调参 → 优雅停机」全链路。

| 项 | 内容 |
|---|---|
| 改哪些模块 | 新增 `metapool-adapter-hikari`（由 `metapool-pool-db` 改造）|
| 交付物 | `HikariAdapter implements ManagedResource, Pool<Connection>`；`bindTo` 复用 HikariCP Micrometer；`Tunable` 经 `HikariConfigMXBean` 热调 `maximum-pool-size` |
| 为什么 | 验证「真·池」在新抽象下工作；产出第一个可 demo 的完整故事 |
| 风险 | 中。优雅 drain 语义（等 active 连接归还 vs 超时强关）需明确边界 |
| 验收标准 | ① Testcontainers 起 PG，acquire/release 正常；② `stop(5s)` 能 drain；③ 指标出现在 MeterRegistry 且带统一 tag；④ 运行时 `tune` 改 max-pool-size 生效；⑤ 并发 100×1000 无泄露 |

---

## Milestone 3 — 第二个 Adapter（Bucket4j）+ 控制面 + Starter

**目标**：证明「非池资源」同样被治理；打通 Spring 一站式接入。

| 项 | 内容 |
|---|---|
| 改哪些模块 | 新增 `metapool-adapter-bucket4j`（由 `metapool-pool-rate-limit` 改造）；改造 `metapool-spring-starter` |
| 交付物 | `Bucket4jAdapter implements ManagedResource, RateLimiter`（**不实现 Pool**）；`ResourceManager` 实现（注册表 + 启停编排 + 全局 metrics/health/tune）；starter 自动装配 + `application.yml` 绑定 + Actuator `tune`/`health` 端点 |
| 为什么 | 一池一非池并存 → 证明抽象普适；starter 是头牌的「5 分钟接入」DX |
| 风险 | 中。YAML → 底层库参数直通的绑定映射要清晰；tune 端点需鉴权/审计 |
| 验收标准 | ① 一个 Spring app 同时纳管 datasource + rate-limiter；② 一个 MeterRegistry 里两类资源指标共存；③ 全局优雅停机逆序执行；④ Actuator 端点可查/可调 |

---

## Milestone 4 — 测试 / Benchmark / 文档（作品集打磨）

**目标**：把项目打磨到「面试官愿意点开」的开源级成色。

| 项 | 内容 |
|---|---|
| 改哪些模块 | 全模块补测试；新增 `metapool-examples`/`metapool-benchmark`；重写 `README` + Grafana 看板 |
| 交付物 | ① 并发/故障注入测试；② JMH 测**治理开销**（bindTo/tune 损耗 ≤ 5%，对齐 BRD 硬指标）；③ 一个 Grafana 看板同时展示 DB+限流 指标（头牌截图）；④ README「5 分钟接入」+ 架构图 + 设计文档链接；⑤ BOM + `io.github.*` groupId 发布配置 |
| 为什么 | 作品集头牌的第一印象来自 README + 可运行 demo + 一张漂亮看板 |
| 风险 | 低。属打磨，不改架构 |
| 验收标准 | ① CI 绿；② 覆盖率达标；③ benchmark 结果留档；④ README 让陌生人 5 分钟跑通 demo；⑤ 看板截图进 README |

---

## 里程碑依赖图

```
M0 清场 ──▶ M1 核心契约(签名先确认) ──▶ M2 HikariCP adapter ──▶ M3 Bucket4j + 控制面 + starter ──▶ M4 测试/benchmark/文档
                                                                                                          │
                          横向扩展（M3 之后按需）：redis(Lettuce) / object(Commons-Pool2) / thread(JDK) / lock(Redisson) / memory(Netty)
```

## 变更记录

| 日期 | 变更 |
|---|---|
| 2026-07 | 初版路线图，M0 执行 |
