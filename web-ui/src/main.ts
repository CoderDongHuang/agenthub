import { createApp } from 'vue'
import { createPinia } from 'pinia'
import {
  ElButton,
  ElCol,
  ElConfigProvider,
  ElDialog,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElInputNumber,
  ElLink,
  ElLoading,
  ElOption,
  ElRow,
  ElSelect,
  ElSlider,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus'
import 'element-plus/es/components/base/style/css'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/col/style/css'
import 'element-plus/es/components/config-provider/style/css'
import 'element-plus/es/components/dialog/style/css'
import 'element-plus/es/components/empty/style/css'
import 'element-plus/es/components/form/style/css'
import 'element-plus/es/components/form-item/style/css'
import 'element-plus/es/components/icon/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/input-number/style/css'
import 'element-plus/es/components/link/style/css'
import 'element-plus/es/components/loading/style/css'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/option/style/css'
import 'element-plus/es/components/row/style/css'
import 'element-plus/es/components/select/style/css'
import 'element-plus/es/components/slider/style/css'
import 'element-plus/es/components/switch/style/css'
import 'element-plus/es/components/table/style/css'
import 'element-plus/es/components/table-column/style/css'
import 'element-plus/es/components/tag/style/css'

import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import './style.css'
import './styles/site.css'
import './styles/console.css'
import './styles/console-refine.css'

const app = createApp(App)

const elementComponents = [
  ElButton, ElCol, ElConfigProvider, ElDialog, ElEmpty, ElForm, ElFormItem,
  ElIcon, ElInput, ElInputNumber, ElLink, ElOption, ElRow, ElSelect, ElSlider,
  ElSwitch, ElTable, ElTableColumn, ElTag,
]
elementComponents.forEach(component => app.component(component.name!, component))
app.directive('loading', ElLoading.directive)

app.use(createPinia())
app.use(router)
app.use(i18n)

app.mount('#app')
