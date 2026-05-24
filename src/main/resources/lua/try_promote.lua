-- KEYS[1] = wait:token:{waitToken}
-- ARGV[1] = new status when READY ('PROCESSING')
--
-- 반환: 현재 상태 ("WAITING" / "READY" / "PROCESSING" / "PAID" / "FAILED" / "NOT_FOUND")
-- READY 발견 시 PROCESSING 으로 원자 전환 (호출자가 결제 비동기 시작)

local status = redis.call('HGET', KEYS[1], 'status')
if status == 'READY' then
    redis.call('HSET', KEYS[1], 'status', ARGV[1])
    return 'READY'
end
if status == false or status == nil then
    return 'NOT_FOUND'
end
return status
