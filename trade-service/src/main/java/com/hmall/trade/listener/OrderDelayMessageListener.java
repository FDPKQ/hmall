package com.hmall.trade.listener;


import com.hmall.api.clients.PayClient;
import com.hmall.api.domain.dto.PayOrderDTO;
import com.hmall.trade.constants.MqConstants;
import com.hmall.trade.domain.po.Order;
import com.hmall.trade.service.IOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderDelayMessageListener {

    private final IOrderService orderService;
    private final PayClient payClient;

    /**
     * 监听延迟队列中的订单支付延迟消息。
     * 该方法用于处理因支付超时而需要延迟处理的订单。通过监听MQ中的特定消息，
     * 对应的订单状态进行更新，以确保订单处理的及时性和正确性。
     *
     * @param orderId 订单ID，用于根据ID查询和操作订单。
     */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = MqConstants.DELAY_ORDER_QUEUE_NAME),
            exchange = @Exchange(name = MqConstants.DELAY_EXCHANGE_NAME, delayed = "true"),
            key = MqConstants.DELAY_ORDER_KEY
    ))
    public void listenOrderDelayMessage(Long orderId) {
        // 根据订单ID查询订单信息
        Order order = orderService.getById(orderId);
        // 如果订单不存在或订单状态不是待支付，则直接返回
        if (order == null || order.getStatus() != 1) {
            return;
        }
        // 查询支付订单信息
        PayOrderDTO payOrder = payClient.queryPayOrderByBizOrderNo(orderId);
        // 如果支付订单存在且支付状态为已支付，则标记订单支付成功
        if (payOrder != null && payOrder.getStatus() == 3) {
            orderService.markOrderPaySuccess(orderId);
        } else {
            // 否则，取消订单
            orderService.cancelOrder(orderId);
        }
    }

}