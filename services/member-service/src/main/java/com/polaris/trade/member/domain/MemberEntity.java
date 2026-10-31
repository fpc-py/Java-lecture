package com.polaris.trade.member.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.polaris.trade.data.crypto.EncryptTypeHandler;
import lombok.Data;

import java.time.Instant;

/**
 * 会员实体。
 *
 * <p>两条与 ch18 直接相关的设计：
 * <ul>
 *   <li>{@code phone} 存的是 AES-GCM 密文（{@code v1:Base64(iv):Base64(ct)}），
 *       由 {@code EncryptTypeHandler} 透明加解密。数据库被拖走时，
 *       攻击者拿到的是密文而不是 3000 万手机号</li>
 *   <li>{@code phoneHash} 存 HMAC-SHA256（不是裸 SHA256）。
 *       加密列无法建索引、无法等值查询，所以"按手机号查会员"必须靠哈希列。
 *       裸 SHA256 会被彩虹表反查 —— 手机号空间只有 10^11，几分钟就能全量撞库</li>
 * </ul>
 */
@Data
@TableName("t_member")
public class MemberEntity {

    /** 等级 → 折扣基点（9500 = 95 折）。 */
    public static final int LEVEL_NORMAL = 1;
    public static final int LEVEL_SILVER = 2;
    public static final int LEVEL_GOLD = 3;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 业务用户号，形如 u_88231。 */
    private String userId;

    private String nickname;

    private Integer level;

    /** 密文手机号。逐字段声明，不要依赖全局注册（见 EncryptTypeHandler 的注释）。 */
    @TableField(typeHandler = EncryptTypeHandler.class)
    private String phone;

    /** HMAC-SHA256(phone)，用于等值查询。 */
    private String phoneHash;

    private Instant createdAt;

    private Instant updatedAt;

    /** 折扣基点：等级越高折扣越大。放在领域对象上，而不是散在 if-else 里。 */
    public int discountBasisPoints() {
        return switch (level == null ? LEVEL_NORMAL : level) {
            case LEVEL_GOLD -> 8_800;    // 88 折
            case LEVEL_SILVER -> 9_500;  // 95 折
            default -> 10_000;           // 无折扣
        };
    }

    public String levelName() {
        return switch (level == null ? LEVEL_NORMAL : level) {
            case LEVEL_GOLD -> "GOLD";
            case LEVEL_SILVER -> "SILVER";
            default -> "NORMAL";
        };
    }
}
