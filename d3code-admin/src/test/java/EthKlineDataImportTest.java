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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Autowired
    private com.alphay.boot.bpm.mapper.EthKlineSecondMapper ethKlineSecondMapper;

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
     * 增量同步数据：诊断数据滞后情况，下载缺失数据，保证无断档。
     * 自动兼容微秒/毫秒两种时间戳格式。可重复执行。
     */
    @Test
    public void syncDataToNow() {
        log.info("========== 数据完整性诊断 & 增量同步 ==========");
        try {
            long nowMs = System.currentTimeMillis();
            log.info("当前时间: {} ({})", nowMs, formatMs(nowMs));

            // === Phase 1: 诊断 ===
            List<EthKlineSecond> latest = ethKlineSecondService.selectRecent(1);
            long lastDbMs;
            boolean dbIsMicro = false;

            if (latest.isEmpty()) {
                lastDbMs = nowMs - 30L * 24 * 60 * 60 * 1000;
                log.info("数据库为空，从30天前开始同步");
            } else {
                long rawTs = latest.get(0).getTimestamp();
                dbIsMicro = rawTs > 1000000000000000L;
                lastDbMs = dbIsMicro ? rawTs / 1000 : rawTs;
                log.info("数据库最后记录: {} ({}) [{}]",
                        rawTs, formatMs(lastDbMs), dbIsMicro ? "微秒" : "毫秒");

                // 1.1 检测重复 & 断档（检查最近10分钟）
                long checkStart = lastDbMs - 600_000;
                List<EthKlineSecond> recent = ethKlineSecondService.selectByTimeRange(
                        dbIsMicro ? checkStart * 1000 : checkStart,
                        dbIsMicro ? (lastDbMs + 1000) * 1000 : lastDbMs + 1000);

                Set<Long> seen = new HashSet<>();
                int dupCount = 0;
                Long prevTs = null;
                int gapCount = 0;
                long firstGapMs = 0;

                for (EthKlineSecond r : recent) {
                    long ts = dbIsMicro ? r.getTimestamp() / 1000 : r.getTimestamp();
                    if (!seen.add(ts)) dupCount++;
                    if (prevTs != null && ts - prevTs > 2000) {
                        gapCount++;
                        if (firstGapMs == 0) firstGapMs = prevTs;
                        log.warn("⚠️ 断档: {} → {} (缺失{}秒)",
                                formatMs(prevTs), formatMs(ts), (ts - prevTs) / 1000 - 1);
                    }
                    prevTs = ts;
                }

                if (dupCount > 0)
                    log.warn("⚠️ 发现 {} 条重复时间戳，同步时会自动去重", dupCount);
                if (gapCount > 0) {
                    log.warn("⚠️ 发现 {} 处断档，将从断档处重新下载", gapCount);
                    lastDbMs = firstGapMs;
                }

                long gapS = (nowMs - lastDbMs) / 1000;
                log.info("数据滞后: {}秒 = {}小时 = {}天",
                        gapS, String.format("%.1f", gapS / 3600.0), String.format("%.1f", gapS / 86400.0));
                if (gapS < 60 && gapCount == 0) {
                    log.info("✅ 数据完整且最新，无需同步");
                    return;
                }
            }

            // === Phase 2: 安全下载 ===
            LocalDate startDate = java.time.Instant.ofEpochMilli(lastDbMs)
                    .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
            LocalDate endDate = LocalDate.now().minusDays(1);

            List<LocalDate> dates = new ArrayList<>();
            LocalDate d = startDate;
            while (!d.isAfter(endDate)) { dates.add(d); d = d.plusDays(1); }
            log.info("需下载 {} 天: {} ~ {}", dates.size(), startDate, endDate);
            if (dates.isEmpty()) { log.info("✅ 无需下载"); return; }

            log.info("========== 开始安全下载（自动去重） ==========");
            long t0 = System.currentTimeMillis();
            int total = 0;
            for (LocalDate date : dates) {
                int n = downloadAndImportSafe(date, lastDbMs, dbIsMicro);
                if (n > 0) { total += n; log.info("  ✅ {}: +{} 条", date, n); }
            }

            // === Phase 3: 验证 ===
            log.info("========== 同步完成 ==========");
            log.info("总耗时: {} 秒", (System.currentTimeMillis() - t0) / 1000);
            log.info("新增记录: {} 条", total);

            List<EthKlineSecond> after = ethKlineSecondService.selectRecent(1);
            if (!after.isEmpty()) {
                long latestTs = after.get(0).getTimestamp();
                long latestMs = latestTs > 1000000000000000L ? latestTs / 1000 : latestTs;
                long gapAfter = (nowMs - latestMs) / 1000;
                log.info("同步后最新记录: {} ({}) 滞后: {}秒", latestTs, formatMs(latestMs), gapAfter);
            }

            // 最终验证：检查连续性
            List<EthKlineSecond> verify = ethKlineSecondService.selectByTimeRange(
                    dbIsMicro ? (lastDbMs - 60000) * 1000 : lastDbMs - 60000,
                    dbIsMicro ? (nowMs + 1000) * 1000 : nowMs + 1000);
            if (verify.size() > 1) {
                Long prev = null;
                int finalGaps = 0;
                for (EthKlineSecond r : verify) {
                    long ts = dbIsMicro ? r.getTimestamp() / 1000 : r.getTimestamp();
                    if (prev != null && ts - prev > 2000) finalGaps++;
                    prev = ts;
                }
                if (finalGaps == 0)
                    log.info("✅ 数据连续性验证通过（{}条无断档）", verify.size());
                else
                    log.warn("⚠️ 仍有 {} 处断档，可能当日归档尚未生成", finalGaps);
            }
        } catch (Exception e) {
            log.error("同步数据失败", e);
        }
    }

    private String formatMs(long ms) {
        return java.time.Instant.ofEpochMilli(ms)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDateTime().toString().replace("T", " ");
    }

    private int downloadAndImportSafe(LocalDate date, long lastDbMs, boolean dbIsMicro) {
        String dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String urlStr = String.format("%s/ETHUSDT-1s-%s.zip", BINANCE_ARCHIVE_URL, dateStr);
        try {
            Request req = new Request.Builder().url(urlStr).get().build();
            try (Response resp = OK_HTTP_CLIENT.newCall(req).execute()) {
                if (!resp.isSuccessful()) return resp.code() == 404 ? 0 : 0;
                try (ZipArchiveInputStream zis = new ZipArchiveInputStream(resp.body().byteStream())) {
                    org.apache.commons.compress.archivers.ArchiveEntry entry;

                    // Step 1: 解析所有CSV，筛选 > lastDbMs 的新数据
                    List<EthKlineSecond> allNew = new ArrayList<>();
                    while ((entry = zis.getNextEntry()) != null) {
                        if (!entry.isDirectory()) {
                            byte[] content = IOUtils.toByteArray(zis);
                            String csv = new String(content, StandardCharsets.UTF_8);
                            int s = 0, e;
                            while ((e = csv.indexOf('\n', s)) != -1) {
                                String line = csv.substring(s, e).trim();
                                s = e + 1;
                                if (line.isEmpty()) continue;
                                EthKlineSecond data = parseCsvLineFast(line);
                                if (data == null) continue;
                                long csvTsMs = data.getTimestamp();
                                if (csvTsMs <= lastDbMs) continue;
                                if (dbIsMicro) data.setTimestamp(csvTsMs * 1000);
                                allNew.add(data);
                            }
                        }
                    }

                    if (allNew.isEmpty()) return 0;

                    // Step 2: 查询DB中已存在的时间戳，构建去重集合
                    EthKlineSecond first = allNew.get(0);
                    EthKlineSecond last = allNew.get(allNew.size() - 1);
                    long firstTs = dbIsMicro ? first.getTimestamp() / 1000 : first.getTimestamp();
                    long lastTs = dbIsMicro ? last.getTimestamp() / 1000 : last.getTimestamp();

                    List<EthKlineSecond> existing = ethKlineSecondService.selectByTimeRange(
                            dbIsMicro ? firstTs * 1000 : firstTs,
                            dbIsMicro ? lastTs * 1000 : lastTs);
                    Set<Long> existingTs = new HashSet<>();
                    for (EthKlineSecond ex : existing) {
                        existingTs.add(dbIsMicro ? ex.getTimestamp() / 1000 : ex.getTimestamp());
                    }

                    // Step 3: 过滤已存在的，只插入新数据
                    List<EthKlineSecond> batch = new ArrayList<>(BATCH_SIZE);
                    int cnt = 0;
                    for (EthKlineSecond data : allNew) {
                        long ts = dbIsMicro ? data.getTimestamp() / 1000 : data.getTimestamp();
                        if (!existingTs.contains(ts)) {
                            batch.add(data);
                            cnt++;
                            if (batch.size() >= BATCH_SIZE) {
                                ethKlineSecondService.saveBatchKlineData(batch);
                                batch.clear();
                            }
                        }
                    }
                    if (!batch.isEmpty()) ethKlineSecondService.saveBatchKlineData(batch);
                    return cnt;
                }
            }
        } catch (Exception e) {
            log.warn("下载 {} 失败: {}", dateStr, e.getMessage());
            return 0;
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
     * 检测 eth_kline_second 表数据的时间连续性
     *
     * 检查项：
     * 1. 时间断档（相邻两条记录间隔 > 1秒）
     * 2. 重复时间戳
     * 3. 时间戳格式（毫秒/微秒）
     * 4. 重复数据统计
     *
     * 处理策略：分块查询，避免亿级数据OOM
     */
    @Test
    public void testTimeContinuity() {
        log.info("========== eth_kline_second 时间连续性检测 ==========");
        long t0 = System.currentTimeMillis();

        // 1. 获取数据范围，判断时间戳格式
        List<EthKlineSecond> latest = ethKlineSecondService.selectRecent(1);
        if (latest.isEmpty()) {
            log.info("✅ 表为空，无需检测");
            return;
        }

        long latestTs = latest.get(0).getTimestamp();
        boolean isMicro = latestTs > 1000000000000000L;
        long latestMs = isMicro ? latestTs / 1000 : latestTs;
        log.info("最新记录: {} ({}) [{}]", latestTs, formatMs(latestMs), isMicro ? "微秒" : "毫秒");

        // 2. 获取最早记录
        List<EthKlineSecond> earliest = ethKlineSecondService.selectByTimeRange(
                isMicro ? 0L : 0L,
                isMicro ? (latestTs + 1000) : (latestTs + 1000));
        // 改用 selectRecent 倒序后取最后一条的方式不可靠，使用大范围查询
        long earliestMs = Long.MAX_VALUE;

        // 3. 分块查询，统计断档和重复
        long chunkSize = isMicro ? 3600_000_000L : 3600_000L; // 1小时
        long cursor = isMicro ? 0L : 0L;
        long totalRecords = 0;
        long totalGaps = 0;
        long totalDuplicates = 0;
        long maxGapSeconds = 0;
        long maxGapStart = 0;
        long maxGapEnd = 0;
        List<String> gapReport = new ArrayList<>();
        final int MAX_GAP_REPORT = 20; // 最多记录20条断档详情

        while (cursor < latestTs) {
            long chunkEnd = Math.min(cursor + chunkSize, latestTs);
            List<EthKlineSecond> chunk = ethKlineSecondService.selectByTimeRange(cursor, chunkEnd);

            if (!chunk.isEmpty()) {
                // 排序
                chunk.sort(Comparator.comparingLong(EthKlineSecond::getTimestamp));

                totalRecords += chunk.size();

                Long prevTs = null;
                Set<Long> seenTs = new HashSet<>();
                for (EthKlineSecond row : chunk) {
                    long ts = isMicro ? row.getTimestamp() / 1000 : row.getTimestamp();

                    // 最早记录
                    if (ts < earliestMs) earliestMs = ts;

                    // 重复检测
                    if (!seenTs.add(ts)) {
                        totalDuplicates++;
                        continue;
                    }

                    // 断档检测
                    if (prevTs != null) {
                        long gap = ts - prevTs;
                        if (gap > 1000) { // 间隔 > 1秒
                            totalGaps++;
                            long gapSeconds = (gap / 1000) - 1;
                            if (gapSeconds > maxGapSeconds) {
                                maxGapSeconds = gapSeconds;
                                maxGapStart = prevTs;
                                maxGapEnd = ts;
                            }
                            if (gapReport.size() < MAX_GAP_REPORT) {
                                gapReport.add(String.format("  %s → %s (缺失 %d秒)",
                                        formatMs(prevTs), formatMs(ts), gapSeconds));
                            }
                        }
                    }
                    prevTs = ts;
                }
            }

            cursor = chunkEnd + (isMicro ? 1000L : 1L);

            if (totalRecords > 0 && totalRecords % 500000 == 0) {
                log.info("  进度: {}条, 断档{}处, 重复{}条", totalRecords, totalGaps, totalDuplicates);
            }
        }

        // 4. 汇总输出
        long totalSeconds = (latestMs - earliestMs) / 1000;
        long expectedRecords = totalSeconds + 1;
        long missingRecords = expectedRecords - (totalRecords - totalDuplicates);

        log.info("========== 检测结果 ==========");
        log.info("数据范围: {} ~ {}", formatMs(earliestMs), formatMs(latestMs));
        log.info("总跨度: {}秒 = {}小时 = {}天",
                totalSeconds, String.format("%.1f", totalSeconds / 3600.0),
                String.format("%.1f", totalSeconds / 86400.0));
        log.info("实际记录: {}条 (含重复)", totalRecords);
        log.info("期望记录: {}条 (每秒一条)", expectedRecords);
        log.info("缺失记录: {}条 (缺失率 {:.2f}%)", missingRecords,
                expectedRecords > 0 ? (missingRecords * 100.0 / expectedRecords) : 0);
        log.info("断档次数: {}处", totalGaps);
        log.info("重复记录: {}条", totalDuplicates);

        if (maxGapSeconds > 0) {
            log.info("最大断档: {}秒 ({} → {})",
                    maxGapSeconds, formatMs(maxGapStart), formatMs(maxGapEnd));
        }

        if (!gapReport.isEmpty()) {
            log.info("断档详情 (前{}条):", Math.min(gapReport.size(), MAX_GAP_REPORT));
            for (String s : gapReport) {
                log.info(s);
            }
        }

        if (totalGaps == 0 && totalDuplicates == 0) {
            log.info("✅ 数据时间连续性完美，无断档无重复！");
        } else if (totalGaps == 0) {
            log.info("⚠️ 时间连续，但发现 {} 条重复记录", totalDuplicates);
        } else {
            log.warn("❌ 发现 {} 处断档，需重新下载缺失数据", totalGaps);
        }

        log.info("检测耗时: {}秒", (System.currentTimeMillis() - t0) / 1000);
    }

    /**
     * 检测 eth_kline_second 表数据的时间连续性（一条SQL版本）
     *
     * 使用 MySQL LEAD() 窗口函数，在数据库端直接计算相邻行时间差，
     * 只返回断档行，无需分块遍历所有数据。
     *
     * 检查项：
     * 1. 时间断档（相邻记录间隔 > 1秒）
     * 2. 重复时间戳
     * 3. 数据总量与期望值对比
     */
    @Test
    public void testTimeContinuity2() {
        log.info("========== eth_kline_second 时间连续性检测 ==========");
        long t0 = System.currentTimeMillis();

        // 1. 获取数据范围，判断时间戳格式
        List<EthKlineSecond> latest = ethKlineSecondService.selectRecent(1);
        if (latest.isEmpty()) {
            log.info("✅ 表为空，无需检测");
            return;
        }

        long latestTs = latest.get(0).getTimestamp();
        boolean isMicro = latestTs > 1000000000000000L;
        long latestMs = isMicro ? latestTs / 1000 : latestTs;
        log.info("最新记录: {} ({})", latestTs, formatMs(latestMs));

        List<EthKlineSecond> earliest = ethKlineSecondService.selectByTimeRange(
                isMicro ? 0L : 0L, isMicro ? (latestTs + 1000) : (latestTs + 1000));
        earliest.sort(Comparator.comparingLong(EthKlineSecond::getTimestamp));
        long earliestMs = earliest.isEmpty() ? latestMs
                : (isMicro ? earliest.get(0).getTimestamp() / 1000 : earliest.get(0).getTimestamp());

        // 2. 一条SQL查所有断档
        log.info("正在查询断档...");
        List<Map<String, Object>> gaps = ethKlineSecondMapper.findGaps();

        // 3. 查重复时间戳
        List<Map<String, Object>> duplicates = ethKlineSecondMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<EthKlineSecond>()
                        .select("timestamp, COUNT(*) AS cnt")
                        .groupBy("timestamp")
                        .having("cnt > 1"));

        // 4. 汇总输出
        long totalSeconds = (latestMs - earliestMs) / 1000;
        long expectedRecords = totalSeconds + 1;
        long actualRecords = ethKlineSecondService.count();
        long missingRecords = expectedRecords - actualRecords;

        log.info("========== 检测结果 ==========");
        log.info("数据范围: {} ~ {}", formatMs(earliestMs), formatMs(latestMs));
        log.info("总跨度: {}秒 = {}小时 = {}天",
                totalSeconds, String.format("%.1f", totalSeconds / 3600.0),
                String.format("%.1f", totalSeconds / 86400.0));
        log.info("实际记录: {}条, 期望记录: {}条", actualRecords, expectedRecords);
        log.info("缺失记录: {}条 (缺失率 {}%)", missingRecords,
                String.format("%.2f", expectedRecords > 0 ? (missingRecords * 100.0 / expectedRecords) : 0));
        log.info("断档次数: {}处", gaps.size());
        log.info("重复时间戳: {}组", duplicates.size());

        // 5. 断档详情
        if (!gaps.isEmpty()) {
            long maxGap = 0;
            String maxGapDetail = "";
            int showCount = Math.min(gaps.size(), 20);
            log.info("断档详情 (前{}条):", showCount);
            for (int i = 0; i < showCount; i++) {
                Map<String, Object> g = gaps.get(i);
                long prev = ((Number) g.get("prev_ts")).longValue();
                long curr = ((Number) g.get("curr_ts")).longValue();
                long gapSec = ((Number) g.get("gap_seconds")).longValue();
                if (gapSec > maxGap) {
                    maxGap = gapSec;
                    maxGapDetail = formatMs(prev) + " → " + formatMs(curr);
                }
                log.info("  {} → {} (缺失 {}秒)", formatMs(prev), formatMs(curr), gapSec);
            }
            if (gaps.size() > 20) {
                log.info("  ...还有 {} 处断档未显示", gaps.size() - 20);
            }
            log.info("最大断档: {}秒 ({})", maxGap, maxGapDetail);
        }

        if (gaps.isEmpty() && duplicates.isEmpty()) {
            log.info("✅ 数据时间连续性完美，无断档无重复！");
        } else if (gaps.isEmpty()) {
            log.info("⚠️ 时间连续，但有 {} 组重复时间戳", duplicates.size());
        } else {
            log.warn("❌ 发现 {} 处断档，需重新下载缺失数据", gaps.size());
        }

        log.info("检测耗时: {}秒", (System.currentTimeMillis() - t0) / 1000);
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