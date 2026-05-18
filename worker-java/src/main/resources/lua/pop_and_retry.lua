-- ============================================
-- pop_and_retry.lua
-- Atomic retry queue processor for VulnWatch Worker
-- ============================================
--
-- KEYS[1] = scan:retry (sorted set)
-- ARGV[1] = current timestamp (epoch seconds)
-- ARGV[2] = max batch size
--
-- Returns: List of retry keys that were popped from ZSET
-- ============================================

local retry_zset = KEYS[1]
local now = tonumber(ARGV[1])
local batch_size = tonumber(ARGV[2]) or 10

-- Get up to batch_size jobs with score <= now
local jobs = redis.call('ZRANGEBYSCORE', retry_zset, '-inf', now, 'LIMIT', 0, batch_size)

if #jobs == 0 then
    return {}
end

-- Remove all popped jobs from ZSET atomically
if #jobs > 0 then
    redis.call('ZREM', retry_zset, unpack(jobs))
end

-- Return the list of job keys
return jobs