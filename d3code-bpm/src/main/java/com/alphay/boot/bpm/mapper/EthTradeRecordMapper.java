package com.alphay.boot.bpm.mapper;

import com.alphay.boot.bpm.api.domain.EthTradeRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 交易记录 Mapper
 */
@Mapper
public interface EthTradeRecordMapper extends BaseMapper<EthTradeRecord> {

    /** 查询待结算的交易 */
    List<EthTradeRecord> selectPendingRecords();

    /** 按时间范围查询交易记录 */
    List<EthTradeRecord> selectByTimeRange(@Param("startTime") Long startTime, @Param("endTime") Long endTime);
}