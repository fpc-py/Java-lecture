package com.polaris.trade.security;

import com.polaris.trade.common.lang.Hashes;
import com.polaris.trade.common.trace.TraceIds;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

/**
 * 审计日志：只 INSERT、哈希链、独立存储（ch18 §2.6）。
 *
 * <p>Hash-chain 的作用不是"防止有人改库"（能改库的人也能重算整条链），
 * 而是"让篡改<b>可被发现</b>"：
 * <pre>
 *   hash(n) = sha256(ts + actorId + action + resource + detail + hash(n-1))
 * </pre>
 * 只要有一个环节被改，后面所有 hash 都对不上。
 * 真正的不可篡改要靠 WORM 存储或第三方时间戳，成本高得多。
 * 这里刻意实现"够用"的版本，并在文档里写清它的上限。
 *
 * <p>另一个关键点：审计<b>不能和业务共库共账号</b>（ch18 场景 E）。
 * 本项目为了演示方便放在同一实例的不同 schema，生产上应该独立实例 +
 * 只给 INSERT 权限（{@code GRANT INSERT} 而没有 UPDATE/DELETE）。
 */
public class AuditLogger {

    private static final String GENESIS = "0000000000000000000000000000000000000000000000000000000000000000";

    private final JdbcTemplate jdbcTemplate;

    public AuditLogger(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(String actorId, String action, String resource, String detailJson) {
        Instant now = Instant.now();
        String prevHash = lastHash();
        String payload = now + actorId + action + resource + detailJson + prevHash;
        String hash = Hashes.sha256Hex(payload);

        jdbcTemplate.update("""
                INSERT INTO t_audit_log(actor_id, action, resource, detail, trace_id, prev_hash, hash, created_at)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                """, actorId, action, resource, detailJson, TraceIds.current(), prevHash, hash, now);
    }

    private String lastHash() {
        var hashes = jdbcTemplate.queryForList(
                "SELECT hash FROM t_audit_log ORDER BY id DESC LIMIT 1", String.class);
        return hashes.isEmpty() ? GENESIS : hashes.get(0);
    }

    /** 校验链完整性：返回第一处断裂的主键，null 表示链完整。 */
    public Long verifyChain() {
        var rows = jdbcTemplate.queryForList(
                "SELECT id, actor_id, action, resource, detail, prev_hash, hash, created_at "
                        + "FROM t_audit_log ORDER BY id ASC");
        String previous = GENESIS;
        for (var row : rows) {
            String expected = Hashes.sha256Hex(
                    row.get("created_at").toString()
                            + row.get("actor_id")
                            + row.get("action")
                            + row.get("resource")
                            + row.get("detail")
                            + previous);
            if (!expected.equals(row.get("hash"))) {
                return ((Number) row.get("id")).longValue();
            }
            previous = (String) row.get("hash");
        }
        return null;
    }
}
