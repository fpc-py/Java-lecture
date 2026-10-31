package com.polaris.trade.notify;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 通知服务：把订单域的事件翻译成"对用户可见的动作"（短信/推送/站内信）。
 *
 * <p>它是案例里第一条<b>纯粹的事件驱动链路</b>，用来演示三件事：
 * <ol>
 *   <li>事件消费必须幂等：MQ 语义是 at-least-once，重复消费是常态而非异常</li>
 *   <li>下游失败不能让上游重试：通知失败只记日志 + 重试，绝不能反压订单</li>
 *   <li>消费要能"从零重建"：把 offset 重置到最早，用同一份事件重建全部通知记录，
 *       这是验证"事件是否真的携带了足够信息"的最狠的测试</li>
 * </ol>
 */
@SpringBootApplication(scanBasePackages = "com.polaris.trade")
// 平台层的 Mapper（如 OutboxMapper）不在本服务的包下，必须显式扫
@MapperScan(basePackages = "com.polaris.trade", annotationClass = Mapper.class)
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
