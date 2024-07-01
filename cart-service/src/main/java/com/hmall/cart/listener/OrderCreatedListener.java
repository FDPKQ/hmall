package com.hmall.cart.listener;


import com.hmall.cart.service.ICartService;
import com.hmall.cart.service.impl.CartServiceImpl;

import com.hmall.common.utils.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class OrderCreatedListener {
    private final ICartService cartService;

    public OrderCreatedListener(ICartService cartService) {
        this.cartService = cartService;
    }


    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "cart.clear.queue", durable = "true"),
            exchange = @Exchange(value = "trade.topic", type = "topic"),
            key = "order.create"
    ))
    public void listenerOrderCreated(Message message, List<Long> ids) {
        Long userId = message.getMessageProperties().getHeader("user_INFO");
        UserContext.setUser(userId);
        cartService.removeByItemIds(ids);
        UserContext.removeUser();
    }
}
