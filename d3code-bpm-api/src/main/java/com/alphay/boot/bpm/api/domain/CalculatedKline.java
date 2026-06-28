package com.alphay.boot.bpm.api.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * K线计算特征容器（用于高性能矩阵式算法）
 * 一次解析，到处使用，避免频繁 String 转 Double
 * 
 * 规范：所有需要用到的字段一次性转换为 double，预计算好所有衍生特征
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalculatedKline {

    /**
     * K线开始时间
     */
    private Long openTime;

    /**
     * 收盘价
     */
    private double close;

    /**
     * 成交量
     */
    private double volume;

    /**
     * 主动买入成交量
     */
    private double takerBuyBase;

    // === 当前K线自身或基于历史的特征（自变量） ===

    /**
     * 当前分钟自身涨跌幅 = (close - open) / open * 100
     */
    private double priceChangeRate;

    /**
     * 成交量较真实上一分钟的变化率
     * = (当前volume - 前一根volume) / 前一根volume * 100
     */
    private double volChangeRate;

    /**
     * 主动买入成交量占比 = takerBuyBase / volume
     */
    private double takerBuyRatio;

    /**
     * K线实体比例 = |close - open| / (high - low)
     * 值越大说明实体越饱满，多空分歧越小
     */
    private double bodyRatio;

    /**
     * 上影线比例 = (high - Max(open, close)) / (high - low)
     * 值越大说明上方抛压越重
     */
    private double upperShadowRatio;

    /**
     * 下影线比例 = (Min(open, close) - low) / (high - low)
     * 值越大说明下方支撑越强
     */
    private double lowerShadowRatio;
}