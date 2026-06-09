package com.alphay.boot.web.controller.vallix;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * BTC特征工程服务
 * 实现完整的量化特征计算和预测逻辑
 */
@Service
@Slf4j
public class BtcFeatureEngineeringService {

    // ==================== 数据模型 ====================

    @Data
    public static class FeatureData {
        // 时间戳
        private Long timestamp;

        // ===== 价格类特征 =====
        private Double priceSlope1m;      // 1分钟价格斜率
        private Double priceSlope3m;      // 3分钟价格斜率
        private Double priceSlope5m;      // 5分钟价格斜率

        private Double ret1m;             // 1分钟涨跌幅
        private Double ret3m;             // 3分钟涨跌幅
        private Double ret5m;             // 5分钟涨跌幅

        private Double position1m;        // 1分钟区间分位
        private Double position3m;        // 3分钟区间分位
        private Double position5m;        // 5分钟区间分位

        private Double ema9;              // EMA9
        private Double ema21;             // EMA21
        private Double diffEma9;          // 价格相对EMA9差值
        private Double diffEma21;         // 价格相对EMA21差值
        private Integer maAlignment;      // 均线排列: 1多头, 0空头
        private Double ema9Slope;         // EMA9斜率
        private Double ema21Slope;        // EMA21斜率

        private Boolean isHigh30s;        // 是否为30秒局部高点
        private Boolean isLow30s;         // 是否为30秒局部低点

        // ===== 量能类特征 =====
        private Double volRatio5m;        // 5分钟相对成交量
        private String volPricePattern;   // 量价匹配模式

        // ===== 动量指标 =====
        private Double rsi14;             // RSI(14)
        private Double rsiDelta;          // RSI变化量

        // ===== 波动率特征 =====
        private Double volatility1m;      // 1分钟波动率
        private Double volatility3m;      // 3分钟波动率
        private Double volatility5m;      // 5分钟波动率

        // ===== 预测标签 =====
        private Integer trueLabel;        // 真实标签: 1上涨, 0下跌
    }

    @Data
    public static class PredictionResult {
        private Long timestamp;
        private FeatureData features;

        // 单因子预测
        private Integer predInertia;      // 惯性因子预测
        private Integer predPosition;     // 位置因子预测
        private Integer predMA;           // 均线因子预测
        private Integer predRSI;          // RSI因子预测
        private Integer predVol;          // 量能因子预测

        // 组合预测
        private Integer predCombo1;       // 组合1预测
        private Integer predCombo2;       // 组合2预测
        private Integer predCombo3;       // 组合3预测

        // 真实结果
        private Integer trueLabel;
        private Boolean isValid;          // 是否为有效样本
    }

    @Data
    public static class StatisticsResult {
        // 单因子统计
        private FactorStats inertiaStats;
        private FactorStats positionStats;
        private FactorStats maStats;
        private FactorStats rsiStats;
        private FactorStats volStats;
        
        // 组合统计
        private FactorStats combo1Stats;
        private FactorStats combo2Stats;
        private FactorStats combo3Stats;
        private FactorStats combo4Stats;  // 趋势跟踪
        private FactorStats combo5Stats;  // 均值回归
        private FactorStats combo6Stats;  // 动量突破
        private FactorStats combo7Stats;  // 多重共振
        
        private int totalSamples;
        private int validSamples;
    }

    @Data
    public static class FactorStats {
        private String factorName;
        private int totalPredictions;
        private int correctPredictions;
        private int longPredictions;      // 预测上涨次数
        private int longCorrect;          // 预测上涨正确次数
        private int shortPredictions;     // 预测下跌次数
        private int shortCorrect;         // 预测下跌正确次数

        public double getAccuracy() {
            return totalPredictions > 0 ? (double) correctPredictions / totalPredictions * 100 : 0;
        }

        public double getLongAccuracy() {
            return longPredictions > 0 ? (double) longCorrect / longPredictions * 100 : 0;
        }

        public double getShortAccuracy() {
            return shortPredictions > 0 ? (double) shortCorrect / shortPredictions * 100 : 0;
        }
    }

    // ==================== 核心特征计算方法 ====================

    /**
     * 计算价格斜率（线性回归）
     */
    public double calculatePriceSlope(List<Double> prices) {
        if (prices == null || prices.size() < 2) return 0;

        int n = prices.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;

        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += prices.get(i);
            sumXY += i * prices.get(i);
            sumX2 += i * i;
        }

        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
        return slope;
    }

    /**
     * 计算区间涨跌幅
     */
    public double calculateReturn(List<Double> prices, int window) {
        if (prices == null || prices.size() < window + 1) return 0;

        double currentPrice = prices.get(prices.size() - 1);
        double pastPrice = prices.get(prices.size() - 1 - window);

        return (currentPrice - pastPrice) / pastPrice * 100;
    }

    /**
     * 计算价格在区间的分位
     */
    public double calculatePosition(List<Double> prices, int window) {
        if (prices == null || prices.size() < window) return 0.5;

        List<Double> windowPrices = prices.subList(prices.size() - window, prices.size());
        double currentPrice = prices.get(prices.size() - 1);

        double high = windowPrices.stream().mapToDouble(Double::doubleValue).max().orElse(currentPrice);
        double low = windowPrices.stream().mapToDouble(Double::doubleValue).min().orElse(currentPrice);

        if (high == low) return 0.5;

        return (currentPrice - low) / (high - low);
    }

    /**
     * 计算EMA（指数移动平均）
     */
    public double calculateEMA(List<Double> prices, int period) {
        if (prices == null || prices.size() < period) return 0;

        double multiplier = 2.0 / (period + 1);
        double ema = prices.get(0);

        for (int i = 1; i < prices.size(); i++) {
            ema = (prices.get(i) - ema) * multiplier + ema;
        }

        return ema;
    }

    /**
     * 计算RSI
     */
    public double calculateRSI(List<Double> prices, int period) {
        if (prices == null || prices.size() < period + 1) return 50;

        double gainSum = 0, lossSum = 0;

        for (int i = prices.size() - period; i < prices.size(); i++) {
            double change = prices.get(i) - prices.get(i - 1);
            if (change > 0) {
                gainSum += change;
            } else {
                lossSum += Math.abs(change);
            }
        }

        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;

        if (avgLoss == 0) return 100;

        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    /**
     * 计算波动率（标准差/均值）
     */
    public double calculateVolatility(List<Double> prices, int window) {
        if (prices == null || prices.size() < window) return 0;

        List<Double> windowPrices = prices.subList(prices.size() - window, prices.size());
        double mean = windowPrices.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        if (mean == 0) return 0;

        double variance = windowPrices.stream()
                .mapToDouble(p -> Math.pow(p - mean, 2))
                .average()
                .orElse(0);

        double stdDev = Math.sqrt(variance);
        return stdDev / mean * 100;
    }

    /**
     * 判断是否为局部极值
     */
    public boolean isLocalHigh(List<Double> prices, int window) {
        if (prices == null || prices.size() < window) return false;

        double currentPrice = prices.get(prices.size() - 1);
        List<Double> windowPrices = prices.subList(prices.size() - window, prices.size() - 1);

        return windowPrices.stream().allMatch(p -> p <= currentPrice);
    }

    public boolean isLocalLow(List<Double> prices, int window) {
        if (prices == null || prices.size() < window) return false;

        double currentPrice = prices.get(prices.size() - 1);
        List<Double> windowPrices = prices.subList(prices.size() - window, prices.size() - 1);

        return windowPrices.stream().allMatch(p -> p >= currentPrice);
    }

    // ==================== 特征提取主方法 ====================

    /**
     * 从K线数据提取所有特征
     */
    public List<FeatureData> extractFeatures(List<Vallisusdt> klineData, int predictWindow) {
        List<FeatureData> features = new ArrayList<>();
        
        if (klineData == null || klineData.size() < 30) {
            log.warn("数据不足，无法提取特征");
            return features;
        }
        
        // 预提取价格序列和成交量序列，避免重复转换
        double[] prices = new double[klineData.size()];
        double[] volumes = new double[klineData.size()];
        
        for (int i = 0; i < klineData.size(); i++) {
            prices[i] = Double.parseDouble(klineData.get(i).getEndPrice());
            volumes[i] = Double.parseDouble(klineData.get(i).getCalcCount());
        }
        
        // 预计算EMA数组，避免重复计算
        double[] ema9Array = calculateEMAArray(prices, 9);
        double[] ema21Array = calculateEMAArray(prices, 21);
        
        // 遍历每个时间点（预留未来数据用于计算标签）
        for (int i = 20; i < klineData.size() - predictWindow; i++) {
            FeatureData feature = new FeatureData();
            feature.setTimestamp(klineData.get(i).getStartTime().getTime());
            
            // ===== 价格类特征 =====
            // 优化：直接使用数组切片，减少List操作
            feature.setPriceSlope1m(calculatePriceSlopeFast(prices, i, 1));
            feature.setPriceSlope3m(calculatePriceSlopeFast(prices, i, 3));
            feature.setPriceSlope5m(calculatePriceSlopeFast(prices, i, 5));
            
            feature.setRet1m(calculateReturnFast(prices, i, 1));
            feature.setRet3m(calculateReturnFast(prices, i, 3));
            feature.setRet5m(calculateReturnFast(prices, i, 5));
            
            feature.setPosition1m(calculatePositionFast(prices, i, 1));
            feature.setPosition3m(calculatePositionFast(prices, i, 3));
            feature.setPosition5m(calculatePositionFast(prices, i, 5));
            
            // 使用预计算的EMA
            feature.setEma9(ema9Array[i]);
            feature.setEma21(ema21Array[i]);
            
            double currentPrice = prices[i];
            feature.setDiffEma9(currentPrice - feature.getEma9());
            feature.setDiffEma21(currentPrice - feature.getEma21());
            feature.setMaAlignment(feature.getEma9() > feature.getEma21() ? 1 : 0);
            
            // EMA斜率（简化计算）
            feature.setEma9Slope(i >= 3 ? (ema9Array[i] - ema9Array[i-3]) / 3 : 0);
            feature.setEma21Slope(i >= 3 ? (ema21Array[i] - ema21Array[i-3]) / 3 : 0);
            
            // 局部极值（简化）
            feature.setIsHigh30s(i > 0 && prices[i] > prices[i-1]);
            feature.setIsLow30s(i > 0 && prices[i] < prices[i-1]);
            
            // ===== 量能特征 =====
            if (i >= 5) {
                double avgVol = 0;
                for (int j = i - 5; j <= i; j++) {
                    avgVol += volumes[j];
                }
                avgVol /= 6;
                
                double currentVol = volumes[i];
                feature.setVolRatio5m(avgVol > 0 ? currentVol / avgVol : 1);
                
                // 量价匹配
                double priceChange = i > 0 ? (prices[i] - prices[i - 1]) / prices[i - 1] : 0;
                if (priceChange > 0 && feature.getVolRatio5m() > 1) {
                    feature.setVolPricePattern("价涨量增");
                } else if (priceChange > 0 && feature.getVolRatio5m() <= 1) {
                    feature.setVolPricePattern("价涨量缩");
                } else if (priceChange < 0 && feature.getVolRatio5m() > 1) {
                    feature.setVolPricePattern("价跌量增");
                } else {
                    feature.setVolPricePattern("价跌量缩");
                }
            }
            
            // ===== RSI（优化计算）=====
            feature.setRsi14(calculateRSIFast(prices, i, 14));
            if (i > 0) {
                double prevRsi = calculateRSIFast(prices, i - 1, 14);
                feature.setRsiDelta(feature.getRsi14() - prevRsi);
            }
            
            // ===== 波动率 =====
            feature.setVolatility1m(calculateVolatilityFast(prices, i, 1));
            feature.setVolatility3m(calculateVolatilityFast(prices, i, 3));
            feature.setVolatility5m(calculateVolatilityFast(prices, i, 5));
            
            // ===== 真实标签（5分钟后价格变化）=====
            double futurePrice = prices[i + predictWindow];
            feature.setTrueLabel(futurePrice > currentPrice ? 1 : 0);
            
            features.add(feature);
        }
        
        log.info("特征提取完成，共{}个样本", features.size());
        return features;
    }
    
    /**
     * 快速计算价格斜率（使用数组）
     */
    private double calculatePriceSlopeFast(double[] prices, int currentIndex, int window) {
        if (currentIndex < window) return 0;
        
        int n = window + 1;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        
        for (int i = 0; i < n; i++) {
            double x = i;
            double y = prices[currentIndex - window + i];
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }
        
        double denominator = n * sumX2 - sumX * sumX;
        if (denominator == 0) return 0;
        
        return (n * sumXY - sumX * sumY) / denominator;
    }
    
    /**
     * 快速计算涨跌幅
     */
    private double calculateReturnFast(double[] prices, int currentIndex, int window) {
        if (currentIndex < window) return 0;
        
        double currentPrice = prices[currentIndex];
        double pastPrice = prices[currentIndex - window];
        
        if (pastPrice == 0) return 0;
        
        return (currentPrice - pastPrice) / pastPrice * 100;
    }
    
    /**
     * 快速计算区间分位
     */
    private double calculatePositionFast(double[] prices, int currentIndex, int window) {
        if (currentIndex < window) return 0.5;
        
        double currentPrice = prices[currentIndex];
        double high = Double.MIN_VALUE;
        double low = Double.MAX_VALUE;
        
        for (int i = currentIndex - window; i <= currentIndex; i++) {
            if (prices[i] > high) high = prices[i];
            if (prices[i] < low) low = prices[i];
        }
        
        if (high == low) return 0.5;
        
        return (currentPrice - low) / (high - low);
    }
    
    /**
     * 预计算EMA数组
     */
    private double[] calculateEMAArray(double[] prices, int period) {
        double[] ema = new double[prices.length];
        double multiplier = 2.0 / (period + 1);
        
        // 第一个EMA使用简单移动平均
        double sum = 0;
        for (int i = 0; i < period && i < prices.length; i++) {
            sum += prices[i];
        }
        ema[period - 1] = sum / period;
        
        // 后续使用EMA公式
        for (int i = period; i < prices.length; i++) {
            ema[i] = (prices[i] - ema[i-1]) * multiplier + ema[i-1];
        }
        
        // 填充前面的值
        for (int i = 0; i < period - 1 && i < prices.length; i++) {
            ema[i] = ema[period - 1];
        }
        
        return ema;
    }
    
    /**
     * 快速计算RSI
     */
    private double calculateRSIFast(double[] prices, int currentIndex, int period) {
        if (currentIndex < period) return 50;
        
        double gainSum = 0, lossSum = 0;
        
        for (int i = currentIndex - period + 1; i <= currentIndex; i++) {
            double change = prices[i] - prices[i - 1];
            if (change > 0) {
                gainSum += change;
            } else {
                lossSum += Math.abs(change);
            }
        }
        
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        
        if (avgLoss == 0) return 100;
        
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }
    
    /**
     * 快速计算波动率
     */
    private double calculateVolatilityFast(double[] prices, int currentIndex, int window) {
        if (currentIndex < window) return 0;
        
        double sum = 0;
        for (int i = currentIndex - window; i <= currentIndex; i++) {
            sum += prices[i];
        }
        double mean = sum / (window + 1);
        
        if (mean == 0) return 0;
        
        double variance = 0;
        for (int i = currentIndex - window; i <= currentIndex; i++) {
            variance += Math.pow(prices[i] - mean, 2);
        }
        variance /= (window + 1);
        
        double stdDev = Math.sqrt(variance);
        return stdDev / mean * 100;
    }

    // ==================== 单因子预测逻辑 ====================

    /**
     * 因子1：价格惯性预测
     */
    public Integer predictByInertia(FeatureData feature) {
        // 使用3分钟斜率
        if (Math.abs(feature.getPriceSlope3m()) < 0.001) {
            return null; // 震荡，无效样本
        }
        return feature.getPriceSlope3m() > 0 ? 1 : 0;
    }

    /**
     * 因子2：价格区间位置预测（反转逻辑）
     */
    public Integer predictByPosition(FeatureData feature) {
        double pos = feature.getPosition5m();
        if (pos > 0.8) {
            return 0; // 高位，预测回落
        } else if (pos < 0.2) {
            return 1; // 低位，预测反弹
        }
        return null; // 中部，无效
    }

    /**
     * 因子3：均线状态预测
     */
    public Integer predictByMA(FeatureData feature) {
        double maDiff = Math.abs(feature.getEma9() - feature.getEma21());
        double threshold = feature.getEma21() * 0.001; // 0.1%作为粘合阈值

        if (maDiff < threshold) {
            return null; // 均线粘合，无效
        }

        if (feature.getMaAlignment() == 1 && feature.getEma9Slope() > 0) {
            return 1; // 多头排列且向上
        } else if (feature.getMaAlignment() == 0 && feature.getEma9Slope() < 0) {
            return 0; // 空头排列且向下
        }

        return null;
    }

    /**
     * 因子4：RSI超买超卖预测
     */
    public Integer predictByRSI(FeatureData feature) {
        double rsi = feature.getRsi14();
        if (rsi > 70) {
            return 0; // 超买，预测下跌
        } else if (rsi < 30) {
            return 1; // 超卖，预测上涨
        }
        return null; // 中性区域，无效
    }

    /**
     * 因子5：量价配合预测
     */
    public Integer predictByVolume(FeatureData feature) {
        if (feature.getVolRatio5m() == null || feature.getVolRatio5m() < 1.5) {
            return null; // 缩量，无效
        }

        if (feature.getRet1m() != null) {
            if (feature.getRet1m() > 0) {
                return 1; // 放量涨，预测继续涨
            } else {
                return 0; // 放量跌，预测继续跌
            }
        }

        return null;
    }

    // ==================== 多因子组合策略 ====================

    /**
     * 组合1：经典技术面组合（均线+RSI+量能）
     */
    public Integer predictCombo1(FeatureData feature) {
        Integer maPred = predictByMA(feature);
        Integer rsiPred = predictByRSI(feature);
        Integer volPred = predictByVolume(feature);

        // 看多条件
        if (maPred != null && maPred == 1 &&
                feature.getRsi14() > 30 && feature.getRsi14() < 60 &&
                volPred != null && volPred == 1) {
            return 1;
        }

        // 看空条件
        if (maPred != null && maPred == 0 &&
                feature.getRsi14() > 40 && feature.getRsi14() < 70 &&
                volPred != null && volPred == 0) {
            return 0;
        }

        return null; // 不满足条件，不做预测
    }

    /**
     * 组合2：短期微观组合（价格位置+盘口+惯性）
     * 注：盘口数据需要额外接入，这里简化处理
     */
    public Integer predictCombo2(FeatureData feature) {
        Integer posPred = predictByPosition(feature);
        Integer inertiaPred = predictByInertia(feature);

        // 简化版：只用位置和惯性
        if (posPred != null && inertiaPred != null) {
            // 两者同向才预测
            if (posPred.equals(inertiaPred)) {
                return posPred;
            }
        }

        return null;
    }

    /**
     * 组合3：强共振组合（全因子同向）
     */
    public Integer predictCombo3(FeatureData feature) {
        Integer inertiaPred = predictByInertia(feature);
        Integer maPred = predictByMA(feature);
        Integer rsiPred = predictByRSI(feature);
        Integer volPred = predictByVolume(feature);

        // 所有因子都有效且同向
        if (inertiaPred != null && maPred != null && rsiPred != null && volPred != null) {
            if (inertiaPred.equals(maPred) && maPred.equals(rsiPred) && rsiPred.equals(volPred)) {
                return inertiaPred;
            }
        }

        return null;
    }

    // ==================== 回测与统计 ====================

    /**
     * 执行完整回测
     */
    public StatisticsResult backtest(List<Vallisusdt> klineData, int predictWindow) {
        log.info("开始回测，数据量: {}, 预测窗口: {}分钟", klineData.size(), predictWindow);
        
        // 1. 提取特征
        List<FeatureData> features = extractFeatures(klineData, predictWindow);
        
        // 2. 生成预测并统计
        StatisticsResult stats = new StatisticsResult();
        stats.setTotalSamples(features.size());
        
        // 初始化各因子统计
        stats.setInertiaStats(new FactorStats());
        stats.getInertiaStats().setFactorName("价格惯性");
        
        stats.setPositionStats(new FactorStats());
        stats.getPositionStats().setFactorName("区间位置");
        
        stats.setMaStats(new FactorStats());
        stats.getMaStats().setFactorName("均线状态");
        
        stats.setRsiStats(new FactorStats());
        stats.getRsiStats().setFactorName("RSI超买超卖");
        
        stats.setVolStats(new FactorStats());
        stats.getVolStats().setFactorName("量价配合");
        
        stats.setCombo1Stats(new FactorStats());
        stats.getCombo1Stats().setFactorName("组合1-技术面");
        
        stats.setCombo2Stats(new FactorStats());
        stats.getCombo2Stats().setFactorName("组合2-微观");
        
        stats.setCombo3Stats(new FactorStats());
        stats.getCombo3Stats().setFactorName("组合3-强共振");
        
        // 新增高胜率策略
        stats.setCombo4Stats(new FactorStats());
        stats.getCombo4Stats().setFactorName("组合4-趋势跟踪(严)");
        
        stats.setCombo5Stats(new FactorStats());
        stats.getCombo5Stats().setFactorName("组合5-均值回归(严)");
        
        stats.setCombo6Stats(new FactorStats());
        stats.getCombo6Stats().setFactorName("组合6-动量突破(严)");
        
        stats.setCombo7Stats(new FactorStats());
        stats.getCombo7Stats().setFactorName("组合7-多重共振");

        int validCount = 0;
        
        for (FeatureData feature : features) {
            // 单因子预测
            evaluatePrediction(stats.getInertiaStats(), predictByInertia(feature), feature.getTrueLabel());
            evaluatePrediction(stats.getPositionStats(), predictByPosition(feature), feature.getTrueLabel());
            evaluatePrediction(stats.getMaStats(), predictByMA(feature), feature.getTrueLabel());
            evaluatePrediction(stats.getRsiStats(), predictByRSI(feature), feature.getTrueLabel());
            evaluatePrediction(stats.getVolStats(), predictByVolume(feature), feature.getTrueLabel());
            
            // 原有组合预测
            evaluatePrediction(stats.getCombo1Stats(), predictCombo1(feature), feature.getTrueLabel());
            evaluatePrediction(stats.getCombo2Stats(), predictCombo2(feature), feature.getTrueLabel());
            evaluatePrediction(stats.getCombo3Stats(), predictCombo3(feature), feature.getTrueLabel());
            
            // 新增高胜率策略
            evaluatePrediction(stats.getCombo4Stats(), predictTrendFollowing(feature), feature.getTrueLabel());
            evaluatePrediction(stats.getCombo5Stats(), predictMeanReversion(feature), feature.getTrueLabel());
            evaluatePrediction(stats.getCombo6Stats(), predictMomentumBreakout(feature), feature.getTrueLabel());
            evaluatePrediction(stats.getCombo7Stats(), predictMultiResonance(feature), feature.getTrueLabel());
            
            // 统计有效样本
            if (hasAnyValidPrediction(feature)) {
                validCount++;
            }
        }

        stats.setValidSamples(validCount);
        
        log.info("回测完成 - 总样本: {}, 有效样本: {}", stats.getTotalSamples(), stats.getValidSamples());
        printStatistics(stats);
        
        return stats;
    }
    
    /**
     * 组合4：趋势跟踪策略（超高胜率版）
     * 核心逻辑：只在极强趋势时交易，宁可错过不做错
     */
    public Integer predictTrendFollowing(FeatureData feature) {
        // 条件1：均线强烈多头排列且快速发散
        boolean strongBullishTrend = feature.getMaAlignment() == 1 && 
                                    feature.getEma9Slope() > 0.05 && 
                                    feature.getEma21Slope() > 0.03 &&
                                    (feature.getEma9() - feature.getEma21()) / feature.getEma21() > 0.005; // EMA9比EMA21高0.5%以上
        
        // 条件2：均线强烈空头排列且快速发散
        boolean strongBearishTrend = feature.getMaAlignment() == 0 && 
                                    feature.getEma9Slope() < -0.05 && 
                                    feature.getEma21Slope() < -0.03 &&
                                    (feature.getEma21() - feature.getEma9()) / feature.getEma21() > 0.005;
        
        // 条件3：价格在均线上方且远离均线（强势）
        boolean priceStrongAbove = feature.getDiffEma9() > 0 && 
                                  feature.getDiffEma21() > 0 &&
                                  feature.getDiffEma9() / feature.getEma9() > 0.002; // 高于EMA9 0.2%以上
        
        // 条件4：价格在均线下万且远离均线（弱势）
        boolean priceStrongBelow = feature.getDiffEma9() < 0 && 
                                  feature.getDiffEma21() < 0 &&
                                  Math.abs(feature.getDiffEma9()) / feature.getEma9() > 0.002;
        
        // 条件5：RSI确认趋势强度（非极端）
        boolean rsiConfirmBullish = feature.getRsi14() > 55 && feature.getRsi14() < 72;
        boolean rsiConfirmBearish = feature.getRsi14() < 45 && feature.getRsi14() > 28;
        
        // 条件6：显著放量（>1.5倍）
        boolean volumeConfirm = feature.getVolRatio5m() != null && feature.getVolRatio5m() > 1.5;
        
        // 条件7：价格斜率强劲
        boolean momentumConfirm = feature.getPriceSlope3m() > 0.08; // 看涨
        boolean momentumConfirmDown = feature.getPriceSlope3m() < -0.08; // 看跌
        
        // 条件8：处于区间强势位置
        boolean positionConfirm = feature.getPosition5m() > 0.6; // 看涨
        boolean positionConfirmDown = feature.getPosition5m() < 0.4; // 看跌
        
        // 看多：所有8个条件必须全部满足
        if (strongBullishTrend && priceStrongAbove && rsiConfirmBullish && 
            volumeConfirm && momentumConfirm && positionConfirm) {
            return 1;
        }
        
        // 看空：所有8个条件必须全部满足
        if (strongBearishTrend && priceStrongBelow && rsiConfirmBearish && 
            volumeConfirm && momentumConfirmDown && positionConfirmDown) {
            return 0;
        }
        
        return null; // 不满足严格条件，不交易
    }
    
    /**
     * 组合5：均值回归策略（超高胜率版）
     * 核心逻辑：只在极端偏离且出现反转信号时交易
     */
    public Integer predictMeanReversion(FeatureData feature) {
        // 计算价格相对EMA21的偏离百分比
        double deviationFromMA = 0;
        if (feature.getEma21() > 0) {
            deviationFromMA = (feature.getDiffEma21() / feature.getEma21()) * 100;
        }
        
        // 条件1：极度超买（偏离>3%且RSI>80）
        boolean extremeOverbought = deviationFromMA > 3.0 && feature.getRsi14() > 80;
        
        // 条件2：极度超卖（偏离<-3%且RSI<20）
        boolean extremeOversold = deviationFromMA < -3.0 && feature.getRsi14() < 20;
        
        // 条件3：处于区间极端位置（前5%）
        boolean atExtremeHigh = feature.getPosition5m() > 0.95;
        boolean atExtremeLow = feature.getPosition5m() < 0.05;
        
        // 条件4：严重缩量（<0.6倍，表示动能衰竭）
        boolean veryShrinkingVolume = feature.getVolRatio5m() != null && feature.getVolRatio5m() < 0.6;
        
        // 条件5：出现反转K线信号（价格斜率开始反向）
        boolean reversalSignalDown = feature.getPriceSlope1m() < 0 && feature.getPriceSlope3m() > 0; // 由正转负
        boolean reversalSignalUp = feature.getPriceSlope1m() > 0 && feature.getPriceSlope3m() < 0; // 由负转正
        
        // 条件6：RSI开始拐头
        boolean rsiTurningDown = feature.getRsiDelta() < -2; // RSI快速下降
        boolean rsiTurningUp = feature.getRsiDelta() > 2; // RSI快速上升
        
        // 预测回落：超买+极端高位+缩量+反转信号
        if (extremeOverbought && atExtremeHigh && veryShrinkingVolume && reversalSignalDown && rsiTurningDown) {
            return 0;
        }
        
        // 预测反弹：超卖+极端低位+缩量+反转信号
        if (extremeOversold && atExtremeLow && veryShrinkingVolume && reversalSignalUp && rsiTurningUp) {
            return 1;
        }
        
        return null;
    }
    
    /**
     * 组合6：动量突破策略（超高胜率版）
     * 核心逻辑：捕捉真突破，过滤假突破
     */
    public Integer predictMomentumBreakout(FeatureData feature) {
        // 条件1：价格斜率非常强劲
        boolean veryStrongMomentum = Math.abs(feature.getPriceSlope3m()) > 0.12;
        
        // 条件2：突破区间极值
        boolean breakoutUp = feature.getPosition5m() > 0.92 && feature.getPriceSlope5m() > 0.1;
        boolean breakoutDown = feature.getPosition5m() < 0.08 && feature.getPriceSlope5m() < -0.1;
        
        // 条件3：巨量突破（>2.5倍平均成交量）
        boolean hugeVolume = feature.getVolRatio5m() != null && feature.getVolRatio5m() > 2.5;
        
        // 条件4：RSI强势但不超买/超卖
        boolean rsiVeryStrong = feature.getRsi14() > 60 && feature.getRsi14() < 72;
        boolean rsiVeryWeak = feature.getRsi14() < 40 && feature.getRsi14() > 28;
        
        // 条件5：均线强力支持
        boolean strongMaSupport = feature.getMaAlignment() == 1 && 
                                 feature.getDiffEma9() > 0 &&
                                 feature.getEma9Slope() > 0.05;
        boolean strongMaResistance = feature.getMaAlignment() == 0 && 
                                    feature.getDiffEma9() < 0 &&
                                    feature.getEma9Slope() < -0.05;
        
        // 条件6：波动率放大（突破确认）
        boolean volatilityExpanding = feature.getVolatility1m() > feature.getVolatility5m() * 1.2;
        
        // 条件7：连续上涨/下跌惯性
        boolean continuousUp = feature.getRet1m() > 0 && feature.getRet3m() > 0 && feature.getRet5m() > 0;
        boolean continuousDown = feature.getRet1m() < 0 && feature.getRet3m() < 0 && feature.getRet5m() < 0;
        
        // 向上突破：所有7个条件
        if (veryStrongMomentum && breakoutUp && hugeVolume && rsiVeryStrong && 
            strongMaSupport && volatilityExpanding && continuousUp) {
            return 1;
        }
        
        // 向下突破：所有7个条件
        if (veryStrongMomentum && breakoutDown && hugeVolume && rsiVeryWeak && 
            strongMaResistance && volatilityExpanding && continuousDown) {
            return 0;
        }
        
        return null;
    }
    
    /**
     * 新增组合7：多重共振超强策略（最高胜率）
     * 核心逻辑：只有当多个独立因子强烈同向时才交易
     */
    public Integer predictMultiResonance(FeatureData feature) {
        // 收集所有因子的预测
        Integer inertiaPred = predictByInertia(feature);
        Integer maPred = predictByMA(feature);
        Integer rsiPred = predictByRSI(feature);
        Integer volPred = predictByVolume(feature);
        Integer trendPred = predictTrendFollowing(feature);
        Integer momentumPred = predictMomentumBreakout(feature);
        
        // 统计看涨和看跌的票数
        int bullishVotes = 0;
        int bearishVotes = 0;
        int validFactors = 0;
        
        if (inertiaPred != null) {
            validFactors++;
            if (inertiaPred == 1) bullishVotes++; else bearishVotes++;
        }
        if (maPred != null) {
            validFactors++;
            if (maPred == 1) bullishVotes++; else bearishVotes++;
        }
        if (rsiPred != null) {
            validFactors++;
            if (rsiPred == 1) bullishVotes++; else bearishVotes++;
        }
        if (volPred != null) {
            validFactors++;
            if (volPred == 1) bullishVotes++; else bearishVotes++;
        }
        if (trendPred != null) {
            validFactors++;
            if (trendPred == 1) bullishVotes++; else bearishVotes++;
        }
        if (momentumPred != null) {
            validFactors++;
            if (momentumPred == 1) bullishVotes++; else bearishVotes++;
        }
        
        // 至少需要4个因子有效
        if (validFactors < 4) {
            return null;
        }
        
        // 计算共识度
        double bullishRatio = (double) bullishVotes / validFactors;
        double bearishRatio = (double) bearishVotes / validFactors;
        
        // 要求80%以上的因子同向
        if (bullishRatio >= 0.8) {
            return 1; // 强烈看涨
        } else if (bearishRatio >= 0.8) {
            return 0; // 强烈看跌
        }
        
        return null;
    }

    /**
     * 评估单个预测
     */
    private void evaluatePrediction(FactorStats stats, Integer prediction, Integer trueLabel) {
        if (prediction == null) {
            return; // 无效预测，跳过
        }

        stats.setTotalPredictions(stats.getTotalPredictions() + 1);

        if (prediction == 1) {
            stats.setLongPredictions(stats.getLongPredictions() + 1);
            if (prediction.equals(trueLabel)) {
                stats.setLongCorrect(stats.getLongCorrect() + 1);
                stats.setCorrectPredictions(stats.getCorrectPredictions() + 1);
            }
        } else {
            stats.setShortPredictions(stats.getShortPredictions() + 1);
            if (prediction.equals(trueLabel)) {
                stats.setShortCorrect(stats.getShortCorrect() + 1);
                stats.setCorrectPredictions(stats.getCorrectPredictions() + 1);
            }
        }
    }

    /**
     * 判断是否有有效预测
     */
    private boolean hasAnyValidPrediction(FeatureData feature) {
        return predictByInertia(feature) != null ||
                predictByPosition(feature) != null ||
                predictByMA(feature) != null ||
                predictByRSI(feature) != null ||
                predictByVolume(feature) != null;
    }

    /**
     * 打印统计结果
     */
    private void printStatistics(StatisticsResult stats) {
        log.info("\n========== 回测统计结果 ==========");
        log.info("总样本数: {}, 有效样本数: {}", stats.getTotalSamples(), stats.getValidSamples());
        log.info("\n--- 单因子表现 ---");
        printFactorStats(stats.getInertiaStats());
        printFactorStats(stats.getPositionStats());
        printFactorStats(stats.getMaStats());
        printFactorStats(stats.getRsiStats());
        printFactorStats(stats.getVolStats());

        log.info("\n--- 组合策略表现 ---");
        printFactorStats(stats.getCombo1Stats());
        printFactorStats(stats.getCombo2Stats());
        printFactorStats(stats.getCombo3Stats());
        log.info("====================================");
    }

    private void printFactorStats(FactorStats stats) {
        log.info("{}: 总预测={}, 胜率={:.2f}%, 多头胜率={:.2f}%, 空头胜率={:.2f}%",
                stats.getFactorName(),
                stats.getTotalPredictions(),
                stats.getAccuracy(),
                stats.getLongAccuracy(),
                stats.getShortAccuracy());
    }
}
