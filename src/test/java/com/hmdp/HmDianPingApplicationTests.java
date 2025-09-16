package com.hmdp;

import cn.hutool.json.JSONUtil;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

@SpringBootTest
public class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void testSaveShop() throws InterruptedException {
        // 手动预热所有数据
/*        for (int i = 1; i < 15; i++) {
            shopService.saveShop2Redis((long) i,10L);
        }*/
//        shopService.saveShop2Redis(1L,10L);
        stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY+11L, "200");
    }

    @Test
    public void testHyperLogLog(){
        String[] values = new String[1000];
        // 批量保存100w条用户记录，每一批一个记录
        int j=0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if (j == 999) stringRedisTemplate.opsForHyperLogLog().add("hl2", values);
        }

        //统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println("count = " + count);
    }



}
