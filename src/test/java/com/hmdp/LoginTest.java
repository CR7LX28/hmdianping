package com.hmdp;

import com.hmdp.dto.LoginFormDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
public class LoginTest {


    @Autowired
    private UserServiceImpl userService;

    /**
     * 测试登录功能
     */
    @Test
    public void testLogin() {
/*        // 先从数据库中查出所有用户，并保存为列表
        User user = userService.getById(1011);
            LoginFormDTO loginForm = new LoginFormDTO();
            loginForm.setPhone(user.getPhone());
            loginForm.setPassword(user.getPassword());
            userService.login(loginForm, null);*/
        // 获取所有用户
        List<User> users = userService.list();
        for (User user : users) {
            try {
                LoginFormDTO loginForm = new LoginFormDTO();
                loginForm.setPhone(user.getPhone());
                loginForm.setPassword(user.getPassword()); // 注意：确保密码是明文或逻辑上可登录
                userService.login(loginForm, null);
                System.out.println("登录成功：" + user.getPhone());
            } catch (Exception e) {
                System.err.println("登录失败：" + user.getPhone() + "，原因：" + e.getMessage());
            }
        }

    }
}
