/**
 * ECharts 按需引入配置。
 *
 * 仅注册项目实际使用的图表类型和组件，控制打包体积（~100KB gzip）。
 * vue-echarts 会自动使用此处注册的模块进行渲染。
 *
 * @author zsg
 * @since 2026-03-07
 */
import { use } from 'echarts/core'
import { PieChart, LineChart, BarChart } from 'echarts/charts'
import {
  TooltipComponent,
  LegendComponent,
  GridComponent,
  MarkPointComponent,
  DataZoomComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

// 注册图表类型、交互组件和渲染器
use([
  PieChart,
  LineChart,
  BarChart,
  TooltipComponent,
  LegendComponent,
  GridComponent,
  MarkPointComponent,
  DataZoomComponent,
  CanvasRenderer,
])
