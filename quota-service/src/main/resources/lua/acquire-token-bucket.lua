-- Redis serializes the entire refill/check/debit operation across every quota-service replica.
local key = KEYS[1]
local state = redis.call('HMGET', key, 'algorithm', 'capacity', 'rate', 'max_batch',
    'lease_ttl', 'tokens', 'updated_at_us')
if not state[1] then return {-1} end
if state[1] ~= 'token_bucket' then return {-3} end
local maximum = tonumber(state[4])
local minimum = tonumber(ARGV[2])
if minimum > maximum then return {-2} end
local clock = redis.call('TIME')
local now = math.max(tonumber(clock[1]) * 1000000 + tonumber(clock[2]), tonumber(state[7]))
local rate = tonumber(state[3])
local tokens = math.min(tonumber(state[2]), tonumber(state[6]) + (now - tonumber(state[7])) * rate / 1000000)
local granted = math.min(tonumber(ARGV[1]), maximum, math.floor(tokens))
local retry = 0
if granted < minimum then
    granted = 0
    retry = math.max(1, math.ceil((minimum - tokens) * 1000 / rate))
end
tokens = tokens - granted
redis.call('HSET', key, 'tokens', string.format('%.17g', tokens), 'updated_at_us', string.format('%.0f', now))
return {granted, math.floor(tokens), retry, tonumber(state[5])}
