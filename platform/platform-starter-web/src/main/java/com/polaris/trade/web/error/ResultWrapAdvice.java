package com.polaris.trade.web.error;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.polaris.trade.common.error.Result;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 统一响应包装：控制器可以只返回业务对象，由这里包成 {@link Result}。
 *
 * <p>为什么不用 AOP 包 controller：AOP 包不住 {@code ResponseEntity} 与
 * 异常处理路径，会出现"正常返回是 Result、异常返回不是 Result"的裂口，
 * 前端要做两套解析。ResponseBodyAdvice 位于消息转换之前，是唯一能覆盖全部分支的位置。
 *
 * <p><b>内部接口不包装</b>（{@code /internal/**}）。这条规则初看是细节，实际很关键：
 * 内部接口的调用方是另一个服务的客户端代码（如 {@code HttpInventoryClient}），
 * 它期望拿到的是 {@code StockSnapshot} 本身。如果在中间悄悄套一层
 * {@code {code, message, data}}，客户端就得手写一次拆包，
 * 而每个内部客户端都写一遍拆包 = 契约分散在 N 个地方，
 * 最终一定会有一个忘了拆。统一包装是给"外部消费者"的体验，不是给内部 RPC 的。
 *
 * <p>避开的五类：已经是 Result 的（不重复包）、文件下载（字节流结构不能改）、
 * Swagger（结构不能改）、{@code /internal/**}（服务间契约保持裸 DTO）、
 * <b>{@code /error}</b>（容器错误页）。
 *
 * <p><b>{@code /error} 这一条是踩出来的</b>：Spring Boot 的 {@code BasicErrorController}
 * 在某些路径下返回值会被容器当作 String 处理；一旦这里把它的 body 包成 {@link Result}，
 * 容器与 Spring Security 的 {@code ExceptionTranslationFilter} 就会因为类型不匹配抛
 * {@code ClassCastException}，最终<b>把原始异常彻底掩盖</b> —— 日志里只剩下
 * "Result cannot be cast to String"，真正让请求失败的异常不见了。
 *
 * <p>文档归属：ch06 §2.2。
 */
@RestControllerAdvice(basePackages = "com.polaris.trade")
public class ResultWrapAdvice implements ResponseBodyAdvice<Object> {

    private final ObjectMapper objectMapper;

    public ResultWrapAdvice(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class converterType) {
        return !Result.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class selectedConverterType, ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body instanceof Result<?> || body instanceof byte[]) {
            return body;
        }
        String path = request.getURI().getPath();
        if (path.startsWith("/internal/") || path.startsWith("/error")
                || path.contains("/actuator") || path.contains("/v3/api-docs")) {
            return body;
        }

        // ---------------------------------------------------------------
        // 这里必须先处理 String，否则必炸。原因在 Spring MVC 的消息转换机制：
        //
        // 转换器是**按控制器方法的声明返回类型**选的，不是按 body 的实际类型。
        // 当方法签名是 `public String create(...)` 时，选中的是
        // StringHttpMessageConverter，它内部会做 `(String) body` 强转。
        // 而我们把 body 换成了 Result 对象，于是运行期：
        //   ClassCastException: class Result cannot be cast to class java.lang.String
        //
        // 更麻烦的是这条异常会先被 Spring Security 的 ExceptionTranslationFilter
        // 捕获再重抛，堆栈上全是 Security 的帧，看起来像鉴权问题，
        // 排查方向直接被带偏（本项目第一次跑通下单时就在这里卡了一轮）。
        //
        // 解法：对 String 返回值手动序列化成 JSON 字符串。这样 body 仍然是 String，
        // 转换器不会强转失败，同时前端拿到的仍是统一的 Result 结构。
        // ---------------------------------------------------------------
        if (body instanceof String text) {
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            try {
                return objectMapper.writeValueAsString(Result.ok(text));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("包装字符串响应失败", e);
            }
        }
        return Result.ok(body);
    }
}

