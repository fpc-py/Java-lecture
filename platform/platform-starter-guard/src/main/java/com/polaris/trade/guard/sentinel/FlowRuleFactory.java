package com.polaris.trade.guard.sentinel;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.polaris.trade.guard.GuardProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 规则工厂：把"限流/熔断"从散落的数字变成可推导、可复现的规则对象。
 *
 * <p>两条规则各自的推导（ch11 §2.1 / §2.2）：
 * <ul>
 *   <li><b>流控</b>：QPS + 线程数<b>双维度</b>。只配 QPS 挡不住"每个请求都特别慢"的情况
 *       （QPS 达标但线程被占满）；只配线程数又挡不住瞬时尖峰</li>
 *   <li><b>预热 30s</b>：应用刚启动时 JIT 未编译、连接池未预热，直接放全量流量
 *       会把冷启动压成超时。WARM_UP 让阈值在 30s 内线性爬升</li>
 *   <li><b>熔断用"慢调用比例"而不是"异常比例"</b>：下游变慢时往往不报错，
 *       异常率是 0，等报错时用户已经等了几十秒。慢调用比例 0.5 + RT 阈值 300ms
 *       能在"用户开始骂人"之前熔断</li>
 * </ul>
 */
public class FlowRuleFactory {

    private static final Logger log = LoggerFactory.getLogger(FlowRuleFactory.class);

    private final GuardProperties properties;

    public FlowRuleFactory(GuardProperties properties) {
        this.properties = properties;
    }

    public void apply() {
        GuardProperties.Flow flow = properties.flow();
        GuardProperties.Degrade degrade = properties.degrade();

        List<FlowRule> flowRules = new ArrayList<>();

        FlowRule qpsRule = new FlowRule(flow.resource());
        qpsRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        qpsRule.setCount(flow.qps());
        qpsRule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_WARM_UP);
        qpsRule.setWarmUpPeriodSec(flow.warmUpSeconds());
        flowRules.add(qpsRule);

        // 线程数维度：Tomcat 200 线程是硬上限，超过它 QPS 规则没有意义（请求已无处可跑）
        FlowRule threadRule = new FlowRule(flow.resource());
        threadRule.setGrade(RuleConstant.FLOW_GRADE_THREAD);
        threadRule.setCount(flow.thread());
        flowRules.add(threadRule);

        FlowRuleManager.loadRules(flowRules);

        DegradeRule degradeRule = new DegradeRule(degrade.resource());
        // Sentinel 1.8.x 里"慢调用比例"仍然用 DEGRADE_GRADE_RT 这个常量（历史命名），
        // 是否启用比例模式取决于有没有 setSlowRatioThreshold —— 这是个高频踩坑点，
        // 很多人去找 DEGRADE_GRADE_SLOW_REQUEST_RATIO，结果发现这个常量根本不存在。
        degradeRule.setGrade(RuleConstant.DEGRADE_GRADE_RT);
        degradeRule.setCount(degrade.slowRtMs());
        degradeRule.setSlowRatioThreshold(degrade.slowRatio());
        degradeRule.setMinRequestAmount(degrade.minRequestAmount());
        degradeRule.setStatIntervalMs(degrade.statIntervalMs());
        degradeRule.setTimeWindow(degrade.timeWindowSeconds());
        DegradeRuleManager.loadRules(List.of(degradeRule));

        log.info("Sentinel 规则已加载 flow={} qps={} thread={} warmUp={}s degrade={} slowRt={}ms ratio={} window={}s",
                flow.resource(), flow.qps(), flow.thread(), flow.warmUpSeconds(),
                degrade.resource(), degrade.slowRtMs(), degrade.slowRatio(), degrade.timeWindowSeconds());
    }
}
