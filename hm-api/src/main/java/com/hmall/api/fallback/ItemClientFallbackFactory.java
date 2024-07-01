package com.hmall.api.fallback;

import com.hmall.api.clients.ItemClient;
import com.hmall.api.domain.dto.ItemDTO;
import com.hmall.api.domain.dto.OrderDetailDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Slf4j
public class ItemClientFallbackFactory implements FallbackFactory<ItemClient> {
    @Override
    public ItemClient create(Throwable cause) {
        return new ItemClient() {
            @Override
            public List<ItemDTO> queryItemByIds(Collection<Long> ids) {
                log.error("查询商品失败", cause);
                return Collections.emptyList();
            }

            @Override
            public void deductStock(List<OrderDetailDTO> items) {
                log.error("扣减商品库存失败", cause);
                throw new RuntimeException(cause);
            }

            @Override
            public void restoreStock(List<OrderDetailDTO> orderDetailDTOs) {
                log.error("恢复商品库存失败", cause);
                throw new RuntimeException(cause);
            }


            @Override
            public ItemDTO queryItemById(Long id) {
                log.error("查询商品失败", cause);
                throw new RuntimeException(cause);
            }
        };
    }
}
