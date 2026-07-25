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

## 发布

```bash
# 1. 去掉 -SNAPSHOT，定版（如 2.0.0）
mvn versions:set -DnewVersion=2.0.0

# 2. 用 release profile 构建 + 签名 + 上传（source/javadoc/gpg 均在该 profile 内）
mvn -Prelease clean deploy

# autoPublish=false：产物上传后在 https://central.sonatype.com 的 Deployments 里人工确认发布
```

发布的构件（`metapool-bom` 与各库；`metapool-examples` / `metapool-benchmark` 已设 `maven.deploy.skip`）：

| 构件 | 用途 |
|---|---|
| `io.github.roseri66:metapool-bom` | 物料清单，import 后统一对齐版本 |
| `io.github.roseri66:metapool-common` | 契约层 |
| `io.github.roseri66:metapool-core` | 控制面实现 |
| `io.github.roseri66:metapool-adapter-hikari` | HikariCP 适配器 |
| `io.github.roseri66:metapool-adapter-bucket4j` | Bucket4j 适配器 |
| `io.github.roseri66:metapool-spring-starter` | Spring Boot Starter |

## 使用方引入（发布后）

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.roseri66</groupId>
      <artifactId>metapool-bom</artifactId>
      <version>2.0.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```
之后引用各 `metapool-*` 无需再写 version。
