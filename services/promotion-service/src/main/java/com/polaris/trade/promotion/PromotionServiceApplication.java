package com.polaris.trade.promotion;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 促销/优惠券服务。
 *
 * <p>这个服务在案例里的定位很特别：它是<b>被刻意设计成不稳定的弱依赖</b>。
 * 真实项目里促销往往是最复杂的域 —— 规则引擎、叠加、限购、实时活动配置，
 * 任何一处慢查询都会让它从 20ms 变成 5s。而它恰好又不是下单的必要条件。
 *
 * <p>所以 ch11（熔断降级）与 ch17（故障演练）都拿它当主角：
 * <ul>
 *   <li>线程隔离：促销用独立线程池，慢了只影响自己</li>
 *   <li>超时兜底：120ms 拿不到就按"无优惠"继续</li>
 *   <li>降级开关：一键关掉整个优惠计算，验证开关是不是真的有效</li>
 * </ul>
 * 这三个手段的效果都能被 {@code scripts/limit-check.sh} 量化出来。
 */
@SpringBootApplication(scanBasePackages = "com.polaris.trade")
// 平台层的 Mapper（如 OutboxMapper）不在本服务的包下，必须显式扫
@MapperScan(basePackages = "com.polaris.trade", annotationClass = Mapper.class)
public class PromotionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PromotionServiceApplication.class, args);
    }
}
