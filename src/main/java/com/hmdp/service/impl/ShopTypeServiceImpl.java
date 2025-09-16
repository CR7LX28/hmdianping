package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询所有商铺类型 添加查询缓存版
     * @return 商铺类型列表
     */
    @Override
    public Result queryTypeList() {
        String key = CACHE_SHOP_TYPE_KEY;
        // 1.  从redis中查询商铺缓存
        String shopType = stringRedisTemplate.opsForValue().get(key);

        List<ShopType> shopTypeList = null;
        // 2.判断缓存是否命中
        if (!StrUtil.isBlank(shopType)) {
            // 3. 缓存命中，直接返回
            shopTypeList = JSONUtil.toList(shopType, ShopType.class);
            return Result.ok(shopTypeList);
        }
        //4.  未命中，查询数据库(升序排列）
        shopTypeList = this.list(new LambdaQueryWrapper<ShopType>()
                .orderByAsc(ShopType::getSort));

        // 判断list是否存在
        if (shopTypeList == null){
            return Result.fail("查询失败");
        }
        // 5. 存在写入缓存
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shopTypeList),
                CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);

        return Result.ok(shopTypeList);
    }
}
