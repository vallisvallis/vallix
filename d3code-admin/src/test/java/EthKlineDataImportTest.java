// D:\vallix\d3code\d3code-admin\src\test\java\EthKlineDataImportTest.java
package com.alphay.boot.bpm.test;

import com.alphay.boot.bpm.api.domain.EthKlineSecond;
import com.alphay.boot.bpm.service.impl.IEthKlineSecondService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ETH秒级K线数据导入测试（优化版）
 * 使用多线程并行下载 + 批量插入优化速度
 *
 * @author d3code
 */
@Slf4j
@SpringBootTest
public class EthKlineDataImportTest {

    @Autowired
    private IEthKlineSecondService ethKlineSecondService;

    /**
     * 币安数据归档基础URL
     */
    private static final String BINANCE_ARCHIVE_URL = "https://data.binance.vision/data/spot/daily/klines/ETHUSDT/1s";

    /**
     * 并行线程数（根据网络带宽调整）
     */
    private static final int PARALLEL_THREADS = 8;

    /**
     * 批量插入大小（根据数据库配置调整）
     */
    private static final int BATCH_SIZE = 10000;

    /**
     * OkHttp连接池配置
     */
    private static final OkHttpClient OK_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectionPool(new ConnectionPool(16, 5, TimeUnit.MINUTES))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build();

    /**
     * 总导入计数
     */
    private final AtomicInteger totalImported = new AtomicInteger(0);

    /**
     * 成功导入天数
     */
    private final AtomicInteger successDays = new AtomicInteger(0);

    /**
     * 失败天数
     */
    private final AtomicInteger failedDays = new AtomicInteger(0);

    /**
     * 导入最近1个月的秒级数据（优化版）
     */
    @Test
    public void importRecentMonthDataOptimized() {
        log.info("========== 开始优化版ETH/USDT秒级K线数据导入 ==========");
        log.info("并行线程数: {}, 批量插入大小: {}", PARALLEL_THREADS, BATCH_SIZE);

        long startTime = System.currentTimeMillis();

        try {
            // 获取最近30天的日期列表
            List<LocalDate> dates = getRecent30Days();
            log.info("需要获取 {} 天的数据", dates.size());

            // 使用线程池并行处理（增加线程数）
            ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_THREADS, new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(1);
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r, "DataImporter-" + counter.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                }
            });
            List<Future<DayImportResult>> futures = new ArrayList<>();

            // 提交任务
            for (LocalDate date : dates) {
                futures.add(executor.submit(new HighPerformanceDayImporter(date)));
            }

            // 等待所有任务完成并收集结果
            for (Future<DayImportResult> future : futures) {
                try {
                    DayImportResult result = future.get(10, TimeUnit.MINUTES);
                    if (result.success) {
                        totalImported.addAndGet(result.count);
                        successDays.incrementAndGet();
                        log.info("✅ {}: 成功导入 {} 条", result.date, result.count);
                    } else {
                        failedDays.incrementAndGet();
                        log.warn("❌ {}: 导入失败 - {}", result.date, result.errorMessage);
                    }
                } catch (TimeoutException e) {
                    failedDays.incrementAndGet();
                    log.error("⏰ 任务超时");
                } catch (Exception e) {
                    failedDays.incrementAndGet();
                    log.error("处理结果异常", e);
                }
            }

            executor.shutdown();

            // 统计耗时
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            log.info("========== 导入完成 ==========");
            log.info("总耗时: {} 秒", duration / 1000);
            log.info("成功天数: {}, 失败天数: {}", successDays.get(), failedDays.get());
            log.info("总共导入: {} 条数据", totalImported.get());
            log.info("平均速度: {} 条/秒", totalImported.get() / (duration / 1000));

        } catch (Exception e) {
            log.error("导入过程发生错误", e);
        }
    }

    /**
     * 获取最近30天的日期列表（从昨天开始）
     */
    private List<LocalDate> getRecent30Days() {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (int i = 1; i <= 30; i++) {
            dates.add(today.minusDays(i));
        }

        return dates;
    }

    /**
     * 高性能单日数据导入器
     */
    private class HighPerformanceDayImporter implements Callable<DayImportResult> {

        private final LocalDate date;
        private final DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE;

        public HighPerformanceDayImporter(LocalDate date) {
            this.date = date;
        }

        @Override
        public DayImportResult call() {
            String dateStr = date.format(formatter);
            String urlStr = String.format("%s/ETHUSDT-1s-%s.zip", BINANCE_ARCHIVE_URL, dateStr);

            try {
                // 使用OkHttp下载
                Request request = new Request.Builder().url(urlStr).get().build();
                try (Response response = OK_HTTP_CLIENT.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        if (response.code() == 404) {
                            return new DayImportResult(dateStr, true, 0, null);
                        }
                        throw new RuntimeException("HTTP请求失败: " + response.code());
                    }

                    // 直接读取流并解析（流式处理，边读边存）
                    try (ZipArchiveInputStream zipStream = new ZipArchiveInputStream(response.body().byteStream())) {
                        org.apache.commons.compress.archivers.ArchiveEntry entry;
                        int totalCount = 0;
                        List<EthKlineSecond> batchList = new ArrayList<>(BATCH_SIZE);

                        while ((entry = zipStream.getNextEntry()) != null) {
                            if (!entry.isDirectory()) {
                                // 读取CSV内容（直接解析，零拷贝）
                                byte[] content = IOUtils.toByteArray(zipStream);
                                totalCount += parseAndSaveCsv(content, batchList);
                            }
                        }

                        // 保存剩余数据
                        if (!batchList.isEmpty()) {
                            ethKlineSecondService.saveBatchKlineData(batchList);
                        }

                        return new DayImportResult(dateStr, true, totalCount, null);
                    }
                }
            } catch (Exception e) {
                return new DayImportResult(dateStr, false, 0, e.getMessage());
            }
        }

        /**
         * 解析CSV并批量保存（零拷贝优化）
         */
        private int parseAndSaveCsv(byte[] content, List<EthKlineSecond> batchList) {
            int count = 0;
            String csv = new String(content, StandardCharsets.UTF_8);
            int start = 0;
            int end;

            while ((end = csv.indexOf('\n', start)) != -1) {
                String line = csv.substring(start, end).trim();
                start = end + 1;

                if (line.isEmpty()) continue;

                // 快速解析CSV行（避免split创建数组）
                EthKlineSecond data = parseCsvLineFast(line);
                if (data != null) {
                    batchList.add(data);
                    count++;

                    // 批量保存
                    if (batchList.size() >= BATCH_SIZE) {
                        ethKlineSecondService.saveBatchKlineData(batchList);
                        batchList.clear();
                    }
                }
            }

            return count;
        }

        /**
         * 快速解析CSV行（使用索引定位替代split）
         */
        private EthKlineSecond parseCsvLineFast(String line) {
            try {
                int idx1 = line.indexOf(',');
                int idx2 = line.indexOf(',', idx1 + 1);
                int idx3 = line.indexOf(',', idx2 + 1);
                int idx4 = line.indexOf(',', idx3 + 1);
                int idx5 = line.indexOf(',', idx4 + 1);

                if (idx1 == -1 || idx2 == -1 || idx3 == -1 || idx4 == -1 || idx5 == -1) {
                    return null;
                }

                return EthKlineSecond.builder()
                        .timestamp(Long.parseLong(line.substring(0, idx1)))
                        .open(line.substring(idx1 + 1, idx2))
                        .high(line.substring(idx2 + 1, idx3))
                        .low(line.substring(idx3 + 1, idx4))
                        .close(line.substring(idx4 + 1, idx5))
                        .volume(line.substring(idx5 + 1))
                        .build();
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * 单日导入结果
     */
    private static class DayImportResult {
        String date;
        boolean success;
        int count;
        String errorMessage;

        DayImportResult(String date, boolean success, int count, String errorMessage) {
            this.date = date;
            this.success = success;
            this.count = count;
            this.errorMessage = errorMessage;
        }
    }

    /**
     * 测试单天导入（用于调试）
     */
    @Test
    public void testSingleDayImport() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        HighPerformanceDayImporter importer = new HighPerformanceDayImporter(yesterday);

        try {
            DayImportResult result = importer.call();
            log.info("测试导入结果: {}", result.success ? "成功" : "失败");
            log.info("导入数量: {}", result.count);
        } catch (Exception e) {
            log.error("测试导入失败", e);
        }
    }

    /**
     * 测试导入5分钟数据（使用币安数据归档）
     */
    @Test
    public void test5MinutesData() {
        log.info("========== 测试导入5分钟数据（使用币安数据归档） ==========");
        long startTime = System.currentTimeMillis();

        try {
            // 获取今天的日期
            LocalDate today = LocalDate.now();
            String dateStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE);
            
            // 使用币安数据归档地址
            String urlStr = String.format("%s/ETHUSDT-1s-%s.zip", BINANCE_ARCHIVE_URL, dateStr);
            
            log.info("正在获取 {} 的数据...", dateStr);

            // 使用OkHttp下载ZIP文件
            Request request = new Request.Builder().url(urlStr).get().build();
            try (Response response = OK_HTTP_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    // 如果今天没有数据，尝试昨天
                    log.warn("今天没有数据，尝试获取昨天的数据...");
                    LocalDate yesterday = LocalDate.now().minusDays(1);
                    dateStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE);
                    urlStr = String.format("%s/ETHUSDT-1s-%s.zip", BINANCE_ARCHIVE_URL, dateStr);
                    
                    request = new Request.Builder().url(urlStr).get().build();
                    try (Response yesterdayResponse = OK_HTTP_CLIENT.newCall(request).execute()) {
                        if (!yesterdayResponse.isSuccessful()) {
                            log.error("获取数据失败: {}", yesterdayResponse.code());
                            return;
                        }
                        processZipData(yesterdayResponse, startTime, 300); // 5分钟 = 300秒
                    }
                    return;
                }
                
                processZipData(response, startTime, 300); // 5分钟 = 300秒
            }

        } catch (Exception e) {
            log.error("测试失败", e);
        }
    }

    /**
     * 处理ZIP数据并统计时间
     */
    private void processZipData(Response response, long startTime, int limit) throws Exception {
        try (org.apache.commons.compress.archivers.zip.ZipArchiveInputStream zipStream = 
                new org.apache.commons.compress.archivers.zip.ZipArchiveInputStream(response.body().byteStream())) {
            
            org.apache.commons.compress.archivers.ArchiveEntry entry;
            int totalCount = 0;
            int testCount = 0;
            List<EthKlineSecond> batchList = new ArrayList<>(BATCH_SIZE);

            while ((entry = zipStream.getNextEntry()) != null && testCount < limit) {
                if (!entry.isDirectory()) {
                    byte[] content = IOUtils.toByteArray(zipStream);
                    String csv = new String(content, StandardCharsets.UTF_8);
                    int start = 0;
                    int end;

                    while ((end = csv.indexOf('\n', start)) != -1 && testCount < limit) {
                        String line = csv.substring(start, end).trim();
                        start = end + 1;

                        if (line.isEmpty()) continue;

                        EthKlineSecond data = parseCsvLineFast(line);
                        if (data != null) {
                            batchList.add(data);
                            testCount++;

                            if (batchList.size() >= BATCH_SIZE) {
                                ethKlineSecondService.saveBatchKlineData(batchList);
                                totalCount += batchList.size();
                                batchList.clear();
                            }
                        }
                    }
                }
            }

            // 保存剩余数据
            if (!batchList.isEmpty()) {
                ethKlineSecondService.saveBatchKlineData(batchList);
                totalCount += batchList.size();
            }

            long endTimeMs = System.currentTimeMillis();
            long duration = endTimeMs - startTime;

            log.info("✅ 测试完成");
            log.info("导入数量: {} 条", totalCount);
            log.info("耗时: {} 毫秒", duration);
            log.info("平均速度: {} 条/秒", duration > 0 ? (totalCount * 1000 / duration) : 0);
        }
    }

    /**
     * 测试导入最近一天的数据（使用币安数据归档）
     */
    @Test
    public void testRecentDay() {
        log.info("========== 测试导入最近一天数据（使用币安数据归档） ==========");
        long startTime = System.currentTimeMillis();

        try {
            // 获取昨天的日期
            LocalDate yesterday = LocalDate.now().minusDays(1);
            String dateStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE);
            
            // 使用币安数据归档地址
            String urlStr = String.format("%s/ETHUSDT-1s-%s.zip", BINANCE_ARCHIVE_URL, dateStr);
            
            log.info("正在获取 {} 的数据...", dateStr);

            // 使用OkHttp下载ZIP文件
            Request request = new Request.Builder().url(urlStr).get().build();
            try (Response response = OK_HTTP_CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    if (response.code() == 404) {
                        log.warn("当天没有数据");
                        return;
                    }
                    log.error("HTTP请求失败: {}", response.code());
                    return;
                }

                // 解析ZIP并保存（取前500条测试）
                try (org.apache.commons.compress.archivers.zip.ZipArchiveInputStream zipStream = 
                        new org.apache.commons.compress.archivers.zip.ZipArchiveInputStream(response.body().byteStream())) {
                    
                    org.apache.commons.compress.archivers.ArchiveEntry entry;
                    int totalCount = 0;
                    int testCount = 0;
                    final int TEST_LIMIT = 500; // 只测试500条
                    List<EthKlineSecond> batchList = new ArrayList<>(BATCH_SIZE);

                    while ((entry = zipStream.getNextEntry()) != null && testCount < TEST_LIMIT) {
                        if (!entry.isDirectory()) {
                            byte[] content = IOUtils.toByteArray(zipStream);
                            String csv = new String(content, StandardCharsets.UTF_8);
                            int start = 0;
                            int end;

                            while ((end = csv.indexOf('\n', start)) != -1 && testCount < TEST_LIMIT) {
                                String line = csv.substring(start, end).trim();
                                start = end + 1;

                                if (line.isEmpty()) continue;

                                EthKlineSecond data = parseCsvLineFast(line);
                                if (data != null) {
                                    batchList.add(data);
                                    testCount++;

                                    if (batchList.size() >= BATCH_SIZE) {
                                        ethKlineSecondService.saveBatchKlineData(batchList);
                                        totalCount += batchList.size();
                                        batchList.clear();
                                    }
                                }
                            }
                        }
                    }

                    // 保存剩余数据
                    if (!batchList.isEmpty()) {
                        ethKlineSecondService.saveBatchKlineData(batchList);
                        totalCount += batchList.size();
                    }

                    long endTimeMs = System.currentTimeMillis();
                    long duration = endTimeMs - startTime;

                    log.info("✅ 测试完成");
                    log.info("导入数量: {} 条", totalCount);
                    log.info("耗时: {} 毫秒", duration);
                    log.info("平均速度: {} 条/秒", duration > 0 ? (totalCount * 1000 / duration) : 0);
                }
            }

        } catch (Exception e) {
            log.error("测试失败", e);
        }
    }

    /**
     * 快速解析CSV行（使用索引定位替代split）
     */
    private EthKlineSecond parseCsvLineFast(String line) {
        try {
            int idx1 = line.indexOf(',');
            int idx2 = line.indexOf(',', idx1 + 1);
            int idx3 = line.indexOf(',', idx2 + 1);
            int idx4 = line.indexOf(',', idx3 + 1);
            int idx5 = line.indexOf(',', idx4 + 1);

            if (idx1 == -1 || idx2 == -1 || idx3 == -1 || idx4 == -1 || idx5 == -1) {
                return null;
            }

            // 处理时间戳，确保是毫秒级（13位）
            long timestamp = Long.parseLong(line.substring(0, idx1));
            // 如果是微秒级（16位），转换为毫秒级
            if (timestamp > 10000000000000L) { // 大于13位
                timestamp = timestamp / 1000;
            }

            return EthKlineSecond.builder()
                    .timestamp(timestamp)
                    .open(line.substring(idx1 + 1, idx2))
                    .high(line.substring(idx2 + 1, idx3))
                    .low(line.substring(idx3 + 1, idx4))
                    .close(line.substring(idx4 + 1, idx5))
                    .volume(line.substring(idx5 + 1))
                    .build();
        } catch (Exception e) {
            return null;
        }
    }
}