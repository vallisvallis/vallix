<template>
  <div class="kline-monitor-container">
    <!-- 头部标题 -->
    <div class="header">
      <div class="title-section">
        <h1>BTC/USDT K线监控（近一周）</h1>
        <div class="status-bar">
          <span class="update-time">最后更新: {{ formatTime(updateTime) }}</span>
          <span class="auto-refresh-status" :class="{ 'enabled': autoRefreshEnabled }">
            <i class="el-icon-clock"></i>
            {{ autoRefreshEnabled ? '自动刷新已开启' : '自动刷新已关闭' }}
          </span>
          <span class="refresh-btn" @click="toggleAutoRefresh">
            <i class="el-icon-switch-button"></i>
            {{ autoRefreshEnabled ? '关闭自动刷新' : '开启自动刷新' }}
          </span>
          <span class="refresh-btn" @click="loadAllKlineData">
            <i class="el-icon-refresh" :class="{ 'refreshing': klineLoading }"></i>
            立即刷新
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
        <!-- 移动平均价信息面板 -->
        <div class="ma-info-panel">
          <div class="ma-info-item">
            <span class="ma-label">MA5:</span>
            <span class="ma-value">{{ calculateMA(1, 5).price }}</span>
            <span class="ma-diff" :class="calculateMA(1, 5).diffClass">{{ calculateMA(1, 5).diffPercent }}</span>
          </div>
          <div class="ma-info-item">
            <span class="ma-label">MA10:</span>
            <span class="ma-value">{{ calculateMA(1, 10).price }}</span>
            <span class="ma-diff" :class="calculateMA(1, 10).diffClass">{{ calculateMA(1, 10).diffPercent }}</span>
          </div>
          <div class="ma-info-item">
            <span class="ma-label">MA20:</span>
            <span class="ma-value">{{ calculateMA(1, 20).price }}</span>
            <span class="ma-diff" :class="calculateMA(1, 20).diffClass">{{ calculateMA(1, 20).diffPercent }}</span>
          </div>
        </div>
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
        <!-- 移动平均价信息面板 -->
        <div class="ma-info-panel">
          <div class="ma-info-item">
            <span class="ma-label">MA5:</span>
            <span class="ma-value">{{ calculateMA(10, 5).price }}</span>
            <span class="ma-diff" :class="calculateMA(10, 5).diffClass">{{ calculateMA(10, 5).diffPercent }}</span>
          </div>
          <div class="ma-info-item">
            <span class="ma-label">MA10:</span>
            <span class="ma-value">{{ calculateMA(10, 10).price }}</span>
            <span class="ma-diff" :class="calculateMA(10, 10).diffClass">{{ calculateMA(10, 10).diffPercent }}</span>
          </div>
          <div class="ma-info-item">
            <span class="ma-label">MA20:</span>
            <span class="ma-value">{{ calculateMA(10, 20).price }}</span>
            <span class="ma-diff" :class="calculateMA(10, 20).diffClass">{{ calculateMA(10, 20).diffPercent }}</span>
          </div>
        </div>
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
        <!-- 移动平均价信息面板 -->
        <div class="ma-info-panel">
          <div class="ma-info-item">
            <span class="ma-label">MA5:</span>
            <span class="ma-value">{{ calculateMA(30, 5).price }}</span>
            <span class="ma-diff" :class="calculateMA(30, 5).diffClass">{{ calculateMA(30, 5).diffPercent }}</span>
          </div>
          <div class="ma-info-item">
            <span class="ma-label">MA10:</span>
            <span class="ma-value">{{ calculateMA(30, 10).price }}</span>
            <span class="ma-diff" :class="calculateMA(30, 10).diffClass">{{ calculateMA(30, 10).diffPercent }}</span>
          </div>
          <div class="ma-info-item">
            <span class="ma-label">MA20:</span>
            <span class="ma-value">{{ calculateMA(30, 20).price }}</span>
            <span class="ma-diff" :class="calculateMA(30, 20).diffClass">{{ calculateMA(30, 20).diffPercent }}</span>
          </div>
        </div>
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
        <!-- 移动平均价信息面板 -->
        <div class="ma-info-panel">
          <div class="ma-info-item">
            <span class="ma-label">MA5:</span>
            <span class="ma-value">{{ calculateMA(60, 5).price }}</span>
            <span class="ma-diff" :class="calculateMA(60, 5).diffClass">{{ calculateMA(60, 5).diffPercent }}</span>
          </div>
          <div class="ma-info-item">
            <span class="ma-label">MA10:</span>
            <span class="ma-value">{{ calculateMA(60, 10).price }}</span>
            <span class="ma-diff" :class="calculateMA(60, 10).diffClass">{{ calculateMA(60, 10).diffPercent }}</span>
          </div>
          <div class="ma-info-item">
            <span class="ma-label">MA20:</span>
            <span class="ma-value">{{ calculateMA(60, 20).price }}</span>
            <span class="ma-diff" :class="calculateMA(60, 20).diffClass">{{ calculateMA(60, 20).diffPercent }}</span>
          </div>
        </div>
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
import * as api from '@/api/tool/btcusdt/btcusdt'
import * as echarts from 'echarts'

export default {
  name: 'BtcusdtKlineMonitor',
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
      klineChart60: null,
      refreshTimer: null,
      autoRefreshEnabled: true
    }
  },

  mounted() {
    this.$nextTick(() => {
      this.initKlineCharts()
      this.loadAllKlineData()
      this.startAutoRefresh()
    })
  },

  beforeDestroy() {
    this.stopAutoRefresh()
    this.disposeCharts()
  },

  methods: {
    /**
     * 计算移动平均价和差值百分比
     * @param {number} periodType - K线周期类型 (1, 10, 30, 60)
     * @param {number} maPeriod - MA周期 (5, 10, 20)
     * @returns {Object} { price, diffPercent, diffClass }
     */
    calculateMA(periodType, maPeriod) {
      let dataList = []

      switch (periodType) {
        case 1:
          dataList = this.klineDataList1
          break
        case 10:
          dataList = this.klineDataList10
          break
        case 30:
          dataList = this.klineDataList30
          break
        case 60:
          dataList = this.klineDataList60
          break
        default:
          return { price: '-', diffPercent: '-', diffClass: '' }
      }

      if (!dataList || dataList.length < maPeriod) {
        return { price: '-', diffPercent: '-', diffClass: '' }
      }

      const currentPrice = parseFloat(dataList[dataList.length - 1][2])

      let sum = 0
      for (let i = dataList.length - maPeriod; i < dataList.length; i++) {
        sum += parseFloat(dataList[i][2])
      }
      const maPrice = sum / maPeriod

      const diffPercent = ((currentPrice - maPrice) / maPrice) * 100

      const formattedPrice = '$' + maPrice.toFixed(2)

      const sign = diffPercent > 0 ? '+' : ''
      const formattedDiff = sign + diffPercent.toFixed(4) + '%'

      const diffClass = diffPercent > 0 ? 'positive' : (diffPercent < 0 ? 'negative' : 'neutral')

      return {
        price: formattedPrice,
        diffPercent: formattedDiff,
        diffClass: diffClass
      }
    },

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

            const open = value[1] !== undefined && value[1] !== null ? parseFloat(value[1]).toFixed(2) : '0.00'
            const close = value[2] !== undefined && value[2] !== null ? parseFloat(value[2]).toFixed(2) : '0.00'
            const high = value[3] !== undefined && value[3] !== null ? parseFloat(value[3]).toFixed(2) : '0.00'
            const low = value[4] !== undefined && value[4] !== null ? parseFloat(value[4]).toFixed(2) : '0.00'
            const volume = value[5] !== undefined && value[5] !== null ? parseFloat(value[5]).toFixed(2) : '0.00'

            const priceChange = parseFloat(close) - parseFloat(open)
            const changeAmount = priceChange.toFixed(2)
            const amountColor = priceChange > 0 ? '#67c23a' : (priceChange < 0 ? '#f56c6c' : '#909399')
            const changeSymbol = priceChange > 0 ? '+' : ''

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
                  <span style="color: #409eff; font-weight: bold;">${volume} BTC</span>
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

        console.log('开始加载BTC K线数据...')

        const res1 = await api.getBtcRawKlineData(oneMinLimit, '1m')

        console.log('BTC 1分钟API响应:', res1)

        let klineArray1 = []

        if (res1 && res1.code === 200 && res1.data) {
          klineArray1 = Array.isArray(res1.data) ? res1.data : []
          console.log('BTC 1分钟原始数据:', klineArray1.length)
        } else {
          console.warn('BTC 1分钟数据获取失败:', res1)
          throw new Error('无法获取BTC 1分钟K线数据')
        }

        console.log('开始在前端聚合BTC K线数据...')
        const klineArray10 = this.aggregateKlineData(klineArray1, 10)
        const klineArray30 = this.aggregateKlineData(klineArray1, 30)
        const klineArray60 = this.aggregateKlineData(klineArray1, 60)

        console.log('BTC聚合完成:', {
          '1分钟': klineArray1.length,
          '10分钟': klineArray10.length,
          '30分钟': klineArray30.length,
          '60分钟': klineArray60.length
        })

        const displayLimit = 100
        const display1 = klineArray1.slice(-displayLimit)
        const display10 = klineArray10.slice(-displayLimit)
        const display30 = klineArray30.slice(-displayLimit)
        const display60 = klineArray60.slice(-displayLimit)

        if (display1.length > 0) {
          this.klineDataList1 = this.processKlineData(display1)
          this.updateSingleChart(this.klineChart1, this.klineDataList1)
        }

        if (display10.length > 0) {
          this.klineDataList10 = this.processKlineData(display10)
          this.updateSingleChart(this.klineChart10, this.klineDataList10)
        }

        if (display30.length > 0) {
          this.klineDataList30 = this.processKlineData(display30)
          this.updateSingleChart(this.klineChart30, this.klineDataList30)
        }

        if (display60.length > 0) {
          this.klineDataList60 = this.processKlineData(display60)
          this.updateSingleChart(this.klineChart60, this.klineDataList60)
        }

        this.updateTime = Date.now()

        console.log('BTC K线数据加载完成（实时模式）:', {
          '1分钟': this.klineDataList1.length + '根',
          '10分钟': this.klineDataList10.length + '根',
          '30分钟': this.klineDataList30.length + '根',
          '60分钟': this.klineDataList60.length + '根'
        })
      } catch (error) {
        console.error('加载BTC K线数据失败:', error)
        console.error('错误详情:', {
          message: error.message,
          code: error.code,
          response: error.response,
          config: error.config
        })

        let errorMessage = '加载BTC K线数据失败'
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

    aggregateKlineData(oneMinData, period) {
      if (!oneMinData || oneMinData.length < period) {
        return []
      }

      const aggregated = []

      for (let i = oneMinData.length - 1; i >= period - 1; i -= period) {
        const startIndex = Math.max(0, i - period + 1)
        const endIndex = i + 1
        const window = oneMinData.slice(startIndex, endIndex)

        if (window.length !== period) {
          continue
        }

        const openPrice = parseFloat(window[0].startPrice || window[0].start_price || 0)
        const closePrice = parseFloat(window[window.length - 1].endPrice || window[window.length - 1].end_price || 0)

        let maxPrice = 0
        let minPrice = Number.MAX_VALUE
        let totalVolume = 0

        window.forEach(item => {
          const high = parseFloat(item.maxPrice || item.max_price || 0)
          const low = parseFloat(item.minPrice || item.min_price || 0)
          const volume = parseFloat(item.calcCount || item.volume || 0)

          if (high > maxPrice) maxPrice = high
          if (low < minPrice) minPrice = low
          totalVolume += volume
        })

        const startTime = window[0].startTime || window[0].start_time || window[0].timestamp || Date.now()
        const endTime = window[window.length - 1].endTime || window[window.length - 1].end_time || Date.now()

        aggregated.unshift({
          startPrice: openPrice,
          endPrice: closePrice,
          maxPrice: maxPrice,
          minPrice: minPrice,
          calcCount: totalVolume,
          startTime: startTime,
          endTime: endTime
        })
      }

      return aggregated
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

    startAutoRefresh() {
      if (this.refreshTimer) {
        clearInterval(this.refreshTimer)
      }

      if (this.autoRefreshEnabled) {
        console.log('启动BTC自动刷新：每60秒刷新一次')
        this.refreshTimer = setInterval(() => {
          console.log('执行BTC自动刷新...')
          this.loadAllKlineData()
        }, 60000)
      }
    },

    stopAutoRefresh() {
      if (this.refreshTimer) {
        clearInterval(this.refreshTimer)
        this.refreshTimer = null
        console.log('BTC自动刷新已停止')
      }
    },

    toggleAutoRefresh() {
      this.autoRefreshEnabled = !this.autoRefreshEnabled

      if (this.autoRefreshEnabled) {
        this.startAutoRefresh()
        this.$message.success('已开启BTC自动刷新（每60秒）')
      } else {
        this.stopAutoRefresh()
        this.$message.info('已关闭BTC自动刷新')
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

.auto-refresh-status {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #909399;
  font-size: 14px;
  padding: 4px 8px;
  border-radius: 4px;
  background: #f4f4f5;
}

.auto-refresh-status.enabled {
  color: #67c23a;
  background: #f0f9ff;
}

.auto-refresh-status i {
  animation: pulse 2s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
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

.ma-info-panel {
  display: flex;
  justify-content: space-around;
  align-items: center;
  padding: 12px 16px;
  background: linear-gradient(135deg, #f8f9fa 0%, #e9ecef 100%);
  border-radius: 8px;
  margin-bottom: 16px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.05);
}

.ma-info-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}

.ma-label {
  font-weight: 600;
  color: #495057;
  min-width: 40px;
}

.ma-value {
  font-weight: 700;
  color: #212529;
  font-family: 'Courier New', monospace;
}

.ma-diff {
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 12px;
  min-width: 70px;
  text-align: center;
}

.ma-diff.positive {
  background: #d4edda;
  color: #155724;
}

.ma-diff.negative {
  background: #f8d7da;
  color: #721c24;
}

.ma-diff.neutral {
  background: #e2e3e5;
  color: #383d41;
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
