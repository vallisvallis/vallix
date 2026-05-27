package com.alphay.boot.bpm.api.domain;

import com.alphay.boot.common.annotation.Excel;
import com.alphay.boot.common.core.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.util.Date;

/**
 * 【请填写功能名称】对象 vallisusdt
 *
 * @author ruoyi
 * @date 2025-05-26
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Vallisusdt extends BaseEntity {
    private static final long serialVersionUID = 1L;

    /**
     * $column.columnComment
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    /**1
     * 开始时间
     */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private Date startTime;
    /**
     * 结束时间
     */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private Date endTime;
    /**
     * 开盘价
     */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private String startPrice;
    /**
     * 最高价
     */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private String maxPrice;
    /**
     * 最低价
     */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private String minPrice;
    /**
     * 收盘价
     */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private String endPrice;
    /**
     * 成交量
     */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private String calcCount;
    /**
     * 成交额
     */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private String priceCount;
    /**
     * 成交笔数
     */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private String numCount;
    /**
     * 主动买入成交量
     */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private String zdBuyCount;
    /**
     * 主动买入成交额
     */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private String zdSellCount;
    /**
     * $column.columnComment
     */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private String resultStr;

    private String resultPrice;

    public Double getSp() {
        return Double.parseDouble(this.startPrice);
    }


    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
                .append("id", getId())
                .append("startTime", getStartTime())
                .append("endTime", getEndTime())
                .append("startPrice", getStartPrice())
                .append("maxPrice", getMaxPrice())
                .append("minPrice", getMinPrice())
                .append("endPrice", getEndPrice())
                .append("calcCount", getCalcCount())
                .append("priceCount", getPriceCount())
                .append("numCount", getNumCount())
                .append("zdBuyCount", getZdBuyCount())
                .append("zdSellCount", getZdSellCount())
                .append("resultStr", getResultStr())
                .append("createTime", getCreateTime())
                .append("updateTime", getUpdateTime())
                .append("deleted", getDeleted())
                .append("createBy", getCreateBy())
                .append("updateBy", getUpdateBy())
                .append("remark", getRemark())
                .toString();
    }
}
