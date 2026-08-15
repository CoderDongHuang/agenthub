<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { translateUiText } from '../i18n/uiText'
import type { AppLocale } from '../i18n'

const { locale } = useI18n()
const originalText = new WeakMap<CharacterData, string>()
const originalAttributes = new WeakMap<Element, Map<string, string>>()
const translatedAttributes = ['aria-label', 'placeholder', 'title', 'description', 'alt']
let observer: MutationObserver | undefined
let queued = false

function shouldSkip(node: Node) {
  const element = node.nodeType === Node.ELEMENT_NODE ? node as Element : node.parentElement
  return Boolean(element?.closest('script, style, pre, code, .markdown-body, [data-no-ui-translate]'))
}

function updateText(node: CharacterData) {
  if (shouldSkip(node)) return
  const current = node.data
  if (!originalText.has(node) && /\p{Script=Han}/u.test(current)) originalText.set(node, current)
  const source = originalText.get(node)
  if (!source) return
  const next = locale.value === 'zh-CN' ? source : translateUiText(source, locale.value as AppLocale)
  if (node.data !== next) node.data = next
}

function updateAttributes(element: Element) {
  if (shouldSkip(element)) return
  let originals = originalAttributes.get(element)
  if (!originals) { originals = new Map(); originalAttributes.set(element, originals) }
  for (const name of translatedAttributes) {
    const current = element.getAttribute(name)
    if (current && !originals.has(name) && /\p{Script=Han}/u.test(current)) originals.set(name, current)
    const source = originals.get(name)
    if (!source) continue
    const next = locale.value === 'zh-CN' ? source : translateUiText(source, locale.value as AppLocale)
    if (current !== next) element.setAttribute(name, next)
  }
}

function walk(root: Node) {
  if (root.nodeType === Node.TEXT_NODE) return updateText(root as CharacterData)
  if (root.nodeType !== Node.ELEMENT_NODE && root.nodeType !== Node.DOCUMENT_FRAGMENT_NODE) return
  if (root.nodeType === Node.ELEMENT_NODE) updateAttributes(root as Element)
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT)
  let node = walker.nextNode()
  while (node) {
    if (node.nodeType === Node.TEXT_NODE) updateText(node as CharacterData)
    else updateAttributes(node as Element)
    node = walker.nextNode()
  }
}

function applyAll() {
  queued = false
  observer?.disconnect()
  walk(document.body)
  observer?.observe(document.body, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: translatedAttributes })
}

function scheduleApply() {
  if (queued) return
  queued = true
  window.requestAnimationFrame(applyAll)
}

onMounted(() => {
  observer = new MutationObserver(scheduleApply)
  applyAll()
})
watch(locale, async value => {
  document.documentElement.lang = value
  await nextTick()
  scheduleApply()
})
onBeforeUnmount(() => observer?.disconnect())
</script>

<template><span class="ui-language-bridge" aria-hidden="true" /></template>

<style scoped>.ui-language-bridge { display: none; }</style>
