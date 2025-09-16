package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.support.SimpleTriggerContext;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PathVariable;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1.获取当前登录用户的id
        Long userId = UserHolder.getUser().getId();
        String followKey = "follows:" + userId;
        // 2.查询
        if (isFollow){//关注
            //保存数据到数据库
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSave = save(follow);
            if (isSave){
                //把当前用户关注的用户id放入redis的set集合中 sadd follows:userId（key） followerId（value）
                stringRedisTemplate.opsForSet().add(followKey,followUserId.toString());
            }
        }else{
            //取消关注,删除关注表中的相关信息
            boolean isRemove = remove(new LambdaQueryWrapper<Follow>().
                    eq(Follow::getUserId, userId).
                    eq(Follow::getFollowUserId, followUserId));
            if (isRemove) {
                //把当前用户关注的用户id从redis的set集合中移除
                stringRedisTemplate.opsForSet().remove(followKey,followUserId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 是否关注用户
     *
     * @param followUserId 关注用户的id
     * @return
     */
    @Override
    public Result isFollow(Long followUserId) {
        // 1.获取当前登录用户的id
        Long userId = UserHolder.getUser().getId();
        // 2.查询
        int count = this.count(new LambdaQueryWrapper<Follow>()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getFollowUserId, followUserId));

        return Result.ok(count>0);
    }

    @Override
    public Result followCommons(Long followUserId) {
        // 1、 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        String key1 = "follows:" + userId;
        String key2 = "follows:" + followUserId;//存放对方关注的人的key

        // 2、求交集
        Set<String> set = stringRedisTemplate.opsForSet().intersect(key1, key2);
        //没有交集，返回空集合
        if (set == null || set.isEmpty()) {
            //没有 intersection
            return Result.ok(Collections.emptyList());
        }
        // 3、解析出id数组
        List<Long> ids = set.stream().map(Long::valueOf).collect(Collectors.toList());

        // 4、根据id查询用户数组   也可以查询完一个一个遍历
        List<UserDTO> userDTOS = userService.listByIds(ids).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

}
