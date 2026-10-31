package com.polaris.trade.monolith;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Polaris 单体启动器 —— 本案例"一套代码两种装配"的落地点。
 *
 * <p>{@code apps/polaris-monolith} 里<b>没有一行业务代码</b>。
 * 它只是把 6 个服务的普通 jar 依赖进来（不含 gateway，那是 WebFlux 的），
 * 然后用一个 Spring Boot 启动器把它们装配到同一个 JVM。
 *
 * <p>为什么能做到"不写两套代码"，关键在 {@code spring-boot-maven-plugin}：
 * 各服务的 {@code <classifier>exec</classifier>} 让可执行 fat jar 变成副产物，
 * 主产物仍是普通 jar，可以被其他模块直接依赖。很多人卡在"打出来的 jar
 * 依赖不进来"，原因就是主产物被 repackage 成了 fat jar。
 *
 * <p>装配之后要盯的三件事（M3 的验收项）：
 * <ol>
 *   <li><b>Bean 冲突</b>：六个服务都有 {@code @Mapper}、{@code @Scheduled}，
 *       扫同一个包时会不会重复注册？（答案：MyBatis 按接口类型注册，不会）</li>
 *   <li><b>Flyway 版本冲突</b>：六份 V1__ 会在一次迁移里撞车（已改为分段版本号）</li>
 *   <li><b>事务边界变形</b>：单体下一个 JDBC 连接就能跨"服务"改数据，
 *       于是"反正同一个事务"的写法会悄悄出现 —— 这是拆分时最大的返工来源。
 *       ArchUnit 规则 {@code no-cross-domain-transaction} 就是拦这个的</li>
 * </ol>
 *
 * <p><b>关于 {@code @MapperScan} 为什么必须显式写</b>：
 * {@code scanBasePackages} 只影响 Spring 的<b>组件扫描</b>（找 @Component / @Service），
 * 而 MyBatis 的 Mapper 是<b>接口</b>，普通组件扫描永远不会注册接口。
 * Mapper 的注册由 MyBatis 自己的 scanner 完成，它默认只扫
 * {@code AutoConfigurationPackages} —— 也就是启动类所在的包
 * （这里是 {@code com.polaris.trade.monolith}），而不是 {@code scanBasePackages} 指定的范围。
 * 于是 {@code com.polaris.trade.mq.outbox.OutboxMapper} 这类"平台层里的 Mapper"
 * 会报 {@code required a bean of type 'OutboxMapper' that could not be found}。
 * 这个报错看起来像"漏了注解"，实际是"两个扫描器扫的范围不一样"，很难猜。
 *
 * <p><b>但把范围放大到 {@code com.polaris.trade} 又会引入第二个坑</b>：
 * 这个包下不只有 Mapper，还有 {@code contract} 层的业务接口（{@code InventoryClient}、
 * {@code PayClient}、{@code CouponClient} ……）。MapperScannerConfigurer 会把
 * <b>范围内所有接口</b>都注册成 MyBatis Mapper，于是 {@code InventoryClient} 被当成了
 * 一张表，调用它时抛：
 * <pre>
 *   org.apache.ibatis.binding.BindingException:
 *     Invalid bound statement (not found): com.polaris.trade.contract.stock.InventoryClient.priceOf
 * </pre>
 * 这条错误信息极具误导性 —— 它说的是"找不到 SQL"，而真实原因是
 * "这个接口根本不该是 Mapper"。因此扫描必须加 {@code annotationClass = Mapper.class}，
 * 只认显式标注过的接口。<b>这是"范围放宽"和"误伤"之间必须做的取舍</b>。
 *
 * <p>文档归属：ch10 §3.3。
 */
@SpringBootApplication(scanBasePackages = "com.polaris.trade")
@MapperScan(basePackages = "com.polaris.trade", annotationClass = Mapper.class)
@EnableScheduling
public class MonolithApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonolithApplication.class, args);
    }
}
