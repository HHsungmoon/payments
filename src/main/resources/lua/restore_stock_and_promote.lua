-- KEYS[1] = stock:product:{productId}
-- KEYS[2] = hold:product:{productId}:booking:{bid}
-- KEYS[3] = waitlist:product:{productId}
-- ARGV[1] = stock_total (가드)
--
-- 반환:
--   "RESTORED"               대기열 비어있음, 재고 복구만
--   "PROMOTED:{waitToken}"   다음 대기자 승격됨 (호출자가 hold/slot 키 SET)
--   "OVERFLOW"               stock_total 초과 — 이상 상황

local after = redis.call('INCR', KEYS[1])
if tonumber(ARGV[1]) ~= nil and after > tonumber(ARGV[1]) then
    redis.call('DECR', KEYS[1])
    redis.call('DEL', KEYS[2])
    return 'OVERFLOW'
end
redis.call('DEL', KEYS[2])

local popped = redis.call('ZPOPMIN', KEYS[3], 1)
if #popped == 0 then
    return 'RESTORED'
end
local waitToken = popped[1]
redis.call('DECR', KEYS[1])
return 'PROMOTED:' .. waitToken
