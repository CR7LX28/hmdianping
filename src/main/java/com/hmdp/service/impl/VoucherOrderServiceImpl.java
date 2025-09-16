package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.concurrent.ListenableFutureCallback;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    // 代理对象
    private IVoucherOrderService proxy;

    //加载" 判断秒杀券库存是否充足 并且判断用户是否已下单 "的Lua脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //========================使用阻塞队列实现异步秒杀==========================
/*    //存储订单的阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);

    // 类加载后就持续从阻塞队列出取出订单信息
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //线程任务，从阻塞队列中获取订单
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while ( true){
                VoucherOrder voucherOrder = null;
                try {
                    voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    throw new RuntimeException(e);
                }
            }
        }
    }*/
//===============================================================================
    /**
     * 创建订单
     * @param voucherOrder
     */
    @Transactional
    public void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean hasLock = lock.tryLock();
        if (!hasLock) {
            log.error("不允许重复下单！");
            return;
        }
        try {
            proxy = (IVoucherOrderService) AopContext.currentProxy();
            proxy.createVoucherOrder(voucherOrder);//默认是this,我们要实现事务需要proxy
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    // ============================使用RabbitMQ实现异步秒杀==========================
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取当前用户
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        //2.判断结果是否为0，为0就是成功，可下单，下单信息保存到阻塞队列
        if (result != null && !result.equals(0L)) {
            return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
        }
        //3.走到这一步说明有购买资格，将订单信息存到消息队列
        // 创建订单
        //6.返回订单id
        VoucherOrder voucherOrder = new VoucherOrder();
        //6.1订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //6.2用户id
        voucherOrder.setUserId(userId);
        //6.3代金券id
        voucherOrder.setVoucherId(voucherId);

        //将订单放入阻塞队列 orderTasks.add(voucherOrder);

        //4. 存入消息队列等待异步消费
        // 4.1 创建CorrelationData
        CorrelationData correlationData = new CorrelationData();
        // 4.2 给Future添加ConfirmCallback
        correlationData.getFuture().addCallback(new ListenableFutureCallback<CorrelationData.Confirm>() {
            @Override
            public void onFailure(Throwable throwable) {
                log.error("消息发送失败,发生异常" + throwable.getMessage());
            }

            @Override
            public void onSuccess(CorrelationData.Confirm confirm) {
                if (confirm.isAck()) {
                    log.debug("消息发送成功,收到ack");
                }else {
                    log.error("消息发送失败,收到nack"+confirm.getReason());
                }
            }
        });
        rabbitTemplate.convertAndSend("hmdianping.direct",
                "direct.seckill",voucherOrder,correlationData);

        //获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //返回订单id
        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        //查询订单看看是否存在
//        Long userId = UserHolder.getUser().getId();   由于此时已经在子线程中，所以无法从UserHolder中获取用户id
/*        Long userId = voucherOrder.getUserId();
        if (query().eq("user_id",userId).eq("voucher_id", voucherOrder.getUserId()).count()>0) {
            log.error("用户已经购买过一次!");
            return;
        }*/

        //4.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock",0)//where id = ? and stock >0 添加了乐观锁
                .update();

        if(!success){
            log.error("优惠券库存不足!");
            return;
        }

        //7.订单写入数据库
        save(voucherOrder);

    }

}
