package com.hmall.trade.listener;

import com.hmall.trade.service.IOrderService;
import com.hmall.trade.service.impl.OrderServiceImpl;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PayStatusListener {

    private final IOrderService orderService;

    public PayStatusListener(OrderServiceImpl orderServiceImpl) {
        this.orderService = orderServiceImpl;
    }

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "trade.pay.success.queue", durable = "true"),
            exchange = @Exchange(value = "pay.direct", type = "direct"),
            key = "pay.success"
    ))
    public void listenerPaySuccess(Long orderId) {
        orderService.markOrderPaySuccess(orderId);
    }

}
