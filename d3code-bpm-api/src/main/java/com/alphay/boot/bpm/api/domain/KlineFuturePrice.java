package com.alphay.boot.bpm.api.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * K线 OHLC 价格 + 主动买入比率
 * <p>
 * 从 vallisusdt 表直接查询，包含计算 RangePosition 所需的 OHLC 价格
 * 以及 takerBuyRatio（主动买入成交量 / 总成交量），用于二级过滤。
 *
 * @author d3code
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KlineFuturePrice {

    /** K线开始时间（毫秒时间戳） */
    private Long openTime;

    /** 开盘价 */
    private Double openPrice;

    /** 最高价 */
    private Double highPrice;

    /** 最低价 */
    private Double lowPrice;

    /** 收盘价（当前K线） */
    private Double closePrice;

    /**
     * 主动买入占比 = takerBuyBase / volume
     * 在 SQL 中直接计算好，避免 Java 侧二次遍历
     */
    private Double takerBuyRatio;

    /**
     * 成交量（基础资产）
     * 用于计算 volume/maVolume 相对量能比
     */
    private Double volume;
}