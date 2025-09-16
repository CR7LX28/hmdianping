package com.hmdp.config;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import javax.annotation.Resource;
import java.io.IOException;

@Configuration
public class RabbitMQConfig {

    @Resource
    @Lazy
    IVoucherOrderService voucherOrderService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "direct.seckill.queue"),
            key = "direct.seckill",
            exchange = @Exchange(name = "hmdianping.direct", type = ExchangeTypes.DIRECT)
    ))
    public void recieveMessage(Message message, Channel channel, VoucherOrder voucherOrder){
        try {
            voucherOrderService.handleVoucherOrder(voucherOrder);
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        System.out.println("监听到了"+message);
    }



    @Bean
    public MessageConverter messageConverter() {
        // 1. 定义消息转换器
        Jackson2JsonMessageConverter jjmc = new Jackson2JsonMessageConverter();
        // 2. 设置自动创建消息id，用于识别不同消息，也可以在业务中基于ID判断是否是重复消息
        jjmc.setCreateMessageIds(true);
        return jjmc;
    }
}
