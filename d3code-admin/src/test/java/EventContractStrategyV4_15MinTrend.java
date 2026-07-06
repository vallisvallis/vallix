package com.alphay.boot.web.test;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 事件合约策略 V4 - 15分钟趋势确认
 * 策略参数：15分钟趋势确认 + 0.3%阈值 + 20分钟极值窗口
 *
 * 与基准版对比：
 * - 趋势窗口从5分钟扩大到15分钟，需要更长周期的趋势确认
 * - 目的：过滤假突破，减少噪音交易
 * - 预期：信号数量减少，但胜率可能更高
 *
 * @author d3code
 */
@SpringBootTest
public class EventContractStrategyV4_15MinTrend extends EventContractStrategyBase {

    /** 注入数据库Mapper */
    @Autowired
    public void setMapper(com.alphay.boot.bpm.mapper.EthKlineSecondMapper mapper) {
        this.ethKlineSecondMapper = mapper;
    }

    @Override protected String getStrategyName() { return "V4_15分钟趋势"; }
    @Override protected int getTrendMinutes() { return 15; }         // 15分钟趋势确认窗口（更大）
    @Override protected double getTrendThreshold() { return 0.003; } // 0.3%趋势阈值
    @Override protected int getExtremeWindowMinutes() { return 20; }  // 20分钟极值窗口
    @Override protected boolean isOnlyTrendAligned() { return true; } // 只做顺趋势单
    @Override protected boolean isSkipSideways() { return true; }     // 横盘不开单

    /** 执行回测 */
    @Test
    public void backtest() {
        run();
    }
}