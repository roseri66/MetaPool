# MetaPool 开发规范（RULES）

> 本文件是 MetaPool 的**唯一规范入口**：协作方式、工程约定、构建/发布纪律，以及**踩坑台账**。
> 规则：**每踩一个坑，必须当场追加到第七节「踩坑台账」**，不允许只在会话里口头说完就算。
> 相关：需求与设计见 `docs/design/`，发布流程见 `docs/PUBLISHING.md`，跨会话上下文见 `../项目记忆.md`。
> 最后更新：2026-08-12（新增 P-19 / P-20 / P-21，来自 2.1 第一个 P1 适配器 jdk-executor 及其接入面）

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
   - **例外（2026-07-26 确认）**：当底层库/JDK 的异常类型**本身就是生态契约的一部分**时，
     **透传，不包装**。典型即线程池饱和的 `java.util.concurrent.RejectedExecutionException`
     —— `CompletableFuture`、Spring `@Async` 等基础设施会按该类型做处理，包装即破坏互操作。
   - 判据一句话：**不要在接口层发明第二套已有既定含义的语义**。这与 §2.1「统一的是治理，
     不是用法」同源 —— 包装一个人人都认识的异常，等于发明第二套词汇。
   - 反向边界：MetaPool **自己**产生的错误（未启动、配置非法、调参被拒、资源不存在）
     仍必须是 `MetaPoolException` + `ErrorCode`。透传只适用于「底层库在其既定语义下抛出的异常」。
3. 配置**不可变 + 启动即校验（fail-fast）**。
4. 指标统一 tag：`metapool.resource` / `metapool.type`。新 adapter 必须遵守，否则「一个看板看全部」的头牌能力就破了。
5. 生命周期方法（`start`/`stop`）要 `synchronized`，`stop()` 必须**释放并置空**底层持有对象，保证 stop→start 能重启（见坑 P-01）。
6. 新代码跟随周边代码风格：注释密度、命名、惯用法保持一致，不单独引入新风格。

## 4. 测试规则

1. 每个新能力至少一条测试；**修一个 bug 必须补一条会失败的回归测试**。
2. 合入前 `mvn clean test` 必须全绿。当前基线：**70 个测试通过 + 1 跳过**
   （跳过的是 Testcontainers PG 用例，无 Docker 时自动跳过；2026-08-12 随 jdk-executor 适配器
   与其接入面从 41 提升，实测 `mvn -B clean verify` 汇总）。
   改动基线数字时必须是实测值（`Tests run` 汇总），不许估。
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
6. **commit message 不带任何 AI 尾注**（`Co-Authored-By`、`Claude-Session` 等）。
   2026-07-29 已用 `git filter-repo` 从全部 50 个 commit 里清除过一次，代价是 GitHub Actions
   运行历史随孤儿 commit 归零、badge 一度变灰（详见 `../项目记忆.md` 第十二节）。**别再写回去。**
   未经要求不 commit、不 push。
7. **类库模块的 `src/main/resources` 根目录只允许放带命名空间的文件**（如 `META-INF/...`）。
   任何会被框架按约定名自动拾取的配置（`logback-spring.xml`、`application.yml`、`banner.txt` 等）
   一律不许打进类库 jar —— 它会劫持使用方应用的配置（见坑 P-08）。
8. **构建告警不许长期无视**。`must be unique`、deprecation 等出现即当错误处理（见坑 P-10、P-16）。
9. Boot BOM 已管理的坐标（micrometer / jackson / slf4j …）不要自设版本属性，除非整族一起升（见坑 P-16）。

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
- **原因**：机器上同时装了 `C:\Program Files\Java\jdk-1.8`（8）和 `C:\Program Files\ojdkbuild\java-17-...`（17），而终端起来后 `JAVA_HOME` 反复是前者。
  - 2026-07-26 查清的注册表实况：**HKCU（用户级）原本为空**，**HKLM（机器级）存的是 Unix 风格路径 `/c/Program Files/ojdkbuild/...`——在 Windows 上无效**。已把 HKCU 设为正确的 `C:\Program Files\ojdkbuild\java-17-openjdk-17.0.3.0.6-1`（非 PATH 类变量 User 覆盖 Machine，故新终端取到 JDK 17）。**HKLM 那条无效值仍待清理（需管理员权限）。**
  - 排查提醒：`$env:JAVA_HOME` 是**进程**环境，子进程继承父进程环境块而非重读注册表，所以「开个子 shell 看看」验不出持久化配置。要查实际存的值就直接读注册表（`HKCU:\Environment`、`HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Environment`）。
- **修法**：构建前设 `JAVA_HOME` 指向 JDK 17；deploy 前务必先 `mvn -v` 确认显示 `Java version: 17.x`。
  - PowerShell：`$env:JAVA_HOME = "C:\Program Files\ojdkbuild\java-17-openjdk-17.0.3.0.6-1"`
  - Git Bash：`export JAVA_HOME='/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1'`
- **⚠️ 2026-07-26 复发**：AI 侧每条命令都显式带了 JAVA_HOME 所以一路绿，人在自己终端里跑 `mvn -Prelease clean deploy` 就炸。两个教训：
  ① `PUBLISHING.md` 原先只给了 bash 的 `export` 形式，而本机默认终端是 PowerShell —— **文档给命令必须匹配实际用的 shell**，已补两种形式；
  ② 「构建前记得设环境变量」是纯手工约定，且失败信息（「无效的目标发行版: 17」）完全不提 JDK 版本，靠人记不住。**根治办法是让构建自己检查并给出可读报错**。
- **✅ 已根治**：父 pom 配了 maven-enforcer-plugin 3.5.0 的 `requireJavaVersion [17,)`（`validate` 阶段），
  JDK 不对时直接给出可读报错并指回本条。报错文案**刻意写成纯 ASCII 英文**——它只在「跑错 JDK」时出现，
  而那时往往正是 JDK 8 + GBK 控制台，中文会变乱码。
- **日期**：2026-07（2026-07-26 复发并加强；2026-07-26 加 enforcer 根治）

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

### P-08 starter 打包 `logback-spring.xml`，劫持并静默关掉使用方的全部日志
- **现象**：使用方（examples 应用）启动后 banner 之后**一行日志都没有**；连 MetaPool 自己的 `[MetaPool] datasource 'main' started` 治理流水也不见。
- **原因**：**类库绝不能打包 `logback-spring.xml`**。Spring Boot 的 `LogbackLoggingSystem` 在 classpath 根发现它就当成「使用方应用自己的」日志配置，覆盖 Boot 默认值；而该文件的 appender 全包在 `<springProfile name="dev|prod">` 里，两个 profile 都不激活时 root logger 一个 appender 都没有。加 `-Dspring.profiles.active=dev` 会冒出 1986 行 DEBUG 且用的是该文件的 pattern —— 坐实它在掌控使用方日志。该文件本身还是 1.0 残留（`io.lettuce` logger 早已不是依赖）。
- **修法**：删除该文件（示例日志配置属于应用，不属于类库）。**防复发**：`StarterPackagingTest` 断言 classpath 根不存在 `logback{,-spring}.{xml,groovy}`，已验证还原文件后该测试确实失败。**通用教训：类库的 `src/main/resources` 根目录只放带命名空间的文件（如 `META-INF/...`），任何会被框架按约定名自动拾取的配置都不许放。**
- **日期**：2026-07-26（2.0.0 已发布受影响，需 2.0.1 修复）

### P-09 `Bucket4jAdapter.stop()` 漏 `synchronized`，与 `start()` 竞争会丢 stop
- **现象**：并发 start/stop 后，`stop()` 已返回而限流器仍在放行流量（停机后仍在服务）。
- **原因**：违反本文件 §3.5。`start()` 有 `synchronized`，`stop()` 没有：`stop()` 在 `start()` 的「`bucket != null`」检查与赋值之间把字段置空，`start()` 随后赋上新桶，stop 被彻底丢弃。HikariAdapter 两个方法都加了，此处不对称。
- **修法**：`stop()` 补 `synchronized` + 幂等早返回。**防复发**：竞态不可确定性复现，故用反射断言两个生命周期方法都带 `synchronized` 修饰（`lifecycleMethods_areSynchronized_perRules`）。**新增 adapter 时照抄这条测试。**
- **日期**：2026-07-26

### P-10 子 pom 重复声明同一依赖，后一条静默覆盖前一条（P-05 家族）
- **现象**：starter pom 里两个 adapter 各声明两次（一次 `optional` compile、一次 `test`），Maven 每次构建告警 `must be unique ... future Maven versions might no longer support building such malformed projects`；`help:effective-pom` 显示各只剩一条 `<scope>test</scope>`，`<optional>` 被吃掉。
- **原因**：同 groupId:artifactId:type:classifier 的依赖声明两次时，后者整体覆盖前者，不是合并。
- **修法**：删掉重复声明 —— `optional` 的 compile 依赖本来就在测试 classpath 上。**诚实评估**：使用方解析出的 classpath 其实没变（optional 与 test 都不传递），无运行时故障；代价是已发布 POM 表述错了意图 + 长期带告警。**防复发：构建告警不许长期无视，`must be unique` 一出现就当错误处理。**
- **日期**：2026-07-26

### P-11 `start()` 中途失败不回滚，已启动资源永远没人关
- **现象**：多资源场景下第 2 个资源启动失败，第 1 个的 HikariCP 池（线程 + 真实数据库连接）泄漏到 JVM 结束。
- **原因**：fail-fast 做到了但没做到 **fail-clean**。异常抛穿 Spring 的 `@Bean` 方法 → 容器拿不到 bean → `destroyMethod="close"` 永不执行 → 再没有任何人有机会关掉已启动的资源。
- **修法**：`start()` 记录已成功启动者，失败时逆序 `stop(Duration.ZERO)`（此刻无业务在用，不必等 graceful）后重抛原异常；回滚中的异常挂到 suppressed 上不掩盖首因。**防复发**：`start_failure_rollsBackAlreadyStartedResources_inReverse` 断言事件序列，已验证修复前因缺回滚而失败。
- **日期**：2026-07-26

### P-12 `PoolStats.totalReleased` 在原生用法下恒为 0
- **现象**：`totalBorrowed` 一路涨、`totalReleased` 恒为 0，累计计数自相矛盾。
- **原因**：`getConnection()` 计了 borrow，但归还走 `Connection.close()`，适配器观测不到，`release()` 永不被调用。而导致失真的写法 `try (Connection c = ds.getConnection())` 正是类 javadoc 自己推荐的那个。
- **修法**：累计计数只统计 `Pool` 能力路径（`borrow`/`release`），两边可比可对账；原生路径不计入并在 javadoc 写明。**未改 `PoolStats` 的 record 结构**——它是已发布公开 API，patch 版本不破坏兼容性。**通用教训：包装成熟库时，凡「归还/释放」由底层对象自己的方法完成的，适配器就别自作多情记计数器。**
- **日期**：2026-07-26

### P-13 `tunable` 白名单原样透传，拼错的 key 要到调参时才发现
- **现象**：`tunable: [maximum-poolsize]`（漏连字符）能正常启动，等生产上运维真去调参才收到 `rejected: {...: "not in tunable whitelist"}`。
- **原因**：违反 §3.3「配置启动即校验」。四处（两个 Builder + 两个 Factory）都没校验声明的白名单。
- **修法**：两个 adapter 各声明 `SUPPORTED_TUNABLE_KEYS`，构造期校验为其子集，否则抛 `MetaPoolConfigException`；Builder 默认值与 Factory 兜底值都改为引用该常量。**这是 2.0.1 唯一的行为变更**（此前能带病启动的错配置现在启动即失败），发布说明须写明。
- **日期**：2026-07-26

### P-14 自持 `metricsBound` 标志会让第二个 registry 拿不到任何指标
- **现象**：把同一资源 `bindTo` 到第二个 `MeterRegistry` 时静默什么都不注册。
- **原因**：`MetricsSource` javadoc 承诺的是「对**同一** registry 重复调用不重复注册」，而一个布尔标志实现的是「全局只绑一次」。而幂等本来就由 Micrometer 保证（同名同 tag 重复注册返回已有实例），这个标志纯属多余且有害。
- **修法**：删掉标志，直接依赖 Micrometer 的去重。**新增 adapter 不要再抄这个标志。**
- **日期**：2026-07-26

### P-15 两个 adapter 的「调参后重启」语义不一致
- **现象**：Bucket4j 调高上限后 stop→start 保留调参值；Hikari 却静默退回原始池大小。
- **原因**：`Bucket4jAdapter.apply()` 更新了 `limitForPeriod` 字段，而 `HikariAdapter.apply()` 只写 `HikariConfigMXBean`、从不回写 `this.config`，而 `start()` 是用 `this.config` 重建池的。
- **修法**：Hikari 侧回写 `config`，统一为「调参结果重启不丢」。**通用教训：`Tunable.apply()` 必须同时更新「运行中的对象」和「用于重建的配置」，否则重启即回退。新增 adapter 自查这一条。**
- **日期**：2026-07-26

### P-16 单独钉 `micrometer-registry-prometheus` 版本导致 micrometer 模块错配
- **现象**：`micrometer-core` 1.14.4（来自 spring-boot-dependencies）对 `micrometer-registry-prometheus` 1.14.5，而 Micrometer 要求各模块同版本。
- **原因**：根 pom 自设 `micrometer.version` 属性只作用于 registry 一个模块，core 仍由 Boot BOM 管。
- **修法**：删掉显式版本与 `micrometer.version` 属性，全部交给 `spring-boot-dependencies`。核对 examples fat jar：五个 micrometer 模块现均为 1.14.4。**通用教训：凡 Boot BOM 已管的坐标（micrometer/jackson/slf4j…）不要再自设版本属性，除非整族一起升。**
- **日期**：2026-07-26

### P-17 精确断言把测试绑死在时序上
- **现象**：`assertEquals(5, ok)` 在本机连跑 5 次全过，但只要机器慢或 CI 有负载就会多放行 1~2 个而假失败。
- **原因**：桶 5/s 且 **greedy 补充**（每 200ms 回 1 个令牌），「恰好放行 5 个」只在整个 10 请求循环跑完于一个补充周期内才成立。
- **修法**：改区间断言（`ok >= 容量`、`ok < 总数`、`ok + limited == 总数`），守住「限流真实生效」这个被测行为本身而非某个时序巧合。**2.1 上 GitHub Actions 前，凡断言依赖挂钟时间的测试都先按此复查。**
- **日期**：2026-07-26

### P-18 `spring-boot-maven-plugin` 不以 Boot 父 pom 为父时不会自动绑定 `repackage`
- **现象**：examples 的 `mvn package` 只产出 6.7KB 瘦 jar，`java -jar` 报没有主清单属性。
- **原因**：`repackage` 的默认执行来自 `spring-boot-starter-parent` 的 pluginManagement；本项目用自己的父 pom，只声明插件不会带上该 execution。
- **修法**：显式声明 `<executions><execution><goals><goal>repackage</goal>`。修复后产出 28.9MB 可执行 jar。（文档推荐的 `mvn spring-boot:run` 一直可用，所以这个坑藏了很久。）
- **日期**：2026-07-26

### P-19 `ThreadPoolExecutor` 两个 size 的 setter 各自校验，逐个应用会炸在中间态
- **现象**：一个调参 patch 同时带 `core-pool-size` 和 `maximum-pool-size` 时，`apply()` 抛出裸的
  `java.lang.IllegalArgumentException`，线程池被改了一半。
- **原因**：`setCorePoolSize` 与 `setMaximumPoolSize` **各自**都校验 `core <= max`。
  当前 core=1/max=2，要调到 core=4/max=4：先设 core 时 `4 > 2` 立即抛异常。缩容方向对称地成立
  （先设 max 时 `1 < 4` 同样抛）。**注意 `Map.of()` 的迭代顺序是未定义的**，
  所以「碰巧顺序对了」不构成正确性，必须由实现显式排序。
- **修法**：① 先把整组目标值算出来并整体校验，非法则**整组拒绝**，绝不留下半成品线程池；
  ② 应用顺序随方向变化 —— **扩容先抬 max 再抬 core，缩容先降 core 再降 max**。
  **防复发**：`tune_bothSizesInOnePatch_appliesInSafeOrder` 覆盖两个方向。
  已用探针实测确认该测试是承重的：把排序改回固定顺序后，它与 `tune_survivesRestart_perPitfallP15`
  两条立即以 `IllegalArgumentException` 报错。
- **通用教训**：包装成熟库做「批量调参」时，凡底层是**多个互相约束的独立 setter**，
  就必须自己定义中间态安全的应用顺序 —— 底层库只保证每一步的合法性，不保证你这一批的原子性。
- **日期**：2026-08-12

### P-20 `SynchronousQueue.remainingCapacity()` 恒为 0，只看队列会把健康判断带偏
- **现象**：设计线程池 `health()` 时，若按「队列剩余容量为 0 ⇒ 饱和 ⇒ DEGRADED」判断，
  所有 `queue-capacity: 0`（直接交接型）线程池将**永远**报 DEGRADED，哪怕一个任务都没有。
- **原因**：`SynchronousQueue` 不存元素，其 `remainingCapacity()` 按契约恒返回 0，`size()` 恒返回 0。
  这个 0 表示「本来就没有容量」，不表示「满了」——同一个数字，两种含义。
- **修法**：饱和判据必须叠加线程维度：`queue.remainingCapacity() == 0 && poolSize >= maximumPoolSize`。
  **防复发**：`health_isUp_forIdleSynchronousQueueExecutor` 直接断言空闲直接交接型线程池为 UP。
- **诚实说明**：本条是**设计期识别并直接规避**的，不是线上踩出来的；记进台账是因为
  下一个 adapter（Netty / commons-pool2）同样要写 `health()`，容易重犯。
- **通用教训**：把底层库的某个数值当健康信号前，先问「这个值的 0 / 最大值是不是有第二种含义」。
  `ExecutorStats.queueRemainingCapacity` 的 javadoc 早就为无界队列约定了 `Integer.MAX_VALUE`，
  是同一类问题的另一面。
- **日期**：2026-08-12

### P-21 共享 Spring 上下文里的有状态资源，让测试变成顺序依赖的假失败
- **现象**：examples 里新加的用例断言 200，实测收到 **429**。单独跑该用例是绿的。
- **原因**：被治理的限流器是**有状态**的（一个 5/s 的令牌桶），而 `@SpringBootTest` 默认**缓存并复用
  上下文**，同类里所有用例共享同一个桶。`rateLimit_kicksIn_afterCapacity` 会刻意把桶抽干，
  于是排在它后面的任何用例都必然被限流。**故障表现取决于 JUnit 的方法执行顺序**——最难查的一类。
- **修法**：`@DirtiesContext(classMode = BEFORE_EACH_TEST_METHOD)`，每个用例拿干净上下文。
  代价是多两次上下文启动（examples 模块 3.8s → 8.2s 实测），换执行顺序无关的确定性。
- **⚠️ 不要这样修**：加 sleep 等令牌补充。那是把断言绑回挂钟，等于用 P-17 换 P-21。
  也不要靠 `@Order` 排顺序——那只是把依赖藏起来，下一个人加用例照样中招。
- **通用教训**：判断标准是「这个资源有没有状态」。MetaPool 治理的资源**大多有状态**
  （连接池、令牌桶、线程池、锁），所以凡在共享上下文里断言它们的行为，都要先想清楚谁污染谁。
  这条随资源类型变多只会更容易踩。
- **日期**：2026-08-12
