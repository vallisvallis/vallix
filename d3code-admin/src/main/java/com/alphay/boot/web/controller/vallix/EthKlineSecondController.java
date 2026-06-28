
package com.alphay.boot.web.controller.vallix;

import com.alphay.boot.bpm.api.domain.EthKlineSecond;

import com.alphay.boot.bpm.service.impl.IEthKlineSecondService;
import com.alphay.boot.common.core.domain.AjaxResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

/**
 * ETH秒级K线数据Controller
 *
 * @author d3code
 */
@Slf4j
@RestController
@RequestMapping("/system/ethkline")
public class EthKlineSecondController {

    @Resource
    private IEthKlineSecondService ethKlineSecondService;

    /**
     * 查询最近N条秒级K线数据
     *
     * @param limit 数量限制（默认1000条）
     * @return K线数据列表
     */
    @GetMapping("/recent")
    public AjaxResult getRecentKline(@RequestParam(defaultValue = "1000") int limit) {
        log.info("查询ETH秒级K线数据，数量: {}", limit);
        List<EthKlineSecond> data = ethKlineSecondService.selectRecent(limit);
        return AjaxResult.success(data);
    }

    /**
     * 根据时间范围查询秒级K线数据
     *
     * @param startTime 开始时间（毫秒时间戳）
     * @param endTime   结束时间（毫秒时间戳）
     * @return K线数据列表
     */
    @GetMapping("/range")
    public AjaxResult getKlineByTimeRange(
            @RequestParam Long startTime,
            @RequestParam Long endTime) {
        log.info("查询ETH秒级K线数据，时间范围: {} - {}", startTime, endTime);
        List<EthKlineSecond> data = ethKlineSecondService.selectByTimeRange(startTime, endTime);
        return AjaxResult.success(data);
    }

    /**
     * 查询最近N条秒级K线数据（包含10分钟对比）
     *
     * @param limit 数量限制（默认1000条）
     * @return K线数据列表
     */
    @GetMapping("/recentWithCompare")
    public AjaxResult getRecentKlineWithCompare(@RequestParam(defaultValue = "1000") int limit) {
        log.info("查询ETH秒级K线数据（包含10分钟对比），数量: {}", limit);
        List<?> data = ethKlineSecondService.selectRecentWithCompare(limit);
        return AjaxResult.success(data);
    }

    /**
     * 根据时间范围查询秒级K线数据（包含10分钟对比）
     *
     * @param startTime 开始时间（毫秒时间戳）
     * @param endTime   结束时间（毫秒时间戳）
     * @return K线数据列表
     */
    @GetMapping("/rangeWithCompare")
    public AjaxResult getKlineByTimeRangeWithCompare(
            @RequestParam Long startTime,
            @RequestParam Long endTime) {
        log.info("查询ETH秒级K线数据（包含10分钟对比），时间范围: {} - {}", startTime, endTime);
        List<?> data = ethKlineSecondService.selectByTimeRangeWithCompare(startTime, endTime);
        return AjaxResult.success(data);
    }

    /**
     * 保存秒级K线数据
     *
     * @param data K线数据
     * @return 是否成功
     */
    @PostMapping("/save")
    public AjaxResult saveKline(@RequestBody EthKlineSecond data) {
        log.info("保存ETH秒级K线数据，时间戳: {}", data.getTimestamp());
        boolean success = ethKlineSecondService.saveKlineData(data);
        return success ? AjaxResult.success() : AjaxResult.error();
    }

    /**
     * 批量保存秒级K线数据
     *
     * @param dataList K线数据列表
     * @return 是否成功
     */
    @PostMapping("/batchSave")
    public AjaxResult batchSaveKline(@RequestBody List<EthKlineSecond> dataList) {
        log.info("批量保存ETH秒级K线数据，数量: {}", dataList.size());
        boolean success = ethKlineSecondService.saveBatchKlineData(dataList);
        return success ? AjaxResult.success() : AjaxResult.error();
    }

    /**
     * 清理过期数据（保留最近一个月）
     *
     * @return 删除数量
     */
    @DeleteMapping("/clean")
    public AjaxResult cleanExpiredData() {
        log.info("清理ETH秒级K线过期数据");
        int deleted = ethKlineSecondService.cleanExpiredData();
        return AjaxResult.success("清理完成，删除 " + deleted + " 条记录");
    }
}