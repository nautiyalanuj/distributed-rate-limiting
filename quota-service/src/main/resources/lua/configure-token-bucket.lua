-- Policy and bucket state are updated together. Reapplying a policy never resets quota.
local key = KEYS[1]
local previous = redis.call('HMGET', key, 'algorithm', 'capacity', 'rate', 'tokens', 'updated_at_us')
if previous[1] and previous[1] ~= ARGV[6] then return -1 end
local clock = redis.call('TIME')
-- Epoch microseconds fit exactly into Lua's double; persist without tostring's rounding.
local now = tonumber(clock[1]) * 1000000 + tonumber(clock[2])
local capacity = tonumber(ARGV[2])
local tokens = capacity
if previous[1] then
    now = math.max(now, tonumber(previous[5]))
    local elapsed = (now - tonumber(previous[5])) / 1000000
    tokens = math.min(tonumber(previous[2]), tonumber(previous[4]) + elapsed * tonumber(previous[3]))
    tokens = math.min(capacity, tokens)
end
redis.call('HSET', key, 'policy', ARGV[1], 'algorithm', ARGV[6],
    'capacity', ARGV[2], 'rate', ARGV[3], 'max_batch', ARGV[4], 'lease_ttl', ARGV[5],
    'tokens', string.format('%.17g', tokens), 'updated_at_us', string.format('%.0f', now))
return 1
