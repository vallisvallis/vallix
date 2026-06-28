package com.alphay.boot.bpm.service.impl;

import com.alphay.boot.bpm.api.domain.EthTradeRecord;
import com.alphay.boot.bpm.mapper.EthTradeRecordMapper;
import com.alphay.boot.bpm.service.IEthTradeRecordService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 交易记录 Service 实现
 */
@Slf4j
@Service
public class EthTradeRecordServiceImpl
        extends ServiceImpl<EthTradeRecordMapper, EthTradeRecord>
        implements IEthTradeRecordService {

    @Override
    public EthTradeRecord createTrade(EthTradeRecord record) {
        record.setStatus("待结算");
        save(record);
        log.info("✅ 开单记录已保存: id={}, 方向={}, 价格={}",
                record.getId(), record.getDirection(), record.getOpenPrice());
        return record;
    }

    @Override
    public boolean settleTrade(Long id, Long closeTimestamp, String closePrice,
                               String profit, String profitPercent, String status) {
        EthTradeRecord record = getById(id);
        if (record == null || !"待结算".equals(record.getStatus())) {
            return false;
        }
        record.setCloseTimestamp(closeTimestamp);
        record.setClosePrice(closePrice);
        record.setProfit(profit);
        record.setProfitPercent(profitPercent);
        record.setStatus(status);
        boolean updated = updateById(record);
        if (updated) {
            log.info("💰 交易结算: id={}, 方向={}, 开仓={}, 平仓={}, 收益={}, 状态={}",
                    id, record.getDirection(), record.getOpenPrice(),
                    closePrice, profit, status);
        }
        return updated;
    }

    @Override
    public TradeStats getStats() {
        TradeStats stats = new TradeStats();
        stats.totalTrades = count(new LambdaQueryWrapper<EthTradeRecord>()
                .eq(EthTradeRecord::getDeleted, 0));
        stats.pendingTrades = count(new LambdaQueryWrapper<EthTradeRecord>()
                .eq(EthTradeRecord::getDeleted, 0)
                .eq(EthTradeRecord::getStatus, "待结算"));
        stats.winCount = count(new LambdaQueryWrapper<EthTradeRecord>()
                .eq(EthTradeRecord::getDeleted, 0)
                .eq(EthTradeRecord::getStatus, "盈利"));
        stats.loseCount = count(new LambdaQueryWrapper<EthTradeRecord>()
                .eq(EthTradeRecord::getDeleted, 0)
                .eq(EthTradeRecord::getStatus, "亏损"));

        List<EthTradeRecord> settled = list(new LambdaQueryWrapper<EthTradeRecord>()
                .eq(EthTradeRecord::getDeleted, 0)
                .isNotNull(EthTradeRecord::getProfit));
        stats.totalProfit = settled.stream()
                .mapToDouble(r -> Double.parseDouble(r.getProfit()))
                .sum();
        if (stats.totalTrades > 0) {
            stats.winRate = (stats.winCount * 100.0) / stats.totalTrades;
        }
        return stats;
    }
}