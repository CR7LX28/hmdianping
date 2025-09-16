package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String KEY_PREFIX = "lock:";
    // 线程标识,使用UUID
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    // 提供构造函数
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //1 .尝试获取锁,value一般设置为线程的标识号码
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name,  ID_PREFIX + Thread.currentThread().getId() + "",
                timeoutSec, TimeUnit.SECONDS);

        return BooleanUtil.isTrue(flag);
    }

    @Override
    public void unlock() {
        // 删除锁，删除锁之前需要判断锁的owner是否是当前线程（将当前线程的id与redis中存的线程ID进行比较)
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        if (stringRedisTemplate.opsForValue().get(KEY_PREFIX + name).equals(threadId)){
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
