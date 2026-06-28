package com.alphay.boot.bpm.api.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 价格涨跌特征分析结果
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FeatureAnalysisResult {

    /**
     * 上涨样本数
     */
    private long upCount;

    /**
     * 下跌样本数
     */
    private long downCount;

    /**
     * 上涨样本的平均特征值
     */
    private FeatureStats upFeatureStats;

    /**
     * 下跌样本的平均特征值
     */
    private FeatureStats downFeatureStats;

    /**
     * 特征相关性分析（特征名 -> 与涨跌的相关系数）
     */
    private Map<String, Double> featureCorrelations;
}

