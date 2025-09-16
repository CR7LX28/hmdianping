package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.pagehelper.PageInfo;
import com.hmdp.constant.JwtClaimsConstant;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.PageResult;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.properties.JwtProperties;
import com.hmdp.service.IUserService;
import com.hmdp.utils.JwtUtil;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;
import com.github.pagehelper.PageHelper;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JwtProperties jwtProperties;
    /**
     * 发送验证码
     * @param phone  手机号
     * @param session session
     * @return 验证码发送结果
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号:利用util下RegexUtils进行正则验证
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式不正确!");
        }
        //2.生成验证码:导入hutool依赖，内有RandomUtil   六位验证码
        String code = RandomUtil.randomNumbers(6);
        //3.保存验证码到session
        // session.setAttribute("code",code);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //4.发送验证码
        log.info("验证码为: " + code);
        log.debug("发送短信验证码成功!");

        return Result.ok();

    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     * @return 登录结果
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        //校验手机
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //校验验证码（从redis中获取）
//        Object cacheCode = session.getAttribute("code");4
/*        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code = loginForm.getCode();
        if(cacheCode==null || !cacheCode.equals(code)){
            //不一致，报错
            return Result.fail("验证码错误");
        }*/
        //一致根据手机号查用户
        User user = query().eq("phone", phone).one();//mybatisplus写法
        //判断用户是否存在
        if(user==null){
            //不存在，创建用户并保存
            user = createUserWithPhone(loginForm.getPhone());
        }

        // 6.生成JWT
        Map<String,Object> claims = new HashMap<>();
        claims.put(JwtClaimsConstant.USER_ID,user.getId());
        String jwtToken = JwtUtil.createJWT(jwtProperties.getUserSecretKey(),
                jwtProperties.getUserTtl(),
                claims);

        //保存用户信息到redis
        // 随机生成token，作为登录令牌
//        String token = UUID.randomUUID().toString();
        String filePath = "E:\\JavaSE\\点评\\资料\\token2.txt"; // 文件路径

        try (FileWriter writer = new FileWriter(filePath, true)) { // true 表示追加模式
            writer.write(jwtToken + "\n"); // 写入token并换行
            System.out.println(jwtToken + "-----");
            System.out.println("Token 已追加到文件！");
        } catch (IOException e) {
            System.err.println("写入文件时出错: " + e.getMessage());
        }

        // 将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user,UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(), //beanToMap方法执行了对象到Map的转换
                CopyOptions.create()
                        .setIgnoreNullValue(true) //BeanUtil在转换过程中忽略所有null值的属性
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())); //对于每个字段值，它简单地调用toString()方法，将字段值转换为字符串。
/*        HashMap<Object, Object> userMap = new HashMap<>();
        userMap.put("id", userDTO.getId().toString());
        userMap.put("nickName", userDTO.getNickName());
        userMap.put("icon", userDTO.getIcon());*/
        //(3)存储到Redis
        String tokenKey = LOGIN_USER_KEY + userDTO.getId();
        userMap.put("jwttoken",jwtToken);
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);

        //设置有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL, TimeUnit.MINUTES);

        //返回token
        return Result.ok(jwtToken);
    }

    @Override
    public Result sign() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /**
     * 统计连续签到天数功能
     * @return
     */
    @Override
    public Result signCount() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        // 1. 获取本月截止至今天为止的签到记录 bitfield sign:1012:202507 get u(index) 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        //如果没有任何签到记录
        if (result == null || result.size() == 0) return Result.ok(0);
        Long number = result.get(0);
        if (number == null || number==0) return Result.ok(0);
        //使用number位运算求签到次数
        int count = 0;
        while (number>0){
            if ((number & 1)==1) {
                count++;
                number = number >> 1;
            }else break;
        }
        return Result.ok(count);
    }

    @Override
    public PageResult listByPage(int pageNo, int pageSize) {
        PageHelper.startPage(pageNo,pageSize);
//        List<User> users = userMapper.selectAll();

        PageInfo<User> pageInfo = new PageInfo<>(this.list());
        long total = pageInfo.getTotal();
        List<User> users = pageInfo.getList();

        return new PageResult(total,users);
    }


    private User createUserWithPhone(String phone){
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        //2。保存用户
        save(user);
        return user;
    }

}
