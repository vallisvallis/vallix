package com.alphay.boot.web.task;

import com.alphay.boot.bpm.api.domain.Vallisusdt;
import com.alphay.boot.bpm.service.impl.IVallisusdtService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Random;

/**
 * Vallisusdt 数据插入定时任务
 *
 * @author d3code
 */
@Slf4j
@Component
public class VallisusdtInsertTask {

    @Autowired
    private IVallisusdtService vallisusdtService;

    private final Random random = new Random();

    /**
     * 每隔5分钟插入一条测试数据
     * cron表达式：秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 0/5 * * * ?")
    public void insertTestData() {
        try {
            log.info("开始执行 Vallisusdt 数据插入任务...");

            // 生成随机测试数据
            double basePrice = 50000.0 + (random.nextDouble() * 10000);
            double maxPrice = basePrice + random.nextDouble() * 1000;
            double minPrice = basePrice - random.nextDouble() * 1000;
            double endPrice = basePrice + (random.nextDouble() - 0.5) * 500;

            Vallisusdt testData = Vallisusdt.builder()
                    .startTime(new Date())
                    .endTime(new Date())
                    .startPrice(String.format("%.2f", basePrice))
                    .maxPrice(String.format("%.2f", maxPrice))
                    .minPrice(String.format("%.2f", minPrice))
                    .endPrice(String.format("%.2f", endPrice))
                    .calcCount(String.format("%.2f", random.nextDouble() * 200))
                    .priceCount(String.format("%.2f", random.nextDouble() * 10000000))
                    .numCount(String.valueOf(random.nextInt(3000)))
                    .zdBuyCount(String.format("%.2f", random.nextDouble() * 100))
                    .zdSellCount(String.format("%.2f", random.nextDouble() * 100))
                    .resultStr("test_" + System.currentTimeMillis())
                    .resultPrice(String.format("%.2f", endPrice))
                    .remark("定时任务自动插入 - " + new Date())
                    .build();

            boolean result = vallisusdtService.save(testData);

            if (result) {
                log.info("✅ Vallisusdt 测试数据插入成功！ID: {}, 开盘价: {}, 收盘价: {}",
                        testData.getId(), testData.getStartPrice(), testData.getEndPrice());
            } else {
                log.error("❌ Vallisusdt 测试数据插入失败！");
            }

        } catch (Exception e) {
            log.error("❌ Vallisusdt 数据插入异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 应用启动时立即插入一条测试数据（仅执行一次）
     */
    @Scheduled(initialDelay = 5000, fixedRate = Long.MAX_VALUE)
    public void insertOnStartup() {
        log.info("应用启动，立即插入一条测试数据...");
        insertTestData();
    }
}
