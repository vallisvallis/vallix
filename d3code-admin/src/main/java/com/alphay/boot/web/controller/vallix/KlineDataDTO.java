
package com.alphay.boot.web.controller.vallix;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * K线数据DTO（前端期望格式）
 * 用于将后端Vallisusdt实体转换为前端期望的字段格式
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KlineDataDTO {
    /** 开盘价 */
    private Double startPrice;
    
    /** 收盘价 */
    private Double endPrice;
    
    /** 最高价 */
    private Double maxPrice;
    
    /** 最低价 */
    private Double minPrice;
    
    /** 成交量 */
    private Double calcCount;
    
    /** K线开始时间 */
    private Long startTime;
    
    /** K线结束时间 */
    private Long endTime;
}
