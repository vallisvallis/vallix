package com.alphay.boot.bpm.service.impl;

import com.alphay.boot.bpm.api.domain.Vallisusdt;
import com.alphay.boot.bpm.mapper.VallisusdtMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 【请填写功能名称】Service业务层处理
 *
 * @author ruoyi
 * @date 2025-05-26
 */
@Service
public class VallisusdtServiceImpl extends ServiceImpl<VallisusdtMapper, Vallisusdt> implements IVallisusdtService {


    /**
     * 查询【请填写功能名称】列表
     *
     * @param vallisusdt 【请填写功能名称】
     * @return 【请填写功能名称】
     */
    @Override
    public List<Vallisusdt> selectVallisusdtList(Vallisusdt vallisusdt) {
        return baseMapper.selectVallisusdtList(vallisusdt);
    }

    @Override
    public boolean save(Vallisusdt entity) {
        return super.save(entity);
    }
}
