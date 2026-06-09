import com.alphay.boot.D3codeApplication;
import com.alphay.boot.bpm.api.domain.Vallisusdt;
import com.alphay.boot.web.controller.vallix.VallisUsdtEventTool;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;
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
}