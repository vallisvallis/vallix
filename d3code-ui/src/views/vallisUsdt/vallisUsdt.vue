<template>
  <div class="eth-kline-container">
    <!-- 实时价格面板 -->
    <div class="price-panel" :class="priceChange >= 0 ? 'up' : 'down'">
      <span class="price-label">ETH/USDT</span>
      <span class="current-price">${{ currentPrice.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }}</span>
      <span class="price-change">
        {{ priceChange >= 0 ? '+' : '' }}{{ priceChange.toFixed(2) }} ({{ priceChangePercent >= 0 ? '+' : '' }}{{ priceChangePercent.toFixed(2) }}%)
      </span>
      <span class="update-indicator" :class="{ 'active': isUpdating }">●</span>
    </div>

    <!-- 控制面板 -->
    <div class="control-panel">
      <button
          @click="toggleWebSocket"
          :class="{ 'connected': wsConnected, 'disconnected': !wsConnected }"
          :disabled="connecting"
      >
        {{ connecting ? '连接中...' : (wsConnected ? '🔌 断开连接' : '🔗 连接WebSocket') }}
      </button>
      <span class="status" :class="wsConnected ? 'online' : 'offline'">
        {{ wsConnected ? '✓ 已连接' : '✗ 未连接' }}
      </span>
      <div class="indicator-selector">
        <span>指标:</span>
        <button
            v-for="indicator in indicators"
            :key="indicator.key"
            @click="toggleIndicator(indicator.key)"
            :class="{ active: activeIndicators.includes(indicator.key) }"
        >
          {{ indicator.name }}
        </button>
      </div>
      <span class="update-time">延迟: {{ latency }}ms</span>
      <button @click="toggleLogs" class="log-toggle">
        {{ showLogs ? '▼ 隐藏日志' : '▲ 显示日志' }}
      </button>
    </div>

    <!-- 调试日志区域 -->
    <div class="log-panel" v-show="showLogs">
      <div class="log-header">
        <span>📝 连接日志</span>
        <button @click="clearLogs">清空</button>
      </div>
      <div class="log-content" ref="logContent">
        <div v-for="(log, index) in logs" :key="index" :class="log.type">
          <span class="log-time">{{ log.time }}</span>
          <span>{{ log.message }}</span>
        </div>
      </div>
    </div>

    <!-- K线图 -->
    <div ref="klineChartRef" class="kline-chart"></div>
  </div>
</template>

<script>
import * as echarts from 'echarts'

export default {
  name: 'EthKlineChart',
  data() {
    return {
      klineChart: null,
      klineData: [],
      currentPrice: 0,
      priceChange: 0,
      priceChangePercent: 0,
      previousPrice: 0,
      ws: null,
      wsConnected: false,
      connecting: false,
      showLogs: false,
      logs: [],
      isUpdating: false,
      latency: 0,
      lastReceiveTime: 0,
      reconnectCount: 0,
      maxReconnectAttempts: 10,
      maxDataPoints: 300,
      indicators: [
        { key: 'ma', name: 'MA' },
        { key: 'macd', name: 'MACD' },
        { key: 'volume', name: '成交量' }
      ],
      activeIndicators: ['ma', 'volume'],
      initialized: false
    }
  },

  mounted() {
    this.$nextTick(() => {
      this.initKlineChart()
    })
  },

  beforeDestroy() {
    this.disconnectWebSocket()
    this.disposeChart()
  },

  methods: {
    addLog(type, message) {
      const now = new Date()
      const time = `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}:${now.getSeconds().toString().padStart(2, '0')}`
      this.logs.unshift({ time, type, message })
      if (this.logs.length > 100) {
        this.logs.pop()
      }
    },

    clearLogs() {
      this.logs = []
    },

    toggleLogs() {
      this.showLogs = !this.showLogs
    },

    toggleIndicator(key) {
      const index = this.activeIndicators.indexOf(key)
      if (index > -1) {
        this.activeIndicators.splice(index, 1)
      } else {
        this.activeIndicators.push(key)
      }
      this.updateChartFull()
    },

    initKlineChart() {
      const container = this.$refs.klineChartRef
      if (!container) {
        this.addLog('error', '图表容器不存在')
        return
      }

      this.klineChart = echarts.init(container)
      this.addLog('info', 'ECharts图表初始化成功')

      const option = {
        backgroundColor: '#0d0d1a',
        tooltip: {
          trigger: 'axis',
          axisPointer: {
            type: 'cross',
            crossStyle: { color: '#999' }
          },
          backgroundColor: 'rgba(13, 13, 26, 0.98)',
          borderColor: '#2a2a4a',
          borderWidth: 1,
          padding: [12, 16],
          textStyle: {
            color: '#fff',
            fontSize: 12
          },
          formatter: (params) => {
            const candle = params[0]
            const volume = params[1]
            if (!candle) return ''
            const data = candle.data
            const color = data[1] >= data[2] ? '#00D4AA' : '#FF4757'
            return `<div style="font-weight:bold;margin-bottom:8px;color:${color}">${candle.axisValue}</div>` +
                `<div>开盘: <span style="color:#999">$${data[1].toFixed(4)}</span></div>` +
                `<div>收盘: <span style="color:${color}">$${data[2].toFixed(4)}</span></div>` +
                `<div>最高: <span style="color:#00D4AA">$${data[3].toFixed(4)}</span></div>` +
                `<div>最低: <span style="color:#FF4757">$${data[4].toFixed(4)}</span></div>` +
                `<div style="margin-top:4px">成交量: <span style="color:#2196F3">${volume ? volume.data.toLocaleString() : 0}</span></div>`
          }
        },
        grid: [
          { left: '8%', right: '5%', top: '5%', height: '58%' },
          { left: '8%', right: '5%', top: '66%', height: '18%' }
        ],
        xAxis: [
          {
            type: 'category',
            data: [],
            axisLine: { lineStyle: { color: '#2a2a4a' } },
            axisLabel: { color: '#666', rotate: 0, fontSize: 11, margin: 8 },
            axisTick: { show: false },
            splitLine: { show: false }
          },
          {
            type: 'category',
            gridIndex: 1,
            data: [],
            axisLine: { lineStyle: { color: '#2a2a4a' } },
            axisLabel: { show: false },
            axisTick: { show: false },
            splitLine: { show: false }
          }
        ],
        yAxis: [
          {
            type: 'value',
            scale: true,
            min: 'dataMin',
            max: 'dataMax',
            axisLine: { show: true, lineStyle: { color: '#2a2a4a' } },
            axisLabel: { color: '#666', fontSize: 11, formatter: (v) => '$' + v.toFixed(2) },
            splitLine: { lineStyle: { color: '#1a1a3a', type: 'solid', width: 1 } }
          },
          {
            type: 'value',
            gridIndex: 1,
            scale: true,
            axisLine: { show: true, lineStyle: { color: '#2a2a4a' } },
            axisLabel: { color: '#666', fontSize: 10, formatter: (v) => v >= 1000 ? (v / 1000).toFixed(1) + 'K' : v >= 1 ? v.toFixed(0) : v.toFixed(2) },
            splitLine: { lineStyle: { color: '#1a1a3a', type: 'solid', width: 1 } }
          }
        ],
        dataZoom: [
          { type: 'inside', xAxisIndex: [0, 1], start: 70, end: 100, zoomLock: false },
          {
            type: 'slider',
            xAxisIndex: [0, 1],
            start: 70,
            end: 100,
            height: 22,
            bottom: 5,
            borderColor: '#2a2a4a',
            backgroundColor: '#0d0d1a',
            fillerColor: 'rgba(33, 150, 243, 0.15)',
            handleStyle: { color: '#2196F3', borderColor: '#1976D2', borderWidth: 1 },
            textStyle: { color: '#666', fontSize: 10 },
            brushSelect: false
          }
        ],
        series: [
          {
            name: 'ETH/USDT',
            type: 'candlestick',
            data: [],
            itemStyle: {
              color: '#00D4AA',
              color0: '#FF4757',
              borderColor: '#00D4AA',
              borderColor0: '#FF4757'
            },
            emphasis: {
              itemStyle: {
                borderWidth: 2,
                shadowBlur: 10,
                shadowColor: 'rgba(0, 212, 170, 0.5)'
              }
            }
          },
          {
            name: '成交量',
            type: 'bar',
            xAxisIndex: 1,
            yAxisIndex: 1,
            data: [],
            itemStyle: {
              color: (params) => {
                const index = params.dataIndex
                const klineData = this.klineData[index]
                if (klineData && klineData[1] >= klineData[2]) {
                  return 'rgba(0, 212, 170, 0.6)'
                }
                return 'rgba(255, 71, 87, 0.6)'
              },
              borderRadius: [2, 2, 0, 0]
            }
          },
          { name: 'MA5', type: 'line', data: [], lineStyle: { color: '#FFD700', width: 1.5, opacity: 0.85 }, showSymbol: false },
          { name: 'MA10', type: 'line', data: [], lineStyle: { color: '#00BFFF', width: 1.5, opacity: 0.85 }, showSymbol: false },
          { name: 'MA20', type: 'line', data: [], lineStyle: { color: '#FF69B4', width: 1.5, opacity: 0.85 }, showSymbol: false },
          {
            name: '10分钟对比',
            type: 'custom',
            data: [],
            renderItem: (params, api) => {
              const value = api.value(1)
              if (value === null || value === undefined) {
                return { type: 'group', children: [] }
              }
              const x = api.coord([params.dataIndex, 0])[0]
              const y = api.coord([params.dataIndex, api.value(0)])[1]
              const isUp = value > 0
              const color = isUp ? '#00D4AA' : '#FF4757'
              const changeText = (isUp ? '+' : '') + value.toFixed(2) + '%'
              
              // 箭头形状
              const arrowSize = 8
              const arrowY = y - 15
              const arrowChildren = isUp ? [
                { type: 'polygon', shape: { points: [[x, arrowY - arrowSize], [x - arrowSize / 2, arrowY + arrowSize / 2], [x + arrowSize / 2, arrowY + arrowSize / 2]] }, style: { fill: color, stroke: color, lineWidth: 1 } }
              ] : [
                { type: 'polygon', shape: { points: [[x, arrowY + arrowSize], [x - arrowSize / 2, arrowY - arrowSize / 2], [x + arrowSize / 2, arrowY - arrowSize / 2]] }, style: { fill: color, stroke: color, lineWidth: 1 } }
              ]
              
              // 百分比文字
              const textY = isUp ? arrowY - arrowSize - 12 : arrowY + arrowSize + 6
              const textChildren = [
                { type: 'text', style: { text: changeText, x: x, y: textY, textAlign: 'center', textBaseline: 'middle', fontSize: 10, fill: color } }
              ]
              
              return {
                type: 'group',
                children: [...arrowChildren, ...textChildren]
              }
            }
          }
        ]
      }

      this.klineChart.setOption(option)
      window.addEventListener('resize', () => this.klineChart && this.klineChart.resize())
    },

    toggleWebSocket() {
      if (this.connecting) return

      if (this.wsConnected) {
        this.disconnectWebSocket()
      } else {
        this.connectWebSocket()
      }
    },

    connectWebSocket() {
      if (this.wsConnected || this.connecting) return

      this.connecting = true
      this.addLog('info', '🔗 正在连接WebSocket...')

      const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      const host = window.location.hostname || 'localhost'
      const url = `${protocol}//${host}:8080/system/vallisusdt/ws/kline`

      this.ws = new WebSocket(url)

      this.ws.onopen = () => {
        this.connecting = false
        this.wsConnected = true
        this.reconnectCount = 0
        this.addLog('success', '✅ WebSocket连接成功')
        this.ws.send(JSON.stringify({ action: 'subscribe', interval: '1s' }))
      }

      this.ws.onmessage = (event) => {
        this.latency = Date.now() - this.lastReceiveTime
        this.lastReceiveTime = Date.now()
        this.isUpdating = true
        setTimeout(() => { this.isUpdating = false }, 100)

        try {
          const data = JSON.parse(event.data)
          this.handleWsMessage(data)
        } catch (error) {
          this.addLog('error', `❌ 解析消息失败: ${error.message}`)
        }
      }

      this.ws.onerror = (error) => {
        this.connecting = false
        this.wsConnected = false
        this.addLog('error', `❌ WebSocket错误: ${error.message || '未知错误'}`)
        this.tryReconnect()
      }

      this.ws.onclose = () => {
        this.connecting = false
        this.wsConnected = false
        this.addLog('warning', '⚠️ WebSocket连接已关闭')
        this.tryReconnect()
      }
    },

    disconnectWebSocket() {
      if (this.ws) {
        this.ws.close()
        this.ws = null
      }
      this.wsConnected = false
      this.addLog('info', '🔌 WebSocket已断开')
    },

    tryReconnect() {
      if (this.reconnectCount >= this.maxReconnectAttempts) {
        this.addLog('error', '❌ 已达到最大重连次数，停止尝试')
        return
      }

      const delay = Math.pow(2, this.reconnectCount) * 1000
      this.reconnectCount++
      this.addLog('warning', `⚠️ ${delay / 1000}秒后尝试重连 (${this.reconnectCount}/${this.maxReconnectAttempts})`)

      setTimeout(() => {
        if (!this.wsConnected) {
          this.connectWebSocket()
        }
      }, delay)
    },

    handleWsMessage(data) {
      if (!data || !data.data) return

      this.addLog('info', `📥 收到数据: ${data.data.length}条`)

      // 使用数据中第一个时间戳作为基准，确保时间戳一致性
      const firstTimestamp = data.data[0]?.startTime || data.data[0]?.timestamp || Date.now()
      const newData = data.data.map((item, index) => [
        item.startTime || item.timestamp || (firstTimestamp - (data.data.length - index - 1) * 1000),
        item.open || item[1] || 0,
        item.close || item[2] || 0,
        item.high || item[3] || 0,
        item.low || item[4] || 0,
        item.volume || item[5] || 0
      ])

      if (this.klineData.length === 0) {
        this.klineData = newData
        this.updateChartFull()
      } else {
        this.updateDataIncremental(newData)
      }

      const lastData = newData[newData.length - 1]
      if (lastData) {
        const newPrice = lastData[2]
        if (this.previousPrice !== 0) {
          this.priceChange = newPrice - this.previousPrice
          this.priceChangePercent = (this.priceChange / this.previousPrice) * 100
        }
        this.previousPrice = this.currentPrice
        this.currentPrice = newPrice
      }
    },

    updateDataIncremental(newData) {
      if (!newData || newData.length === 0) return

      const lastExistingTime = this.klineData[this.klineData.length - 1]?.[0]
      const lastNewTime = newData[newData.length - 1]?.[0]

      if (!lastExistingTime || !lastNewTime) {
        this.klineData = newData
        this.updateChartFull()
        return
      }

      // 使用更精确的时间差判断（考虑毫秒级误差）
      const timeDiff = lastNewTime - lastExistingTime
      const isNewCandle = timeDiff >= 900 // 允许100ms误差

      if (isNewCandle) {
        this.klineData.push(newData[newData.length - 1])
        if (this.klineData.length > this.maxDataPoints) {
          this.klineData.shift()
        }
        this.updateChartWithNewCandle(newData[newData.length - 1])
      } else {
        this.klineData[this.klineData.length - 1] = newData[newData.length - 1]
        this.updateChartLastCandle(newData[newData.length - 1])
      }
    },

    calculateMA(data, dayCount) {
      const result = []
      for (let i = 0; i < data.length; i++) {
        if (i < dayCount - 1) {
          result.push(null)
          continue
        }
        let sum = 0
        for (let j = 0; j < dayCount; j++) {
          sum += data[i - j][2]
        }
        result.push(sum / dayCount)
      }
      return result
    },

    calculateMACD(data, shortPeriod = 12, longPeriod = 26, signalPeriod = 9) {
      const prices = data.map(item => item[2])
      const EMA = (arr, period) => {
        const result = []
        const multiplier = 2 / (period + 1)
        let ema = arr[0]
        result.push(ema)
        for (let i = 1; i < arr.length; i++) {
          ema = (arr[i] - ema) * multiplier + ema
          result.push(ema)
        }
        return result
      }
      const shortEMA = EMA(prices, shortPeriod)
      const longEMA = EMA(prices, longPeriod)
      const DIF = shortEMA.map((s, i) => s - longEMA[i])
      const DEA = EMA(DIF, signalPeriod)
      const MACD = DIF.map((d, i) => (d - DEA[i]) * 2)
      return { DIF, DEA, MACD }
    },

    // 计算每根K线相对于10分钟前价格的变化百分比
    calculate10MinCompare() {
      const result = []
      const minutes10Ago = 600 // 10分钟 = 600秒 = 600根K线
      
      for (let i = 0; i < this.klineData.length; i++) {
        const currentPrice = this.klineData[i][2] // 收盘价
        const tenMinAgoIndex = i - minutes10Ago
        
        if (tenMinAgoIndex >= 0 && this.klineData[tenMinAgoIndex]) {
          const tenMinAgoPrice = this.klineData[tenMinAgoIndex][2]
          const changePercent = ((currentPrice - tenMinAgoPrice) / tenMinAgoPrice) * 100
          // [最高价, 变化百分比]
          const highPrice = this.klineData[i][3]
          result.push([highPrice, changePercent])
        } else {
          result.push([null, null])
        }
      }
      return result
    },

    updateChartFull() {
      if (!this.klineChart || !this.klineData || this.klineData.length === 0) {
        return
      }

      const times = this.klineData.map((item, index) => {
        const d = new Date(item[0])
        const hours = d.getHours().toString().padStart(2, '0')
        const minutes = d.getMinutes().toString().padStart(2, '0')
        const seconds = d.getSeconds().toString().padStart(2, '0')
        return `${hours}:${minutes}:${seconds}`
      })

      const candleData = this.klineData.map(item => [item[1], item[2], item[4], item[3]])
      const volumeData = this.klineData.map(item => item[5])
      const ma5 = this.calculateMA(this.klineData, 5)
      const ma10 = this.calculateMA(this.klineData, 10)
      const ma20 = this.calculateMA(this.klineData, 20)
      const compare10minData = this.calculate10MinCompare()

      this.klineChart.setOption({
        xAxis: [{ data: times }, { data: times }],
        series: [
          { data: candleData },
          { data: volumeData },
          { data: ma5 },
          { data: ma10 },
          { data: ma20 },
          { data: compare10minData }
        ]
      })

      this.initialized = true
    },

    updateChartWithNewCandle(newCandle) {
      if (!this.klineChart || !newCandle) return

      // 重新计算所有数据并全量更新图表
      // 这种方式虽然效率稍低，但能确保数据同步正确
      this.updateChartFull()
    },

    updateChartLastCandle(updatedCandle) {
      if (!this.klineChart || !updatedCandle || this.klineData.length === 0) return

      const lastIndex = this.klineData.length - 1

      // 获取当前图表数据
      const option = this.klineChart.getOption()

      // 直接更新最后一根K线数据
      if (option.series[0] && option.series[0].data && option.series[0].data[lastIndex]) {
        option.series[0].data[lastIndex] = [updatedCandle[1], updatedCandle[2], updatedCandle[4], updatedCandle[3]]
      }
      if (option.series[1] && option.series[1].data && option.series[1].data[lastIndex]) {
        option.series[1].data[lastIndex] = updatedCandle[5]
      }

      // 计算并更新MA指标
      const ma5 = this.calculateMA(this.klineData, 5)
      const ma10 = this.calculateMA(this.klineData, 10)
      const ma20 = this.calculateMA(this.klineData, 20)
      const compare10minData = this.calculate10MinCompare()

      if (option.series[2] && option.series[2].data && option.series[2].data[lastIndex]) {
        option.series[2].data[lastIndex] = ma5[lastIndex]
      }
      if (option.series[3] && option.series[3].data && option.series[3].data[lastIndex]) {
        option.series[3].data[lastIndex] = ma10[lastIndex]
      }
      if (option.series[4] && option.series[4].data && option.series[4].data[lastIndex]) {
        option.series[4].data[lastIndex] = ma20[lastIndex]
      }
      if (option.series[5] && option.series[5].data && option.series[5].data[lastIndex]) {
        option.series[5].data[lastIndex] = compare10minData[lastIndex]
      }

      this.klineChart.setOption(option)
    },

    updateChart() {
      this.updateChartFull()
    },

    disposeChart() {
      if (this.klineChart) {
        this.klineChart.dispose()
        this.klineChart = null
      }
    }
  }
}
</script>

<style scoped>
.eth-kline-container {
  width: 100%;
  height: 100vh;
  background: #0d0d1a;
  display: flex;
  flex-direction: column;
  color: #fff;
  position: relative;
  overflow: hidden;
}

.price-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 20px;
  padding: 20px 20px;
  background: linear-gradient(180deg, #1a1a2e 0%, #0d0d1a 100%);
  border-bottom: 2px solid #444;
  flex-shrink: 0;
  z-index: 100;
  transition: all 0.3s ease;
}

.price-panel.up {
  border-bottom-color: #00D4AA;
}

.price-panel.down {
  border-bottom-color: #FF4757;
}

.price-panel .price-label {
  font-size: 18px;
  color: #666;
  font-weight: 500;
}

.price-panel .current-price {
  font-size: 42px;
  font-weight: bold;
  font-family: 'Courier New', monospace;
  letter-spacing: 2px;
}

.price-panel.up .current-price {
  color: #00D4AA;
}

.price-panel.down .current-price {
  color: #FF4757;
}

.price-panel .price-change {
  font-size: 18px;
  padding: 8px 15px;
  border-radius: 6px;
  font-weight: 500;
}

.price-panel.up .price-change {
  background: rgba(0, 212, 170, 0.2);
  color: #00D4AA;
}

.price-panel.down .price-change {
  background: rgba(255, 71, 87, 0.2);
  color: #FF4757;
}

.price-panel .update-indicator {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #666;
  transition: all 0.1s ease;
}

.price-panel .update-indicator.active {
  background: #00D4AA;
  transform: scale(1.5);
  box-shadow: 0 0 10px #00D4AA;
}

.control-panel {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 12px 20px;
  background: #1a1a2e;
  border-bottom: 1px solid #333;
  flex-shrink: 0;
  z-index: 100;
}

.control-panel button {
  padding: 8px 16px;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  transition: all 0.3s;
}

.control-panel button.connected {
  background: #FF4757;
  color: white;
}

.control-panel button.disconnected {
  background: #2196F3;
  color: white;
}

.control-panel button.log-toggle {
  margin-left: auto;
  background: #2a2a4a;
  color: #999;
  font-size: 12px;
  padding: 6px 12px;
}

.control-panel button:hover:not(:disabled) {
  opacity: 0.9;
  transform: translateY(-1px);
}

.control-panel button:disabled {
  background: #333;
  cursor: not-allowed;
}

.control-panel .status {
  font-size: 12px;
  padding: 4px 12px;
  border-radius: 4px;
}

.control-panel .status.online {
  background: rgba(0, 212, 170, 0.2);
  color: #00D4AA;
}

.control-panel .status.offline {
  background: rgba(255, 71, 87, 0.2);
  color: #FF4757;
}

.control-panel .update-time {
  font-size: 12px;
  color: #2196F3;
}

.indicator-selector {
  display: flex;
  align-items: center;
  gap: 8px;
}

.indicator-selector span {
  font-size: 12px;
  color: #666;
}

.indicator-selector button {
  padding: 4px 10px;
  font-size: 12px;
  background: #2a2a4a;
  color: #999;
}

.indicator-selector button.active {
  background: #2196F3;
  color: white;
}

.log-panel {
  background: #1a1a2e;
  border-bottom: 1px solid #333;
  height: 150px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  z-index: 50;
}

.log-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 15px;
  background: #2a2a4a;
  font-size: 12px;
}

.log-header button {
  background: none;
  border: none;
  color: #666;
  cursor: pointer;
  font-size: 12px;
}

.log-header button:hover {
  color: #fff;
}

.log-content {
  flex: 1;
  overflow-y: auto;
  padding: 8px 15px;
  font-size: 12px;
}

.log-content .log-time {
  color: #666;
  margin-right: 8px;
}

.log-content .info {
  color: #2196F3;
}

.log-content .success {
  color: #00D4AA;
}

.log-content .error {
  color: #FF4757;
}

.log-content .warning {
  color: #FF9800;
}

.kline-chart {
  flex: 1;
  width: 100%;
  z-index: 10;
}
</style>