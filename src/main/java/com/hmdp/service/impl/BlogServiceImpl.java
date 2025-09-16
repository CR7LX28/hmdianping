package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jodd.io.StreamUtil;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private UserServiceImpl userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {
        // 1.查询blog
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在！");
        }
        // 2.查询blog有关的用户
        queryBlogUser(blog);
        // 3.查询blog是否被点赞
        isLikeBlog(blog);
        return Result.ok(blog);
    }


    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isLikeBlog(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        //1.判断当前用户是否已点赞
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null){
            //2.未点赞：数据库赞+1
            boolean isSuccess = update().setSql("liked = liked +  1").eq("id", id).update();
            //3.用户信息保存到Redis的点赞set
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(), System.currentTimeMillis());
            }
        }
        else{
            //4.已点赞:数据库-1
            boolean isSuccess = update().setSql("liked = liked -  1").eq("id", id).update();
            //5.把用户信息从Redis的点赞set移除
            if(isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询点赞用户
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        // 1.查询top5的点赞用户  zrange key 0 4,从缓存中获取前五个
        String key = BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        //如果为空，返回空集合
        if (top5==null || top5.isEmpty()) return Result.ok(Collections.emptyList());
        //2.获取用户id,然后根据userId查询到user,再转化为userDTO
        //将top5转换成List集合
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
//        List<User> users = userService.listByIds(ids); 可以遍历每个user，通过拷贝属性的方法拷贝成DTO返回
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> userDTOS = userService.query()
                .in("id",ids).last("ORDER BY FIELD(id,"+idStr+")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user,UserDTO.class))
                .collect(Collectors.toList());

        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 1. 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2. 保存探店博文
        save(blog);
        // 3. 查询粉丝,获取粉丝列表
        List<Follow> followIds = followService.list(new LambdaQueryWrapper<Follow>().eq(Follow::getFollowUserId, user.getId()));
        for (Follow followId : followIds) {
            Long followUserId = followId.getUserId();
            // 4. zadd key(粉丝账号） value（博客编号） score（时间戳)  推送到收件箱
            stringRedisTemplate.opsForZSet().add(FEED_KEY+followUserId,blog.getId().toString(),System.currentTimeMillis());
        }

        return Result.ok(blog.getId());
    }


    /**
     * 获取当前用户收件箱
     * @param max 上一次查询的最小时间戳
     * @param offset 偏移量
     * @return
     */
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询当前用户收件箱 zrevrangebyscore key max min limit offset count
        String feedKey = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(feedKey, 0, max, offset, 2);
        if(typedTuples==null||typedTuples.isEmpty()){
            return Result.ok();
        }
        //3.解析出收件箱中的blogId,score(时间戳)，offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int count = 1;//最小时间的相同个数
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            //3.1 获取id
            ids.add(Long.valueOf(typedTuple.getValue()));//blog的id
            //3.2 获取分数(时间戳)
            long time = typedTuple.getScore().longValue();
            // 集合中的分数（时间戳）都是非升序排列的
            if(time == minTime){
                count++;
            }else{
                minTime = time;
                count=1;
            }
        }
        //4.根据blogId查找blog
        String idStr = StrUtil.join(",",ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id, " + idStr + ")").list();

        for (Blog blog : blogs) {
            //4.1 查询blog有关的用户
            queryBlogUser(blog);
            //4.2 查询blog是否被点过赞
            isLikeBlog(blog);
        }

        //5.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(count);
        r.setMinTime(minTime);
        return Result.ok(r);
    }


    private void isLikeBlog(Blog blog) {
        // 1.获取登录用户的id
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询
            return;
        }
        Long userId = user.getId();
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //将当前用户的点赞状态保存到blog中
        blog.setIsLike(score!=null);
    }


    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
