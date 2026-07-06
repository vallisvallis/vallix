package com.alphay.boot.bpm.service.impl;

import com.alphay.boot.bpm.api.domain.EthTradeRecord;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 交易记录 Service 接口
 */
public interface IEthTradeRecordService extends IService<EthTradeRecord> {

    /** 创建开单记录 */
    EthTradeRecord createTrade(EthTradeRecord record);

    /** 结算交易 */
    boolean settleTrade(Long id, Long closeTimestamp, String closePrice,
                        String profit, String profitPercent, String status);

    /** 获取统计信息 */
    TradeStats getStats();

    class TradeStats {
        public long totalTrades;
        public long pendingTrades;
        public long winCount;
        public long loseCount;
        public double totalProfit;
        public double winRate;
    }
}