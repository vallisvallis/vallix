package com.alphay.boot.bpm.api.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * BTC价格统计结果实体
 * 用于统计每条K线数据未来某个时间窗口后的价格变化
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PriceChangeStat {

    /**
     * 当前K线开始时间（毫秒时间戳）
     */
    private Long openTime;

    /**
     * 当前收盘价
     */
    private Double currentClose;

    /**
     * 未来某个时间窗口后的收盘价
     */
    private Double futureClose;

    /**
     * 价格变化百分比
     */
    private Double changePercent;

    /**
     * 价格变化方向 (UP-上涨/DOWN-下跌/FLAT-持平)
     */
    private String direction;

    /**
     * 时间窗口（分钟）: 10, 30, 60, 1440(1天)
     */
    private Integer windowMinutes;
}