# MetaPool — A Resource Governance Control Plane for Java

[English](README.en.md) · [简体中文](README.md)

[![Maven Central](https://img.shields.io/maven-central/v/io.github.roseri66/metapool-spring-starter?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.roseri66/metapool-spring-starter)
[![JDK](https://img.shields.io/badge/JDK-17-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-brightgreen)](https://spring.io/projects/spring-boot)
[![CI](https://github.com/roseri66/MetaPool/actions/workflows/ci.yml/badge.svg)](https://github.com/roseri66/MetaPool/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-green)](LICENSE)

> **MetaPool does not reinvent connection pools. It brings HikariCP, Bucket4j and other
> heterogeneous resource managers under a single facade for lifecycle, observability,
> runtime tuning and graceful shutdown.**
>
> A typical application runs a connection pool, a rate limiter, a thread pool and a distributed
> lock side by side — each with its own API, its own configuration style and its own way of being
> monitored. MetaPool is a **Resource Governance Control Plane** layered on top of them:
> it unifies **governance**, not **usage**. If HikariCP is the right pool, keep using HikariCP —
> MetaPool only takes over managing it.

> ⚠️ **About this repository's history**: version 1.0 tried to build **seven resource pools from
> scratch**. That was the wrong road: every hand-written pool lost to the mature, specialised
> library it competed with, and forcing all of them behind one `acquire/release` interface broke
> the Liskov Substitution Principle. 2.0 pivoted to *governing mature libraries* instead.
> The full architecture review and the reasoning behind the rewrite are in
> [`docs/design/metapool-2.0.md`](docs/design/metapool-2.0.md) (Chinese).

---

## The problem

Resource management in a typical Java application is fragmented:

| Resource | Typical choice | Configured via | Monitored via |
|---|---|---|---|
| DB connection pool | HikariCP | `spring.datasource.hikari.*` | HikariCP's own MBean |
| Rate limiting | Bucket4j / Resilience4j | hard-coded | library-specific |
| Thread pool | JDK `ThreadPoolExecutor` | `new` / `@Bean` | roll your own |
| Distributed lock | Redisson | hard-coded `Config` | library-specific |

**N resources = N APIs + N configuration styles + N monitoring stories (or none).** When something
goes wrong you end up reading the Hikari MBean, a rate-limiter counter and a thread dump at the
same time — every layer is an island. MetaPool unifies that governance layer.

## The answer: unify governance, not usage

```
                    +------------------------------+
                    |       ResourceManager        |  Control plane: registry + orchestration
                    |   register / start / close   |  unified metrics / health / tune
                    +--------------+---------------+
                                   |  governs N heterogeneous resources uniformly
                    +--------------v---------------+
                    |       ManagedResource        |  Governance contract (every resource)
                    |   + ManagedLifecycle         |  start / stop(graceful) / health
                    |   + MetricsSource            |  bindTo(MeterRegistry), unified tags
                    +--------------+---------------+
                                   |  Optional capabilities: implemented only where they apply,
                                   |  isolated at compile time -- no UnsupportedOperationException
      +------------+-----------+-------------+----------------+
      v            v           v             v                v
   Tunable      Pool<T>    RateLimiter  DistributedLock  ManagedExecutor
  hot-tuning  borrow/release  tryAcquire  tryLock→handle   execute/submit
      |
      v
   +----------------------------------------------+
   |  ResourceAdapterFactory (SPI extension pt.)  |  one more adapter jar on the classpath
   |  HikariAdapter / Bucket4jAdapter / ...       |  = one more resource type, zero core changes
   +----------------------------------------------+
```

**The key decision**: functional APIs (`borrow/release`, `tryAcquire`, `lock/unlock`) are pulled out
of the shared contract into **optional capability interfaces**, and each resource implements only
the ones it actually has. A connection pool implements `Pool`; a rate limiter implements
`RateLimiter`; nobody has to fake a method that doesn't apply to it. `Bucket4jAdapter` is `final`
and does not implement `Pool`, so `instanceof Pool` on it **fails to compile** — the isolation is
enforced by the compiler, not by convention.

**All five capability interfaces now have real implementations, and not one of them was forced to
throw `UnsupportedOperationException`.** The converse holds too: the Redisson adapter **does not
implement `Tunable`** — a lock takes `waitTime`/`leaseTime` per call, so it has no runtime-tunable
parameters, so it simply does not claim the capability.

---

## Quick start

### Option 1 — Spring Boot (declarative YAML, recommended)

```xml
<!-- Optional: import the BOM to align versions, then drop <version> below -->
<dependency>
    <groupId>io.github.roseri66</groupId>
    <artifactId>metapool-spring-starter</artifactId>
    <version>2.3.0</version>
</dependency>
<!-- Add an adapter for each resource type you use (discovered via SPI) -->
<dependency>
    <groupId>io.github.roseri66</groupId>
    <artifactId>metapool-adapter-hikari</artifactId>
    <version>2.3.0</version>
</dependency>
<!-- Needed for the metapool_* metrics below to show up on /actuator/prometheus.
     This is a standard Spring Boot requirement, not something MetaPool adds.
     Skip it if you only use /actuator/metapool to inspect and tune. -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

```yaml
metapool:
  datasources:
    main:
      jdbc-url: jdbc:postgresql://localhost:5432/app
      username: app
      maximum-pool-size: 20        # passed straight through to HikariCP — no second naming scheme
      tunable: [maximum-pool-size, connection-timeout]   # whitelist of runtime-tunable keys
  rate-limiters:
    order-api:
      limit-for-period: 100        # passed straight through to Bucket4j
      refill-period: 1s
      tunable: [limit-for-period]
```

That's it: every declared resource is governed, metrics are registered with Micrometer,
`/actuator/metapool` can inspect and tune them, and the container shuts them down gracefully in
reverse order.

### Option 2 — Programmatic (no Spring)

```java
ResourceManager metaPool = MetaPool.create();
metaPool.register(HikariAdapter.from(hikariConfig).named("main").build());
metaPool.register(Bucket4jAdapter.builder()
        .named("order-api").limitForPeriod(100).refillPeriod(Duration.ofSeconds(1)).build());

metaPool.bindMetrics(meterRegistry);
metaPool.start();                        // started in registration order

// ... use the underlying native APIs as usual ...
metaPool.close();                        // graceful shutdown in reverse order (with drain)
```

---

## Unified observability + runtime tuning

**Metrics for all five resource kinds live in the same `MeterRegistry` under the same tag
scheme** — one Grafana dashboard covers every governed resource:

```
metapool.datasource.connections.active{metapool.resource="main",        metapool.type="datasource"}
metapool.ratelimiter.available.tokens{metapool.resource="order-api",    metapool.type="rate-limiter"}
metapool.executor.queue.size         {metapool.resource="order-worker", metapool.type="executor"}
metapool.lock.lease.expired.total    {metapool.resource="order-lock",   metapool.type="lock"}
metapool.object.pending              {metapool.resource="buffer-pool",  metapool.type="object"}
```

Underneath sit five unrelated libraries — HikariCP, Bucket4j, the JDK, Redisson and Commons Pool2 —
yet their metrics line up on a single dashboard. That is what "unified governance" buys you.

**Tune parameters at runtime, without a restart**, through the Actuator endpoint:

```bash
# List every governed resource
GET  /actuator/metapool

# Raise the pool ceiling from 20 to 40 — no restart
POST /actuator/metapool/main   {"key": "maximum-pool-size", "value": "40"}
```

Under the hood HikariCP goes through `HikariConfigMXBean` and Bucket4j through
`replaceConfiguration`; MetaPool unifies them behind a single `apply(patch)` facade that accepts
only whitelisted keys and logs an audit trail. An unsupported key in the `tunable` whitelist fails
**at startup**, not when an operator finally tries to use it.

> ⚠️ **Production safety**: `POST /actuator/metapool/{name}` is a **mutating** endpoint, and
> Actuator endpoints are unauthenticated by default. Protect the management port with Spring
> Security, or bind it to an internal-only interface (`management.server.port` +
> `management.server.address`). The bundled example app has no authentication so that it runs out
> of the box — **do not copy it into production as-is**.

### Run the monitoring stack locally (Prometheus + Grafana)

```bash
mvn -pl metapool-examples spring-boot:run                # example app, exposes /actuator/prometheus
docker compose -f deploy/docker-compose.dev.yml up -d    # Prometheus + Grafana + AlertManager
# Grafana at http://localhost:3000 (admin/admin) → "MetaPool — Resource Governance Overview"
```

The bundled dashboard
[`deploy/grafana/dashboards/metapool-overview.json`](deploy/grafana/dashboards/metapool-overview.json)
has five rows **on one screen**: connection pool (active/idle/pending), rate limiter (available
tokens, allow/reject rates), thread pool (threads, queue depth, completed/rejected rates),
distributed lock (locks held, acquire/timeout, lease expiry) and object pool (active/idle, waiters,
borrow/release rates). Metric names and alert rules live in [`deploy/`](deploy).

---

## Core abstractions

| Abstraction | Responsibility | Why it exists |
|---|---|---|
| `ManagedResource` | Governance identity (name/type) + the two below | Lets the control plane treat heterogeneous resources uniformly |
| `ManagedLifecycle` | `start` / `stop(graceful)` / `health` | The only capability *every* resource genuinely shares — the pivot point of the redesign |
| `MetricsSource` | `bindTo(MeterRegistry)` with unified tags | The technical basis for "one dashboard for everything" |
| `Tunable` *(optional)* | Whitelisted runtime tuning | Governance without restarts |
| `Pool<T>` / `RateLimiter` *(optional)* | Borrow/return; rate limiting | Capability isolation — this is what eliminates the LSP violation |
| `DistributedLock` / `LockHandle` *(optional, 2.1)* | Acquiring a lock hands back a **holder token**; there is deliberately no `unlock(key)` | `unlock(key)` cannot tell whether the caller still holds the lock, so it eventually unlocks *someone else's* |
| `ManagedExecutor` *(optional, 2.1)* | Submit tasks; extends `Executor` but **never** `ExecutorService` | `ExecutorService` carries `shutdown()`, which would open a second shutdown path around the control plane |
| `ResourceManager` | Registry + orchestration + aggregated health | Governance is cross-cutting and needs a central orchestrator |
| `ResourceAdapterFactory` | SPI extension point | Supporting a new resource = writing one adapter |

The full design — including a "what breaks if we don't have it?" argument for each abstraction —
is in [`docs/design/metapool-2.0.md`](docs/design/metapool-2.0.md) (Chinese).

---

## Modules

| Module | Responsibility |
|---|---|
| `metapool-common` | Pure contract layer: governance abstractions, capability interfaces, control-plane interface, SPI, value objects (depends only on micrometer-core / slf4j) |
| `metapool-core` | Control plane: `DefaultResourceManager`, `ResourceAdapterLoader`, the `MetaPool` entry point |
| `metapool-adapter-hikari` | Brings HikariCP under governance (`datasource`) |
| `metapool-adapter-bucket4j` | Brings Bucket4j under governance (`rate-limiter` — a non-pool resource) |
| `metapool-adapter-jdk-executor` | Brings the JDK `ThreadPoolExecutor` under governance (`executor` — a non-pool resource) |
| `metapool-adapter-redisson` | Brings Redisson distributed locks under governance (`lock` — a non-pool resource; **does not implement `Tunable`**) |
| `metapool-adapter-commons-pool2` | Brings Commons Pool2 generic object pools under governance (`object` — an actual pool) |
| `metapool-adapter-lettuce` | Brings Lettuce Redis connections under governance (`redis` — **deliberately not a `Pool`**: a multiplexed connection has no borrow/return semantics) |
| `metapool-spring-starter` | Spring Boot auto-configuration + Actuator health/tune endpoints |

## Supporting a new resource type

Implement `ResourceAdapterFactory` and register it through `META-INF/services`. One more jar on the
classpath means one more supported resource type, with **zero changes to the core**.

That claim is verifiable rather than aspirational: adding `metapool-adapter-jdk-executor` touched
**neither `metapool-core` nor `metapool-common`** (see `git show 8685ee3 --stat`).

Third-party types are declarable from YAML too (since 2.1):

```yaml
metapool:
  resources:
    my-custom-type:        # your adapter's type(), discovered via SPI
      whatever:
        some-native-key: 42
```

The per-type sections (`datasources`, `rate-limiters`, …) are all still supported; the two forms mix freely.

Capability interfaces are genuinely optional, and that is verifiable too: `metapool-adapter-redisson`
**does not implement `Tunable`** — a Redisson lock takes `waitTime` / `leaseTime` per call, so it has
no runtime-tunable parameters, so it simply does not claim the capability. Implement what you have;
never pad the surface to look complete. That is the exact inverse of 1.0, which defined one large
interface, forced every resource to implement it, and threw `UnsupportedOperationException` where
the semantics did not fit.

Planned adapters: Netty (memory).

---

## Governance overhead

MetaPool is not competing with HikariCP or Bucket4j on speed — it *uses* them. The number worth
measuring is what the governance layer costs on top of the raw library:

| Operation | Raw | Governed | Overhead |
|---|---:|---:|---|
| Rate limiter `tryAcquire` | 22.27 ns | 22.66 ns | **+0.4 ns (+1.75%)** |
| Connection borrow/release | 86.28 ns | 106.17 ns | **+19.9 ns (+23%)** |

The +23% figure is reported honestly rather than hidden: the overhead is a **~20 ns constant**
(two `AtomicLong` counters plus method forwarding), and it only *looks* large because an in-memory
H2 `getConnection()` baseline is a mere 86 ns. Against a real PostgreSQL, where acquiring a
connection costs microseconds to milliseconds, the same constant is **< 0.1%**. Full methodology
and results: [`docs/benchmarks.md`](docs/benchmarks.md) (Chinese).

---

## Build

```bash
mvn clean test      # JDK 17+, compiles and tests every module
```

CI runs the same build on **ubuntu-latest and windows-latest** with JDK 17. On Linux the
Testcontainers PostgreSQL integration test executes against a real database; where Docker is
unavailable it skips itself rather than failing the build.

## Status and roadmap

| Milestone | Scope | Status |
|---|---|:--:|
| M0 | Clear the decks (drop the hand-written pools, AI and Agent debt) | ✅ |
| M1 | Core contracts (governance + capability isolation) | ✅ |
| M2 | HikariCP adapter | ✅ |
| M3 | Bucket4j adapter + control plane + starter | ✅ |
| M4 | Docs / examples / JMH benchmark / Testcontainers | ✅ |
| Release | BOM + `io.github.roseri66` groupId + Central `release` profile | ✅ `2.3.0` on Maven Central, 10 artifacts |
| CI | GitHub Actions: ubuntu + windows × JDK 17, plus a manual release workflow | ✅ |
| 2.1 P0 | `DistributedLock` / `ManagedExecutor` capability interfaces | ✅ |
| 2.1–2.3 | Adapter lineage: `executor`, `lock`, `object`, `redis` landed; memory to go — see the [2.2 roadmap](docs/design/roadmap-2.2.md) | 🚧 |

## What this project is, and is not

- ✅ **Is**: an in-process governance facade that unifies lifecycle, observability and runtime
  tuning across heterogeneous resources.
- ❌ **Is not**: yet another connection pool implementation — it wraps mature ones instead of
  competing with them.
- ❌ **Is not**: a distributed system. The control plane is a `Map` plus orchestration logic;
  there is no message queue, service registry or microservice anywhere in it.

---

## License

[Apache License 2.0](LICENSE) · 100% OSI-licensed dependencies, nothing paid.
