package com.metapool.examples;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * MetaPool 示例应用。
 *
 * <p>演示：几行 YAML 让 MetaPool 同时纳管一个数据库连接池（HikariCP over H2）与一个限流器（Bucket4j），
 * 业务代码只通过控制面的能力接口访问它们，并在 {@code /actuator/metapool}、{@code /actuator/prometheus}
 * 观察统一治理。
 *
 * <p>运行：{@code mvn -pl metapool-examples spring-boot:run}，然后：
 * <pre>
 *   curl -XPOST localhost:8080/orders/A1
 *   curl localhost:8080/actuator/metapool
 *   curl localhost:8080/actuator/prometheus | grep metapool
 * </pre>
 *
 * <p>排除 Spring 自带的 DataSource 自动装配——本例的数据源由 MetaPool 治理，而非 Spring。
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class ExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExampleApplication.class, args);
    }
}
