import { createApp } from 'vue'
import { createPinia } from 'pinia'
import Antd, { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
import updateLocale from 'dayjs/plugin/updateLocale'
import isoWeek from 'dayjs/plugin/isoWeek'
import 'ant-design-vue/dist/reset.css'
import './style.css'
import App from './App.vue'
import router from './router'

dayjs.extend(updateLocale)
dayjs.extend(isoWeek)
dayjs.locale('zh-cn')
dayjs.updateLocale('zh-cn', {
  months: '一月_二月_三月_四月_五月_六月_七月_八月_九月_十月_十一月_十二月'.split('_'),
  monthsShort: '1月_2月_3月_4月_5月_6月_7月_8月_9月_10月_11月_12月'.split('_')
})

const app = createApp(App)

/** 未捕获的组件渲染/生命周期错误 */
app.config.errorHandler = (err, _instance, info) => {
  console.error('[Vue error]', info, err)
  message.error('页面异常，请刷新后重试')
}

/** 未处理的 Promise 拒绝（非 axios 已 toast 的） */
window.addEventListener('unhandledrejection', (event) => {
  const reason = event.reason
  // axios 拦截器已 reject Error 并 toast，避免重复弹窗
  if (reason instanceof Error && reason.message) {
    console.error('[unhandledrejection]', reason)
    return
  }
  console.error('[unhandledrejection]', reason)
  message.error('操作异常，请稍后重试')
})

app.use(createPinia())
app.use(router)
app.use(Antd)
app.mount('#app')
