package com.alphay.boot.bpm.api.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 特征统计数据
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeatureStats {

    /**
     * 平均涨幅（当前K线）
     */
    private Double avgPriceChange;

    /**
     * 平均成交量
     */
    private Double avgVolume;

    /**
     * 平均成交量变化率
     */
    private Double avgVolumeChangeRate;

    /**
     * 平均波动幅度
     */
    private Double avgVolatility;

    /**
     * 平均主动买入占比
     */
    private Double avgTakerBuyRatio;

    /**
     * 阳线比例
     */
    private Double bullishRatio;

    /**
     * 平均成交笔数
     */
    private Double avgTrades;

    // ========== 新增特征 ==========

    /**
     * 平均实体比率（实体长度/波动幅度）
     */
    private Double avgBodyRatio;

    /**
     * 平均上影线比率
     */
    private Double avgUpperShadowRatio;

    /**
     * 平均下影线比率
     */
    private Double avgLowerShadowRatio;

    /**
     * 平均成交额
     */
    private Double avgQuoteVolume;

    /**
     * 平均成交额变化率
     */
    private Double avgQuoteVolumeChangeRate;

    /**
     * 平均主动买入成交额占比
     */
    private Double avgTakerBuyQuoteRatio;

    /**
     * 平均每笔成交量
     */
    private Double avgVolumePerTrade;

    /**
     * 平均波动率变化率
     */
    private Double avgVolatilityChangeRate;

    /**
     * 平均价格变化率（与前一根K线比较）
     */
    private Double avgPriceChangeRate;

    /**
     * 锤子线比例（下影线长，实体小，无上影线）
     */
    private Double hammerRatio;

    /**
     * 流星线比例（上影线长，实体小，无下影线）
     */
    private Double shootingStarRatio;

    /**
     * 平均持仓量变化率（基于成交量和主动买入）
     */
    private Double avgPositionChangeRate;
}