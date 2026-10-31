package com.polaris.trade.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 采样策略的可见性：启动时把"当前生效的采样率"打出来。
 *
 * <p>为什么这值得一个类：采样率是最容易被"临时调一下"然后忘记改回来的配置。
 * 事故时为了查问题把采样率调到 100%，事后忘了改回 10%，
 * 一个月后账单来了才发现存储成本涨了 10 倍。
 * 把它打印在启动日志里，至少能在日志里留下痕迹。
 */
public class ObservationSamplingLogger {

    private static final Logger log = LoggerFactory.getLogger(ObservationSamplingLogger.class);

    public ObservationSamplingLogger(double probability) {
        log.info("链路采样率 = {}（尾部补采策略：error=true 或 耗时>1s 强制 100%，由 OTel Collector 执行）",
                probability);
        if (probability >= 0.5D) {
            log.warn("采样率 {} 偏高：若非排障期，请核对存储成本（ch15 §3.4 的容量测算）", probability);
        }
    }
}
