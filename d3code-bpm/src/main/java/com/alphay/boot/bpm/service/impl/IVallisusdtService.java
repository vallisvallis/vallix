package com.alphay.boot.bpm.service.impl;

import com.alphay.boot.bpm.api.domain.FeatureAnalysisResult;
import com.alphay.boot.bpm.api.domain.PositionBucket;
import com.alphay.boot.bpm.api.domain.PriceChangeStat;
import com.alphay.boot.bpm.api.domain.Vallisusdt;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Map;

/**
 * BTC K线数据Service接口
 *
 * @author d3code
 * @date 2025-05-26
 */
public interface IVallisusdtService extends IService<Vallisusdt> {

    /**
     * 查询K线数据列表
     *
     * @param vallisusdt K线数据
     * @return K线数据集合
     */
    public List<Vallisusdt> selectVallisusdtList(Vallisusdt vallisusdt);

    /**
     * 统计每条数据10分钟后的价格变化（默认窗口）
     *
     * @return 价格变化统计结果列表
     */
    public List<PriceChangeStat> calculatePriceChangeStats();

    /**
     * 统计每条数据指定时间窗口后的价格变化
     *
     * @param windowMinutes 时间窗口（分钟）: 10, 30, 60, 1440(1天)
     * @return 价格变化统计结果列表
     */
    public List<PriceChangeStat> calculatePriceChangeStats(int windowMinutes);

    /**
     * 统计价格变化方向分布（上涨/下跌/持平）
     *
     * @return 方向分布统计（UP/DOWN/FLAT -> 数量）
     */
    public Map<String, Long> calculateDirectionDistribution();

    /**
     * 统计指定时间窗口的价格变化方向分布
     *
     * @param windowMinutes 时间窗口（分钟）
     * @return 方向分布统计（UP/DOWN/FLAT -> 数量）
     */
    public Map<String, Long> calculateDirectionDistribution(int windowMinutes);

    /**
     * 分析上涨/下跌时的特征差异（默认10分钟窗口）
     * 找出价格上涨或下跌时的共同特点
     *
     * @return 特征分析结果
     */
    public FeatureAnalysisResult analyzePriceChangeFeatures();

    /**
     * 分析指定时间窗口上涨/下跌时的特征差异
     *
     * @param windowMinutes 时间窗口（分钟）
     * @return 特征分析结果
     */
    public FeatureAnalysisResult analyzePriceChangeFeatures(int windowMinutes);

    /**
     * 高性能量化特征分析
     * 核心算法：先在全局时间轴上算出所有特征，再根据未来窗口的涨跌分流样本
     *
     * @param windowMinutes 时间窗口（分钟）: 10, 30, 60, 1440
     * @param moveThresholdPercent 涨跌阈值（过滤横盘噪音，如 0.05 表示涨跌超过0.05%才算有效）
     * @return 特征分析结果
     */
    public FeatureAnalysisResult analyzePriceChangeFeatures(int windowMinutes, double moveThresholdPercent);

    /**
     * 事件合约回测：基于区间位置（RangePosition）统计未来固定窗口的涨跌胜率。
     * <p>
     * 核心逻辑：
     * <ol>
     *   <li>SQL 层用 LEAD() 窗口函数获取 futureClose，避免 Java O(n²) 嵌套循环</li>
     *   <li>Java 层单次滑动窗口计算 RangePosition（(Close-Low_N)/(High_N-Low_N)）</li>
     *   <li>按 0-20% / 20-40% / 40-60% / 60-80% / 80-100% 分桶统计胜率</li>
     *   <li>胜率 = 该方向获胜次数 / (UP+DOWN+FLAT)，FLAT 算作无效盈利</li>
     * </ol>
     *
     * @param lookbackPeriods 回看周期（用于计算 RangePosition 的 High_N / Low_N / MA_N）
     * @param forwardMinutes  前看窗口（事件合约结算周期，如 10、30、60、1440）
     * @return 五个位置档位的统计桶列表
     */
    public List<PositionBucket> analyzeEventContractWinRate(int lookbackPeriods, int forwardMinutes);

    /**
     * 逆向特征挖掘：从胜利样本反推共同特征画像
     * <p>
     * 核心思路（结果逆向工程）：
     * <ol>
     *   <li>先通过未来价格方向判定胜利样本（UP大胜 / DOWN大胜）</li>
     *   <li>逆向提取胜利样本在当前开仓瞬间的特征值：
     *       RangePosition / takerBuyRatio / volumeRatio(量能比)</li>
     *   <li>统计平均值、标准差、精细化分箱分布（每5%一箱）</li>
     *   <li>找出覆盖胜利样本 60% 以上的"黄金特征交集"</li>
     * </ol>
     * 
     * 架构铁律：所有特征提取严格模拟流式滑动窗口，严禁引入未来函数。
     *
     * @param lookbackPeriods 回看周期（窗口大小，用于计算 RangePosition 和 MA_Volume）
     * @param forwardMinutes  前看窗口（事件合约到期周期）
     */
    public void mineWinningSampleFeatures(int lookbackPeriods, int forwardMinutes);

    /**
     * 针对 30 分钟到期事件合约的专门逆向特征挖掘。
     * <p>
     * 固定参数：
     * <ul>
     *   <li>RangePosition 窗口：过去 60 分钟</li>
     *   <li>到期结算：T + 30 分钟收盘价</li>
     *   <li>VolumeRatio 窗口：过去 5 分钟均线</li>
     * </ul>
     * <p>
     * 架构铁律：所有特征计算只使用 ≤T 时刻的数据，严禁未来函数泄露。
     */
    public void mine30MinEventContractFeatures();
}