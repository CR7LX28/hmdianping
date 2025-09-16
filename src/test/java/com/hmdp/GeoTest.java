package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
public class GeoTest {

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Test
    public void localshopData(){
        //1.查询店铺信息
        List<Shop> shops = shopService.list();
        //2.店铺按照typeId进行分组 map<typeId,店铺集合>
        Map<Long,List<Shop>> map = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3.分批写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            //3.1获取类型id
            Long typeId = entry.getKey();
            String typeKey = SHOP_GEO_KEY + typeId;
            //3.2获取这个类型的所有店铺，组成集合
            List<Shop> value = entry.getValue();
            //3.3 写入Redis geoadd key 经度 维度 member
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            for (Shop shop : value) {
                //stringRedisTemplate.opsForZSet().add(typeKey, new Point(shop.getX(),shop.getY()), shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(typeKey,locations);
        }
    }

}
