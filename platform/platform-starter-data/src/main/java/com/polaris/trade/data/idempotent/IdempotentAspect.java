package com.polaris.trade.data.idempotent;

import com.polaris.trade.common.error.BizRejectException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.time.Duration;

/**
 * {@link Idempotent} 的实现：Redis SETNX 抢占业务键。
 *
 * <p>三个关键细节：
 * <ol>
 *   <li><b>先抢键后执行</b>：顺序反了就等于没有幂等（两个线程都执行完才抢键）</li>
 *   <li><b>执行失败要释放键</b>：否则一次临时故障会让这笔业务在 24 小时内都无法重试 ——
 *       这比不幂等更糟，因为它把"可恢复故障"变成了"需要人工介入"</li>
 *   <li><b>抛 BizRejectException 而不是返回 null</b>：让 MQ 消费端明确知道
 *       "这是重复消息，直接 ACK 掉，不要重试"（ch08 重试/死信决策树）</li>
 * </ol>
 */
@Aspect
public class IdempotentAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotentAspect.class);

    private final StringRedisTemplate redis;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    public IdempotentAspect(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        String bizKey = resolveKey(joinPoint, idempotent);
        String redisKey = idempotent.prefix() + ":" + bizKey;

        Boolean acquired = redis.opsForValue()
                .setIfAbsent(redisKey, "1", Duration.ofSeconds(idempotent.ttlSeconds()));
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("幂等命中，跳过执行 key={}", redisKey);
            throw new BizRejectException(idempotent.message() + "：" + bizKey);
        }

        try {
            return joinPoint.proceed();
        } catch (Throwable t) {
            // 执行失败必须释放，否则业务在 TTL 内无法重试
            redis.delete(redisKey);
            throw t;
        }
    }

    private String resolveKey(ProceedingJoinPoint joinPoint, Idempotent idempotent) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] names = nameDiscoverer.getParameterNames(signature.getMethod());
        Object[] args = joinPoint.getArgs();

        EvaluationContext context = new StandardEvaluationContext();
        if (names != null) {
            for (int i = 0; i < names.length && i < args.length; i++) {
                context.setVariable(names[i], args[i]);
            }
        }
        Expression expression = parser.parseExpression(idempotent.key());
        Object value = expression.getValue(context);
        if (value == null) {
            throw new IllegalArgumentException(
                    "幂等键表达式求值为空：" + idempotent.key() + "，参数名是否编译时未保留？（需要 -parameters）");
        }
        return String.valueOf(value);
    }
}
