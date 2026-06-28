

import com.alphay.boot.D3codeApplication;
import com.alphay.boot.bpm.api.domain.FeatureAnalysisResult;
import com.alphay.boot.bpm.api.domain.FeatureStats;
import com.alphay.boot.bpm.api.domain.PositionBucket;
import com.alphay.boot.bpm.api.domain.PriceChangeStat;
import com.alphay.boot.bpm.api.domain.Vallisusdt;
import com.alphay.boot.web.controller.vallix.VallisUsdtEventTool;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 数据库连接测试 - 验证 vallisusdt 表连接
 */
@SpringBootTest(classes = D3codeApplication.class)
@Slf4j
public class VallisusdtDatabaseTest {

    @Resource
    private DataSource dataSource;

    @Resource
    private com.alphay.boot.bpm.service.impl.IVallisusdtService vallisusdtService;

    @Resource
    private VallisUsdtEventTool vallisUsdtEventTool;

    /**
     * 测试数据库连接是否正常
     */
    @Test
    public void testDatabaseConnection() {
        log.info("========== 开始测试数据库连接 ==========");
        try (Connection connection = dataSource.getConnection()) {
            log.info("✅ 数据库连接成功!");
            log.info("数据库产品名称: {}", connection.getMetaData().getDatabaseProductName());
            log.info("数据库版本: {}", connection.getMetaData().getDatabaseProductVersion());
            log.info("用户名: {}", connection.getMetaData().getUserName());
            log.info("JDBC驱动版本: {}", connection.getMetaData().getDriverVersion());
        } catch (SQLException e) {
            log.error("❌ 数据库连接失败: {}", e.getMessage(), e);
            throw new RuntimeException("数据库连接失败", e);
        }
        log.info("========== 数据库连接测试完成 ==========");
    }

    /**
     * 测试插入一条测试数据
     */
    @Test
    public void testInsertData() {
        log.info("========== 开始测试插入数据 ==========");

        Random random = new Random();
        double basePrice = 50000.0 + (random.nextDouble() * 10000);

        Vallisusdt testData = Vallisusdt.builder()
                .openTime(System.currentTimeMillis())
                .open(String.format("%.2f", basePrice))
                .high(String.format("%.2f", basePrice + random.nextDouble() * 100))
                .low(String.format("%.2f", basePrice - random.nextDouble() * 100))
                .close(String.format("%.2f", basePrice + (random.nextDouble() - 0.5) * 50))
                .volume(String.format("%.2f", random.nextDouble() * 200))
                .closeTime(System.currentTimeMillis())
                .quoteVolume(String.format("%.2f", random.nextDouble() * 10000000))
                .trades((long) random.nextInt(3000))
                .takerBuyBase(String.format("%.2f", random.nextDouble() * 100))
                .takerBuyQuote(String.format("%.2f", random.nextDouble() * 5000000))
                .build();

        boolean result = vallisusdtService.save(testData);

        if (result) {
            log.info("✅ 数据插入成功!");
            log.info("插入记录ID: {}", testData.getId());
            log.info("开盘价: {}", testData.getOpen());
            log.info("收盘价: {}", testData.getClose());
        } else {
            log.error("❌ 数据插入失败!");
            throw new RuntimeException("数据插入失败");
        }
        log.info("========== 数据插入测试完成 ==========");
    }

    /**
     * 测试查询数据
     */
    @Test
    public void testQueryData() {
        log.info("========== 开始测试查询数据 ==========");

        // 查询所有数据
        java.util.List<Vallisusdt> dataList = vallisusdtService.list();

        if (dataList != null && !dataList.isEmpty()) {
            log.info("✅ 查询成功!");
            log.info("总记录数: {}", dataList.size());

            // 打印前3条记录
            int count = Math.min(dataList.size(), 3);
            for (int i = 0; i < count; i++) {
                Vallisusdt data = dataList.get(i);
                log.info("记录[{}]: ID={}, 时间={}, 开盘价={}, 收盘价={}",
                        i + 1,
                        data.getId(),
                        new Date(data.getOpenTime()),
                        data.getOpen(),
                        data.getClose());
            }
        } else {
            log.warn("⚠️ 查询结果为空，表中可能没有数据");
        }
        log.info("========== 查询测试完成 ==========");
    }

    /**
     * 测试完整流程：插入 -> 查询 -> 验证
     */
    @Test
    public void testCompleteFlow() {
        log.info("========== 开始完整流程测试 ==========");

        // 1. 测试连接
        testDatabaseConnection();

        // 2. 测试插入
        testInsertData();

        // 3. 测试查询
        testQueryData();

        log.info("========== 完整流程测试通过! ==========");
    }

    /**
     * 获取并保存BTC近一年分钟数据
     * 数据量：约 365天 × 24小时 × 60分钟 = 525,600 条
     * 预计耗时：10-20分钟（取决于网络状况）
     */
    @Test
    public void testFetchAndSaveBtcYearData() {
        log.info("========== 开始获取BTC近一年分钟数据 ==========");
        log.info("⚠️ 此操作预计需要 10-20 分钟，请耐心等待...");

        long startTime = System.currentTimeMillis();

        try {
            // 1. 从币安API获取近一年数据
            log.info("📥 正在从币安API获取数据...");
            List<Vallisusdt> dataList = vallisUsdtEventTool.getBtcYearMinuteData();
            log.info("✅ 数据获取完成，共 {} 条", dataList.size());

            if (dataList.isEmpty()) {
                log.warn("⚠️ 没有获取到数据，任务终止");
                return;
            }

            // 2. 保存到数据库（批量保存，每1000条提交一次）
            log.info("💾 开始保存数据到数据库（批量插入）...");
            int successCount = 0;
            int batchSize = 1000;

            for (int i = 0; i < dataList.size(); i += batchSize) {
                int end = Math.min(i + batchSize, dataList.size());
                List<Vallisusdt> batchList = dataList.subList(i, end);

                try {
                    vallisusdtService.saveBatch(batchList);
                    successCount += batchList.size();
                } catch (Exception e) {
                    log.warn("❌ 批量保存失败: {}", e.getMessage());
                }

                // 每保存10000条输出一次进度
                if ((i + batchSize) % 10000 == 0 || end == dataList.size()) {
                    log.info("📊 保存进度: {}/{} 条 ({}%)",
                            end, dataList.size(),
                            String.format("%.1f", end * 100.0 / dataList.size()));
                }
            }

            // 计算耗时
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            log.info("========== BTC近一年数据保存完成! ==========");
            int failCount = dataList.size() - successCount;
            log.info("📈 总记录数: {}", dataList.size());
            log.info("✅ 成功: {} 条", successCount);
            log.info("❌ 失败: {} 条", failCount);
            log.info("⏱️ 耗时: {} 秒 ({} 分钟)",
                    duration / 1000,
                    String.format("%.1f", duration / 60000.0));

        } catch (Exception e) {
            log.error("❌ 获取或保存数据失败: {}", e.getMessage(), e);
            throw new RuntimeException("获取BTC数据失败", e);
        }
    }

    /**
     * 获取并保存BTC指定天数的数据（用于测试小数据量）
     * @param days 天数
     */
    private void fetchAndSaveBtcDataByDays(int days) {
        log.info("========== 开始获取BTC {}天分钟数据 ==========", days);

        try {
            List<Vallisusdt> dataList = vallisUsdtEventTool.getBtcHistoricalDataByDays(days);
            log.info("获取到 {} 条数据", dataList.size());

            int successCount = 0;
            for (Vallisusdt data : dataList) {
                try {
                    vallisusdtService.save(data);
                    successCount++;
                } catch (Exception e) {
                    log.warn("保存失败: {}", e.getMessage());
                }
            }

            log.info("✅ 保存完成，成功 {} 条", successCount);
        } catch (Exception e) {
            log.error("❌ 失败: {}", e.getMessage());
        }
    }

    /**
     * 统计每条数据10分钟后的价格相对高低
     * 使用LEAD窗口函数，52万条数据秒级完成
     */
    @Test
    public void testPriceChangeStats() {
        log.info("========== 开始统计10分钟后价格变化 ==========");

        long startTime = System.currentTimeMillis();

        try {
            // 执行统计 - 使用LEAD窗口函数，性能最优
            List<PriceChangeStat> stats = vallisusdtService.calculatePriceChangeStats();

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            log.info("✅ 统计完成!");
            log.info("⏱️ 耗时: {} 秒 ({} 分钟)",
                    duration / 1000,
                    String.format("%.1f", duration / 60000.0));
            log.info("📊 总记录数: {}", stats.size());

            // 统计方向分布
            Map<String, Long> distribution = vallisusdtService.calculateDirectionDistribution();
            long total = distribution.values().stream().mapToLong(Long::longValue).sum();

            log.info("📈 上涨(UP): {} 条 ({}%)",
                    distribution.get("UP"),
                    String.format("%.2f", total > 0 ? distribution.get("UP") * 100.0 / total : 0));
            log.info("📉 下跌(DOWN): {} 条 ({}%)",
                    distribution.get("DOWN"),
                    String.format("%.2f", total > 0 ? distribution.get("DOWN") * 100.0 / total : 0));
            log.info("➡️ 持平(FLAT): {} 条 ({}%)",
                    distribution.get("FLAT"),
                    String.format("%.2f", total > 0 ? distribution.get("FLAT") * 100.0 / total : 0));

            // 打印前5条记录作为示例
            int count = Math.min(stats.size(), 5);
            log.info("\n📋 前5条记录示例:");
            for (int i = 0; i < count; i++) {
                PriceChangeStat stat = stats.get(i);
                log.info("记录[{}]: 时间={}, 当前价={}, 10分钟后价={}, 变化={}%, 方向={}",
                        i + 1,
                        new Date(stat.getOpenTime()),
                        stat.getCurrentClose(),
                        stat.getFutureClose(),
                        stat.getChangePercent() != null ? String.format("%.4f", stat.getChangePercent()) : "N/A",
                        stat.getDirection());
            }

        } catch (Exception e) {
            log.error("❌ 统计失败: {}", e.getMessage(), e);
            throw new RuntimeException("统计失败", e);
        }

        log.info("========== 价格变化统计完成 ==========");
    }

    /**
     * 分析上涨/下跌时的特征差异
     * 找出价格上涨或下跌时的共同特点
     */
    @Test
    public void testFeatureAnalysis() {
        log.info("========== 开始特征分析 ==========");

        long startTime = System.currentTimeMillis();

        try {
            // 执行特征分析
            FeatureAnalysisResult result = vallisusdtService.analyzePriceChangeFeatures();

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            log.info("✅ 特征分析完成!");
            log.info("⏱️ 耗时: {} 秒 ({} 分钟)",
                    duration / 1000,
                    String.format("%.1f", duration / 60000.0));

            log.info("\n📊 样本分布:");
            log.info("   上涨样本数: {} 条", result.getUpCount());
            log.info("   下跌样本数: {} 条", result.getDownCount());

            FeatureStats upStats = result.getUpFeatureStats();
            FeatureStats downStats = result.getDownFeatureStats();

            log.info("\n📈 上涨样本特征:");
            log.info("   平均涨幅: {}%", formatDouble(upStats.getAvgPriceChange()));
            log.info("   平均成交量: {}", formatDouble(upStats.getAvgVolume()));
            log.info("   平均成交量变化率: {}%", formatDouble(upStats.getAvgVolumeChangeRate()));
            log.info("   平均波动幅度: {}%", formatDouble(upStats.getAvgVolatility()));
            log.info("   平均主动买入占比: {}%", formatDouble(upStats.getAvgTakerBuyRatio() * 100));
            log.info("   阳线比例: {}%", formatDouble(upStats.getBullishRatio()));
            log.info("   平均成交笔数: {}", formatDouble(upStats.getAvgTrades()));

            log.info("\n📉 下跌样本特征:");
            log.info("   平均涨幅: {}%", formatDouble(downStats.getAvgPriceChange()));
            log.info("   平均成交量: {}", formatDouble(downStats.getAvgVolume()));
            log.info("   平均成交量变化率: {}%", formatDouble(downStats.getAvgVolumeChangeRate()));
            log.info("   平均波动幅度: {}%", formatDouble(downStats.getAvgVolatility()));
            log.info("   平均主动买入占比: {}%", formatDouble(downStats.getAvgTakerBuyRatio() * 100));
            log.info("   阳线比例: {}%", formatDouble(downStats.getBullishRatio()));
            log.info("   平均成交笔数: {}", formatDouble(downStats.getAvgTrades()));

            log.info("\n🔗 特征相关性分析（与10分钟后涨跌的相关系数）:");
            Map<String, Double> correlations = result.getFeatureCorrelations();
            for (Map.Entry<String, Double> entry : correlations.entrySet()) {
                String significance = getCorrelationSignificance(entry.getValue());
                log.info("   {}: {} ({})",
                        entry.getKey(),
                        String.format("%.4f", entry.getValue()),
                        significance);
            }

            // 输出分析结论
            log.info("\n💡 分析结论:");
            if (upStats.getAvgTakerBuyRatio() != null && downStats.getAvgTakerBuyRatio() != null) {
                if (upStats.getAvgTakerBuyRatio() > downStats.getAvgTakerBuyRatio()) {
                    log.info("   ✅ 上涨前主动买入占比更高 ({}% vs {}%)",
                            String.format("%.2f", upStats.getAvgTakerBuyRatio() * 100),
                            String.format("%.2f", downStats.getAvgTakerBuyRatio() * 100));
                }
            }
            if (upStats.getBullishRatio() != null && downStats.getBullishRatio() != null) {
                log.info("   ✅ 上涨前阳线比例: {}%, 下跌前阳线比例: {}%",
                        formatDouble(upStats.getBullishRatio()),
                        formatDouble(downStats.getBullishRatio()));
            }
            if (upStats.getAvgVolume() != null && downStats.getAvgVolume() != null) {
                if (upStats.getAvgVolume() > downStats.getAvgVolume()) {
                    log.info("   ✅ 上涨前成交量更大 ({} vs {})",
                            String.format("%.2f", upStats.getAvgVolume()),
                            String.format("%.2f", downStats.getAvgVolume()));
                }
            }

        } catch (Exception e) {
            log.error("❌ 特征分析失败: {}", e.getMessage(), e);
            throw new RuntimeException("特征分析失败", e);
        }

        log.info("========== 特征分析完成 ==========");
    }

    private String formatDouble(Double value) {
        return value != null ? String.format("%.2f", value) : "N/A";
    }

    private String getCorrelationSignificance(Double correlation) {
        if (correlation == null) return "无数据";
        double abs = Math.abs(correlation);
        if (abs >= 0.5) return "强相关";
        if (abs >= 0.3) return "中等相关";
        if (abs >= 0.1) return "弱相关";
        return "几乎无关";
    }

    /**
     * 批量量化特征挖掘实验
     * 遍历多个时间窗口和判定阈值，寻找事件合约预测的黄金特征
     * 
     * 实验设计：
     * - 时间窗口: 10分钟(短期), 30分钟(中期), 60分钟(长中), 1440分钟(1天)
     * - 过滤阈值: 0.02%, 0.05%, 0.1%（分别对应不同噪音容忍度）
     * - 合计: 4 x 3 = 12 组实验
     */
    @Test
    public void runQuantFeatureExperiment() {
        log.info("╔══════════════════════════════════════════════════════════╗");
        log.info("║       BTC K线量化特征批量挖掘实验                          ║");
        log.info("╚══════════════════════════════════════════════════════════╝");

        int[] targetWindows = {10, 30, 60, 1440};
        double[] thresholds = {0.02, 0.05, 0.1};
        String[] windowLabels = {"10分钟", "30分钟", "60分钟", "1天"};

        for (int wi = 0; wi < targetWindows.length; wi++) {
            int window = targetWindows[wi];
            String windowLabel = windowLabels[wi];

            for (double threshold : thresholds) {
                log.info("\n┌──────────────────────────────────────────────────────────┐");
                log.info("│ [实验] 目标窗口: {}, 过滤阈值: {}%", windowLabel, threshold);
                log.info("└──────────────────────────────────────────────────────────┘");

                long startTime = System.currentTimeMillis();
                FeatureAnalysisResult result = vallisusdtService.analyzePriceChangeFeatures(window, threshold);
                long duration = System.currentTimeMillis() - startTime;

                // 基础统计
                log.info("⏱️  计算耗时: {} ms", duration);
                long upCount = result.getUpCount();
                long downCount = result.getDownCount();
                long total = upCount + downCount;
                double upPct = total > 0 ? upCount * 100.0 / total : 0;
                double downPct = total > 0 ? downCount * 100.0 / total : 0;
                log.info("📊 样本分布: 上涨={} ({}%), 下跌={} ({}%), 总有效={}",
                        upCount, String.format("%.2f", upPct),
                        downCount, String.format("%.2f", downPct),
                        total);
                // 相关系数分析
                log.info("\n📐 Pearson 相关系数分析（越接近 ±1 越有预测价值）:");
                Map<String, Double> correlations = result.getFeatureCorrelations();
                correlations.forEach((feature, score) -> {
                    String significance = getCorrelationSignificance(score);
                    String direction = score >= 0 ? "正相关 ↗" : "负相关 ↘";
                    log.info("   ├─ {} = {} | {} | {}",
                            feature,
                            score != null ? String.format("%.4f", score) : "N/A",
                            direction,
                            significance);
                });

                // 寻找最强特征
                Map.Entry<String, Double> bestFeature = correlations.entrySet().stream()
                        .max((a, b) -> Double.compare(Math.abs(a.getValue()), Math.abs(b.getValue())))
                        .orElse(null);
                if (bestFeature != null && Math.abs(bestFeature.getValue()) >= 0.05) {
                    String signal = bestFeature.getValue() >= 0 ? "看涨信号" : "看跌信号";
                    log.info("\n🌟 黄金特征提示: [{}] 相关性最强 = {} → 可作为 {} 参考指标",
                            bestFeature.getKey(),
                            String.format("%.4f", bestFeature.getValue()),
                            signal);

                }

                // 形态学对比
                FeatureStats upStats = result.getUpFeatureStats();
                FeatureStats downStats = result.getDownFeatureStats();

                log.info("\n📈 看涨 vs 看跌 — 形态学差异对照表:");
                log.info("   ┌──────────────────────┬────────────┬────────────┬──────────┐");
                log.info("   │ 特征指标              │ 上涨前均值  │ 下跌前均值  │ 差异      │");
                log.info("   ├──────────────────────┼────────────┼────────────┼──────────┤");
                printFeatureRow("当前K线涨跌幅(%)",
                        upStats.getAvgPriceChange(), downStats.getAvgPriceChange());
                printFeatureRow("成交量变化率(%)",
                        upStats.getAvgVolumeChangeRate(), downStats.getAvgVolumeChangeRate());
                printFeatureRow("主动买入占比",
                        upStats.getAvgTakerBuyRatio(), downStats.getAvgTakerBuyRatio());
                printFeatureRow("K线实体比率",
                        upStats.getAvgBodyRatio(), downStats.getAvgBodyRatio());
                printFeatureRow("上影线比率",
                        upStats.getAvgUpperShadowRatio(), downStats.getAvgUpperShadowRatio());
                printFeatureRow("下影线比率",
                        upStats.getAvgLowerShadowRatio(), downStats.getAvgLowerShadowRatio());
                log.info("   └──────────────────────┴────────────┴────────────┴──────────┘");

                // 智能交易建议
                log.info("\n💡 交易建议:");
                if (upStats.getAvgTakerBuyRatio() != null && downStats.getAvgTakerBuyRatio() != null) {
                    double takerDiff = upStats.getAvgTakerBuyRatio() - downStats.getAvgTakerBuyRatio();
                    if (Math.abs(takerDiff) > 0.01) {
                        log.info("   ✅ 主动买入占比差异={}，{}于看涨特征",
                                String.format("%.4f", takerDiff), takerDiff > 0 ? "偏向" : "不利");
                    }
                }
                if (upStats.getAvgBodyRatio() != null && downStats.getAvgBodyRatio() != null) {
                    double bodyDiff = upStats.getAvgBodyRatio() - downStats.getAvgBodyRatio();
                    if (Math.abs(bodyDiff) > 0.01) {
                        log.info("   ✅ K线实体比率差异={}，实体越{}越可能上涨",
                                String.format("%.4f", bodyDiff), bodyDiff > 0 ? "饱满" : "稀疏");
                    }
                }
                if (upStats.getAvgUpperShadowRatio() != null && downStats.getAvgUpperShadowRatio() != null) {
                    double shadowDiff = upStats.getAvgUpperShadowRatio() - downStats.getAvgUpperShadowRatio();
                    if (Math.abs(shadowDiff) > 0.01) {
                        log.info("   ✅ 上影线比率差异={}，上方抛压越{}越可能上涨",
                                String.format("%.4f", shadowDiff), shadowDiff < 0 ? "小" : "大");
                    }
                }

            }
        }

        log.info("\n╔══════════════════════════════════════════════════════════╗");
        log.info("║        批量量化特征挖掘实验 — 全部完成!                    ║");
        log.info("╚══════════════════════════════════════════════════════════╝");
    }

    private void printFeatureRow(String featureName, Double upValue, Double downValue) {
        String upStr = upValue != null ? String.format("%.4f", upValue) : "N/A";
        String downStr = downValue != null ? String.format("%.4f", downValue) : "N/A";
        String diffStr = (upValue != null && downValue != null)
                ? String.format("%+.4f", upValue - downValue)
                : "N/A";
        String row = String.format(
                "   │ %-20s │ %12s │ %12s │ %10s │",
                featureName, upStr, downStr, diffStr);
        log.info(row);
    }

    // ==================== 事件合约胜率回测 ====================

    /**
     * 事件合约胜率回测实验
     * <p>
     * 遍历多种回看周期 × 前看窗口组合，寻找 RangePosition 指标下的高胜率交易区间。
     * 赔率固定 1:0.8，数学保本线 = 55.56%。
     * 测试结束后自动将全部结果写入 CSV 文件。
     */
    @Test
    void testEventContractBacktest() {
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║       币安事件合约 — RangePosition 胜率回测实验               ║");
        log.info("║       赔率 1:0.8 | 保本线 55.56%                              ║");
        log.info("╚══════════════════════════════════════════════════════════════╝");

        int[] lookbackWindows = {10, 20, 30, 60};
        int[] forwardWindows  = {10, 30, 60, 1440};

        // 收集所有结果，用于最终写入文件
        List<Object[]> allCsvRows = new ArrayList<>();
        List<String> profitableSignals = new ArrayList<>();

        for (int lookback : lookbackWindows) {
            for (int forward : forwardWindows) {
                log.info("\n┌──────────────────────────────────────────────────────────────┐");
                log.info("│ 回看: {} 根K线 | 前看: {} 分钟 | 结算周期: {} 分钟",
                        lookback, forward, forward);
                log.info("└──────────────────────────────────────────────────────────────┘");

                long t0 = System.currentTimeMillis();
                List<PositionBucket> buckets = vallisusdtService.analyzeEventContractWinRate(lookback, forward);
                long elapsed = System.currentTimeMillis() - t0;

                printEventContractReport(buckets, elapsed, lookback, forward);

                // 收集 CSV 数据行
                if (buckets != null) {
                    for (PositionBucket b : buckets) {
                        allCsvRows.add(new Object[]{
                                lookback, forward,
                                b.getLabel(),
                                b.getTotalSamples(),
                                b.getUpCount(), b.getUpWinRate(),
                                b.getDownCount(), b.getDownWinRate(),
                                b.getFlatCount(),
                                b.isUpProfitable(), b.isDownProfitable()
                        });
                        // 收集正期望信号
                        if (b.isUpProfitable()) {
                            profitableSignals.add(String.format(
                                    "[回看%3d|前看%4d] %s → 🔥做多 %.2f%%",
                                    lookback, forward, b.getLabel(), b.getUpWinRate()));
                        }
                        if (b.isDownProfitable()) {
                            profitableSignals.add(String.format(
                                    "[回看%3d|前看%4d] %s → 🔥做空 %.2f%%",
                                    lookback, forward, b.getLabel(), b.getDownWinRate()));
                        }
                    }
                }
            }
        }

        log.info("\n╔══════════════════════════════════════════════════════════════╗");
        log.info("║         事件合约回测 — 全部完成!                              ║");
        log.info("╚══════════════════════════════════════════════════════════════╝");

        // 写入 CSV 文件
        writeBacktestResultToFile(allCsvRows, profitableSignals);
    }

    /**
     * 将回测结果写入 CSV 文件和信号摘要文件
     */
    private void writeBacktestResultToFile(List<Object[]> rows, List<String> profitableSignals) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String dirPath = "backtest_results";
        File dir = new File(dirPath);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // ---- CSV 明细文件 ----
        String csvPath = dirPath + "/event_contract_backtest_" + timestamp + ".csv";
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(csvPath), StandardCharsets.UTF_8)))) {
            // BOM 头，保证 Excel 直接打开不乱码
            pw.print('\uFEFF');
            // 表头
            pw.println("回看周期,前看窗口(分钟),位置档位,总样本,UP次数,UP胜率%,DOWN次数,DOWN胜率%,FLAT次数,做多正期望,做空正期望");
            // 数据行
            for (Object[] row : rows) {
                pw.println(String.format("%d,%d,%s,%d,%d,%.2f,%d,%.2f,%d,%s,%s",
                        (int) row[0], (int) row[1], (String) row[2],
                        (long) row[3],
                        (long) row[4], (double) row[5],
                        (long) row[6], (double) row[7],
                        (long) row[8],
                        (boolean) row[9] ? "Y" : "",
                        (boolean) row[10] ? "Y" : ""));
            }
            log.info("📁 明细 CSV 已保存: {}", new File(csvPath).getAbsolutePath());
        } catch (Exception e) {
            log.error("写入 CSV 文件失败", e);
        }

        // ---- 信号摘要文件 ----
        String summaryPath = dirPath + "/profitable_signals_" + timestamp + ".txt";
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(summaryPath), StandardCharsets.UTF_8)))) {
            pw.print('\uFEFF');
            pw.println("====== 事件合约正期望信号汇总 ======");
            pw.println("赔率 1:0.8 | 保本线 55.56%");
            pw.println("生成时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            pw.println();
            if (profitableSignals.isEmpty()) {
                pw.println("（无正期望信号）");
            } else {
                for (String s : profitableSignals) {
                    pw.println(s);
                }
            }
            pw.println();
            pw.println("总计: " + profitableSignals.size() + " 个正期望信号");
            log.info("📁 信号摘要已保存: {}", new File(summaryPath).getAbsolutePath());
        } catch (Exception e) {
            log.error("写入信号摘要文件失败", e);
        }
    }

    /**
     * 打印事件合约胜率回测报表
     * <p>
     * 表格：位置档位 | 总样本 | UP次数 | UP胜率 | DOWN次数 | DOWN胜率 | FLAT | 信号
     * 胜率 > 55.56% 的档位用 🔥 高亮标注。
     */
    private void printEventContractReport(List<PositionBucket> buckets, long elapsedMs,
                                          int lookback, int forward) {
        double breakeven = 55.56;

        log.info("⏱️  回测耗时: {} ms", elapsedMs);

        // 表头
        String headerRow = String.format(
                "   │ %-26s │ %8s │ %8s │ %8s │ %8s │ %8s │ %8s │ %8s │",
                "位置档位", "总样本", "UP次数", "UP胜率%", "DOWN次数", "DOWN胜率%", "FLAT", "信号");
        log.info("   ┌────────────────────────────┬──────────┬──────────┬──────────┬──────────┬──────────┬──────────┬──────────┐");
        log.info(headerRow);
        log.info("   ├────────────────────────────┼──────────┼──────────┼──────────┼──────────┼──────────┼──────────┼──────────┤");

        if (buckets == null || buckets.isEmpty()) {
            String noDataRow = String.format(
                    "   │ %-26s │ %8s │ %8s │ %8s │ %8s │ %8s │ %8s │ %8s │",
                    "无数据", "-", "-", "-", "-", "-", "-", "-");
            log.info(noDataRow);
        } else {
            for (int i = 0; i < buckets.size(); i++) {
                PositionBucket b = buckets.get(i);
                long total = b.getTotalSamples();
                String upRateStr = total > 0 ? String.format("%.2f", b.getUpWinRate()) : "-";
                String downRateStr = total > 0 ? String.format("%.2f", b.getDownWinRate()) : "-";
                String totalStr = String.valueOf(total);

                StringBuilder signal = new StringBuilder();
                if (b.isUpProfitable()) {
                    signal.append("🔥多");
                }
                if (b.isDownProfitable()) {
                    if (signal.length() > 0) signal.append("/");
                    signal.append("🔥空");
                }
                if (signal.length() == 0) {
                    signal.append("—");
                }

                String dataRow = String.format(
                        "   │ %-26s │ %8s │ %8s │ %8s │ %8s │ %8s │ %8s │ %8s │",
                        b.getLabel(), totalStr,
                        String.valueOf(b.getUpCount()), upRateStr,
                        String.valueOf(b.getDownCount()), downRateStr,
                        String.valueOf(b.getFlatCount()), signal.toString());
                log.info(dataRow);

                // 过滤档和基础档之间加分隔线，方便对比
                if (i == 4 && buckets.size() > 5) {
                    log.info("   ├────────────────────────────┼──────────┼──────────┼──────────┼──────────┼──────────┼──────────┼──────────┤");
                    log.info("   │ {} │", String.format("%-26s", "【takerBuyRatio 过滤档】"));
                }
            }
        }
        log.info("   └────────────────────────────┴──────────┴──────────┴──────────┴──────────┴──────────┴──────────┴──────────┘");

        long grandTotal = buckets != null ? buckets.stream().mapToLong(PositionBucket::getTotalSamples).sum() : 0;
        log.info("📊 有效样本总计: {} 条 | 保本胜率线: {}%", grandTotal, String.format("%.2f", breakeven));

        if (buckets != null) {
            for (PositionBucket b : buckets) {
                if (b.isUpProfitable()) {
                    log.info("🔥 [{}] 做多胜率 {}% > {}%，期望值为正，可开多!",
                            b.getLabel(), String.format("%.2f", b.getUpWinRate()), String.format("%.2f", breakeven));
                }
                if (b.isDownProfitable()) {
                    log.info("🔥 [{}] 做空胜率 {}% > {}%，期望值为正，可开空!",
                            b.getLabel(), String.format("%.2f", b.getDownWinRate()), String.format("%.2f", breakeven));
                }
            }
        }
    }
}