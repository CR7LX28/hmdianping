package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    // 开始时间戳（31位）
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    // 序列号的位数
    private static final int COUNT_BITS = 32;

    //注入redis
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成全局唯一id
     * @param keyPrefix
     * @return
     */
    public long nextId(String keyPrefix) {

        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        // 2.生成序列号
        String today = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + today);
        // 3.拼接并返回
        return timestamp << COUNT_BITS | count;
    }
}
