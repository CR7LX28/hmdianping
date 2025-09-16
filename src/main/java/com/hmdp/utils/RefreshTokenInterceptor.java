package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.constant.JwtClaimsConstant;
import com.hmdp.dto.UserDTO;
import com.hmdp.properties.JwtProperties;
import io.jsonwebtoken.Claims;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor{

    private final StringRedisTemplate stringRedisTemplate;
    private final JwtProperties jwtProperties;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate,JwtProperties jwtProperties){
        this.stringRedisTemplate = stringRedisTemplate;
        this.jwtProperties = jwtProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1.获取请求头中的token
//        String token = request.getHeader("authorization");
        String token = request.getHeader(jwtProperties.getUserTokenName());
        if (StrUtil.isBlank(token)){
            return true;
        }
        //解析 token JWT工具
        Claims claims = JwtUtil.parseJWT(jwtProperties.getUserSecretKey(),token);
        Long userId = claims.get(JwtClaimsConstant.USER_ID, Long.class);
        //2.基于token获取redis中用户
        String key=RedisConstants.LOGIN_USER_KEY + userId;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        //3.判断用户是否存在
        if (userMap.isEmpty()){  // 不存在， 直接放行
            return true;
        }
        // 4.判断token是否一致,防止有以前生成的jwt，仍然能够登录
        String jwttoken = userMap.get("jwttoken").toString();
        if(!jwttoken.equals(token)){  // 不一致， 不更新redis有效期并且不保存用户到 ThreadLocal
            return true;
        }
        //5.将查询到的Hash数据转化为UserDTO对象
        UserDTO userDTO=new UserDTO();
        BeanUtil.fillBeanWithMap(userMap,userDTO, false);
        //6.保存用户信息到ThreadLocal
        UserHolder.saveUser(userDTO);
        //7.更新token的有效时间，只要用户还在访问我们就需要更新token的存活时间
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //8.放行
        return true;
    }


    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //销毁，以免内存泄漏
        UserHolder.removeUser();
    }
}
