-- 1.参数列表
-- 1.1 优惠券id
local voucherId = ARGV[1]
-- 1.2用户id
local userId = ARGV[2]

-- 2.数据key
-- 2.1 库存key
local stockKey = 'seckill:stock:'..voucherId
-- 2.2 订单key
local orderKey = 'seckill:order:'..voucherId

-- 3.脚本业务
-- 3.1判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0)
    then return 1
end
-- 3.2判断用户是否下单 sismember orderKey userId
if(redis.call('sismember',orderKey,userId)==1)
    then return 2
end
-- 3.3扣库存 incrby stockKey -1
redis.call('incrby',stockKey,-1)
-- 3.4下单 sadd orderKey userId
redis.call('sadd',orderKey,userId)
return 0
