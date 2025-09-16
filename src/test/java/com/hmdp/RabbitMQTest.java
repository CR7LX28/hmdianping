package com.hmdp;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
public class RabbitMQTest {
    @Resource
    RabbitTemplate rabbitTemplate;
    @Test
    public void testSendMessage(){
        rabbitTemplate.convertAndSend("hmdianping.direct","direct.seckill","测试发送消息");
    }

}
