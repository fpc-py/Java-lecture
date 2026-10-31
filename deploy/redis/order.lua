-- ============================================================
-- order.lua · 造真实访问分布（ch07 热 key / 冷热不均）
--
-- ⚠️ 定位声明（先说清楚，避免被误用）：
--    这个脚本**不是**用来测 Redis 峰值 QPS 的。测峰值用
--        redis-cli -p 16379 --latency-history
--        redis-benchmark -p 16379 -n 1000000 -c 50 -t get,set -q
--    它解决的是另一件 redis-benchmark 做不到的事：
--    **造出符合真实业务的访问分布**（少数 SKU 吃掉大部分流量）。
--
-- 为什么这件事必须单独做：
--   redis-benchmark 的默认 key 空间是均匀随机的（key:000000000001 ...
--   按计数器递增或均匀抽样）。均匀分布下，任何缓存都不会"热"，
--   于是你永远观察不到：
--     · 单个热 key 打满一个 Redis 分片（cluster 下最典型的翻车姿势）
--     · 热 key 过期瞬间几百个请求同时回源（缓存击穿，ch07 场景 B）
--     · 20% 的 key 承载 80% 的流量时，本地缓存命中率的真实上限
--
-- 分布模型：Zipf，rank 越小访问越频繁，P(rank) ∝ 1/rank^s。
--   N=10000、s=1 时，前 10 个 SKU 大约吃掉 20% 的请求 —— 和真实电商接近。
--
-- 用法：
--   redis-cli -p 16379 --eval deploy/redis/order.lua polaris:sku: , 10000 80
--     KEYS[1] = key 前缀（注意后面带冒号）
--     ARGV[1] = SKU 总数 N
--     ARGV[2] = 读请求占比（0~100），其余为写
--
-- 返回值：{ "R"|"W", rank } —— 便于调用方统计读写比与实际命中分布。
-- ============================================================

local prefix   = KEYS[1]
local skuCount = tonumber(ARGV[1])
local readPct  = tonumber(ARGV[2])

if not skuCount or skuCount <= 0 then
  return redis.error_reply("ARGV[1] 必须是正整数 SKU 数量")
end
if not readPct or readPct < 0 or readPct > 100 then
  return redis.error_reply("ARGV[2] 必须是 0~100 的读占比")
end

-- ------------------------------------------------------------------
-- 为什么不用 math.random：
--   Redis 的 Lua 沙箱里 math.random 的种子在脚本开始前由服务端设定，
--   同一份脚本在不同复制模式下表现不一致；而且脚本的**副作用**必须可复现，
--   否则主从/AOF 重放会得到不同的 key 分布。
--
-- 这里改成：用一个自增计数器 + 乘法散列构造"确定性的伪随机序列"。
--   · 完全确定 → 主从重放一致
--   · 分布仍然是扁平的 → 不会出现 rank 集中在一段
--   · 不需要任何随机源
-- ------------------------------------------------------------------
local seq = redis.call('INCR', prefix .. '_seq')

-- 乘法散列：2654435761 = 2^32 / φ，Knuth 推荐的黄金比例乘子。
-- 取低 24 位再对 1000003 取模，得到一个在 [0, 1000003) 上近似均匀的值。
local h = (seq * 2654435761) % 4294967296
local u = (h % 1000003) / 1000003.0

-- Zipf 反函数（s = 1 的近似）：rank ≈ N^u
-- 严格的反 CDF 要用调和数，这里用幂律近似 —— 对"造分布"这个目的足够，
-- 而且不需要预计算 10000 项调和数表。
local rank = math.floor(skuCount ^ u)
if rank < 1 then rank = 1 end
if rank > skuCount then rank = skuCount end

local key = prefix .. rank

-- 读请求占 readPct%，其余为写。读写比是压测里最容易被忽略的变量：
-- 只压 GET 得到的 QPS 会比真实混合负载高 3~5 倍。
local dice = (h % 100) + 1
if dice <= readPct then
  -- 读：命中返回所有字段，未命中返回 nil（调用方据此统计命中率）
  local v = redis.call('HMGET', key, 'total', 'sold')
  if v[1] == false then
    return { 'MISS', rank }
  end
  return { 'HIT', rank }
else
  -- 写：模拟"扣减 + 累加已售"。真实系统里这两个动作必须在
  -- 同一个脚本里完成（ch07：分开写一定会出现"扣了库存没记销量"的中间态）
  redis.call('HINCRBY', key, 'total', -1)
  redis.call('HINCRBY', key, 'sold', 1)
  return { 'WRITE', rank }
end
