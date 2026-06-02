<template>
  <div class="kline-monitor-container">
    <!-- 头部标题 -->
    <div class="header">
      <div class="title-section">
        <h1>ETH/USDT K线监控（近一周）</h1>
        <div class="status-bar">
          <span class="update-time">最后更新: {{ formatTime(updateTime) }}</span>
          <span class="refresh-btn" @click="loadAllKlineData">
            <i class="el-icon-refresh" :class="{ 'refreshing': klineLoading }"></i>
            刷新
          </span>
        </div>
      </div>
    </div>

    <!-- 1分钟K线图 -->
    <div class="card kline-chart-card">
      <div class="card-header">
        <h3><i class="el-icon-video-camera"></i> 1分钟K线图（价格变化标记）</h3>
        <el-tag size="small" type="info">{{ klineDataList1.length }} 根K线</el-tag>
      </div>
      <div class="card-body">
        <div ref="klineChartRef1" class="kline-chart"></div>
        <div class="chart-legend">
          <div class="legend-item">
            <span class="legend-color up"></span>
            <span>价格上涨（高于前一根收盘价）</span>
          </div>
          <div class="legend-item">
            <span class="legend-color down"></span>
            <span>价格下跌（低于前一根收盘价）</span>
          </div>
          <div class="legend-item">
            <span class="legend-color equal"></span>
            <span>价格持平（等于前一根收盘价）</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 10分钟K线图 -->
    <div class="card kline-chart-card">
      <div class="card-header">
        <h3><i class="el-icon-timer"></i> 10分钟K线图（价格变化标记）</h3>
        <el-tag size="small" type="success">{{ klineDataList10.length }} 根K线</el-tag>
      </div>
      <div class="card-body">
        <div ref="klineChartRef10" class="kline-chart"></div>
        <div class="chart-legend">
          <div class="legend-item">
            <span class="legend-color up"></span>
            <span>价格上涨（高于前一根收盘价）</span>
          </div>
          <div class="legend-item">
            <span class="legend-color down"></span>
            <span>价格下跌（低于前一根收盘价）</span>
          </div>
          <div class="legend-item">
            <span class="legend-color equal"></span>
            <span>价格持平（等于前一根收盘价）</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 30分钟K线图 -->
    <div class="card kline-chart-card">
      <div class="card-header">
        <h3><i class="el-icon-time"></i> 30分钟K线图（价格变化标记）</h3>
        <el-tag size="small" type="warning">{{ klineDataList30.length }} 根K线</el-tag>
      </div>
      <div class="card-body">
        <div ref="klineChartRef30" class="kline-chart"></div>
        <div class="chart-legend">
          <div class="legend-item">
            <span class="legend-color up"></span>
            <span>价格上涨（高于前一根收盘价）</span>
          </div>
          <div class="legend-item">
            <span class="legend-color down"></span>
            <span>价格下跌（低于前一根收盘价）</span>
          </div>
          <div class="legend-item">
            <span class="legend-color equal"></span>
            <span>价格持平（等于前一根收盘价）</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 60分钟K线图 -->
    <div class="card kline-chart-card">
      <div class="card-header">
        <h3><i class="el-icon-watch"></i> 60分钟K线图（价格变化标记）</h3>
        <el-tag size="small" type="danger">{{ klineDataList60.length }} 根K线</el-tag>
      </div>
      <div class="card-body">
        <div ref="klineChartRef60" class="kline-chart"></div>
        <div class="chart-legend">
          <div class="legend-item">
            <span class="legend-color up"></span>
            <span>价格上涨（高于前一根收盘价）</span>
          </div>
          <div class="legend-item">
            <span class="legend-color down"></span>
            <span>价格下跌（低于前一根收盘价）</span>
          </div>
          <div class="legend-item">
            <span class="legend-color equal"></span>
            <span>价格持平（等于前一根收盘价）</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import * as api from '@/api/tool/vallisusdt/vallisusdt'
import * as echarts from 'echarts'

export default {
  name: 'VallisusdtKlineMonitor',
  data() {
    return {
      updateTime: null,
      klineLoading: false,
      klineDataList1: [],
      klineDataList10: [],
      klineDataList30: [],
      klineDataList60: [],
      klineChart1: null,
      klineChart10: null,
      klineChart30: null,
      klineChart60: null
    }
  },

  mounted() {
    this.$nextTick(() => {
      this.initKlineCharts()
      this.loadAllKlineData()
    })
  },

  beforeDestroy() {
    this.disposeCharts()
  },

  methods: {
    initKlineCharts() {
      this.initSingleChart('klineChartRef1', 'klineChart1')
      this.initSingleChart('klineChartRef10', 'klineChart10')
      this.initSingleChart('klineChartRef30', 'klineChart30')
      this.initSingleChart('klineChartRef60', 'klineChart60')
    },

    initSingleChart(refName, chartProp) {
      if (!this.$refs[refName]) return

      const chart = echarts.init(this.$refs[refName])
      this[chartProp] = chart

      const option = {
        tooltip: {
          trigger: 'axis',
          axisPointer: {
            type: 'cross',
            label: {
              backgroundColor: '#6a7985'
            }
          },
          formatter: (params) => {
            if (!params || params.length === 0) return ''
            const data = params[0]
            if (!data || !data.data || !data.data.value) return ''

            const time = data.axisValue || '-'
            const value = data.data.value

            // 确保所有字段都有值
            const open = value[1] !== undefined && value[1] !== null ? parseFloat(value[1]).toFixed(2) : '0.00'
            const close = value[2] !== undefined && value[2] !== null ? parseFloat(value[2]).toFixed(2) : '0.00'
            const high = value[3] !== undefined && value[3] !== null ? parseFloat(value[3]).toFixed(2) : '0.00'
            const low = value[4] !== undefined && value[4] !== null ? parseFloat(value[4]).toFixed(2) : '0.00'
            const volume = value[5] !== undefined && value[5] !== null ? parseFloat(value[5]).toFixed(2) : '0.00'

            // 计算涨跌额
            const priceChange = parseFloat(close) - parseFloat(open)
            const changeAmount = priceChange.toFixed(2)
            const amountColor = priceChange > 0 ? '#67c23a' : (priceChange < 0 ? '#f56c6c' : '#909399')
            const changeSymbol = priceChange > 0 ? '+' : ''

            // 价格变化百分比
            let changePercentText = ''
            if (value[6] !== undefined && value[6] !== null) {
              const changeVal = parseFloat(value[6])
              const changeColor = changeVal > 0 ? '#67c23a' : (changeVal < 0 ? '#f56c6c' : '#909399')
              const changeSym = changeVal > 0 ? '+' : ''
              changePercentText = `
                <div style="margin-top: 6px; padding-top: 6px; border-top: 1px solid #e4e7ed;">
                  <strong>📈 相对前K线:</strong>
                  <span style="color: ${changeColor}; font-weight: bold;">${changeSym}${changeVal.toFixed(4)}%</span>
                </div>`
            }

            return `
              <div style="padding: 12px; min-width: 240px; line-height: 1.6;">
                <div style="font-weight: bold; margin-bottom: 8px; color: #303133; border-bottom: 2px solid #409eff; padding-bottom: 6px; font-size: 14px;">
                  📊 K线数据详情
                </div>
                <div style="margin-bottom: 4px;"><strong>📅 时间:</strong> ${time}</div>
                <div style="margin-bottom: 4px;"><strong>📈 开盘价:</strong> $${open}</div>
                <div style="margin-bottom: 4px;"><strong>📉 收盘价:</strong> $${close}</div>
                <div style="margin-bottom: 4px;"><strong>⬆️ 最高价:</strong> $${high}</div>
                <div style="margin-bottom: 4px;"><strong>⬇️ 最低价:</strong> $${low}</div>
                <div style="margin-bottom: 4px;"><strong>💰 涨跌额:</strong>
                  <span style="color: ${amountColor}; font-weight: bold;">$${changeSymbol}${changeAmount}</span>
                </div>
                <div style="margin-bottom: 4px;"><strong>📊 成交量:</strong>
                  <span style="color: #409eff; font-weight: bold;">${volume} ETH</span>
                </div>
                ${changePercentText}
              </div>
            `
          },
          backgroundColor: 'rgba(255, 255, 255, 0.98)',
          borderColor: '#e4e7ed',
          borderWidth: 1,
          textStyle: {
            color: '#606266',
            fontSize: 13
          },
          extraCssText: 'box-shadow: 0 4px 16px 0 rgba(0, 0, 0, 0.15); border-radius: 8px;'
        },
        grid: {
          left: '3%',
          right: '4%',
          bottom: '15%',
          top: '10%',
          containLabel: true
        },
        xAxis: {
          type: 'category',
          data: [],
          boundaryGap: false,
          axisLine: {
            lineStyle: {
              color: '#ddd'
            }
          },
          splitLine: {
            show: true,
            lineStyle: {
              color: '#f0f0f0'
            }
          },
          axisLabel: {
            rotate: 45,
            interval: 'auto',
            formatter: (value) => {
              return value
            }
          }
        },
        yAxis: {
          type: 'value',
          scale: true,
          axisLine: {
            lineStyle: {
              color: '#ddd'
            }
          },
          splitLine: {
            show: true,
            lineStyle: {
              color: '#f0f0f0'
            }
          },
          axisLabel: {
            formatter: (value) => {
              return '$' + value.toFixed(0)
            }
          }
        },
        dataZoom: [
          {
            type: 'inside',
            start: 0,
            end: 100
          },
          {
            show: true,
            type: 'slider',
            top: '90%',
            start: 0,
            end: 100
          }
        ],
        series: [
          {
            name: 'K线',
            type: 'candlestick',
            data: [],
            itemStyle: {
              color: '#67c23a',
              color0: '#f56c6c',
              borderColor: '#67c23a',
              borderColor0: '#f56c6c'
            },
            markPoint: {
              symbol: 'pin',
              symbolSize: 45,
              label: {
                show: true,
                fontSize: 9,
                formatter: (param) => {
                  if (!param.value) return ''
                  return param.value > 0 ? `+${param.value.toFixed(3)}%` : `${param.value.toFixed(3)}%`
                }
              },
              data: []
            }
          }
        ]
      }

      chart.setOption(option)
    },

    async loadAllKlineData() {
      try {
        this.klineLoading = true

        const oneMinLimit = 1008

        console.log('开始加载K线数据...')

        const [res1, res10, res30, res60] = await Promise.all([
          api.getRawKlineData(oneMinLimit, '1m'),
          api.getAggregatedKlineData(oneMinLimit, '10min'),
          api.getAggregatedKlineData(oneMinLimit, '30min'),
          api.getAggregatedKlineData(oneMinLimit, '60min')
        ])

        console.log('API响应:', { res1, res10, res30, res60 })

        let klineArray1 = []
        let klineArray10 = []
        let klineArray30 = []
        let klineArray60 = []

        if (res1 && res1.code === 200 && res1.data) {
          klineArray1 = Array.isArray(res1.data) ? res1.data : []
          console.log('1分钟数据:', klineArray1.length)
        } else {
          console.warn('1分钟数据获取失败:', res1)
        }

        if (res10 && res10.code === 200 && res10.data) {
          klineArray10 = res10.data.rolling_10min || res10.data.natural_10min || []
          console.log('10分钟数据:', klineArray10.length)
        } else {
          console.warn('10分钟数据获取失败:', res10)
        }

        if (res30 && res30.code === 200 && res30.data) {
          klineArray30 = res30.data.rolling_30min || res30.data.natural_30min || []
          console.log('30分钟数据:', klineArray30.length)
        } else {
          console.warn('30分钟数据获取失败:', res30)
        }

        if (res60 && res60.code === 200 && res60.data) {
          klineArray60 = res60.data.rolling_60min || res60.data.natural_60min || []
          console.log('60分钟数据:', klineArray60.length)
        } else {
          console.warn('60分钟数据获取失败:', res60)
        }

        if (klineArray1.length > 100) {
          klineArray1 = klineArray1.slice(-100)
        }
        if (klineArray10.length > 100) {
          klineArray10 = klineArray10.slice(-100)
        }
        if (klineArray30.length > 100) {
          klineArray30 = klineArray30.slice(-100)
        }
        if (klineArray60.length > 100) {
          klineArray60 = klineArray60.slice(-100)
        }

        if (klineArray1.length > 0) {
          this.klineDataList1 = this.processKlineData(klineArray1)
          this.updateSingleChart(this.klineChart1, this.klineDataList1)
        }

        if (klineArray10.length > 0) {
          this.klineDataList10 = this.processKlineData(klineArray10)
          this.updateSingleChart(this.klineChart10, this.klineDataList10)
        }

        if (klineArray30.length > 0) {
          this.klineDataList30 = this.processKlineData(klineArray30)
          this.updateSingleChart(this.klineChart30, this.klineDataList30)
        }

        if (klineArray60.length > 0) {
          this.klineDataList60 = this.processKlineData(klineArray60)
          this.updateSingleChart(this.klineChart60, this.klineDataList60)
        }

        this.updateTime = Date.now()

        if (this.klineDataList1.length === 0 &&
            this.klineDataList10.length === 0 &&
            this.klineDataList30.length === 0 &&
            this.klineDataList60.length === 0) {
          this.$message.warning('暂无K线数据')
        } else {
          console.log('K线数据加载完成（实时模式）:', {
            '1分钟': this.klineDataList1.length + '根',
            '10分钟': this.klineDataList10.length + '根',
            '30分钟': this.klineDataList30.length + '根',
            '60分钟': this.klineDataList60.length + '根'
          })
        }
      } catch (error) {
        console.error('加载K线数据失败:', error)
        console.error('错误详情:', {
          message: error.message,
          code: error.code,
          response: error.response,
          config: error.config
        })

        let errorMessage = '加载K线数据失败'
        if (error.response) {
          errorMessage += `: ${error.response.status} ${error.response.statusText}`
        } else if (error.message) {
          errorMessage += `: ${error.message}`
        }

        this.$message.error(errorMessage)
      } finally {
        this.klineLoading = false
      }
    },

    processKlineData(rawData) {
      const processed = []

      rawData.forEach((item, index) => {
        const open = parseFloat(item.startPrice || item.start_price || 0)
        const close = parseFloat(item.endPrice || item.end_price || 0)
        const high = parseFloat(item.maxPrice || item.max_price || 0)
        const low = parseFloat(item.minPrice || item.min_price || 0)
        const volume = parseFloat(item.calcCount || item.volume || 0)
        const timestamp = item.startTime || item.start_time || item.timestamp || Date.now()

        let changePercent = 0
        if (index > 0) {
          const prevItem = rawData[index - 1]
          const prevClose = parseFloat(prevItem.endPrice || prevItem.end_price || 0)
          if (prevClose > 0) {
            changePercent = ((close - prevClose) / prevClose) * 100
          }
        }

        processed.push([
          timestamp,
          open,
          close,
          high,
          low,
          volume,
          changePercent
        ])
      })

      return processed
    },

    updateSingleChart(chart, dataList) {
      if (!chart || dataList.length === 0) return

      const times = dataList.map(item => {
        const date = new Date(item[0])
        const month = (date.getMonth() + 1).toString().padStart(2, '0')
        const day = date.getDate().toString().padStart(2, '0')
        const hours = date.getHours().toString().padStart(2, '0')
        const minutes = date.getMinutes().toString().padStart(2, '0')

        // 统一时间格式，确保对齐
        return `${month}/${day} ${hours}:${minutes}`
      })

      const klineData = dataList.map((item, index) => ({
        value: [
          item[1],
          item[2],
          item[3],
          item[4],
          item[5],
          item[6]
        ],
        itemStyle: {}
      }))

      const markPointData = []
      dataList.forEach((item, index) => {
        if (index > 0) {
          const changePercent = item[6]
          if (Math.abs(changePercent) > 0.05) {
            markPointData.push({
              name: '价格变化',
              coord: [index, item[3]],
              value: changePercent,
              itemStyle: {
                color: changePercent > 0 ? '#67c23a' : '#f56c6c'
              }
            })
          }
        }
      })

      const option = {
        xAxis: {
          data: times,
          min: 0,
          max: times.length - 1
        },
        series: [
          {
            data: klineData,
            markPoint: {
              data: markPointData
            }
          }
        ]
      }

      chart.setOption(option)
    },

    disposeCharts() {
      if (this.klineChart1) {
        this.klineChart1.dispose()
        this.klineChart1 = null
      }
      if (this.klineChart10) {
        this.klineChart10.dispose()
        this.klineChart10 = null
      }
      if (this.klineChart30) {
        this.klineChart30.dispose()
        this.klineChart30 = null
      }
      if (this.klineChart60) {
        this.klineChart60.dispose()
        this.klineChart60 = null
      }
    },

    formatTime(timestamp) {
      if (!timestamp) return '-'
      const date = new Date(timestamp)
      return date.toLocaleString('zh-CN')
    }
  }
}
</script>

<style scoped>
.kline-monitor-container {
  padding: 20px;
  background: #f5f7fa;
  min-height: 100vh;
  font-family: 'Helvetica Neue', Arial, sans-serif;
}

.header {
  margin-bottom: 24px;
}

.title-section h1 {
  font-size: 24px;
  color: #303133;
  margin-bottom: 12px;
  font-weight: 600;
}

.status-bar {
  display: flex;
  align-items: center;
  gap: 16px;
}

.update-time {
  color: #909399;
  font-size: 14px;
}

.refresh-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #409eff;
  cursor: pointer;
  font-size: 14px;
}

.refresh-btn:hover {
  color: #66b1ff;
}

.refresh-btn .refreshing {
  animation: rotating 2s linear infinite;
}

@keyframes rotating {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.card {
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  overflow: hidden;
  margin-bottom: 24px;
}

.kline-chart-card:last-child {
  margin-bottom: 0;
}

.card-header {
  padding: 16px 20px;
  background: #fafbfc;
  border-bottom: 1px solid #ebeef5;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-header h3 {
  margin: 0;
  font-size: 16px;
  color: #303133;
  display: flex;
  align-items: center;
  gap: 8px;
}

.card-body {
  padding: 20px;
}

.kline-chart {
  width: 100%;
  height: 450px;
  background: #fafbfc;
  border-radius: 8px;
  padding: 10px;
}

.chart-legend {
  display: flex;
  justify-content: center;
  gap: 24px;
  margin-top: 12px;
  padding: 12px;
  background: #f9f9f9;
  border-radius: 8px;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #606266;
}

.legend-color {
  width: 16px;
  height: 16px;
  border-radius: 3px;
  display: inline-block;
}

.legend-color.up {
  background: #67c23a;
}

.legend-color.down {
  background: #f56c6c;
}

.legend-color.equal {
  background: #909399;
}

@media (max-width: 768px) {
  .chart-legend {
    flex-direction: column;
    gap: 8px;
  }

  .kline-chart {
    height: 350px;
  }
}
</style>
