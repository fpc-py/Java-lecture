package com.polaris.trade.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 网关：全站唯一入口。
 *
 * <p><b>这个模块只能有 WebFlux，不能有 Servlet。</b>
 * 只要 classpath 上出现 {@code spring-boot-starter-web}（哪怕是通过传递依赖带进来的），
 * 启动就会直接失败：
 * <pre>
 *   Spring MVC found on classpath, which is incompatible with Spring Cloud Gateway.
 * </pre>
 * 这是搭网关的第一坑，而且报错信息足够明确，反而容易解决。
 * 更难的是第二坑：某个同事为了"复用"一个工具类引了 web starter，
 * 在本地跑得好好的，一上 CI 就崩 —— 因为本地 target 里还是旧的 classpath。
 * 所以 case 里用 {@code mvn dependency:tree | grep starter-web} 做 CI 检查，
 * 而不是靠记忆力。
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
