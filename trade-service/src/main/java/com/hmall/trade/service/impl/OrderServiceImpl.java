package com.hmall.trade.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmall.api.clients.ItemClient;
import com.hmall.api.clients.PayClient;
import com.hmall.api.domain.dto.ItemDTO;
import com.hmall.api.domain.dto.OrderDetailDTO;
import com.hmall.common.exception.BadRequestException;
import com.hmall.common.utils.UserContext;

import com.hmall.trade.constants.MqConstants;
import com.hmall.trade.domain.dto.OrderFormDTO;
import com.hmall.trade.domain.po.Order;
import com.hmall.trade.domain.po.OrderDetail;
import com.hmall.trade.enums.PayStatus;
import com.hmall.trade.mapper.OrderMapper;
import com.hmall.trade.service.IOrderDetailService;
import com.hmall.trade.service.IOrderService;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl extends ServiceImpl<OrderMapper, Order> implements IOrderService {

    private final ItemClient itemClient;
    private final IOrderDetailService detailService;
    private final RabbitTemplate rabbitTemplate;
    private final PayClient payClient;

    /**
     * 创建订单。
     * 使用全局事务确保订单及相关细节的原子性操作。
     *
     * @param orderFormDTO 订单表单数据传输对象，包含订单信息和详情。
     * @return 创建的订单ID。
     * @throws BadRequestException 如果商品不存在或库存不足，则抛出请求异常。
     */
    @Override
    @GlobalTransactional
    public Long createOrder(OrderFormDTO orderFormDTO) {
        // 初始化订单对象
        Order order = new Order();

        // 细化订单详情，统计每个商品的数量
        List<OrderDetailDTO> detailDTOS = orderFormDTO.getDetails();
        Map<Long, Integer> itemNumMap = detailDTOS.stream()
                .collect(Collectors.toMap(OrderDetailDTO::getItemId, OrderDetailDTO::getNum));

        // 获取所有商品ID，用于后续查询商品信息
        Set<Long> itemIds = itemNumMap.keySet();

        // 根据商品ID查询商品信息
        List<ItemDTO> items = itemClient.queryItemByIds(itemIds);
        // 检查商品是否存在，如果不存在或查询数量不匹配，则抛出异常
        if (items == null || items.size() < itemIds.size()) {
            throw new BadRequestException("商品不存在");
        }

        // 计算订单总金额
        int total = 0;
        for (ItemDTO item : items) {
            total += item.getPrice() * itemNumMap.get(item.getId());
        }
        order.setTotalFee(total);

        // 设置订单的支付方式、用户ID、状态和创建时间
        order.setPaymentType(orderFormDTO.getPaymentType());
        order.setUserId(UserContext.getUser());
        order.setStatus(1);
        order.setCreateTime(LocalDateTime.now());

        // 保存订单信息
        save(order);

        // 构建订单详情并保存
        List<OrderDetail> details = buildDetails(order.getId(), items, itemNumMap);
        detailService.saveBatch(details);

        // 尝试扣减商品库存，如果失败，则抛出异常
        try {
            itemClient.deductStock(detailDTOS);
        } catch (Exception e) {
            throw new RuntimeException("库存不足！");
        }

        // 发送消息到RabbitMQ，通知订单创建和延迟任务
        try {
            // 发送订单创建消息，包含用户信息
            rabbitTemplate.convertAndSend("trade.topic", "order.create", itemIds, message -> {
                message.getMessageProperties().setHeader("user_INFO", UserContext.getUser());
                return message;
            });

            // 发送订单支付延迟消息，延迟10秒处理
            rabbitTemplate.convertAndSend(
                    MqConstants.DELAY_EXCHANGE_NAME,
                    MqConstants.DELAY_ORDER_KEY,
                    order.getId(),
                    message -> {
                        message.getMessageProperties().setDelay(10000);
                        return message;
                    }
            );

        } catch (Exception e) {
            log.error("rabbitMQ send error {}", e.getMessage());
        }

        // 返回创建的订单ID
        return order.getId();
    }


    /**
     * 取消订单及其相关操作。
     * 使用全局事务确保订单状态更新、支付状态更新和库存恢复的一致性。
     *
     * @param orderId 需要取消的订单ID。
     * @throws RuntimeException 如果更新支付状态或恢复库存失败，则抛出运行时异常。
     */
    @Override
    @GlobalTransactional
    public void cancelOrder(Long orderId) {
        // 更新订单状态为取消（状态码5）。
        lambdaUpdate()
                .set(Order::getStatus, 5)
                .eq(Order::getId, orderId)
                .update();

        // 尝试更新支付状态为交易关闭。
        try {
            payClient.updateOrderStatusByOrderId(orderId, PayStatus.TRADE_CLOSED.getValue());
        } catch (Exception e) {
            throw new RuntimeException("更新订单状态失败", e);
        }

        // 查询该订单的所有订单详情。
        List<OrderDetail> list = detailService.lambdaQuery()
                .eq(OrderDetail::getOrderId, orderId)
                .list();

        // 如果订单详情为空，则无需进行后续操作。
        if (list == null || list.isEmpty())
            return;

        // 将订单详情转换为DTO列表，为库存恢复操作做准备。
        List<OrderDetailDTO> dtoList = list.stream().map(o -> {
            OrderDetailDTO dto = new OrderDetailDTO();
            BeanUtils.copyProperties(o, dto);
            return dto;
        }).collect(Collectors.toList());

        // 尝试恢复订单所涉及商品的库存。
        try {
            itemClient.restoreStock(dtoList);
        } catch (Exception e) {
            throw new RuntimeException("恢复库存失败");
        }
    }


    @Override
    public void markOrderPaySuccess(Long orderId) {
        Order order = new Order();
        order.setId(orderId);
        order.setStatus(2);
        order.setPayTime(LocalDateTime.now());
        updateById(order);
    }

    private List<OrderDetail> buildDetails(Long orderId, List<ItemDTO> items, Map<Long, Integer> numMap) {
        List<OrderDetail> details = new ArrayList<>(items.size());
        for (ItemDTO item : items) {
            OrderDetail detail = new OrderDetail();
            detail.setName(item.getName());
            detail.setSpec(item.getSpec());
            detail.setPrice(item.getPrice());
            detail.setNum(numMap.get(item.getId()));
            detail.setItemId(item.getId());
            detail.setImage(item.getImage());
            detail.setOrderId(orderId);
            details.add(detail);
        }
        return details;
    }
}
