
package com.alphay.boot.bpm.service.impl;

import com.alphay.boot.bpm.api.domain.EthKlineSecond;
import com.alphay.boot.bpm.api.domain.EthKlineSecondCompareVO;
import com.alphay.boot.bpm.mapper.EthKlineSecondMapper;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ETH秒级K线数据Service业务层处理
 *
 * @author d3code
 */
@Service
@Slf4j
public class EthKlineSecondServiceImpl extends ServiceImpl<EthKlineSecondMapper, EthKlineSecond>
        implements IEthKlineSecondService {

    /**
     * 数据保留时间（30天）
     */
    private static final long RETENTION_DAYS = 30;

    @Override
    public boolean saveKlineData(EthKlineSecond data) {
        return save(data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveBatchKlineData(List<EthKlineSecond> dataList) {
        if (dataList == null || dataList.isEmpty()) {
            return true;
        }
        return saveBatch(dataList, 1000);
    }

    @Override
    public List<EthKlineSecond> selectByTimeRange(Long startTime, Long endTime) {
        return baseMapper.selectByTimeRange(startTime, endTime);
    }

    @Override
    public List<EthKlineSecond> selectRecent(int limit) {
        return baseMapper.selectRecent(limit);
    }

    @Override
    public List<EthKlineSecondCompareVO> selectRecentWithCompare(int limit) {
        return baseMapper.selectRecentWithCompare(limit);
    }

    @Override
    public List<EthKlineSecondCompareVO> selectByTimeRangeWithCompare(Long startTime, Long endTime) {
        return baseMapper.selectByTimeRangeWithCompare(startTime, endTime);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cleanExpiredData() {
        long beforeTime = System.currentTimeMillis() - (RETENTION_DAYS * 24 * 60 * 60 * 1000L);
        int deleted = baseMapper.deleteBefore(beforeTime);
        log.info("清理ETH秒级K线过期数据，删除 {} 条记录", deleted);
        return deleted;
    }
}