package com.hmall.item.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmall.item.domain.dto.ItemDTO;
import com.hmall.item.domain.dto.OrderDetailDTO;
import com.hmall.item.domain.po.Item;

import java.util.Collection;
import java.util.List;


public interface IItemService extends IService<Item> {
    void deductStock(List<OrderDetailDTO> items);

    List<ItemDTO> queryItemByIds(Collection<Long> ids);

    void restoreStock(List<OrderDetailDTO> orderDetailDTOs);
}
