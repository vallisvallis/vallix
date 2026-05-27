package com.alphay.boot.bpm.service.impl;

import com.alphay.boot.bpm.api.domain.Vallisusdt;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 【请填写功能名称】Service接口
 *
 * @author ruoyi
 * @date 2025-05-26
 */
public interface IVallisusdtService extends IService<Vallisusdt> {

    /**
     * 查询【请填写功能名称】列表
     *
     * @param vallisusdt 【请填写功能名称】
     * @return 【请填写功能名称】集合
     */
    public List<Vallisusdt> selectVallisusdtList(Vallisusdt vallisusdt);

}
