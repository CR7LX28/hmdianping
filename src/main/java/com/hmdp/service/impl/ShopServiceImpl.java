package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.config.LocalCacheConfiguration;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private Cache<String, Object> caffeineCache;


    /**
     * 缓存重建线程池
     */
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    @Override
    public Result queryById(Long id) {
/*        //缓存穿透
//        Shop shop = queryWithPassThrough(id);
//        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);


        //互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
        //逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);*/
        //1. 从caffeine中查询数据
        Object o = caffeineCache.getIfPresent(CACHE_SHOP_KEY + id);
        if (Objects.nonNull( o)){
            log.info("从Caffeine中查询到数据...");
            return Result.ok( o);
        }

        // 查询店铺数据，（逻辑过期方式解决缓存击穿方法）
//        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 使用缓存穿透的方法
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        log.info("从Redis中查到数据");
        caffeineCache.put(CACHE_SHOP_KEY+id,shop);

        return Result.ok(shop);
    }

/*    *//**
     * 逻辑过期解决缓存击穿
     *//*
    public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        Shop shop = null;
        //2.判断缓存是否命中
        if (StrUtil.isBlank(shopJson)){
            return null;
        }
        //3.命中，判断缓存是否过期,需要将json数据反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);//类中data属性为Object类型，所以解析出来是JSONObject
        shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);

        //4.判断是否过期
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) return shop;

        //5. 过期了，缓存重建
        //5.1 尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //5.2 判断是否获取锁成功,成功则开启线程,实现重建
        if (isLock){
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        //5.3 返回商铺信息
        return shop;
    }*/

    /**
     * 缓存重建方法
     * @param id 商铺id
     * **/
    public void saveShop2Redis(Long id, Long expireSeconds) {
        //1.查询店铺信息
        Shop shop = this.getById(id);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //存入缓存
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id, JSONUtil.toJsonStr(redisData));
    }

/*    *//**
     * 缓存击穿
     *//*
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        Shop shop = null;
        //2.判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)){
            //3.命中，直接返回
            shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断缓存是否是空值,不是空值那就是""
        if (shopJson != null){
            return null;
        }
        //4.实现缓存重建
        //4.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2 判断是否获取锁成功
            if (!isLock){
                //4.3 失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //4.成功，查询数据库
            shop = this.getById(id);
            // 判断商铺是否存在
            if (shop == null){
                // 将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //5.存在，写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //6.释放锁
            unlock(lockKey);
        }
        return shop;
    }    */

    /**
     * 缓存穿透
     */
    /*
    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 1. 从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        Shop shop = null;
        //2.判断缓存是否命中
        if (StrUtil.isNotBlank(shopJson)){
            //3.命中，直接返回
            shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断缓存是否是空值,不是空值那就是""
        if (shopJson != null){
            return null;
        }
        //4.未命中，查询数据库
        shop = this.getById(id);
        // 判断商铺是否存在
        if (shop == null){
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //5.存在，写入redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    */
    /**
     * 获取互斥锁
     */
    /*
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // 拆箱要判空，防止空指针异常
        return BooleanUtil.isTrue(flag);
    }

    */
    /**
     * 释放锁
     */
    /*
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }*/


    /**
     * 修改商铺信息 , 先更新数据库，后删除缓存
     * @param shop 商铺数据
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空");
        }
        // 1.更新数据库
        this.updateById(shop);
        // 2.删除缓存
        // 现在不需要删除缓存了，由canal监听数据库的变化，然后更新缓存
//        stringRedisTemplate.delete(CACHE_SHOP_KEY+id);

        // 如果使用逻辑过期解决缓存击穿，需要同时更新一下缓存
//        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),
//                CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return Result.ok();
    }

    /**
     * 根据类型分页查询商铺信息
     * @param typeId 商铺类型
     * @param current 页码
     * @param x 经度
     * @param y 纬度
     * @return
     */
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.判断是否需要根据坐标进行查询
        if(x == null || y == null){
            Page<Shop> page = query().eq("type_id", typeId).page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        //2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        //3.使用georadius查询redis，按照距离排序，分页 geosearch bylonlat x y byredius 10 (km/m) withdistance
        String typeKey = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .radius(
                        typeKey,
                        new Circle(new Point(x, y), new Distance(5000, Metrics.KILOMETERS)),
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().sortAscending().limit(end)
                );

        //4.解析出id
        if(results == null){
            return Result.ok(Collections.emptyList());
        }

        //4.1获取结果列表(店铺Id+distance)
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();

        //检查是否超出范围
        if(content.size() <= from){
            // 没有下一页
            return Result.ok(Collections.emptyList());
        }

        //4.2.截取from-end部分
        List<Long> ids = new ArrayList<>(content.size());
        Map<String, Distance> distMap = new HashMap<>(content.size());

        content.stream().skip(from).forEach(result -> {
            //4.2.1 获取店铺Id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));

            //4.2.2 获取距离
            Distance distance = result.getDistance();
            distMap.put(shopIdStr, distance);
        });

        //5.根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("order by field(id," + idStr + ")").list();

        //设置距离信息
        for (Shop shop : shops) {
            shop.setDistance(distMap.get(shop.getId().toString()).getValue());
        }

        return Result.ok(shops);
    }
}
