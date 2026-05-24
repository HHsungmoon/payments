-- KEYS[1] = stock:product:{productId}
-- KEYS[2] = waitlist:product:{productId}
-- KEYS[3] = hold:product:{productId}:booking:{tmpUuid}
-- ARGV[1] = waitToken (tmpUuid)
-- ARGV[2] = hold TTL seconds
-- ARGV[3] = waitlist max size
-- ARGV[4] = enqueued_at ms
--
-- 반환:
--   "SLOT:{remaining}"
--   "WAITLIST:{position 1-based}"
--   "FULL"

local stock = tonumber(redis.call('GET', KEYS[1]))
if stock ~= nil and stock > 0 then
    local after = redis.call('DECR', KEYS[1])
    redis.call('SET', KEYS[3], ARGV[1], 'EX', tonumber(ARGV[2]))
    return 'SLOT:' .. tostring(after)
end

local wlSize = redis.call('ZCARD', KEYS[2])
if wlSize >= tonumber(ARGV[3]) then
    return 'FULL'
end

redis.call('ZADD', KEYS[2], tonumber(ARGV[4]), ARGV[1])
local position = redis.call('ZRANK', KEYS[2], ARGV[1])
return 'WAITLIST:' .. tostring(position + 1)
