package com.polaris.trade.data.crypto;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 透明加解密的 TypeHandler：实体里是明文，数据库里是密文，业务代码无感。
 *
 * <p>用法：{@code @TableField(typeHandler = EncryptTypeHandler.class)}，
 * 并在 XML/注解里声明 {@code jdbcType=VARCHAR}。
 *
 * <p><b>必须说清楚的取舍</b>：MyBatis 的 TypeHandler 由 MyBatis 自己实例化，
 * 拿不到 Spring 容器，因此这里用静态 {@code cipher} 注入。
 * 这不是优雅的设计，但它是社区里代价最小的方案；更干净的做法是
 * 自定义 {@code SqlSessionFactory} 的 {@code TypeHandlerRegistry} 并传构造参数，
 * 代价是每个服务都要重复这段装配代码。真实项目里两种都能见到，
 * 关键是在 ADR 里写清楚为什么选它。
 *
 * <p>另一个代价：<b>加密列不可索引、不可范围查询</b>。
 * 所以手机号这类"既要加密又要按它查"的字段，正确做法是额外存一列
 * {@code phone_hash = sha256(phone)}，用哈希列做等值查询。
 *
 * <p><b>这里刻意没有 {@code @MappedTypes(String.class)}。</b>
 * 一旦打上这个注解，只要有人配置了 {@code mybatis-plus.type-handlers-package}
 * 让它被全局注册，<b>T 库里所有 String 字段都会被加密</b> ——
 * 包括订单备注、状态、traceId。那一刻整库不可用，且已经写入的数据无法回读。
 * 正确用法是逐字段声明：{@code @TableField(typeHandler = EncryptTypeHandler.class)}，
 * 让"哪些列被加密"成为一份可 review 的清单，而不是一个全局隐式行为。
 *
 * <p>文档归属：ch18 §2.5。
 */
public class EncryptTypeHandler extends BaseTypeHandler<String> {

    private static volatile AesGcmCipher cipher;

    /** 由 PolarisDataAutoConfiguration 在启动时注入。 */
    public static void install(AesGcmCipher aesGcmCipher) {
        cipher = aesGcmCipher;
    }

    private static AesGcmCipher requireCipher() {
        AesGcmCipher current = cipher;
        if (current == null) {
            throw new IllegalStateException(
                    "EncryptTypeHandler 未初始化：请确认 polaris.data.crypto.enabled=true 且密钥已配置");
        }
        return current;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, requireCipher().encrypt(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return decrypt(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return decrypt(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return decrypt(cs.getString(columnIndex));
    }

    private String decrypt(String stored) {
        return stored == null ? null : requireCipher().decrypt(stored);
    }
}
