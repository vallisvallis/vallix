package com.alphay.boot.bpm.service.impl;

import com.alphay.boot.bpm.api.domain.*;
import com.alphay.boot.bpm.mapper.VallisusdtMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * BTC K线数据Service业务层处理
 */
@Service
@Slf4j
public class VallisusdtServiceImpl extends ServiceImpl<VallisusdtMapper, Vallisusdt> implements IVallisusdtService {

    // 时间窗口常量
    public static final int WINDOW_10MIN = 10;
    public static final int WINDOW_30MIN = 30;
    public static final int WINDOW_1HOUR = 60;
    public static final int WINDOW_1DAY = 1440;

    @Override
    public List<Vallisusdt> selectVallisusdtList(Vallisusdt vallisusdt) {
        return baseMapper.selectVallisusdtList(vallisusdt);
    }

    @Override
    public boolean save(Vallisusdt entity) {
        return super.save(entity);
    }

    @Override
    public List<PriceChangeStat> calculatePriceChangeStats() {
        return calculatePriceChangeStats(WINDOW_10MIN);
    }

    @Override
    public List<PriceChangeStat> calculatePriceChangeStats(int windowMinutes) {
        // 获取所有K线数据并按时间排序
        List<Vallisusdt> allData = list(new LambdaQueryWrapper<Vallisusdt>()
                .eq(Vallisusdt::getDeleted, 0)
                .orderByAsc(Vallisusdt::getOpenTime));

        int windowSize = windowMinutes;
        List<PriceChangeStat> stats = new ArrayList<>();

        for (int i = 0; i < allData.size() - windowSize; i++) {
            Vallisusdt current = allData.get(i);
            Vallisusdt future = allData.get(i + windowSize);

            double currentClose = parseDouble(current.getClose());
            double futureClose = parseDouble(future.getClose());

            String direction;
            if (futureClose > currentClose) {
                direction = "UP";
            } else if (futureClose < currentClose) {
                direction = "DOWN";
            } else {
                direction = "FLAT";
            }

            double changePercent = currentClose > 0 ?
                    ((futureClose - currentClose) / currentClose) * 100 : 0;

            stats.add(PriceChangeStat.builder()
                    .openTime(current.getOpenTime())
                    .currentClose(currentClose)
                    .futureClose(futureClose)
                    .changePercent(round4(changePercent))
                    .direction(direction)
                    .windowMinutes(windowMinutes)
                    .build());
        }

        return stats;
    }

    @Override
    public Map<String, Long> calculateDirectionDistribution() {
        return calculateDirectionDistribution(WINDOW_10MIN);
    }

    @Override
    public Map<String, Long> calculateDirectionDistribution(int windowMinutes) {
        List<PriceChangeStat> stats = calculatePriceChangeStats(windowMinutes);

        Map<String, Long> distribution = new HashMap<>();
        distribution.put("UP", 0L);
        distribution.put("DOWN", 0L);
        distribution.put("FLAT", 0L);

        for (PriceChangeStat stat : stats) {
            if (stat.getDirection() != null) {
                distribution.merge(stat.getDirection(), 1L, Long::sum);
            }
        }

        return distribution;
    }

    @Override
    public FeatureAnalysisResult analyzePriceChangeFeatures() {
        return analyzePriceChangeFeatures(WINDOW_10MIN);
    }

    @Override
    public FeatureAnalysisResult analyzePriceChangeFeatures(int windowMinutes) {
        // 获取所有K线数据并按时间排序
        List<Vallisusdt> allData = list(new LambdaQueryWrapper<Vallisusdt>()
                .eq(Vallisusdt::getDeleted, 0)
                .orderByAsc(Vallisusdt::getOpenTime));

        int windowSize = windowMinutes;
        int size = allData.size();

        if (size < windowSize + 1) {
            return FeatureAnalysisResult.builder()
                    .upCount(0)
                    .downCount(0)
                    .build();
        }

        // 分离上涨和下跌样本
        List<Vallisusdt> upSamples = new ArrayList<>();
        List<Vallisusdt> downSamples = new ArrayList<>();

        for (int i = 0; i < size - windowSize; i++) {
            Vallisusdt current = allData.get(i);
            Vallisusdt future = allData.get(i + windowSize);

            double currentClose = parseDouble(current.getClose());
            double futureClose = parseDouble(future.getClose());

            if (futureClose > currentClose) {
                upSamples.add(current);
            } else if (futureClose < currentClose) {
                downSamples.add(current);
            }
        }

        // 计算特征统计（包含高阶特征）
        FeatureStats upStats = calculateAdvancedFeatureStats(upSamples);
        FeatureStats downStats = calculateAdvancedFeatureStats(downSamples);

        // 计算特征相关性
        Map<String, Double> correlations = calculateAdvancedCorrelations(allData, windowSize);

        return FeatureAnalysisResult.builder()
                .upCount(upSamples.size())
                .downCount(downSamples.size())
                .upFeatureStats(upStats)
                .downFeatureStats(downStats)
                .featureCorrelations(correlations)
                .build();
    }

    @Override
    public FeatureAnalysisResult analyzePriceChangeFeatures(int windowMinutes, double moveThresholdPercent) {
        log.info("开始量化特征分析，窗口: {}分钟, 阈值: {}%", windowMinutes, moveThresholdPercent);
        long startLog = System.currentTimeMillis();

        // 1. 一次性全量拉取数据，按时间升序
        List<Vallisusdt> originList = list(new LambdaQueryWrapper<Vallisusdt>()
                .eq(Vallisusdt::getDeleted, 0)
                .orderByAsc(Vallisusdt::getOpenTime));

        int size = originList.size();
        if (size <= windowMinutes + 1) {
            return FeatureAnalysisResult.builder().upCount(0).downCount(0).build();
        }

        // ===== 2. 第一遍全局循环：在完整时间轴上算好所有特征（修复幸存者偏差） =====
        List<CalculatedKline> processedKlines = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Vallisusdt curr = originList.get(i);
            double open = parseDouble(curr.getOpen());
            double high = parseDouble(curr.getHigh());
            double low = parseDouble(curr.getLow());
            double close = parseDouble(curr.getClose());
            double volume = parseDouble(curr.getVolume());
            double takerBuy = parseDouble(curr.getTakerBuyBase());

            double priceChangeRate = open > 0 ? ((close - open) / open) * 100 : 0;
            double takerBuyRatio = volume > 0 ? takerBuy / volume : 0;

            double amplitude = high - low;
            double body = Math.abs(close - open);
            double bodyRatio = amplitude > 0 ? body / amplitude : 0;
            double upperShadowRatio = amplitude > 0 ? (high - Math.max(open, close)) / amplitude : 0;
            double lowerShadowRatio = amplitude > 0 ? (Math.min(open, close) - low) / amplitude : 0;

            double volChangeRate = 0;
            if (i > 0) {
                double prevVolume = parseDouble(originList.get(i - 1).getVolume());
                if (prevVolume > 0) {
                    volChangeRate = ((volume - prevVolume) / prevVolume) * 100;
                }
            }

            processedKlines.add(CalculatedKline.builder()
                    .openTime(curr.getOpenTime())
                    .close(close)
                    .volume(volume)
                    .takerBuyBase(takerBuy)
                    .priceChangeRate(priceChangeRate)
                    .volChangeRate(volChangeRate)
                    .takerBuyRatio(takerBuyRatio)
                    .bodyRatio(bodyRatio)
                    .upperShadowRatio(upperShadowRatio)
                    .lowerShadowRatio(lowerShadowRatio)
                    .build());
        }

        // ===== 3. 第二遍滑动窗口：根据未来N分钟价格高低分流样本 =====
        List<CalculatedKline> upSamples = new ArrayList<>();
        List<CalculatedKline> downSamples = new ArrayList<>();

        List<Double> futureReturns = new ArrayList<>();
        List<Double> xVolChange = new ArrayList<>();
        List<Double> xTakerRatio = new ArrayList<>();
        List<Double> xBodyRatio = new ArrayList<>();
        List<Double> xPriceChange = new ArrayList<>();

        for (int i = 0; i < size - windowMinutes; i++) {
            CalculatedKline current = processedKlines.get(i);
            CalculatedKline future = processedKlines.get(i + windowMinutes);

            double futureReturn = current.getClose() > 0
                    ? ((future.getClose() - current.getClose()) / current.getClose()) * 100
                    : 0;

            futureReturns.add(futureReturn);
            xVolChange.add(current.getVolChangeRate());
            xTakerRatio.add(current.getTakerBuyRatio());
            xBodyRatio.add(current.getBodyRatio());
            xPriceChange.add(current.getPriceChangeRate());

            if (futureReturn > moveThresholdPercent) {
                upSamples.add(current);
            } else if (futureReturn < -moveThresholdPercent) {
                downSamples.add(current);
            }
        }

        // ===== 4. 计算上涨/下跌样本的统计学平均值 =====
        FeatureStats upStats = calcAverageStats(upSamples);
        FeatureStats downStats = calcAverageStats(downSamples);

        // ===== 5. 计算各特征与未来涨跌幅的 Pearson 相关系数 =====
        Map<String, Double> correlations = new LinkedHashMap<>();
        correlations.put("当前成交量变化率", calculateCorrelation(futureReturns, xVolChange));
        correlations.put("当前主动买入占比", calculateCorrelation(futureReturns, xTakerRatio));
        correlations.put("当前K线实体比例", calculateCorrelation(futureReturns, xBodyRatio));
        correlations.put("当前K线涨跌幅", calculateCorrelation(futureReturns, xPriceChange));

        long elapsed = System.currentTimeMillis() - startLog;
        log.info("量化特征分析完成! 耗时: {} ms, 上涨: {}, 下跌: {}",
                elapsed, upSamples.size(), downSamples.size());

        return FeatureAnalysisResult.builder()
                .upCount(upSamples.size())
                .downCount(downSamples.size())
                .upFeatureStats(upStats)
                .downFeatureStats(downStats)
                .featureCorrelations(correlations)
                .build();
    }

    /**
     * 事件合约回测：基于区间位置（RangePosition）+ 主动买入占比（takerBuyRatio）统计涨跌胜率。
     *
     * <pre>
     * 升级逻辑：
     *   1. 保留 5 个基础档位（0-20% / 20-40% / ... / 80-100%）
     *   2. 新增两个"过滤后"特殊档位：
     *      - 档位 5：极低位（0-20%）+ takerBuyRatio > 0.55（主动买入极强，主力抄底信号）
     *      - 档位 6：极高位（80-100%）+ takerBuyRatio < 0.45（主动买入极弱，量价背离信号）
     *   3. 原始档位依然统计，以便对比过滤前后胜率提升幅度
     *
     * 算法复杂度：
     *   - 单次遍历 O(n)，环形缓冲区 O(k)，非常高效
     *   - SQL 层已算好 takerBuyRatio，Java 只需要读取和判断
     * </pre>
     */
    @Override
    public List<PositionBucket> analyzeEventContractWinRate(int lookbackPeriods, int forwardMinutes) {
        log.info("========== 事件合约胜率回测 ==========");
        log.info("回看周期: {} 根K线, 前看窗口: {} 分钟 | 二级过滤: takerBuyRatio", lookbackPeriods, forwardMinutes);
        long t0 = System.currentTimeMillis();

        // 过滤阈值（参数可后续调优，当前按用户指定取值）
        final double STRONG_BUY_THRESHOLD = 0.55;  // 极低位 + 主动买入 > 55%
        final double WEAK_BUY_THRESHOLD   = 0.45;  // 极高位 + 主动买入 < 45%

        // 1) SQL 一次性获取所有K线的 OHLC + takerBuyRatio
        List<KlineFuturePrice> allKlines = baseMapper.selectKlineWithFuturePrice();
        int totalRows = allKlines.size();
        log.info("SQL 返回 {} 条K线", totalRows);

        // 边界检查
        int minRequired = lookbackPeriods + forwardMinutes;
        if (totalRows < minRequired) {
            log.warn("数据不足（需要至少 {} 条，实际 {} 条），无法回测", minRequired, totalRows);
            return Collections.emptyList();
        }

        // 2) 初始化 7 个统计桶：
        //    索引 0-4：原始 5 个位置档位
        //    索引   5：极低位 (0-20%) + takerBuyRatio > 0.55
        //    索引   6：极高位 (80-100%) + takerBuyRatio < 0.45
        PositionBucket[] buckets = new PositionBucket[7];
        String[] bucketLabels = {
                "1.极低位 0-20%",
                "2.中低位 20-40%",
                "3.中部 40-60%",
                "4.中高位 60-80%",
                "5.极高位 80-100%",
                "6.极低位 + 买盘强 (>55%)",
                "7.极高位 + 买盘弱 (<45%)"
        };
        for (int b = 0; b < 7; b++) {
            buckets[b] = PositionBucket.builder()
                    .label(bucketLabels[b])
                    .rangeMin(b <= 4 ? b * 20.0 : (b == 5 ? 0.0 : 80.0))
                    .rangeMax(b <= 4 ? (b + 1) * 20.0 : (b == 5 ? 20.0 : 100.0))
                    .totalSamples(0).upCount(0).downCount(0).flatCount(0)
                    .build();
        }

        // 3) 滑动窗口：维护最近 lookbackPeriods 根K线的价格数据（环形缓冲区）
        double[] ringHigh  = new double[lookbackPeriods];
        double[] ringLow   = new double[lookbackPeriods];
        double[] ringClose = new double[lookbackPeriods];
        int ringIdx = 0;
        int ringSize = 0;

        // 有效样本上限：最后 forwardMinutes 条没有未来数据
        int validEnd = totalRows - forwardMinutes;

        for (int i = 0; i < validEnd; i++) {
            KlineFuturePrice k = allKlines.get(i);

            // 3a) 将当前K线写入环形缓冲区
            ringHigh[ringIdx]  = k.getHighPrice();
            ringLow[ringIdx]   = k.getLowPrice();
            ringClose[ringIdx] = k.getClosePrice();
            ringIdx = (ringIdx + 1) % lookbackPeriods;
            if (ringSize < lookbackPeriods) {
                ringSize++;
                continue; // 预热阶段：缓冲区未满，跳过
            }

            // 3b) 从环形缓冲区中扫描得出 maxHigh、minLow
            double maxHigh = ringHigh[0];
            double minLow  = ringLow[0];
            for (int j = 1; j < lookbackPeriods; j++) {
                if (ringHigh[j] > maxHigh) maxHigh = ringHigh[j];
                if (ringLow[j]  < minLow)  minLow  = ringLow[j];
            }

            // 3c) 计算 RangePosition：当前收盘价在过去N周期高低区间的百分比位置
            double rangeHighLow = maxHigh - minLow;
            double rangePosition;
            if (rangeHighLow > 1e-12) {
                rangePosition = ((k.getClosePrice() - minLow) / rangeHighLow) * 100.0;
            } else {
                rangePosition = 50.0; // 一字线，默认中部
            }

            // 3d) 获取当前K线的主动买入占比
            double takerRatio = k.getTakerBuyRatio() != null ? k.getTakerBuyRatio() : 0.5;

            // 3e) O(1) 数组下标索引获取未来结算价，判定方向
            double futureClose = allKlines.get(i + forwardMinutes).getClosePrice();
            double currentClose = k.getClosePrice();

            int direction; // 1=UP, -1=DOWN, 0=FLAT
            if (futureClose > currentClose) {
                direction = 1;
            } else if (futureClose < currentClose) {
                direction = -1;
            } else {
                direction = 0;
            }

            // 3f) 原始档位统计（0-4）
            int baseBucketIdx = (int) (rangePosition / 20.0);
            if (baseBucketIdx < 0) baseBucketIdx = 0;
            if (baseBucketIdx > 4) baseBucketIdx = 4;
            incrementBucketCount(buckets[baseBucketIdx], direction);

            // 3g) 特殊过滤档：极低位 + 强买盘
            if (rangePosition < 20.0 && takerRatio > STRONG_BUY_THRESHOLD) {
                incrementBucketCount(buckets[5], direction);
            }

            // 3h) 特殊过滤档：极高位 + 弱买盘
            if (rangePosition >= 80.0 && takerRatio < WEAK_BUY_THRESHOLD) {
                incrementBucketCount(buckets[6], direction);
            }
        }

        // 4) 计算每个档位的胜率
        double breakevenRate = 55.56;
        for (PositionBucket bucket : buckets) {
            long total = bucket.getTotalSamples();
            if (total > 0) {
                bucket.setUpWinRate(bucket.getUpCount() * 100.0 / total);
                bucket.setDownWinRate(bucket.getDownCount() * 100.0 / total);
            }
            bucket.setUpProfitable(bucket.getUpWinRate() > breakevenRate);
            bucket.setDownProfitable(bucket.getDownWinRate() > breakevenRate);
        }

        long elapsed = System.currentTimeMillis() - t0;
        long grandTotal = Arrays.stream(buckets).limit(5).mapToLong(PositionBucket::getTotalSamples).sum();
        long filteredTotal = Arrays.stream(buckets).skip(5).mapToLong(PositionBucket::getTotalSamples).sum();
        log.info("回测完成, 耗时 {} ms, 基础样本 {} 条, 过滤样本 {} 条", elapsed, grandTotal, filteredTotal);

        return Arrays.asList(buckets);
    }

    /**
     * 辅助：给统计桶累加减涨平计数
     */
    private void incrementBucketCount(PositionBucket bucket, int direction) {
        if (direction > 0) {
            bucket.setUpCount(bucket.getUpCount() + 1);
        } else if (direction < 0) {
            bucket.setDownCount(bucket.getDownCount() + 1);
        } else {
            bucket.setFlatCount(bucket.getFlatCount() + 1);
        }
        bucket.setTotalSamples(bucket.getTotalSamples() + 1);
    }

    /**
     * 高效计算平均特征
     */
    private FeatureStats calcAverageStats(List<CalculatedKline> samples) {
        if (samples.isEmpty()) return new FeatureStats();

        double sumPriceChange = 0, sumVolChange = 0, sumTaker = 0, sumBody = 0, sumUpper = 0, sumLower = 0;
        for (CalculatedKline k : samples) {
            sumPriceChange += k.getPriceChangeRate();
            sumVolChange += k.getVolChangeRate();
            sumTaker += k.getTakerBuyRatio();
            sumBody += k.getBodyRatio();
            sumUpper += k.getUpperShadowRatio();
            sumLower += k.getLowerShadowRatio();
        }

        int n = samples.size();
        return FeatureStats.builder()
                .avgPriceChange(round4(sumPriceChange / n))
                .avgVolumeChangeRate(round4(sumVolChange / n))
                .avgTakerBuyRatio(round4(sumTaker / n))
                .avgBodyRatio(round4(sumBody / n))
                .avgUpperShadowRatio(round4(sumUpper / n))
                .avgLowerShadowRatio(round4(sumLower / n))
                .build();
    }

    /**
     * 计算高阶特征统计数据
     */
    private FeatureStats calculateAdvancedFeatureStats(List<Vallisusdt> samples) {
        if (samples.isEmpty()) {
            return FeatureStats.builder().build();
        }

        double sumPriceChange = 0;
        double sumVolume = 0;
        double sumVolumeChangeRate = 0;
        double sumVolatility = 0;
        double sumTakerBuyRatio = 0;
        int bullishCount = 0;
        double sumTrades = 0;

        // 新增高阶特征
        double sumBodyRatio = 0;
        double sumUpperShadowRatio = 0;
        double sumLowerShadowRatio = 0;
        double sumQuoteVolume = 0;
        double sumQuoteVolumeChangeRate = 0;
        double sumTakerBuyQuoteRatio = 0;
        double sumVolumePerTrade = 0;
        double sumVolatilityChangeRate = 0;
        double sumPriceChangeRate = 0;
        int hammerCount = 0;
        int shootingStarCount = 0;
        double sumPositionChangeRate = 0;

        for (int i = 0; i < samples.size(); i++) {
            Vallisusdt data = samples.get(i);

            double open = parseDouble(data.getOpen());
            double close = parseDouble(data.getClose());
            double high = parseDouble(data.getHigh());
            double low = parseDouble(data.getLow());
            double volume = parseDouble(data.getVolume());
            double takerBuyBase = parseDouble(data.getTakerBuyBase());
            double quoteVolume = parseDouble(data.getQuoteVolume());
            double takerBuyQuote = parseDouble(data.getTakerBuyQuote());
            long trades = data.getTrades() != null ? data.getTrades() : 0;

            // 基础特征
            sumPriceChange += ((close - open) / open) * 100;
            sumVolume += volume;
            sumVolatility += ((high - low) / open) * 100;
            sumTakerBuyRatio += volume > 0 ? takerBuyBase / volume : 0;
            sumTrades += trades;

            if (close >= open) {
                bullishCount++;
            }

            // 成交量变化率
            if (i > 0) {
                double prevVolume = parseDouble(samples.get(i - 1).getVolume());
                sumVolumeChangeRate += prevVolume > 0 ? ((volume - prevVolume) / prevVolume) * 100 : 0;
            }

            // ========== 高阶特征 ==========

            // 实体比率 = 实体长度 / 波动幅度
            double bodyLength = Math.abs(close - open);
            double volatility = high - low;
            sumBodyRatio += volatility > 0 ? bodyLength / volatility : 0;

            // 上影线比率
            double upperShadow = close >= open ? (high - close) : (high - open);
            sumUpperShadowRatio += volatility > 0 ? upperShadow / volatility : 0;

            // 下影线比率
            double lowerShadow = close >= open ? (open - low) : (close - low);
            sumLowerShadowRatio += volatility > 0 ? lowerShadow / volatility : 0;

            // 成交额
            sumQuoteVolume += quoteVolume;

            // 成交额变化率
            if (i > 0) {
                double prevQuoteVolume = parseDouble(samples.get(i - 1).getQuoteVolume());
                sumQuoteVolumeChangeRate += prevQuoteVolume > 0 ? ((quoteVolume - prevQuoteVolume) / prevQuoteVolume) * 100 : 0;
            }

            // 主动买入成交额占比
            sumTakerBuyQuoteRatio += quoteVolume > 0 ? takerBuyQuote / quoteVolume : 0;

            // 每笔成交量
            sumVolumePerTrade += trades > 0 ? volume / trades : 0;

            // 波动率变化率
            if (i > 0) {
                Vallisusdt prev = samples.get(i - 1);
                double prevHigh = parseDouble(prev.getHigh());
                double prevLow = parseDouble(prev.getLow());
                double prevOpen = parseDouble(prev.getOpen());
                double prevVolatility = prevOpen > 0 ? ((prevHigh - prevLow) / prevOpen) * 100 : 0;
                double currentVolatility = ((high - low) / open) * 100;
                sumVolatilityChangeRate += prevVolatility > 0 ? ((currentVolatility - prevVolatility) / prevVolatility) * 100 : 0;
            }

            // 价格变化率（与前一根K线比较）
            if (i > 0) {
                double prevClose = parseDouble(samples.get(i - 1).getClose());
                sumPriceChangeRate += prevClose > 0 ? ((close - prevClose) / prevClose) * 100 : 0;
            }

            // 锤子线识别（下影线长度 > 实体长度的2倍，上影线很短）
            boolean isHammer = (lowerShadow > 2 * bodyLength) && (upperShadow < bodyLength * 0.3);
            if (isHammer) {
                hammerCount++;
            }

            // 流星线识别（上影线长度 > 实体长度的2倍，下影线很短）
            boolean isShootingStar = (upperShadow > 2 * bodyLength) && (lowerShadow < bodyLength * 0.3);
            if (isShootingStar) {
                shootingStarCount++;
            }

            // 持仓量变化率（基于主动买入与成交量的关系）
            double positionChange = volume > 0 ? (takerBuyBase - (volume - takerBuyBase)) / volume * 100 : 0;
            sumPositionChangeRate += positionChange;
        }

        int count = samples.size();
        return FeatureStats.builder()
                // 基础特征
                .avgPriceChange(round2(sumPriceChange / count))
                .avgVolume(round2(sumVolume / count))
                .avgVolumeChangeRate(count > 1 ? round2(sumVolumeChangeRate / (count - 1)) : 0)
                .avgVolatility(round2(sumVolatility / count))
                .avgTakerBuyRatio(round2(sumTakerBuyRatio / count))
                .bullishRatio(round2(bullishCount * 100.0 / count))
                .avgTrades(round2(sumTrades / count))
                // 高阶特征
                .avgBodyRatio(round2(sumBodyRatio / count))
                .avgUpperShadowRatio(round2(sumUpperShadowRatio / count))
                .avgLowerShadowRatio(round2(sumLowerShadowRatio / count))
                .avgQuoteVolume(round2(sumQuoteVolume / count))
                .avgQuoteVolumeChangeRate(count > 1 ? round2(sumQuoteVolumeChangeRate / (count - 1)) : 0)
                .avgTakerBuyQuoteRatio(round2(sumTakerBuyQuoteRatio / count))
                .avgVolumePerTrade(round2(sumVolumePerTrade / count))
                .avgVolatilityChangeRate(count > 1 ? round2(sumVolatilityChangeRate / (count - 1)) : 0)
                .avgPriceChangeRate(count > 1 ? round2(sumPriceChangeRate / (count - 1)) : 0)
                .hammerRatio(round2(hammerCount * 100.0 / count))
                .shootingStarRatio(round2(shootingStarCount * 100.0 / count))
                .avgPositionChangeRate(round2(sumPositionChangeRate / count))
                .build();
    }

    /**
     * 计算高阶特征相关性
     */
    private Map<String, Double> calculateAdvancedCorrelations(List<Vallisusdt> allData, int windowSize) {
        Map<String, Double> correlations = new HashMap<>();
        int size = allData.size();

        if (size < windowSize + 1) {
            return correlations;
        }

        List<Double> priceChanges = new ArrayList<>();

        // 特征列表
        List<Double> featuresPriceChange = new ArrayList<>();
        List<Double> featuresVolume = new ArrayList<>();
        List<Double> featuresVolatility = new ArrayList<>();
        List<Double> featuresTakerBuyRatio = new ArrayList<>();
        List<Double> featuresBullish = new ArrayList<>();

        // 高阶特征
        List<Double> featuresBodyRatio = new ArrayList<>();
        List<Double> featuresUpperShadow = new ArrayList<>();
        List<Double> featuresLowerShadow = new ArrayList<>();
        List<Double> featuresVolumePerTrade = new ArrayList<>();
        List<Double> featuresPositionChange = new ArrayList<>();

        for (int i = 0; i < size - windowSize; i++) {
            Vallisusdt current = allData.get(i);
            Vallisusdt future = allData.get(i + windowSize);

            double currentClose = parseDouble(current.getClose());
            double futureClose = parseDouble(future.getClose());

            // 未来价格变化（因变量）
            double futureChange = ((futureClose - currentClose) / currentClose) * 100;
            priceChanges.add(futureChange);

            // 当前K线特征（自变量）
            double open = parseDouble(current.getOpen());
            double close = parseDouble(current.getClose());
            double high = parseDouble(current.getHigh());
            double low = parseDouble(current.getLow());
            double volume = parseDouble(current.getVolume());
            double takerBuyBase = parseDouble(current.getTakerBuyBase());
            long trades = current.getTrades() != null ? current.getTrades() : 0;

            featuresPriceChange.add(((close - open) / open) * 100);
            featuresVolume.add(volume);
            featuresVolatility.add(((high - low) / open) * 100);
            featuresTakerBuyRatio.add(volume > 0 ? takerBuyBase / volume : 0);
            featuresBullish.add(close >= open ? 1.0 : 0.0);

            // 高阶特征
            double bodyLength = Math.abs(close - open);
            double volatility = high - low;
            featuresBodyRatio.add(volatility > 0 ? bodyLength / volatility : 0);

            double upperShadow = close >= open ? (high - close) : (high - open);
            featuresUpperShadow.add(volatility > 0 ? upperShadow / volatility : 0);

            double lowerShadow = close >= open ? (open - low) : (close - low);
            featuresLowerShadow.add(volatility > 0 ? lowerShadow / volatility : 0);

            featuresVolumePerTrade.add(trades > 0 ? volume / trades : 0);

            double positionChange = volume > 0 ? (takerBuyBase - (volume - takerBuyBase)) / volume : 0;
            featuresPositionChange.add(positionChange);
        }

        // 计算相关性
        correlations.put("当前K线涨幅", calculateCorrelation(priceChanges, featuresPriceChange));
        correlations.put("成交量", calculateCorrelation(priceChanges, featuresVolume));
        correlations.put("波动率", calculateCorrelation(priceChanges, featuresVolatility));
        correlations.put("主动买入占比", calculateCorrelation(priceChanges, featuresTakerBuyRatio));
        correlations.put("阳线", calculateCorrelation(priceChanges, featuresBullish));

        // 高阶特征相关性
        correlations.put("实体比率", calculateCorrelation(priceChanges, featuresBodyRatio));
        correlations.put("上影线比率", calculateCorrelation(priceChanges, featuresUpperShadow));
        correlations.put("下影线比率", calculateCorrelation(priceChanges, featuresLowerShadow));
        correlations.put("每笔成交量", calculateCorrelation(priceChanges, featuresVolumePerTrade));
        correlations.put("持仓变化率", calculateCorrelation(priceChanges, featuresPositionChange));

        return correlations;
    }

    private Double calculateCorrelation(List<Double> x, List<Double> y) {
        if (x.size() != y.size() || x.size() < 2) {
            return 0.0;
        }

        int n = x.size();
        double sumX = x.stream().mapToDouble(Double::doubleValue).sum();
        double sumY = y.stream().mapToDouble(Double::doubleValue).sum();
        double sumXY = 0;
        double sumX2 = 0;
        double sumY2 = 0;

        for (int i = 0; i < n; i++) {
            sumXY += x.get(i) * y.get(i);
            sumX2 += x.get(i) * x.get(i);
            sumY2 += y.get(i) * y.get(i);
        }

        double numerator = n * sumXY - sumX * sumY;
        double denominator = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));

        return denominator == 0 ? 0.0 : round4(numerator / denominator);
    }

    private double parseDouble(String value) {
        try {
            return value != null ? Double.parseDouble(value) : 0.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    // ==================== 逆向特征挖掘：胜利样本共同基因分析 ====================

    /**
     * 逆向特征挖掘：先锁定未来大胜样本，再逆向剖析其当前特征分布。
     *
     * <pre>
     * 数据流：
     *   SQL 全量加载 → 单次遍历 → 流式滑动窗口计算特征 → 方向判定 → 特征收集 → 统计聚合
     *
     * 严格禁止未来函数：
     *   每个时刻 T 的三个特征（RangePosition / takerBuyRatio / volumeRatio）
     *   全部使用 ≤T 时刻的已知数据计算，不读取任何 T 之后的信息。
     *   方向的判定发生在收集之后，不影响特征计算的纯洁性。
     * </pre>
     */
    @Override
    public void mineWinningSampleFeatures(int lookbackPeriods, int forwardMinutes) {
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║    事件合约逆向特征挖掘 — 胜利样本共同基因分析                 ║");
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("║  回看周期: {} 根K线  |  前看窗口: {} 分钟                     ║", lookbackPeriods, forwardMinutes);
        log.info("║  方法: 先锁定胜者 → 逆向提取当前特征 → 寻找交集               ║");
        log.info("╚══════════════════════════════════════════════════════════════╝");
        long t0 = System.currentTimeMillis();

        // ===== 1) SQL 全量加载 =====
        List<KlineFuturePrice> allKlines = baseMapper.selectKlineWithFuturePrice();
        int totalRows = allKlines.size();
        log.info("SQL 返回 {} 条K线", totalRows);

        int minRequired = lookbackPeriods + forwardMinutes;
        if (totalRows < minRequired) {
            log.warn("数据不足（需要至少 {} 条，实际 {} 条），退出", minRequired, totalRows);
            return;
        }

        // ===== 2) 特征收集器：UP胜 / DOWN胜 =====
        List<Double> upRangePositions = new ArrayList<>();
        List<Double> upTakerBuyRatios = new ArrayList<>();
        List<Double> upVolumeRatios = new ArrayList<>();

        List<Double> downRangePositions = new ArrayList<>();
        List<Double> downTakerBuyRatios = new ArrayList<>();
        List<Double> downVolumeRatios = new ArrayList<>();

        // ===== 3) 流式滑动窗口（严格禁止未来函数） =====
        double[] ringHigh = new double[lookbackPeriods];
        double[] ringLow = new double[lookbackPeriods];
        double[] ringVolume = new double[lookbackPeriods];
        int ringIdx = 0;
        int ringSize = 0;

        int validEnd = totalRows - forwardMinutes;
        int upWinners = 0, downWinners = 0, flatCount = 0;

        for (int i = 0; i < validEnd; i++) {
            KlineFuturePrice k = allKlines.get(i);
            double vol = k.getVolume() != null ? k.getVolume() : 0;

            // 写入环形缓冲区
            ringHigh[ringIdx] = k.getHighPrice();
            ringLow[ringIdx] = k.getLowPrice();
            ringVolume[ringIdx] = vol;
            ringIdx = (ringIdx + 1) % lookbackPeriods;
            if (ringSize < lookbackPeriods) {
                ringSize++;
                continue; // 预热
            }

            // ---- 计算三个特征的当前值（只用到 ≤T 时刻的已知数据） ----

            // 特征 1：RangePosition
            double maxHigh = ringHigh[0], minLow = ringLow[0];
            for (int j = 1; j < lookbackPeriods; j++) {
                if (ringHigh[j] > maxHigh) maxHigh = ringHigh[j];
                if (ringLow[j] < minLow) minLow = ringLow[j];
            }
            double rangeHL = maxHigh - minLow;
            double rangePosition;
            if (rangeHL > 1e-12) {
                rangePosition = ((k.getClosePrice() - minLow) / rangeHL) * 100.0;
            } else {
                rangePosition = 50.0;
            }

            // 特征 2：takerBuyRatio（SQL 层已计算好）
            double takerRatio = k.getTakerBuyRatio() != null ? k.getTakerBuyRatio() : 0.5;

            // 特征 3：volumeRatio = 当前量 / 过去 N 周期均量
            double sumVol = 0;
            for (int j = 0; j < lookbackPeriods; j++) {
                sumVol += ringVolume[j];
            }
            double maVolume = sumVol / lookbackPeriods;
            double volumeRatio = maVolume > 1e-12 ? vol / maVolume : 1.0;

            // ---- 方向判定：未来 forwardMinutes 后的收盘价 vs 当前收盘价 ----
            double futureClose = allKlines.get(i + forwardMinutes).getClosePrice();
            double currentClose = k.getClosePrice();

            if (futureClose > currentClose) {
                // 看涨胜利样本
                upWinners++;
                upRangePositions.add(rangePosition);
                upTakerBuyRatios.add(takerRatio);
                upVolumeRatios.add(volumeRatio);
            } else if (futureClose < currentClose) {
                // 看跌胜利样本
                downWinners++;
                downRangePositions.add(rangePosition);
                downTakerBuyRatios.add(takerRatio);
                downVolumeRatios.add(volumeRatio);
            } else {
                flatCount++;
            }
        }

        // ===== 4) 统计聚合 =====
        long elapsed = System.currentTimeMillis() - t0;
        log.info("流式遍历完成, 耗时 {} ms | UP胜:{} | DOWN胜:{} | FLAT:{}",
                elapsed, upWinners, downWinners, flatCount);

        // ---- UP 胜利组统计 ----
        FeatureProfile upProfile = computeFeatureProfile(upRangePositions, upTakerBuyRatios, upVolumeRatios);

        // ---- DOWN 胜利组统计 ----
        FeatureProfile downProfile = computeFeatureProfile(downRangePositions, downTakerBuyRatios, downVolumeRatios);

        // ===== 5) 打印黄金特征报告 =====
        printGoldenFeatureReport(upProfile, downProfile, lookbackPeriods, forwardMinutes, upWinners, downWinners);
    }

    /**
     * 对三个特征列表计算统计画像：平均值、标准差、5%分箱密度
     */
    private FeatureProfile computeFeatureProfile(List<Double> rangePositions,
                                                  List<Double> takerRatios,
                                                  List<Double> volumeRatios) {
        if (rangePositions.isEmpty()) {
            return FeatureProfile.EMPTY;
        }

        // RangePosition
        double rpMean = mean(rangePositions);
        double rpStd = stdDev(rangePositions, rpMean);
        int[][] rpBins = binValues(rangePositions, 0, 100, 5); // 每5%一箱，0-100共20箱

        // takerBuyRatio
        double trMean = mean(takerRatios);
        double trStd = stdDev(takerRatios, trMean);
        int[][] trBins = binValues(takerRatios, 0, 1, 0.05); // 每5%一箱，0-1共20箱

        // volumeRatio
        double vrMean = mean(volumeRatios);
        double vrStd = stdDev(volumeRatios, vrMean);
        int[][] vrBins = binValues(volumeRatios, 0.5, 3.0, 0.25); // 每0.25一箱，0.5-3.0共10箱

        return new FeatureProfile(rangePositions.size(), rpMean, rpStd, rpBins,
                trMean, trStd, trBins, vrMean, vrStd, vrBins);
    }

    /**
     * 计算均值
     */
    private double mean(List<Double> values) {
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.size();
    }

    /**
     * 计算标准差（总体标准差）
     */
    private double stdDev(List<Double> values, double mean) {
        double sumSq = 0;
        for (double v : values) {
            double diff = v - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / values.size());
    }

    /**
     * 精细化分箱：将值按指定步长归入区间，返回每箱的数量。
     *
     * @return int[][] 其中每行为 [区间下界*100, 区间上界*100, 样本数, 占比(0-10000的整数)]
     */
    private int[][] binValues(List<Double> values, double rangeMin, double rangeMax, double step) {
        int binCount = (int) Math.ceil((rangeMax - rangeMin) / step);
        int[] counts = new int[binCount];
        for (double v : values) {
            int idx = (int) ((v - rangeMin) / step);
            if (idx < 0) idx = 0;
            if (idx >= binCount) idx = binCount - 1;
            counts[idx]++;
        }
        int total = values.size();
        int[][] result = new int[binCount][4];
        for (int i = 0; i < binCount; i++) {
            result[i][0] = (int) ((rangeMin + i * step) * 100);    // 下界*100
            result[i][1] = (int) ((rangeMin + (i + 1) * step) * 100); // 上界*100
            result[i][2] = counts[i];                               // 样本数
            result[i][3] = total > 0 ? (counts[i] * 10000 / total) : 0; // 占比*10000 (即保留2位小数的精度)
        }
        return result;
    }

    /**
     * 打印《事件合约胜利样本核心共同特征报告》
     */
    private void printGoldenFeatureReport(FeatureProfile upProfile, FeatureProfile downProfile,
                                           int lookback, int forward, int upTotal, int downTotal) {
        double goldenThreshold = 60.0; // 黄金交集阈值：占比>60%

        log.info("");
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║     事件合约胜利样本核心共同特征报告                              ║");
        log.info("╠══════════════════════════════════════════════════════════════════╣");
        log.info("║  回看周期:{}根K线 | 结算窗口:{}分钟 | 黄金交集阈值:>{}%           ║",
                lookback, forward, String.format("%.0f", goldenThreshold));
        log.info("╚══════════════════════════════════════════════════════════════════╝");

        // ===== UP 胜利组画像 =====
        printDirectionProfile("📈 看涨(UP)胜利样本", upProfile, upTotal, goldenThreshold);

        // ===== DOWN 胜利组画像 =====
        printDirectionProfile("📉 看跌(DOWN)胜利样本", downProfile, downTotal, goldenThreshold);

        // ===== 黄金特征交集 =====
        printGoldenIntersection(upProfile, downProfile, goldenThreshold);
    }

    /**
     * 打印单个方向的特征画像
     */
    private void printDirectionProfile(String title, FeatureProfile p, int total, double goldenThreshold) {
        if (p.isEmpty()) {
            log.info("\n{}: 无胜利样本", title);
            return;
        }

        log.info("");
        log.info("┌──────────────────────────────────────────────────────────────────┐");
        log.info("│  {} (共 {} 个胜利样本)                        │", title, total);
        log.info("├──────────────────────────────────────────────────────────────────┤");

        // 综述
        log.info("│ 【特征综述】                                                      │");
        log.info("│   RangePosition    均值={}%  标准差={}%                      │",
                fmt2(p.rangeMean), fmt2(p.rangeStd));
        log.info("│   takerBuyRatio    均值={}%  标准差={}%                      │",
                fmt2(p.takerMean * 100), fmt2(p.takerStd * 100));
        log.info("│   volumeRatio      均值={}x  标准差={}x                      │",
                fmt2(p.volMean), fmt2(p.volStd));

        // RangePosition 分箱
        printFeatureBins("RangePosition(%)", p.rangeBins, total, goldenThreshold, true);

        // takerBuyRatio 分箱
        printFeatureBins("takerBuyRatio(%)", p.takerBins, total, goldenThreshold, false);

        // volumeRatio 分箱
        printFeatureBins("volumeRatio", p.volBins, total, goldenThreshold, true);

        log.info("└──────────────────────────────────────────────────────────────────┘");
    }

    /**
     * 打印某特征的分箱分布，并高亮标注 >60% 的黄金区间
     */
    private void printFeatureBins(String name, int[][] bins, int total, double goldenThreshold, boolean labelAsPercent) {
        log.info("│                                                                   │");
        log.info("│  [{} 精细化分箱]                                      ", name);
        log.info("│  ┌──────────────────┬──────────┬─────────┬────────┐               │");

        for (int[] bin : bins) {
            double lower = bin[0] / 100.0;
            double upper = bin[1] / 100.0;
            int count = bin[2];
            double pct = bin[3] / 100.0; // 转回百分比
            boolean isGolden = pct > goldenThreshold;

            String rangeLabel;
            if (labelAsPercent) {
                rangeLabel = String.format("%5.0f%%-%-5.0f%%", lower, upper);
            } else {
                rangeLabel = String.format("%.2f-%.2f", lower, upper);
            }

            String flag = isGolden ? " ⭐黄金" : "";
            log.info("│  │ {:<16s} │ {:>8d} │ {:>6.1f}% │ {} │{}",
                    rangeLabel, count, pct,
                    isGolden ? " 高密 " : "      ", flag);
        }
        log.info("│  └──────────────────┴──────────┴─────────┴────────┘               │");
    }

    /**
     * 打印两个方向的"黄金特征交集"——同时出现在 UP 和 DOWN 高密度区的特征
     */
    private void printGoldenIntersection(FeatureProfile up, FeatureProfile down, double goldenThreshold) {
        log.info("");
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║  🔑 黄金特征交集（UP 和 DOWN 共用高密度区间）                      ║");
        log.info("╚══════════════════════════════════════════════════════════════════╝");

        if (up.isEmpty() || down.isEmpty()) {
            log.info("  （数据不足，无法计算交集）");
            return;
        }

        // RangePosition 交集：找出 UP 和 DOWN 中各自的黄金区间
        log.info("");
        log.info("  【RangePosition 黄金区间对比】");
        log.info("  看涨(UP)高密度区间:");
        findGoldenBins("    ", up.rangeBins, goldenThreshold, true);
        log.info("  看跌(DOWN)高密度区间:");
        findGoldenBins("    ", down.rangeBins, goldenThreshold, true);

        // takerBuyRatio 交集
        log.info("  【takerBuyRatio 黄金区间对比】");
        log.info("  看涨(UP)高密度区间:");
        findGoldenBins("    ", up.takerBins, goldenThreshold, false);
        log.info("  看跌(DOWN)高密度区间:");
        findGoldenBins("    ", down.takerBins, goldenThreshold, false);

        // volumeRatio 交集
        log.info("  【volumeRatio 黄金区间对比】");
        log.info("  看涨(UP)高密度区间:");
        findGoldenBins("    ", up.volBins, goldenThreshold, true);
        log.info("  看跌(DOWN)高密度区间:");
        findGoldenBins("    ", down.volBins, goldenThreshold, true);

        // 结论性数值建议
        log.info("");
        log.info("╔══════════════════════════════════════════════════════════════════╗");
        log.info("║  📐 实盘 if 开仓条件建议（基于黄金特征交集）                      ║");
        log.info("╚══════════════════════════════════════════════════════════════════╝");

        log.info("  UP 做多条件（参考值）:");
        log.info("    RangePosition  ≈ {}% ± {}%", fmt2(up.rangeMean), fmt2(up.rangeStd));
        log.info("    takerBuyRatio  ≈ {}% ± {}%", fmt2(up.takerMean * 100), fmt2(up.takerStd * 100));
        log.info("    volumeRatio    ≈ {}x ± {}x", fmt2(up.volMean), fmt2(up.volStd));

        log.info("");
        log.info("  DOWN 做空条件（参考值）:");
        log.info("    RangePosition  ≈ {}% ± {}%", fmt2(down.rangeMean), fmt2(down.rangeStd));
        log.info("    takerBuyRatio  ≈ {}% ± {}%", fmt2(down.takerMean * 100), fmt2(down.takerStd * 100));
        log.info("    volumeRatio    ≈ {}x ± {}x", fmt2(down.volMean), fmt2(down.volStd));
    }

    /**
     * 找出分箱中占比 > goldenThreshold 的高密度区间并打印
     */
    private void findGoldenBins(String prefix, int[][] bins, double goldenThreshold, boolean labelAsPercent) {
        boolean found = false;
        for (int[] bin : bins) {
            double pct = bin[3] / 100.0;
            if (pct > goldenThreshold) {
                if (labelAsPercent) {
                    String range = String.format("%.0f%%-%.0f%%", bin[0] / 100.0, bin[1] / 100.0);
                    log.info("{}⭐ {} 占比 {}%", prefix, range, String.format("%.1f", pct));
                } else {
                    String range = String.format("%.2f-%.2f", bin[0] / 100.0, bin[1] / 100.0);
                    log.info("{}⭐ {} 占比 {}%", prefix, range, String.format("%.1f", pct));
                }
                found = true;
            }
        }
        if (!found) {
            log.info("{}(无超过{}%的高密度区间)", prefix, String.format("%.0f", goldenThreshold));
        }
    }

    private String fmt2(double value) {
        return String.format("%.2f", value);
    }

    /**
     * 特征画像内部类：持有三个特征的全部统计数据
     */
    private static class FeatureProfile {
        static final FeatureProfile EMPTY = new FeatureProfile(0, 0, 0, new int[0][0],
                0, 0, new int[0][0], 0, 0, new int[0][0]);

        final int sampleCount;
        final double rangeMean, rangeStd;
        final int[][] rangeBins;
        final double takerMean, takerStd;
        final int[][] takerBins;
        final double volMean, volStd;
        final int[][] volBins;

        FeatureProfile(int sampleCount, double rangeMean, double rangeStd, int[][] rangeBins,
                       double takerMean, double takerStd, int[][] takerBins,
                       double volMean, double volStd, int[][] volBins) {
            this.sampleCount = sampleCount;
            this.rangeMean = rangeMean;
            this.rangeStd = rangeStd;
            this.rangeBins = rangeBins;
            this.takerMean = takerMean;
            this.takerStd = takerStd;
            this.takerBins = takerBins;
            this.volMean = volMean;
            this.volStd = volStd;
            this.volBins = volBins;
        }

        boolean isEmpty() {
            return sampleCount == 0;
        }
    }

    // ==================== 30分钟事件合约专属逆向特征挖掘 ====================

    @Override
    public void mine30MinEventContractFeatures() {
        final int LOOKBACK_RANGE = 60;
        final int LOOKBACK_VOL = 5;
        final int FORWARD_MINUTES = 30;

        log.info("================================================================");
        log.info("  30分钟事件合约 - 逆向特征挖掘（UP多头胜者画像）");
        log.info("  RangePosition窗口:{}分钟 | VolumeRatio窗口:{}分钟", LOOKBACK_RANGE, LOOKBACK_VOL);
        log.info("  结算窗口:{}分钟 | 黄金阈值:>30%", FORWARD_MINUTES);
        log.info("================================================================");
        long t0 = System.currentTimeMillis();

        List<KlineFuturePrice> allKlines = baseMapper.selectKlineWithFuturePrice();
        int totalRows = allKlines.size();
        log.info("SQL 返回 {} 条K线", totalRows);

        int minRequired = Math.max(LOOKBACK_RANGE, LOOKBACK_VOL) + FORWARD_MINUTES;
        if (totalRows < minRequired) {
            log.warn("数据不足，退出");
            return;
        }

        List<Double> upRangePositions = new ArrayList<>();
        List<Double> upTakerRatios = new ArrayList<>();
        List<Double> upVolumeRatios = new ArrayList<>();
        List<Double> downRangePositions = new ArrayList<>();
        List<Double> downTakerRatios = new ArrayList<>();
        List<Double> downVolumeRatios = new ArrayList<>();

        // RangePosition 窗口：环形缓冲区追踪过去 60 分钟最高/最低
        double[] ringHigh = new double[LOOKBACK_RANGE];
        double[] ringLow = new double[LOOKBACK_RANGE];
        int ringIdx = 0;
        int ringSize = 0;

        // VolumeRatio 窗口：环形缓冲区追踪过去 5 分钟成交量
        double[] ringVol = new double[LOOKBACK_VOL];
        int ringVolIdx = 0;
        int ringVolSize = 0;

        int validEnd = totalRows - FORWARD_MINUTES;
        int upWinners = 0, downWinners = 0, flatCount = 0;

        // 单次遍历 + 流式滑动窗口
        for (int i = 0; i < validEnd; i++) {
            KlineFuturePrice k = allKlines.get(i);
            double vol = k.getVolume() != null ? k.getVolume() : 0;

            // 更新 RangePosition 窗口
            ringHigh[ringIdx] = k.getHighPrice();
            ringLow[ringIdx] = k.getLowPrice();
            ringIdx = (ringIdx + 1) % LOOKBACK_RANGE;
            if (ringSize < LOOKBACK_RANGE) ringSize++;

            // 更新 VolumeRatio 窗口
            ringVol[ringVolIdx] = vol;
            ringVolIdx = (ringVolIdx + 1) % LOOKBACK_VOL;
            if (ringVolSize < LOOKBACK_VOL) ringVolSize++;

            if (ringSize < LOOKBACK_RANGE || ringVolSize < LOOKBACK_VOL) continue;

            // 特征 1: RangePosition_60
            double maxHigh = ringHigh[0], minLow = ringLow[0];
            for (int j = 1; j < LOOKBACK_RANGE; j++) {
                if (ringHigh[j] > maxHigh) maxHigh = ringHigh[j];
                if (ringLow[j] < minLow) minLow = ringLow[j];
            }
            double rangeSpan = maxHigh - minLow;
            double rangePosition = rangeSpan > 1e-12
                    ? ((k.getClosePrice() - minLow) / rangeSpan) * 100.0 : 50.0;

            // 特征 2: TakerBuyRatio
            double takerBuyRatio = k.getTakerBuyRatio() != null ? k.getTakerBuyRatio() : 0.5;

            // 特征 3: VolumeRatio_5 = 当前量 / 过去5分钟均量
            double sumVol5 = 0;
            for (int j = 0; j < LOOKBACK_VOL; j++) sumVol5 += ringVol[j];
            double avgVol5 = sumVol5 / LOOKBACK_VOL;
            double volumeRatio5 = avgVol5 > 1e-12 ? vol / avgVol5 : 1.0;

            // 方向判定（仅用 T 之后的未来数据打标，特征值不受影响）
            double futureClose = allKlines.get(i + FORWARD_MINUTES).getClosePrice();
            double currentClose = k.getClosePrice();

            if (futureClose > currentClose) {
                upWinners++;
                upRangePositions.add(rangePosition);
                upTakerRatios.add(takerBuyRatio);
                upVolumeRatios.add(volumeRatio5);
            } else if (futureClose < currentClose) {
                downWinners++;
                downRangePositions.add(rangePosition);
                downTakerRatios.add(takerBuyRatio);
                downVolumeRatios.add(volumeRatio5);
            } else {
                flatCount++;
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        log.info("遍历完成, 耗时 {} ms | UP胜:{} DOWN胜:{} FLAT:{}", elapsed, upWinners, downWinners, flatCount);

        // 打印分箱报告
        print30MinReport("📈 多头(UP)获胜样本", upRangePositions, upTakerRatios, upVolumeRatios, upWinners);
        print30MinReport("📉 空头(DOWN)获胜样本", downRangePositions, downTakerRatios, downVolumeRatios, downWinners);
        log.info("========== 30分钟事件合约特征挖掘完成 ==========");
    }

    private void print30MinReport(String title, List<Double> rangePositions,
                                   List<Double> takerRatios, List<Double> volumeRatios, int total) {
        if (rangePositions.isEmpty()) { log.info("\n{}: 无样本", title); return; }
        log.info("");
        log.info("┌──────────────────────────────────────────────────────────────┐");
        log.info("│  {} (共 {} 个样本)                    │", title, total);
        log.info("├──────────────────────────────────────────────────────────────┤");

        double rpMean = mean(rangePositions), rpStd = stdDev(rangePositions, rpMean);
        double trMean = mean(takerRatios), trStd = stdDev(takerRatios, trMean);
        double vrMean = mean(volumeRatios), vrStd = stdDev(volumeRatios, vrMean);

        log.info("│ 【特征均值与标准差】                                            │");
        log.info("│   RangePosition   均值={}%  标准差={}%                   ", fmt2(rpMean), fmt2(rpStd));
        log.info("│   TakerBuyRatio   均值={}%  标准差={}%                   ", fmt2(trMean * 100), fmt2(trStd * 100));
        log.info("│   VolumeRatio     均值={}x  标准差={}x                   ", fmt2(vrMean), fmt2(vrStd));

        print30MinBins("RangePosition(%)", rangePositions, 0, 100, 20, true, total);
        print30MinBins("TakerBuyRatio", takerRatios, 0.40, 0.60, 0.05, false, total);
        print30MinBins("VolumeRatio", volumeRatios, 0, 5.0, 0.5, true, total);
        log.info("└──────────────────────────────────────────────────────────────┘");
    }

    private void print30MinBins(String featureName, List<Double> values,
                                 double rangeStart, double rangeEnd, double step,
                                 boolean labelAsPercent, int total) {
        int binCount = (int) Math.ceil((rangeEnd - rangeStart) / step);
        int[] counts = new int[binCount];
        for (double v : values) {
            int idx = (int) ((v - rangeStart) / step);
            if (idx < 0) idx = 0;
            if (idx >= binCount) idx = binCount - 1;
            counts[idx]++;
        }

        log.info("│                                                                   │");
        log.info("│  [{} 分箱分布]                                      ", featureName);
        for (int i = 0; i < binCount; i++) {
            double lower = rangeStart + i * step, upper = rangeStart + (i + 1) * step;
            double pct = total > 0 ? (counts[i] * 100.0 / total) : 0;
            boolean golden = pct > 30.0;
            String rangeLabel = labelAsPercent
                    ? String.format("%5.0f%%-%-5.0f%%", lower, upper)
                    : String.format("%.2f-%.2f", lower, upper);
            StringBuilder bar = new StringBuilder();
            for (int b = 0; b < (int)(pct / 5) && b < 20; b++) bar.append("#");
            log.info("│    {:<16s} {:>6d}  {:>5.1f}%  {} {}",
                    rangeLabel, counts[i], pct, bar.toString(), golden ? "⭐黄金" : "");
        }
    }
}