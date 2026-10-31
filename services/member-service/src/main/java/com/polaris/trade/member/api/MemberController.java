package com.polaris.trade.member.api;

import com.polaris.trade.contract.member.MemberProfile;
import com.polaris.trade.member.app.MemberAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MemberController {

    private final MemberAppService memberAppService;

    public MemberController(MemberAppService memberAppService) {
        this.memberAppService = memberAppService;
    }

    /**
     * 这里直接返回 {@link MemberProfile}，而 {@code phone} 字段带 {@code @Sensitive}，
     * 序列化时会由脱敏逻辑处理成 {@code 138****8000}。
     * 注意脱敏发生在 <b>契约对象</b> 上而不是实体上 ——
     * 实体出了服务边界才算"对外暴露"，脱敏规则应该跟着契约走。
     */
    @GetMapping("/internal/member/{userId}")
    public MemberProfile profile(@PathVariable String userId) {
        return memberAppService.profile(userId);
    }
}
