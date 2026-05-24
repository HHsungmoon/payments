-- KEYS[1] = lock key
-- ARGV[1] = expected lock value (요청 UUID)
--
-- 반환:
--   1: 정상 해제
--   0: 다른 owner의 락이거나 이미 만료 (DEL 안 함)

if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
