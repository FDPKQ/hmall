package com.hmall.search.controller;


import cn.hutool.core.util.StrUtil;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.hmall.common.domain.PageDTO;

import com.hmall.search.domain.dto.ItemDTO;
import com.hmall.search.domain.po.ItemDoc;
import com.hmall.search.domain.query.ItemPageQuery;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.lucene.search.function.CombineFunction;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilder;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.MultiBucketsAggregation;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.sort.SortBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Api(tags = "搜索相关接口")
@Slf4j
@RestController
@RequestMapping("/search")
public class SearchController {

    private final RestHighLevelClient client;
    private final static String ES_ITEMS_INDEX = "items";

    public SearchController() {
        this.client = new RestHighLevelClient(RestClient.builder(
                HttpHost.create("http://vm2.lan.luoxianjun.com:9200")
        ));
    }


    /**
     * 根据ID从Elasticsearch中获取商品信息。
     *
     * @param id 商品的唯一标识符。
     * @return 包含商品信息的DTO对象，如果检索失败则返回null。
     */
    @GetMapping("/{id}")
    public ItemDTO getById(@PathVariable Long id) {
        // 构建GetRequest对象，指定要检索的索引和文档ID。
        GetRequest request = new GetRequest(ES_ITEMS_INDEX).id(String.valueOf(id));
        GetResponse response;
        try {
            // 尝试从Elasticsearch客户端获取文档。
            response = client.get(request, RequestOptions.DEFAULT);
        } catch (IOException e) {
            // 记录检索过程中发生的IO异常。
            log.error("search error", e);
            return null;
        }
        // 从响应中提取原始JSON字符串，表示文档内容。
        String json = response.getSourceAsString();
        ItemDTO itemDTO = new ItemDTO();
        // 将JSON字符串转换为ItemDoc对象，并复制属性到ItemDTO对象中。
        BeanUtils.copyProperties(JSONUtil.toBean(json, ItemDoc.class), itemDTO);
        return itemDTO;
    }


    /**
     * 根据查询条件搜索商品信息。
     *
     * @param query 商品查询条件，包含分页和过滤条件。
     * @return 包含搜索结果的分页信息。
     * @throws IOException 如果搜索请求发生IO异常。
     */
    @ApiOperation("搜索商品")
    @GetMapping("/list")
    public PageDTO<ItemDoc> search(ItemPageQuery query) throws IOException {
        // 初始化ES搜索请求
        SearchRequest searchRequest = new SearchRequest(ES_ITEMS_INDEX);
        // 构建查询条件
        BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();

        // 设置广告商品的加权条件
        FunctionScoreQueryBuilder.FilterFunctionBuilder[] filterFunctionBuilder = {
                new FunctionScoreQueryBuilder.FilterFunctionBuilder(
                        QueryBuilders.termQuery("isAD", "true"),
                        ScoreFunctionBuilders.weightFactorFunction(10f)
                )};
        // 根据查询关键字构建匹配查询条件
        QueryBuilder qb = StrUtil.isNotBlank(query.getKey()) ? QueryBuilders.matchQuery("name", query.getKey()) : QueryBuilders.matchAllQuery();
        // 构建函数评分查询，用于对广告商品进行加权
        FunctionScoreQueryBuilder functionScoreQueryBuilder = QueryBuilders
                .functionScoreQuery(qb, filterFunctionBuilder)
                .boostMode(CombineFunction.MULTIPLY);
        queryBuilder.must(functionScoreQueryBuilder);

        // 填充额外的查询参数
        fillQueryArg(query, queryBuilder);

        // 设置搜索请求的查询条件和分页信息
        searchRequest
                .source()
                .query(queryBuilder)
                .size(query.getPageSize());

        // 设置搜索结果的排序条件
        List<OrderItem> orders = query.toMpPage("updateTime", false).orders();
        for (OrderItem orderItem : orders) {
            searchRequest.source().sort(orderItem.getColumn(), orderItem.isAsc() ? SortOrder.ASC : SortOrder.DESC);
        }

        // 执行搜索请求
        SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);
        SearchHits hits = response.getHits();

        // 计算搜索结果的总条数
        long total = 0;
        if (hits.getTotalHits() != null) {
            total = hits.getTotalHits().value;
        }

        // 计算总页数
        long pages = query.getPageNo();

        // 解析搜索结果，转换为商品文档对象
        SearchHit[] searchHits = hits.getHits();
        ArrayList<ItemDoc> itemDocs = new ArrayList<>();
        if (searchHits != null) {
            for (SearchHit hitsHit : searchHits) {
                itemDocs.add(JSONUtil.toBean(hitsHit.getSourceAsString(), ItemDoc.class));
            }
        }

        // 返回搜索结果的分页信息
        return new PageDTO<>(total, pages, itemDocs);
    }

    @PostMapping("/filters")
    public Map<String, List<String>> filters(ItemPageQuery query) throws IOException {

        SearchRequest searchRequest = new SearchRequest("items");
        BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery();

        if (StrUtil.isNotBlank(query.getKey())) {
            queryBuilder.must(QueryBuilders.matchQuery("name", query.getKey()));
        }
        fillQueryArg(query, queryBuilder);

        String categoryAgg = "category_agg";
        String brandAgg = "brand_agg";
        searchRequest.source().query(queryBuilder).aggregation(
                        AggregationBuilders.terms(categoryAgg).field("category"))
                .aggregation(AggregationBuilders.terms(brandAgg).field("brand"));

        SearchResponse response = client.search(searchRequest, RequestOptions.DEFAULT);

        Map<String, List<String>> resultMap = new HashMap<>();
        Terms terms = response.getAggregations().get(categoryAgg);
        if (terms != null) {
            resultMap.put("category", terms.getBuckets().stream().map(MultiBucketsAggregation.Bucket::getKeyAsString).collect(Collectors.toList()));
        }
        terms = response.getAggregations().get(brandAgg);
        if (terms != null) {
            resultMap.put("brand", terms.getBuckets().stream().map(MultiBucketsAggregation.Bucket::getKeyAsString).collect(Collectors.toList()));
        }

        // 封装并返回
        return resultMap;
    }

    private static void fillQueryArg(ItemPageQuery query, BoolQueryBuilder queryBuilder) {
        if (StrUtil.isNotBlank(query.getBrand())) {
            queryBuilder.filter(QueryBuilders.termQuery("brand", query.getBrand()));
        }
        if (StrUtil.isNotBlank(query.getCategory())) {
            queryBuilder.filter(QueryBuilders.matchQuery("category", query.getCategory()));
        }
        if (query.getMaxPrice() != null) {
            queryBuilder.filter(QueryBuilders.rangeQuery("price").gte(query.getMinPrice()).lte(query.getMaxPrice()));
        }
    }
}
