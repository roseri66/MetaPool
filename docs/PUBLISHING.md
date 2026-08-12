# 发布到 Maven Central

MetaPool 使用 `io.github.roseri66` 作为 groupId（GitHub 用户名反向域名，Central 免验证命名空间）。

## 一次性准备

1. **Sonatype Central 账号**：在 https://central.sonatype.com 注册，验证 `io.github.roseri66`
   命名空间（通过 GitHub 用户名自动验证），生成 **User Token**。
2. **`~/.m2/settings.xml`** 加入 server：
   ```xml
   <servers>
     <server>
       <id>central</id>
       <username>${CENTRAL_TOKEN_USER}</username>
       <password>${CENTRAL_TOKEN_PASSWORD}</password>
     </server>
   </servers>
   ```
3. **GPG 密钥**：`gpg --gen-key`，并把公钥上传到公钥服务器
   （`gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>`）。

## 方式一：GitHub Actions（推荐，凭据不落本机）

仓库 → **Actions** → **Release to Maven Central** → **Run workflow**，填版本号（如 `2.1.0`）。

`dry_run` **默认勾选**：先演练一次，它会完整跑 `-Prelease clean verify`（含 source/javadoc/GPG 签名）
但**不上传**，用来验证 Secrets 配对、GPG 能否非交互签名、javadoc 能否生成 —— 这三样是发布最常见的绊脚石。
演练绿了再取消勾选真发。

### 需要预先配好的 4 个 Repository Secret

仓库 → Settings → Secrets and variables → Actions → New repository secret：

| Secret | 取值 |
|---|---|
| `CENTRAL_TOKEN_USER` | central.sonatype.com 生成的 User Token 的 **username** |
| `CENTRAL_TOKEN_PASSWORD` | 同一个 User Token 的 **password** |
| `GPG_PRIVATE_KEY` | ASCII armored 私钥全文，含首尾 `-----BEGIN/END PGP PRIVATE KEY BLOCK-----`<br>导出：`gpg --armor --export-secret-keys <KEYID>` |
| `GPG_PASSPHRASE` | 该私钥的口令 |

> ⚠️ 这 4 个值**只存在 GitHub Secrets 里**，绝不写进任何文件（RULES §5.4）。

workflow **刻意不做**这几件事，全部留给人把关：不自动点 Publish（父 pom `autoPublish=false`）、
不自动提交版本号变更、不自动打 tag。上传完成后仍需：
central.sonatype.com → Deployments → **Publish** → 打 tag → 建 Release → 把 `main` 推进到下一个 `-SNAPSHOT`。

---

## 方式二：本地发布

```bash
# 0. 确认 JAVA_HOME 指向 JDK 17。
#    本机用户级+机器级 JAVA_HOME 都是 JDK 8，**每个新终端都要重设**，否则报
#    「无效的目标发行版: 17」（RULES 台账 P-03，2026-07-26 又踩了一次）。
#
#    PowerShell（本机默认终端）：
$env:JAVA_HOME = "C:\Program Files\ojdkbuild\java-17-openjdk-17.0.3.0.6-1"
#    Git Bash：
#    export JAVA_HOME='/c/Program Files/ojdkbuild/java-17-openjdk-17.0.3.0.6-1'
#
#    先验一句，必须显示 17.x 才继续：
mvn -v

# 1. 去掉 -SNAPSHOT，定版（下面以 2.1.0 为例）
#    注意：不要用 mvn versions:set —— 本机插件解析会挂住（P-04），用 sed 直接替换
sed -i 's|<version>.*-SNAPSHOT</version>|<version>2.2.0</version>|g' pom.xml metapool-*/pom.xml
grep -rn SNAPSHOT pom.xml metapool-*/pom.xml    # 应无输出

# 2. 先本地验一遍产物（跳过签名，避免 GPG 交互）：测试全绿 + source/javadoc jar 能出
mvn -Prelease clean package -Dgpg.skip=true

# 3. 用 release profile 构建 + 签名 + 上传（source/javadoc/gpg 均在该 profile 内）
mvn -Prelease clean deploy

# autoPublish=false：产物上传后在 https://central.sonatype.com 的 Deployments 里人工确认发布
```

发布确认后收尾：

```bash
git tag -a v2.1.0 -m "MetaPool 2.1.0"          # 打 tag
git push origin v2.1.0
# GitHub 建 Release，正文取 CHANGELOG.md 对应小节

# 回到下一个开发周期（走 Actions 发布时 main 一直是 SNAPSHOT，这步只在本地发布后需要）
sed -i 's|<version>2.2.0</version>|<version>2.2.0-SNAPSHOT</version>|g' pom.xml metapool-*/pom.xml
git commit -am "chore: begin next dev cycle — 2.2.0-SNAPSHOT"
```

发布的构件共 **10 个**（`metapool-examples` / `metapool-benchmark` 已设 `maven.deploy.skip`，不上传）：

| 构件 | 用途 | 起始版本 |
|---|---|---|
| `io.github.roseri66:metapool-bom` | 物料清单，import 后统一对齐版本 | 2.0.0 |
| `io.github.roseri66:metapool-common` | 契约层 | 2.0.0 |
| `io.github.roseri66:metapool-core` | 控制面实现 | 2.0.0 |
| `io.github.roseri66:metapool-adapter-hikari` | HikariCP 适配器（`datasource`） | 2.0.0 |
| `io.github.roseri66:metapool-adapter-bucket4j` | Bucket4j 适配器（`rate-limiter`） | 2.0.0 |
| `io.github.roseri66:metapool-adapter-jdk-executor` | JDK 线程池适配器（`executor`） | **2.1.0** |
| `io.github.roseri66:metapool-adapter-redisson` | Redisson 分布式锁适配器（`lock`） | **2.1.0** |
| `io.github.roseri66:metapool-adapter-commons-pool2` | Commons Pool2 对象池适配器（`object`） | **2.1.0** |
| `io.github.roseri66:metapool-adapter-lettuce` | Lettuce Redis 适配器（`redis`，不实现 `Pool`） | **2.3.0** |
| `io.github.roseri66:metapool-spring-starter` | Spring Boot Starter | 2.0.0 |

> 加了新 adapter 模块后，**记得回来更新这张表** —— 它是「这次到底发了什么」的唯一清单。
> 核对办法：`mvn -Prelease clean package -Dgpg.skip=true` 之后看哪些模块产出了
> `*-sources.jar` / `*-javadoc.jar`。

## 使用方引入（发布后）

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.roseri66</groupId>
      <artifactId>metapool-bom</artifactId>
      <version>2.2.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```
之后引用各 `metapool-*` 无需再写 version。
