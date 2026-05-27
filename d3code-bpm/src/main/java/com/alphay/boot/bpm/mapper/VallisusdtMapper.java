package com.alphay.boot.bpm.mapper;

import com.alphay.boot.bpm.api.domain.Vallisusdt;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

/**
 * 【请填写功能名称】Mapper接口
 *
 * @author ruoyi
 * @date 2025-05-26
 */
public interface VallisusdtMapper extends BaseMapper<Vallisusdt> {
    /**
     * 查询【请填写功能名称】列表
     *
     * @param vallisusdt 【请填写功能名称】
     * @return 【请填写功能名称】集合
     */
    public List<Vallisusdt> selectVallisusdtList(Vallisusdt vallisusdt);

}
