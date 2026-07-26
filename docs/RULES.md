# MetaPool 开发规范（RULES）

> 本文件是 MetaPool 的**唯一规范入口**：协作方式、工程约定、构建/发布纪律，以及**踩坑台账**。
> 规则：**每踩一个坑，必须当场追加到第七节「踩坑台账」**，不允许只在会话里口头说完就算。
> 相关：需求与设计见 `docs/design/`，发布流程见 `docs/PUBLISHING.md`，跨会话上下文见 `../项目记忆.md`。
> 最后更新：2026-07-26

---

## 1. 协作规则（人 ↔ AI）

1. **代码前先确认设计**。接口签名、边界决策先出方案，用户拍板后再写实现。用户说「按推荐」即为授权。
2. **不迎合**。设计有问题直说，包括用户自己定的需求（1.0 就是 PRD 把方向定错）。
3. **诚实数字**。README / benchmark 只写实测数据；不利数据（如相对开销 +23%）照实写并解读，不虚报测试数量、不粉饰结论。
4. **按 Milestone 推进，逐件 commit**。每步验证绿了再进下一步；commit message 说清「为什么」，不只写「改了什么」。
5. **优先简单稳定**。明确不引入：微服务、MQ、DDD 分层、远程配置中心、AI 化包装。为「显得高级」而加的东西一律拒绝。
6. **每个设计回答两个问题**：为什么需要它？不用它会出什么问题？答不上来就不做。
7. 报告结果**带证据**（构建/测试输出），不说「应该没问题」。

## 2. 架构规则

1. **MetaPool 治理成熟库，不重造轮子**。统一的是**治理**（生命周期 / 可观测 / 动态调参 / 优雅停机），不是用法。
2. **能力接口是可选的，必须编译期隔离**。`ManagedResource` 是所有资源的共同契约；`Pool<T>` / `RateLimiter` / `Tunable` 谁有谁实现。
   - **禁止**为了「统一」让某类资源抛 `UnsupportedOperationException` —— 这就是 1.0 的 LSP 破坏，不许复发。
   - 反例基线：`Bucket4jAdapter` 是 `final` 且不实现 `Pool`，`instanceof Pool` 编译不过，且有测试坐实。
3. **参数直通底层库原生命名**（如 Hikari 的 `maximum-pool-size`），不发明第二套 DSL；治理侧只额外加 `tunable` 之类的治理字段。
4. `metapool-common` 是**纯契约层**：只依赖 micrometer-core / slf4j，不得引入任何第三方实现依赖。
5. 扩展走 **JDK ServiceLoader**（`ResourceAdapterFactory`，type→factory）。不自造 `@SPI`/`ExtensionLoader`。
6. `type()` 用 `String` 而非 enum，避免挡住第三方扩展。
7. `ResourceManager` 是**进程内对象**，不是分布式设施。不要往里塞集群/远程语义。
8. 新增 adapter 必须与 hikari/bucket4j **对称**：新模块 + factory + SPI 注册 + 测试，且**核心零改动**。核心需要改，说明抽象漏了，先回到设计。

## 3. 代码规则

1. **JDK 17+**，Spring Boot 3.x。包名 `com.metapool.{module}`，发布 groupId `io.github.roseri66`。
2. 异常统一继承 `MetaPoolException` 并携带 `ErrorCode`（格式 `POOL-NNN`）。
3. 配置**不可变 + 启动即校验（fail-fast）**。
4. 指标统一 tag：`metapool.resource` / `metapool.type`。新 adapter 必须遵守，否则「一个看板看全部」的头牌能力就破了。
5. 生命周期方法（`start`/`stop`）要 `synchronized`，`stop()` 必须**释放并置空**底层持有对象，保证 stop→start 能重启（见坑 P-01）。
6. 新代码跟随周边代码风格：注释密度、命名、惯用法保持一致，不单独引入新风格。

## 4. 测试规则

1. 每个新能力至少一条测试；**修一个 bug 必须补一条会失败的回归测试**。
2. 合入前 `mvn clean test` 必须全绿。当前基线：25 个测试（+1 个 Testcontainers PG 用例在无 Docker 时自动跳过）。
3. 依赖外部环境的集成测试必须**可跳过**，不得阻塞无 Docker 的构建。
4. 不为了凑数写空断言测试；测试数量对外报告时按实测。

## 5. 文档规则

1. 设计文档进 `docs/design/`，每个抽象都写「为什么需要 / 不用会怎样」。
2. README 是门面：治理叙事 + 5 分钟接入，数据必须与 `docs/benchmarks.md` 一致。
3. 版本收尾时同步更新：README、`项目记忆.md`、本文件的踩坑台账。
4. **禁止把凭据写进任何文档**（Central token、GPG 密码等只存 `~/.m2/settings.xml`）。

## 6. 构建与发布规则

1. 构建前确认 `JAVA_HOME` 指向 **JDK 17**（见坑 P-03）。
2. 改版本号用 `sed` 直接替换 `<version>`，**不要用 `mvn versions:set`**（见坑 P-04）。
3. 发布走父 pom 的 `release` profile：`mvn -Prelease clean deploy`，再到 central.sonatype.com → Deployments → Publish。详见 `docs/PUBLISHING.md`。
4. `central-publishing-maven-plugin` 锁 **0.11.0**（0.7.0 有 warnings 字段解析 bug）。
5. 发布后打 tag、建 GitHub Release，并把 `main` 推进到下一个 `-SNAPSHOT`。
6. commit 尾注沿用现有格式（`Co-Authored-By` + `Claude-Session`）。未经要求不 commit、不 push。

---

## 7. 踩坑台账（Pitfall Ledger）

**规则：每发现一个坑，立刻在此追加一条**，格式固定：

```
### P-NN 一句话标题
- **现象**：看到的错误/异常行为
- **原因**：真正的根因
- **修法**：怎么修好的（含防复发手段：测试/配置/检查项）
- **日期**：YYYY-MM-DD
```

编号连续递增，**不复用、不删除**（过时的坑标注「已失效」而不是删掉）。

---

### P-01 `HikariAdapter.stop()` 未置空 dataSource，stop 后 start 留下已关闭的池
- **现象**：资源 stop 之后再 start，拿到的仍是已关闭的连接池，取连接直接失败。
- **原因**：`stop()` 只调了底层 `close()`，没把字段置空，`start()` 判断「非空即已就绪」于是直接复用了死对象。
- **修法**：`stop()` 中 close 后置空字段，`start`/`stop` 加 `synchronized`；两个 adapter 都补了 restart 测试。
- **日期**：2026-07

### P-02 Actuator tune 端点缺 `-parameters`，任何使用方启动即失败
- **现象**：examples demo 启动时 `/actuator/metapool` 端点注册失败。
- **原因**：`@WriteOperation` 需要反射拿到参数名，编译未开 `-parameters`。
- **修法**：父 pom 全局配 `<parameters>true</parameters>`。新增编译配置时不要覆盖掉它。
- **日期**：2026-07

### P-03 JAVA_HOME 默认指向 JDK 8，报「无效的目标发行版: 17」
- **现象**：`mvn` 构建/deploy 直接失败。
- **原因**：机器上同时装了 `C:\Program Files\Java\jdk-1.8`（8）和 `C:\Program Files\ojdkbuild\java-17-...`（17），默认 `JAVA_HOME` 是前者。
- **修法**：构建前 `set JAVA_HOME=<JDK17 路径>`；deploy 前务必先确认。
- **日期**：2026-07

### P-04 `mvn versions:set` 在这台机器上会挂住
- **现象**：改版本号命令长时间无输出（插件解析极慢）。
- **原因**：本机 Maven 插件解析环境问题，非项目问题。
- **修法**：改用 `sed` 替换 `<version>...</version>`，快且可控。
- **日期**：2026-07

### P-05 Maven「被管理 scope 泄漏」：子 pom 直接声明依赖会覆盖传递来的 compile scope
- **现象**：编译期找不到类（common 的 micrometer、benchmark 的 h2 都踩过）。
- **原因**：dependencyManagement 里该依赖是 `test` scope，子 pom 一旦不带 scope 直接声明，就继承了 `test`，覆盖掉原本传递过来的 `compile`。
- **修法**：子 pom 声明时显式写明所需 scope，或不重复声明、直接吃传递依赖。
- **日期**：2026-07

### P-06 `DefaultResourceManager.health()` 聚合时 DOWN 未压过 DEGRADED
- **现象**：有资源已 DOWN，聚合结果却报 DEGRADED，掩盖真实故障。
- **原因**：聚合按遍历顺序取值，没有定义状态优先级。
- **修法**：明确优先级 DOWN > DEGRADED > UP，并补测试。新增健康状态时必须同步更新优先级。
- **日期**：2026-07

### P-07 1.0 用单一 `ResourceLifecycle<T>` 硬套所有资源，破坏里氏替换
- **现象**：线程池的 `acquire()` 直接 `throw UnsupportedOperationException`；令牌桶/锁被建模成「借出一个 Boolean」。
- **原因**：为了「统一抽象」把不同语义的资源塞进同一个接口——需求（PRD）方向就错了，不是实现问题。
- **修法**：2.0 拆成「治理契约（人人实现）+ 可选能力接口（谁有谁实现）」，编译期隔离。**这是本项目最重要的一条，任何新抽象都要先自查是否在重犯。**
- **日期**：2026-07（1.0 遗留，2.0 重构修复）
