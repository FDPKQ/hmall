package com.hmall.api.fallback;


import com.hmall.api.clients.PayClient;
import com.hmall.api.domain.dto.PayOrderDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;


@Slf4j
public class PayClientFallback implements FallbackFactory<PayClient> {
    @Override
    public PayClient create(Throwable cause) {
        return new PayClient() {
            @Override
            public PayOrderDTO queryPayOrderByBizOrderNo(Long id) {
                log.error("queryPayOrderByBizOrderNo失败: ", cause);
                return null;
            }

            @Override
            public void updateOrderStatusByOrderId(Long id, Integer status) {
                log.error("updateOrderStatusByOrderId失败: ", cause);
            }
        };
    }


}