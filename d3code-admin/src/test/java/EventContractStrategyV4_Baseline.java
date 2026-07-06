package com.alphay.boot.web.test;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 事件合约策略 V4 - 基准版
 * 策略参数：5分钟趋势确认 + 0.3%阈值 + 20分钟极值窗口
 *
 * 核心逻辑：
 * - 20分钟窗口内找极值（最高/最低）
 * - 极值出现1~3分钟后开单
 * - 5分钟趋势确认方向（>0.3%为有效趋势）
 * - 只做顺趋势单，横盘不开单
 * - 到期判涨跌：到期价格 > 开单价格 = 多单正确
 *
 * 这是其他策略变体的基准，用于对比不同参数组合的效果
 *
 * @author d3code
 */
@SpringBootTest
public class EventContractStrategyV4_Baseline extends EventContractStrategyBase {

    /** 注入数据库Mapper，通过setter注入避免构造器注入的继承问题 */
    @Autowired
    public void setMapper(com.alphay.boot.bpm.mapper.EthKlineSecondMapper mapper) {
        this.ethKlineSecondMapper = mapper;
    }

    @Override protected String getStrategyName() { return "V4_基准 5min/0.3%"; }
    @Override protected int getTrendMinutes() { return 5; }          // 5分钟趋势确认窗口
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