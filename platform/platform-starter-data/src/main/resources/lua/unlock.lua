-- KEYS[1] = 锁 key
-- ARGV[1] = 持有者标识（uuid:threadId）
--
-- 为什么必须用 Lua：GET + DEL 是两条命令，中间可能被其他客户端插入操作。
-- 加上 Lua 后整个"校验持有者 + 删除"是原子的。
--
-- 为什么不用直接 DEL：ch07 场景 B 的超卖事故里，A 的锁过期后 B 拿到锁，
-- A 的 finally 里 DEL 把 B 的锁删掉，C 又能加锁 —— 三个线程同时进入临界区。
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
else
    return 0
end
