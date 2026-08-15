<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  Bell, Brush, ChatDotRound, Close, Delete, Monitor, MoonNight,
  Operation, Picture, RefreshLeft, Setting, Sunny, UploadFilled,
} from '@element-plus/icons-vue'
import { switchLang } from '../i18n'
import { resetPreferences, usePreferences, type AppPreferences } from '../preferences'

defineProps<{ compact?: boolean }>()

const { locale } = useI18n()
const preferences = usePreferences()
const open = ref(false)
const uploadError = ref('')
const notificationState = ref(typeof Notification === 'undefined' ? 'unsupported' : Notification.permission)
const isEnglish = computed(() => locale.value === 'en-US')
const text = computed(() => isEnglish.value ? {
  trigger: 'Personalization', title: 'Personalization', subtitle: 'Appearance, interaction and notification preferences',
  visual: 'Appearance', language: 'Interface language', theme: 'Theme', dark: 'Default dark', light: 'Clean white', custom: 'Custom',
  upload: 'Choose background image', removeImage: 'Remove image', imageHint: 'JPG, PNG or WebP, up to 2 MB',
  overlay: 'Background overlay', blur: 'Background blur', density: 'Layout density', comfortable: 'Comfortable', compact: 'Compact',
  reduceMotion: 'Reduce motion', reduceMotionHint: 'Disable decorative animation and smooth transitions',
  agent: 'Agent interaction', stream: 'Streaming speed', instant: 'Instant', fast: 'Fast', standard: 'Standard', realistic: 'Natural typing',
  codeTheme: 'Code and JSON theme', collapse: 'Collapse large JSON', collapseHint: 'Collapse tool output over the configured line count', lines: 'lines',
  shortcut: 'Shortcuts', searchShortcut: 'Open global search', both: 'Ctrl/Cmd + K or /', sendShortcut: 'Send message', enter: 'Enter', ctrlEnter: 'Ctrl/Cmd + Enter',
  notifications: 'Notifications & approvals', sound: 'Approval sound', desktop: 'Desktop notification', badge: 'Show pending approval badge',
  authorize: 'Enable browser notifications', authorized: 'Browser notifications enabled', unsupported: 'Notifications are unavailable in this browser',
  local: 'Saved locally on this device', reset: 'Restore defaults', close: 'Close personalization', uploadLarge: 'Image must be smaller than 2 MB.', uploadType: 'Choose a JPG, PNG or WebP image.',
} : {
  trigger: '个性设置', title: '个性设置', subtitle: '统一管理外观、交互与提醒偏好',
  visual: '外观与视觉', language: '界面语言', theme: '主题设置', dark: '默认黑色', light: '洁净白色', custom: '自定义',
  upload: '选择背景图片', removeImage: '移除图片', imageHint: '支持 JPG、PNG、WebP，最大 2 MB',
  overlay: '背景遮罩深度', blur: '背景模糊度', density: '布局紧凑度', comfortable: '默认 / 舒缓', compact: '紧凑',
  reduceMotion: '减弱动效', reduceMotionHint: '关闭装饰动画与平滑过渡，降低渲染开销',
  agent: 'Agent 交互与调试', stream: '流式输出速度', instant: '直接输出', fast: '极速', standard: '标准', realistic: '模拟真实打字',
  codeTheme: '代码与 JSON 主题', collapse: '自动折叠大 JSON', collapseHint: '工具输出超过设定行数时默认收起', lines: '行',
  shortcut: '快捷键偏好', searchShortcut: '唤起全局搜索', both: 'Ctrl/Cmd + K 或 /', sendShortcut: '发送消息', enter: 'Enter', ctrlEnter: 'Ctrl/Cmd + Enter',
  notifications: '消息与审批提醒', sound: '审批提示音', desktop: '桌面系统通知', badge: '显示待审批角标',
  authorize: '授权浏览器通知', authorized: '浏览器通知已启用', unsupported: '当前浏览器不支持系统通知',
  local: '设置已保存在当前设备', reset: '恢复默认设置', close: '关闭个性设置', uploadLarge: '图片大小不能超过 2 MB。', uploadType: '请选择 JPG、PNG 或 WebP 图片。',
})

const streamingOptions = computed(() => [
  { value: 'instant', label: text.value.instant }, { value: 'fast', label: text.value.fast },
  { value: 'standard', label: text.value.standard }, { value: 'realistic', label: text.value.realistic },
])

function setPreference<K extends keyof AppPreferences>(key: K, value: AppPreferences[K]) { preferences[key] = value }
function setLanguage(value: 'zh-CN' | 'en-US') { preferences.language = value; switchLang(value) }
function chooseTheme(value: AppPreferences['theme']) { preferences.theme = value }
function handleKeydown(event: KeyboardEvent) { if (event.key === 'Escape') open.value = false }
function toggleOpen() { open.value = !open.value }

function uploadBackground(event: Event) {
  uploadError.value = ''
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) { uploadError.value = text.value.uploadType; input.value = ''; return }
  if (file.size > 2 * 1024 * 1024) { uploadError.value = text.value.uploadLarge; input.value = ''; return }
  const reader = new FileReader()
  reader.onload = () => { preferences.customBackground = String(reader.result || ''); preferences.theme = 'custom' }
  reader.readAsDataURL(file)
  input.value = ''
}

async function requestNotifications() {
  if (typeof Notification === 'undefined') return
  notificationState.value = await Notification.requestPermission()
  preferences.desktopNotifications = notificationState.value === 'granted'
}

function restoreDefaults() {
  resetPreferences()
  switchLang(preferences.language)
  uploadError.value = ''
}

onMounted(() => window.addEventListener('keydown', handleKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', handleKeydown))
</script>

<template>
  <div class="personalization-root" data-no-ui-translate>
    <button class="personalization-trigger" type="button" :class="{ compact }" :aria-label="text.trigger" :title="compact ? text.trigger : undefined" @click="toggleOpen">
      <el-icon><Setting /></el-icon><span v-if="!compact">{{ text.trigger }}</span>
    </button>

    <Teleport to="body">
      <div v-if="open" class="preference-backdrop" data-no-ui-translate @click.self="open = false">
        <aside class="preference-panel" role="dialog" aria-modal="true" :aria-label="text.title">
          <header class="preference-head"><div><span>PERSONALIZATION</span><h2>{{ text.title }}</h2><p>{{ text.subtitle }}</p></div><button type="button" :aria-label="text.close" @click="open = false"><el-icon><Close /></el-icon></button></header>
          <div class="preference-scroll">
            <section>
              <div class="preference-section-title"><el-icon><Brush /></el-icon><div><span>VISUAL & THEME</span><h3>{{ text.visual }}</h3></div></div>
              <div class="preference-field"><label>{{ text.language }}</label><div class="preference-segment"><button :class="{ active: locale === 'zh-CN' }" @click="setLanguage('zh-CN')">中文</button><button :class="{ active: locale === 'en-US' }" @click="setLanguage('en-US')">English</button></div></div>
              <div class="preference-field"><label>{{ text.theme }}</label><div class="theme-options">
                <button :class="{ active: preferences.theme === 'dark' }" @click="chooseTheme('dark')"><i class="theme-preview dark"><MoonNight /></i><span>{{ text.dark }}</span></button>
                <button :class="{ active: preferences.theme === 'light' }" @click="chooseTheme('light')"><i class="theme-preview light"><Sunny /></i><span>{{ text.light }}</span></button>
                <button :class="{ active: preferences.theme === 'custom' }" @click="chooseTheme('custom')"><i class="theme-preview custom"><Picture /></i><span>{{ text.custom }}</span></button>
              </div></div>
              <div v-if="preferences.theme === 'custom'" class="custom-theme-controls">
                <div class="background-upload"><label><input type="file" accept="image/jpeg,image/png,image/webp" @change="uploadBackground" /><el-icon><UploadFilled /></el-icon><span>{{ text.upload }}</span></label><button v-if="preferences.customBackground" :title="text.removeImage" @click="preferences.customBackground = ''"><el-icon><Delete /></el-icon></button></div>
                <small :class="{ error: uploadError }">{{ uploadError || text.imageHint }}</small>
                <div class="range-field"><label><span>{{ text.overlay }}</span><b>{{ preferences.overlayOpacity }}%</b></label><input v-model.number="preferences.overlayOpacity" type="range" min="0" max="90" /></div>
                <div class="range-field"><label><span>{{ text.blur }}</span><b>{{ preferences.backgroundBlur }}px</b></label><input v-model.number="preferences.backgroundBlur" type="range" min="0" max="20" /></div>
              </div>
              <div class="preference-field"><label>{{ text.density }}</label><div class="preference-segment"><button :class="{ active: preferences.density === 'comfortable' }" @click="setPreference('density', 'comfortable')">{{ text.comfortable }}</button><button :class="{ active: preferences.density === 'compact' }" @click="setPreference('density', 'compact')">{{ text.compact }}</button></div></div>
              <label class="preference-toggle"><span><b>{{ text.reduceMotion }}</b><small>{{ text.reduceMotionHint }}</small></span><input v-model="preferences.reduceMotion" type="checkbox" /><i /></label>
            </section>

            <section>
              <div class="preference-section-title"><el-icon><ChatDotRound /></el-icon><div><span>AGENT INTERACTION</span><h3>{{ text.agent }}</h3></div></div>
              <div class="preference-field"><label>{{ text.stream }}</label><select v-model="preferences.streamingSpeed"><option v-for="option in streamingOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></div>
              <div class="preference-field"><label>{{ text.codeTheme }}</label><select v-model="preferences.codeTheme"><option value="vscode-dark">VS Code Dark</option><option value="github-light">GitHub Light</option><option value="monokai">Monokai</option></select></div>
              <label class="preference-toggle"><span><b>{{ text.collapse }}</b><small>{{ text.collapseHint }}</small></span><input v-model="preferences.collapseLargeJson" type="checkbox" /><i /></label>
              <div v-if="preferences.collapseLargeJson" class="line-threshold"><input v-model.number="preferences.jsonLineThreshold" type="number" min="10" max="500" /><span>{{ text.lines }}</span></div>
            </section>

            <section>
              <div class="preference-section-title"><el-icon><Operation /></el-icon><div><span>SHORTCUTS</span><h3>{{ text.shortcut }}</h3></div></div>
              <div class="preference-field"><label>{{ text.searchShortcut }}</label><select v-model="preferences.searchShortcut"><option value="both">{{ text.both }}</option><option value="ctrl-k">Ctrl / Cmd + K</option><option value="slash">/</option></select></div>
              <div class="preference-field"><label>{{ text.sendShortcut }}</label><div class="preference-segment"><button :class="{ active: preferences.sendShortcut === 'enter' }" @click="setPreference('sendShortcut', 'enter')">{{ text.enter }}</button><button :class="{ active: preferences.sendShortcut === 'ctrl-enter' }" @click="setPreference('sendShortcut', 'ctrl-enter')">{{ text.ctrlEnter }}</button></div></div>
            </section>

            <section>
              <div class="preference-section-title"><el-icon><Bell /></el-icon><div><span>NOTIFICATIONS</span><h3>{{ text.notifications }}</h3></div></div>
              <label class="preference-toggle"><span><b>{{ text.sound }}</b></span><input v-model="preferences.approvalSound" type="checkbox" /><i /></label>
              <label class="preference-toggle"><span><b>{{ text.desktop }}</b></span><input v-model="preferences.desktopNotifications" type="checkbox" :disabled="notificationState !== 'granted'" /><i /></label>
              <button v-if="notificationState === 'default'" class="notification-permission" @click="requestNotifications"><el-icon><Monitor /></el-icon>{{ text.authorize }}</button>
              <p v-else-if="notificationState === 'granted'" class="permission-state"><i />{{ text.authorized }}</p>
              <p v-else-if="notificationState === 'unsupported'" class="permission-state muted">{{ text.unsupported }}</p>
              <label class="preference-toggle"><span><b>{{ text.badge }}</b></span><input v-model="preferences.approvalBadge" type="checkbox" /><i /></label>
            </section>
          </div>
          <footer class="preference-foot"><span><i />{{ text.local }}</span><button @click="restoreDefaults"><el-icon><RefreshLeft /></el-icon>{{ text.reset }}</button></footer>
        </aside>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.personalization-root { display: flex; flex: 0 0 auto; }
.personalization-trigger { min-height: 40px; padding: 0 12px; display: inline-flex; align-items: center; justify-content: center; gap: 7px; border: 1px solid var(--cyber-line-strong); border-radius: 7px; background: var(--cyber-panel); color: var(--cyber-text-soft); font: 700 11px/1 Inter, system-ui, sans-serif; cursor: pointer; backdrop-filter: blur(12px); white-space: nowrap; }
.personalization-trigger:hover { border-color: rgba(99,102,241,.55); color: var(--cyber-text); box-shadow: 0 0 18px rgba(99,102,241,.14); }
.personalization-trigger.compact { width: 40px; padding: 0; }
.preference-backdrop { position: fixed; inset: 0; z-index: 1000; display: flex; justify-content: flex-end; background: rgba(2,6,12,.7); backdrop-filter: blur(5px); }
.preference-panel { width: min(480px, 100%); height: 100%; display: flex; flex-direction: column; border-left: 1px solid var(--cyber-line-strong); background: rgba(11,15,23,.98); color: var(--cyber-text); box-shadow: -28px 0 80px rgba(0,0,0,.42); }
.preference-head { min-height: 104px; padding: 22px 22px 18px; display: flex; align-items: flex-start; justify-content: space-between; border-bottom: 1px solid var(--cyber-line); }
.preference-head span, .preference-section-title span { color: #a5b4fc; font: 700 8px ui-monospace, monospace; }
.preference-head h2 { margin-top: 5px; font-size: 22px; }.preference-head p { margin-top: 6px; color: var(--cyber-muted); font-size: 11px; }
.preference-head > button { width: 36px; height: 36px; display: grid; place-items: center; border: 1px solid var(--cyber-line); border-radius: 7px; background: var(--cyber-panel); color: var(--cyber-text-soft); cursor: pointer; }
.preference-scroll { flex: 1; min-height: 0; padding: 0 22px 28px; overflow-y: auto; }
.preference-scroll section { padding: 24px 0; border-bottom: 1px solid var(--cyber-line); }.preference-scroll section:last-child { border-bottom: 0; }
.preference-section-title { display: flex; align-items: center; gap: 10px; }.preference-section-title > .el-icon { width: 35px; height: 35px; display: grid; place-items: center; border-radius: 7px; background: var(--cyber-indigo-soft); color: #a5b4fc; font-size: 17px; }.preference-section-title h3 { margin-top: 3px; font-size: 14px; }
.preference-field { margin-top: 18px; }.preference-field > label, .range-field label { margin-bottom: 8px; display: flex; justify-content: space-between; color: var(--cyber-text-soft); font-size: 10px; font-weight: 700; }
.preference-segment { padding: 3px; display: grid; grid-template-columns: repeat(2, 1fr); gap: 3px; border: 1px solid var(--cyber-line); border-radius: 7px; background: var(--cyber-bg-deep); }.preference-segment button { min-height: 35px; border: 0; border-radius: 5px; background: transparent; color: var(--cyber-muted); font: inherit; font-size: 10px; cursor: pointer; }.preference-segment button.active { background: var(--cyber-indigo-soft); color: #c7d2fe; box-shadow: inset 0 0 0 1px rgba(99,102,241,.28); }
.theme-options { display: grid; grid-template-columns: repeat(3,1fr); gap: 8px; }.theme-options button { min-width: 0; padding: 7px; display: flex; flex-direction: column; gap: 7px; border: 1px solid var(--cyber-line); border-radius: 7px; background: transparent; color: var(--cyber-muted); font: inherit; font-size: 9px; cursor: pointer; }.theme-options button.active { border-color: rgba(99,102,241,.6); color: var(--cyber-text); box-shadow: 0 0 0 2px rgba(99,102,241,.1); }.theme-preview { height: 52px; display: grid; place-items: center; border-radius: 5px; font-size: 17px; }.theme-preview svg { width: 18px; }.theme-preview.dark { background: #0b0f17; color: #a5b4fc; }.theme-preview.light { background: #f5f7fb; color: #4f46e5; }.theme-preview.custom { background: linear-gradient(135deg,#16213a,#344267); color: #dbeafe; }
.custom-theme-controls { margin-top: 12px; padding: 12px; border: 1px solid var(--cyber-line); border-radius: 7px; background: var(--cyber-panel); }.background-upload { display: flex; gap: 7px; }.background-upload label { min-height: 38px; padding: 0 11px; flex: 1; display: flex; align-items: center; gap: 7px; border: 1px dashed var(--cyber-line-strong); border-radius: 6px; color: var(--cyber-text-soft); font-size: 9px; cursor: pointer; }.background-upload input { display: none; }.background-upload button { width: 38px; border: 1px solid var(--cyber-line); border-radius: 6px; background: transparent; color: var(--cyber-red); cursor: pointer; }.custom-theme-controls > small { margin-top: 7px; display: block; color: var(--cyber-muted); font-size: 8px; }.custom-theme-controls > small.error { color: #fda4af; }
.range-field { margin-top: 15px; }.range-field b { color: #c7d2fe; font-size: 9px; }.range-field input { width: 100%; accent-color: var(--cyber-indigo); }
.preference-field select { width: 100%; min-height: 40px; padding: 0 10px; border: 1px solid var(--cyber-line-strong); border-radius: 7px; outline: 0; background: var(--cyber-bg-deep); color: var(--cyber-text-soft); font: inherit; font-size: 10px; }
.preference-toggle { position: relative; min-height: 48px; margin-top: 14px; padding: 0 50px 0 0; display: flex; align-items: center; cursor: pointer; }.preference-toggle span { display: flex; flex-direction: column; gap: 4px; }.preference-toggle b { color: var(--cyber-text-soft); font-size: 10px; }.preference-toggle small { color: var(--cyber-muted); font-size: 8px; line-height: 1.5; }.preference-toggle input { position: absolute; opacity: 0; }.preference-toggle > i { position: absolute; right: 0; width: 38px; height: 22px; border-radius: 11px; background: #283246; transition: background .18s; }.preference-toggle > i::after { content: ''; position: absolute; left: 3px; top: 3px; width: 16px; height: 16px; border-radius: 50%; background: #a8b3c4; transition: transform .18s, background .18s; }.preference-toggle input:checked + i { background: var(--cyber-indigo); }.preference-toggle input:checked + i::after { transform: translateX(16px); background: #fff; }.preference-toggle input:disabled + i { opacity: .45; }
.line-threshold { width: 120px; min-height: 34px; margin-left: auto; display: grid; grid-template-columns: 1fr auto; align-items: center; border: 1px solid var(--cyber-line); border-radius: 6px; overflow: hidden; }.line-threshold input { min-width: 0; height: 32px; padding: 0 8px; border: 0; outline: 0; background: var(--cyber-bg-deep); color: var(--cyber-text); }.line-threshold span { padding-right: 8px; color: var(--cyber-muted); font-size: 8px; }
.notification-permission { width: 100%; min-height: 38px; margin-top: 9px; display: flex; align-items: center; justify-content: center; gap: 7px; border: 1px solid rgba(99,102,241,.35); border-radius: 6px; background: var(--cyber-indigo-soft); color: #c7d2fe; font: inherit; font-size: 9px; cursor: pointer; }.permission-state { margin-top: 9px; display: flex; align-items: center; gap: 6px; color: #6ee7b7; font-size: 8px; }.permission-state i { width: 6px; height: 6px; border-radius: 50%; background: var(--cyber-green); }.permission-state.muted { color: var(--cyber-muted); }
.preference-foot { min-height: 68px; padding: 12px 22px; display: flex; align-items: center; justify-content: space-between; gap: 12px; border-top: 1px solid var(--cyber-line); background: var(--cyber-bg-deep); }.preference-foot > span { display: flex; align-items: center; gap: 6px; color: var(--cyber-muted); font-size: 8px; }.preference-foot > span i { width: 6px; height: 6px; border-radius: 50%; background: var(--cyber-green); }.preference-foot button { min-height: 36px; padding: 0 11px; display: flex; align-items: center; gap: 6px; border: 1px solid var(--cyber-line-strong); border-radius: 6px; background: var(--cyber-panel); color: var(--cyber-text-soft); font: inherit; font-size: 9px; cursor: pointer; }
@media (max-width: 600px) { .personalization-trigger:not(.compact) { width: 40px; padding: 0; }.personalization-trigger:not(.compact) span { display: none; }.preference-panel { width: 100%; }.preference-scroll { padding-inline: 16px; }.preference-head, .preference-foot { padding-inline: 16px; } }
</style>
