package com.hmall.api.clients;

import com.hmall.api.domain.dto.PayOrderDTO;
import com.hmall.api.fallback.PayClientFallback;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;

@FeignClient(value = "pay-service", fallbackFactory = PayClientFallback.class)
public interface PayClient {
    @GetMapping("/pay-orders/biz/{id}")
    PayOrderDTO queryPayOrderByBizOrderNo(@PathVariable("id") Long id);

    @PutMapping("/pay-orders/biz/{id}/{status}")
    void updateOrderStatusByOrderId(@PathVariable("id") Long id,
                                    @PathVariable("status") Integer status);

}
