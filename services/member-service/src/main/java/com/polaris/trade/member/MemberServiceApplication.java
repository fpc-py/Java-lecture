package com.polaris.trade.member;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 会员服务：用户档案 + 会员等级折扣。
 *
 * <p>它是案例里最"轻"的服务，但承担了两个重要角色：
 * <ol>
 *   <li><b>弱依赖的样板</b>：下单链路拿不到会员折扣就用原价，绝不阻塞</li>
 *   <li><b>敏感数据的样板</b>：手机号加密落库（AES-GCM）+ 展示脱敏，
 *       同一份数据在"存储"和"展示"两个环节各有各的处理（ch18）</li>
 * </ol>
 */
@SpringBootApplication(scanBasePackages = "com.polaris.trade")
// 平台层的 Mapper（如 OutboxMapper）不在本服务的包下，必须显式扫
@MapperScan(basePackages = "com.polaris.trade", annotationClass = Mapper.class)
public class MemberServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemberServiceApplication.class, args);
    }
}
