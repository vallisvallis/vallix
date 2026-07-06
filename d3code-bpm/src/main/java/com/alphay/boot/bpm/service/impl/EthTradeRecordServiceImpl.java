package com.alphay.boot.bpm.service.impl;

import com.alphay.boot.bpm.api.domain.EthTradeRecord;
import com.alphay.boot.bpm.mapper.EthTradeRecordMapper;
import com.alphay.boot.bpm.service.impl.IEthTradeRecordService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
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
        save(record);
        log.info("✅ 开单记录已保存: id={}, 方向={}, 价格={}",
                record.getId(), record.getDirection(), record.getOpenPrice());
        return record;
    }

    @Override
    public boolean settleTrade(Long id, Long closeTimestamp, String closePrice,
                               String profit, String profitPercent, String status) {
        // 使用UpdateWrapper直接操作列名，避免Lombok生成的setter在运行时找不到
        UpdateWrapper<EthTradeRecord> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", id)
               .set("close_timestamp", closeTimestamp)
               .set("close_price", closePrice)
               .set("profit", profit)
               .set("profit_percent", profitPercent)
               .set("status", status);
        boolean updated = update(wrapper);
        if (updated) {
            log.info("💵 交易结算: id={}, 平仓={}, 收益={}, 状态={}",
                    id, closePrice, profit, status);
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