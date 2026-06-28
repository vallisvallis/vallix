package com.alphay.boot.web.test;

import com.alphay.boot.bpm.api.domain.EthKlineSecond;
import com.alphay.boot.bpm.mapper.EthKlineSecondMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 交易策略回测测试（高效版）
 * 针对250万条数据进行优化，支持微秒级时间戳
 *
 * 策略：从每一秒开始，在过去20分钟内的最高价格时刻开空，最低价格时刻开多
 * 统计：开单时刻10分钟后的结果
 */
@SpringBootTest
public class TradingStrategyBacktest {

    @Autowired
    private EthKlineSecondMapper ethKlineSecondMapper;

    // ===== 时间常量（微秒级）=====
    private static final long TWENTY_MINUTES_US = 1200000000L;
    private static final long TEN_MINUTES_US = 600000000L;
    private static final long ONE_MINUTE_US = 60000000L;
    private static final long THREE_MINUTES_US = 180000000L; // 3分钟，开单时间与极值时间的最大间隔
    private static final int BATCH_WRITE_SIZE = 10000;

    @Test
    public void testTradingStrategy() throws IOException {
        System.out.println("======================================");
        System.out.println("    交易策略回测开始");
        System.out.println("======================================");
        long startTime = System.currentTimeMillis();

        System.out.println("\n[1/4] 正在加载数据...");
        List<EthKlineSecond> allData = ethKlineSecondMapper.selectRecent(3000000);
        if (allData.isEmpty()) {
            System.out.println("数据库中没有数据");
            return;
        }
        System.out.println("数据加载完成，共 " + formatNumber(allData.size()) + " 条");

        if (!allData.isEmpty()) {
            long sampleTs = allData.get(0).getTimestamp();
            System.out.println("时间戳格式检测: " + sampleTs + " (" +
                    (sampleTs > 1000000000000000L ? "微秒级" : "毫秒级") + ")");
        }

        System.out.println("\n[2/4] 正在排序数据...");
        Collections.sort(allData, Comparator.comparingLong(EthKlineSecond::getTimestamp));

        System.out.println("[2.5/4] 正在验证时间连续性...");
        validateTimeContinuity(allData);

        System.out.println("[3/4] 正在构建索引...");
        Map<Long, EthKlineSecond> dataMap = new HashMap<>(allData.size());
        List<Long> timestamps = new ArrayList<>(allData.size());
        for (EthKlineSecond data : allData) {
            dataMap.put(data.getTimestamp(), data);
            timestamps.add(data.getTimestamp());
        }

        System.out.println("\n[4/4] 正在执行策略回测...");
        List<TradeRecord> tradeRecords = executeSlidingWindowStrategy(allData, dataMap, timestamps);

        long endTime = System.currentTimeMillis();
        printStatistics(tradeRecords, endTime - startTime);

        String outputPath = "D:\\trading_backtest_result.xlsx";
        saveToExcel(tradeRecords, outputPath);
        System.out.println("\n✅ 结果已保存到: " + outputPath);
        System.out.println("\n======================================");
        System.out.println("    交易策略回测完成");
        System.out.println("======================================");
    }

    private List<TradeRecord> executeSlidingWindowStrategy(List<EthKlineSecond> allData,
                                                           Map<Long, EthKlineSecond> dataMap,
                                                           List<Long> timestamps) {
        List<TradeRecord> tradeRecords = new ArrayList<>();
        Deque<Integer> maxDeque = new LinkedList<>();
        Deque<Integer> minDeque = new LinkedList<>();
        int left = 0;
        long lastShortTs = -1;
        long lastLongTs = -1;
        long lastTradeTs = -1; // 上次开单时间，用于控制每分钟只开一单

        for (int right = 0; right < allData.size(); right++) {
            EthKlineSecond currentData = allData.get(right);
            long currentTs = currentData.getTimestamp();
            double currentPrice = parseDouble(currentData.getClose());

            while (!maxDeque.isEmpty() &&
                    parseDouble(allData.get(maxDeque.peekLast()).getClose()) <= currentPrice) {
                maxDeque.pollLast();
            }
            maxDeque.offerLast(right);

            while (!minDeque.isEmpty() &&
                    parseDouble(allData.get(minDeque.peekLast()).getClose()) >= currentPrice) {
                minDeque.pollLast();
            }
            minDeque.offerLast(right);

            long windowStartTime = currentTs - TWENTY_MINUTES_US;
            while (timestamps.get(left) < windowStartTime) {
                if (maxDeque.peekFirst() == left) maxDeque.pollFirst();
                if (minDeque.peekFirst() == left) minDeque.pollFirst();
                left++;
            }

            // 跳过前1200行（20分钟 = 1200秒），确保有足够的历史数据参与计算
            if (right >= 1200 && right - left >= 60 && !maxDeque.isEmpty() && !minDeque.isEmpty()) {
                int maxIndex = maxDeque.peekFirst();
                int minIndex = minDeque.peekFirst();
                long maxTs = timestamps.get(maxIndex);
                long minTs = timestamps.get(minIndex);
                double maxPrice = parseDouble(allData.get(maxIndex).getClose());
                double minPrice = parseDouble(allData.get(minIndex).getClose());

                // 开单时间必须比最高价/最低价时刻晚至少1分钟，且不得超过3分钟
                // 20分钟窗口范围: [maxTs - TWENTY_MINUTES_US, maxTs)
                // 同时检查冷却时间：上次开单后至少过了一分钟才能再次开单
                if (currentTs - maxTs >= ONE_MINUTE_US && currentTs - maxTs <= THREE_MINUTES_US 
                        && currentTs > maxTs && maxTs != lastShortTs
                        && (lastTradeTs == -1 || currentTs - lastTradeTs >= ONE_MINUTE_US)) {
                    TradeRecord trade = createTradeRecord(allData.get(maxIndex), "空单", maxPrice, minPrice, 
                            formatTimestamp(maxTs), formatTimestamp(minTs), dataMap, currentTs);
                    tradeRecords.add(trade);
                    lastShortTs = maxTs;
                    lastTradeTs = currentTs; // 更新上次开单时间
                }

                if (currentTs - minTs >= ONE_MINUTE_US && currentTs - minTs <= THREE_MINUTES_US 
                        && currentTs > minTs && minTs != lastLongTs
                        && (lastTradeTs == -1 || currentTs - lastTradeTs >= ONE_MINUTE_US)) {
                    TradeRecord trade = createTradeRecord(allData.get(minIndex), "多单", maxPrice, minPrice, 
                            formatTimestamp(maxTs), formatTimestamp(minTs), dataMap, currentTs);
                    tradeRecords.add(trade);
                    lastLongTs = minTs;
                    lastTradeTs = currentTs; // 更新上次开单时间
                }
            }

            if ((right + 1) % 100000 == 0) {
                System.out.println("  处理进度: " + formatNumber(right + 1) + "/" + formatNumber(allData.size()) +
                        " (" + String.format("%.1f%%", ((right + 1) * 100.0 / allData.size())) + ")");
            }
        }

        return tradeRecords;
    }

    private TradeRecord createTradeRecord(EthKlineSecond openData, String direction,
                                          double high20min, double low20min,
                                          String high20minTime, String low20minTime,
                                          Map<Long, EthKlineSecond> dataMap, long openTs) {
        TradeRecord record = new TradeRecord();
        record.setOpenTime(formatTimestamp(openTs));
        record.setOpenTimestamp(openTs);
        record.setDirection(direction);
        record.setOpenPrice(parseDouble(openData.getClose()));
        record.setHigh20min(high20min);
        record.setLow20min(low20min);
        record.setHigh20minTime(high20minTime);
        record.setLow20minTime(low20minTime);

        long targetTs = openTs + TEN_MINUTES_US;
        EthKlineSecond targetData = dataMap.get(targetTs);

        if (targetData != null) {
            record.setTenMinuteLaterPrice(parseDouble(targetData.getClose()));
            record.setTenMinuteLaterTime(formatTimestamp(targetTs));
            double profit = "多单".equals(direction)
                    ? record.getTenMinuteLaterPrice() - record.getOpenPrice()
                    : record.getOpenPrice() - record.getTenMinuteLaterPrice();
            record.setProfit(profit);
            record.setProfitPercent((profit / record.getOpenPrice()) * 100);
            record.setWin(profit > 0);
        }

        return record;
    }

    private void validateTimeContinuity(List<EthKlineSecond> data) {
        if (data.isEmpty()) {
            System.out.println("  ⚠️ 数据为空，跳过时间连续性验证");
            return;
        }

        int gaps = 0;
        long prevTs = data.get(0).getTimestamp();
        long expectedInterval = 1000000L; // 秒级数据，间隔应为1秒（微秒级）

        for (int i = 1; i < data.size(); i++) {
            long currentTs = data.get(i).getTimestamp();
            long diff = currentTs - prevTs;

            if (diff != expectedInterval) {
                gaps++;
                if (gaps <= 5) { // 只显示前5个间隔异常
                    System.out.println("  ⚠️ 时间间隔异常: 位置=" + i + 
                            ", 前一时间=" + formatTimestamp(prevTs) + 
                            ", 当前时间=" + formatTimestamp(currentTs) + 
                            ", 间隔=" + (diff / 1000000.0) + "秒");
                }
            }
            prevTs = currentTs;
        }

        if (gaps == 0) {
            System.out.println("  ✅ 时间连续性验证通过，数据连续");
        } else {
            System.out.println("  ⚠️ 时间连续性验证完成，发现 " + gaps + " 处时间间隔异常");
        }

        // 计算总天数
        long firstTs = data.get(0).getTimestamp();
        long lastTs = data.get(data.size() - 1).getTimestamp();
        double days = (lastTs - firstTs) / (1000000.0 * 60 * 60 * 24);
        System.out.println("  📅 数据覆盖时间: " + String.format("%.2f", days) + " 天");
    }

    private void printStatistics(List<TradeRecord> records, long elapsedTime) {
        if (records.isEmpty()) {
            System.out.println("\n⚠️ 没有生成任何交易记录");
            return;
        }

        List<TradeRecord> longTrades = records.stream()
                .filter(t -> "多单".equals(t.getDirection()))
                .collect(Collectors.toList());

        List<TradeRecord> shortTrades = records.stream()
                .filter(t -> "空单".equals(t.getDirection()))
                .collect(Collectors.toList());

        double totalProfit = records.stream().mapToDouble(TradeRecord::getProfit).sum();
        double avgProfit = records.stream().mapToDouble(TradeRecord::getProfit).average().orElse(0);
        double maxProfit = records.stream().mapToDouble(TradeRecord::getProfit).max().orElse(0);
        double minProfit = records.stream().mapToDouble(TradeRecord::getProfit).min().orElse(0);
        long winCount = records.stream().filter(TradeRecord::isWin).count();
        long loseCount = records.size() - winCount;

        // 固定收益计算：盈利+4U，亏损-5U
        double fixedTotalProfit = winCount * 4.0 - loseCount * 5.0;
        double fixedAvgProfit = records.isEmpty() ? 0 : fixedTotalProfit / records.size();

        // 统计每日开单数量
        Map<String, Long> dailyCounts = records.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getOpenTime().substring(0, 10), // 提取日期部分
                        Collectors.counting()
                ));

        double avgDailyTrades = dailyCounts.values().stream().mapToLong(Long::longValue).average().orElse(0);
        long maxDailyTrades = dailyCounts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        long minDailyTrades = dailyCounts.values().stream().mapToLong(Long::longValue).min().orElse(0);

        System.out.println("\n📊 策略回测统计报告");
        System.out.println("──────────────────────────────────────");
        System.out.println("总耗时: " + formatTime(elapsedTime));
        System.out.println("──────────────────────────────────────");
        System.out.println("交易总数: " + formatNumber(records.size()));
        System.out.println("  ├─ 多单: " + formatNumber(longTrades.size()));
        System.out.println("  └─ 空单: " + formatNumber(shortTrades.size()));
        System.out.println("──────────────────────────────────────");
        System.out.println("每日开单统计:");
        System.out.println("  ├─ 平均每日开单: " + String.format("%.1f", avgDailyTrades) + " 单");
        System.out.println("  ├─ 最多每日开单: " + formatNumber(maxDailyTrades) + " 单");
        System.out.println("  └─ 最少每日开单: " + formatNumber(minDailyTrades) + " 单");
        System.out.println("──────────────────────────────────────");
        System.out.println("胜率: " + String.format("%.2f%%", (winCount * 100.0 / records.size())));
        System.out.println("──────────────────────────────────────");
        System.out.println("收益统计:");
        System.out.println("  ├─ 总收益: $" + String.format("%.2f", totalProfit));
        System.out.println("  ├─ 平均收益: $" + String.format("%.2f", avgProfit));
        System.out.println("  ├─ 最大盈利: $" + String.format("%.2f", maxProfit));
        System.out.println("  └─ 最大亏损: $" + String.format("%.2f", minProfit));
        System.out.println("──────────────────────────────────────");
        System.out.println("固定收益统计 (盈利+4U, 亏损-5U):");
        System.out.println("  ├─ 盈利次数: " + winCount + " × 4U = +" + String.format("%.2f", winCount * 4.0) + "U");
        System.out.println("  ├─ 亏损次数: " + loseCount + " × 5U = -" + String.format("%.2f", loseCount * 5.0) + "U");
        System.out.println("  ├─ 固定总收益: " + (fixedTotalProfit >= 0 ? "+" : "") + String.format("%.2f", fixedTotalProfit) + "U");
        System.out.println("  └─ 固定平均收益: " + String.format("%.2f", fixedAvgProfit) + "U/单");
        System.out.println("──────────────────────────────────────");
    }

    private void saveToExcel(List<TradeRecord> records, String filePath) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            SXSSFSheet dataSheet = workbook.createSheet("交易记录");
            createDataSheet(workbook, dataSheet, records);

            SXSSFSheet statsSheet = workbook.createSheet("统计汇总");
            createStatsSheet(workbook, statsSheet, records);

            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                workbook.write(fos);
            }
        }
    }

    private void createDataSheet(SXSSFWorkbook workbook, SXSSFSheet sheet, List<TradeRecord> records) throws IOException {
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);

        CellStyle winStyle = workbook.createCellStyle();
        Font winFont = workbook.createFont();
        winFont.setColor(IndexedColors.GREEN.getIndex());
        winStyle.setFont(winFont);

        CellStyle loseStyle = workbook.createCellStyle();
        Font loseFont = workbook.createFont();
        loseFont.setColor(IndexedColors.RED.getIndex());
        loseStyle.setFont(loseFont);

        // 基于最高价开单（空单）行背景色：浅红色
        CellStyle shortRowStyle = workbook.createCellStyle();
        shortRowStyle.setFillForegroundColor(IndexedColors.ROSE.getIndex());
        shortRowStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        // 基于最低价开单（多单）行背景色：浅绿色
        CellStyle longRowStyle = workbook.createCellStyle();
        longRowStyle.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
        longRowStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        Row headerRow = sheet.createRow(0);
        String[] headers = {"序号", "开单时刻", "开单方向", "开单价格", 
                "20分钟内最高价", "最高价时间", "20分钟内最低价", "最低价时间",
                "10分钟后时刻", "10分钟后价格", "收益", "收益率(%)", "盈亏"};

        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowNum = 1;
        int seq = 1;
        for (TradeRecord record : records) {
            Row row = sheet.createRow(rowNum++);
            // 根据开单方向设置行背景色
            CellStyle rowStyle = "空单".equals(record.getDirection()) ? shortRowStyle : longRowStyle;

            for (int i = 0; i < headers.length; i++) {
                Cell cell = row.createCell(i);
                cell.setCellStyle(rowStyle);
            }

            row.getCell(0).setCellValue(seq++);
            row.getCell(1).setCellValue(record.getOpenTime());
            row.getCell(2).setCellValue(record.getDirection());
            row.getCell(3).setCellValue(record.getOpenPrice());
            row.getCell(4).setCellValue(record.getHigh20min());
            row.getCell(5).setCellValue(record.getHigh20minTime() != null ? record.getHigh20minTime() : "-");
            row.getCell(6).setCellValue(record.getLow20min());
            row.getCell(7).setCellValue(record.getLow20minTime() != null ? record.getLow20minTime() : "-");
            row.getCell(8).setCellValue(record.getTenMinuteLaterTime() != null ? record.getTenMinuteLaterTime() : "-");
            row.getCell(9).setCellValue(record.getTenMinuteLaterPrice());

            Cell profitCell = row.getCell(10);
            profitCell.setCellValue(record.getProfit());
            profitCell.setCellStyle(record.isWin() ? winStyle : loseStyle);

            Cell percentCell = row.getCell(11);
            percentCell.setCellValue(String.format("%.2f", record.getProfitPercent()));
            percentCell.setCellStyle(record.isWin() ? winStyle : loseStyle);

            row.getCell(12).setCellValue(record.isWin() ? "盈利" : "亏损");

            if (rowNum % BATCH_WRITE_SIZE == 0) {
                sheet.flushRows();
            }
        }

        for (int i = 0; i < headers.length; i++) sheet.setColumnWidth(i, 4000);
        sheet.setColumnWidth(1, 6000);
        sheet.setColumnWidth(5, 6000);
        sheet.setColumnWidth(7, 6000);
        sheet.setColumnWidth(8, 6000);
    }

    private void createStatsSheet(SXSSFWorkbook workbook, SXSSFSheet sheet, List<TradeRecord> records) throws IOException {
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerStyle.setFont(headerFont);

        List<TradeRecord> longTrades = records.stream()
                .filter(t -> "多单".equals(t.getDirection()))
                .collect(Collectors.toList());

        int rowNum = 0;
        Row titleRow = sheet.createRow(rowNum++);
        titleRow.createCell(0).setCellValue("交易策略回测统计汇总");
        titleRow.getCell(0).setCellStyle(headerStyle);

        rowNum++;
        sheet.createRow(rowNum++).createCell(0).setCellValue("交易总数: " + records.size());
        sheet.createRow(rowNum++).createCell(0).setCellValue("多单数量: " + longTrades.size());
        sheet.createRow(rowNum++).createCell(0).setCellValue("空单数量: " + (records.size() - longTrades.size()));

        rowNum++;
        long winCount = records.stream().filter(TradeRecord::isWin).count();
        sheet.createRow(rowNum++).createCell(0).setCellValue("胜率: " + String.format("%.2f%%", (winCount * 100.0 / records.size())));

        rowNum++;
        double totalProfit = records.stream().mapToDouble(TradeRecord::getProfit).sum();
        sheet.createRow(rowNum++).createCell(0).setCellValue("总收益: $" + String.format("%.2f", totalProfit));
        sheet.createRow(rowNum++).createCell(0).setCellValue("平均收益: $" + String.format("%.2f",
                records.stream().mapToDouble(TradeRecord::getProfit).average().orElse(0)));

        rowNum++;
        long loseCount = records.size() - winCount;
        double fixedTotalProfit = winCount * 4.0 - loseCount * 5.0;
        sheet.createRow(rowNum++).createCell(0).setCellValue("固定收益统计 (盈利+4U, 亏损-5U):");
        sheet.createRow(rowNum++).createCell(0).setCellValue("  盈利次数: " + winCount + " × 4U = +" + String.format("%.2f", winCount * 4.0) + "U");
        sheet.createRow(rowNum++).createCell(0).setCellValue("  亏损次数: " + loseCount + " × 5U = -" + String.format("%.2f", loseCount * 5.0) + "U");
        sheet.createRow(rowNum++).createCell(0).setCellValue("  固定总收益: " + (fixedTotalProfit >= 0 ? "+" : "") + String.format("%.2f", fixedTotalProfit) + "U");

        sheet.setColumnWidth(0, 8000);
    }

    private String formatTimestamp(long timestamp) {
        long ts = timestamp > 1000000000000000L ? timestamp / 1000 : timestamp;
        Date date = new Date(ts);
        return String.format("%tF %tT", date, date);
    }

    private double parseDouble(String value) {
        try {
            if (value == null) return 0;
            return Double.parseDouble(value.replace(",", "").trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private String formatNumber(long num) {
        return String.format("%,d", num);
    }

    private String formatTime(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        if (hours > 0) return String.format("%d小时%d分钟%d秒", hours, minutes % 60, seconds % 60);
        if (minutes > 0) return String.format("%d分钟%d秒", minutes, seconds % 60);
        return String.format("%d毫秒", millis);
    }

    public static class TradeRecord {
        private String openTime;
        private long openTimestamp;
        private String direction;
        private double openPrice;
        private double high20min;
        private String high20minTime;  // 20分钟内最高价出现的时间
        private double low20min;
        private String low20minTime;   // 20分钟内最低价出现的时间
        private String tenMinuteLaterTime;
        private double tenMinuteLaterPrice;
        private double profit;
        private double profitPercent;
        private boolean win;

        public String getOpenTime() { return openTime; }
        public void setOpenTime(String openTime) { this.openTime = openTime; }
        public long getOpenTimestamp() { return openTimestamp; }
        public void setOpenTimestamp(long openTimestamp) { this.openTimestamp = openTimestamp; }
        public String getDirection() { return direction; }
        public void setDirection(String direction) { this.direction = direction; }
        public double getOpenPrice() { return openPrice; }
        public void setOpenPrice(double openPrice) { this.openPrice = openPrice; }
        public double getHigh20min() { return high20min; }
        public void setHigh20min(double high20min) { this.high20min = high20min; }
        public String getHigh20minTime() { return high20minTime; }
        public void setHigh20minTime(String high20minTime) { this.high20minTime = high20minTime; }
        public double getLow20min() { return low20min; }
        public void setLow20min(double low20min) { this.low20min = low20min; }
        public String getLow20minTime() { return low20minTime; }
        public void setLow20minTime(String low20minTime) { this.low20minTime = low20minTime; }
        public String getTenMinuteLaterTime() { return tenMinuteLaterTime; }
        public void setTenMinuteLaterTime(String tenMinuteLaterTime) { this.tenMinuteLaterTime = tenMinuteLaterTime; }
        public double getTenMinuteLaterPrice() { return tenMinuteLaterPrice; }
        public void setTenMinuteLaterPrice(double tenMinuteLaterPrice) { this.tenMinuteLaterPrice = tenMinuteLaterPrice; }
        public double getProfit() { return profit; }
        public void setProfit(double profit) { this.profit = profit; }
        public double getProfitPercent() { return profitPercent; }
        public void setProfitPercent(double profitPercent) { this.profitPercent = profitPercent; }
        public boolean isWin() { return win; }
        public void setWin(boolean win) { this.win = win; }
    }
}