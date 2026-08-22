<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ data: unknown; rawLabel?: string }>()

const rows = computed(() => {
  if (Array.isArray(props.data)) return props.data.map((value, index) => ({ key: String(index + 1), value }))
  if (props.data && typeof props.data === 'object') return Object.entries(props.data as Record<string, unknown>).map(([key, value]) => ({ key, value }))
  return [{ key: 'result', value: props.data }]
})

function display(value: unknown) {
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  if (Array.isArray(value)) return value.length ? `${value.length} items` : '0 items'
  if (typeof value === 'object') return `${Object.keys(value as object).length} fields`
  return String(value)
}
</script>

<template>
  <div class="structured-result">
    <dl>
      <div v-for="row in rows" :key="row.key"><dt>{{ row.key }}</dt><dd>{{ display(row.value) }}</dd></div>
    </dl>
    <details><summary>{{ rawLabel || 'Raw JSON' }}</summary><pre>{{ JSON.stringify(data, null, 2) }}</pre></details>
  </div>
</template>

<style scoped>
.structured-result { border: 1px solid var(--console-line); border-radius: 7px; background: var(--console-panel); overflow: hidden; }
dl { margin: 0; display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); }
dl > div { min-height: 64px; padding: 12px 14px; border-right: 1px solid var(--console-line); border-bottom: 1px solid var(--console-line); }
dt { color: var(--console-muted); font: 12px ui-monospace, monospace; overflow-wrap: anywhere; }
dd { margin: 7px 0 0; color: var(--console-ink); font-size: 13px; font-weight: 700; overflow-wrap: anywhere; }
details { padding: 10px 14px; }
summary { color: var(--console-primary); font-size: 12px; cursor: pointer; }
pre { max-height: 320px; margin: 10px -14px -10px; padding: 14px; overflow: auto; border-top: 1px solid var(--console-line); background: #0b0e10; color: var(--console-ink-soft); font: 12px/1.6 ui-monospace, monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
