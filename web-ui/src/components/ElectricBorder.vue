<script setup>
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue'

const props = defineProps({
  color: { type: String, default: '#6366f1' },
  speed: { type: Number, default: 1 },
  chaos: { type: Number, default: 0.12 },
  borderRadius: { type: Number, default: 24 },
})

const canvasRef = ref(null)
const containerRef = ref(null)
let animationId = null
let timeRef = 0
let lastFrameTime = 0

// Noise
const random = (x) => (Math.sin(x * 12.9898) * 43758.5453) % 1

const noise2D = (x, y) => {
  const i = Math.floor(x), j = Math.floor(y)
  const fx = x - i, fy = y - j
  const a = random(i + j * 57), b = random(i + 1 + j * 57)
  const c = random(i + (j + 1) * 57), d = random(i + 1 + (j + 1) * 57)
  const ux = fx * fx * (3 - 2 * fx), uy = fy * fy * (3 - 2 * fy)
  return a * (1 - ux) * (1 - uy) + b * ux * (1 - uy) + c * (1 - ux) * uy + d * ux * uy
}

const octavedNoise = (x, octaves, lacunarity, gain, baseAmp, baseFreq, time, seed, flatness) => {
  let y = 0, amp = baseAmp, freq = baseFreq
  for (let i = 0; i < octaves; i++) {
    y += (i === 0 ? amp * flatness : amp) * noise2D(freq * x + seed * 100, time * freq * 0.3)
    freq *= lacunarity; amp *= gain
  }
  return y
}

const getCornerPoint = (cx, cy, r, startAngle, arcLen, progress) => ({
  x: cx + r * Math.cos(startAngle + progress * arcLen),
  y: cy + r * Math.sin(startAngle + progress * arcLen),
})

const getRoundedRectPoint = (t, left, top, w, h, r) => {
  const sw = w - 2 * r, sh = h - 2 * r
  const corner = (Math.PI * r) / 2
  const total = 2 * sw + 2 * sh + 4 * corner
  const dist = t * total
  let acc = 0

  if (dist <= acc + sw) return { x: left + r + (dist - acc) / sw * sw, y: top }
  acc += sw
  if (dist <= acc + corner) return getCornerPoint(left + w - r, top + r, r, -Math.PI / 2, Math.PI / 2, (dist - acc) / corner)
  acc += corner
  if (dist <= acc + sh) return { x: left + w, y: top + r + (dist - acc) / sh * sh }
  acc += sh
  if (dist <= acc + corner) return getCornerPoint(left + w - r, top + h - r, r, 0, Math.PI / 2, (dist - acc) / corner)
  acc += corner
  if (dist <= acc + sw) return { x: left + w - r - (dist - acc) / sw * sw, y: top + h }
  acc += sw
  if (dist <= acc + corner) return getCornerPoint(left + r, top + h - r, r, Math.PI / 2, Math.PI / 2, (dist - acc) / corner)
  acc += corner
  if (dist <= acc + sh) return { x: left, y: top + h - r - (dist - acc) / sh * sh }
  acc += sh
  return getCornerPoint(left + r, top + r, r, Math.PI, Math.PI / 2, (dist - acc) / corner)
}

const draw = () => {
  const canvas = canvasRef.value
  const container = containerRef.value
  if (!canvas || !container) return

  const ctx = canvas.getContext('2d')
  if (!ctx) return

  const dpr = Math.min(devicePixelRatio || 1, 2)
  const borderOffset = 60
  const rect = container.getBoundingClientRect()
  const w = rect.width + borderOffset * 2
  const h = rect.height + borderOffset * 2

  if (canvas.width !== w * dpr || canvas.height !== h * dpr) {
    canvas.width = w * dpr; canvas.height = h * dpr
    canvas.style.width = w + 'px'; canvas.style.height = h + 'px'
  }

  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.clearRect(0, 0, w, h)

  const now = performance.now()
  const delta = (now - lastFrameTime) / 1000
  timeRef += delta * props.speed
  lastFrameTime = now

  ctx.strokeStyle = props.color
  ctx.lineWidth = 1.5
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'

  const L = borderOffset, T = borderOffset
  const bw = w - 2 * borderOffset, bh = h - 2 * borderOffset
  const maxR = Math.min(bw, bh) / 2
  const radius = Math.min(props.borderRadius, maxR)
  const perimeter = 2 * (bw + bh) + 2 * Math.PI * radius
  const samples = Math.floor(perimeter / 2)

  ctx.beginPath()
  for (let i = 0; i <= samples; i++) {
    const p = i / samples
    const pt = getRoundedRectPoint(p, L, T, bw, bh, radius)
    const nx = octavedNoise(p * 8, 10, 1.6, 0.7, props.chaos, 10, timeRef, 0, 0)
    const ny = octavedNoise(p * 8, 10, 1.6, 0.7, props.chaos, 10, timeRef, 1, 0)
    const dx = pt.x + nx * 60, dy = pt.y + ny * 60
    i === 0 ? ctx.moveTo(dx, dy) : ctx.lineTo(dx, dy)
  }
  ctx.closePath()
  ctx.stroke()

  animationId = requestAnimationFrame(draw)
}

let observer = null
onMounted(async () => {
  await nextTick()
  observer = new ResizeObserver(() => {})
  observer.observe(containerRef.value)
  lastFrameTime = performance.now()
  animationId = requestAnimationFrame(draw)
})

onUnmounted(() => {
  cancelAnimationFrame(animationId)
  observer?.disconnect()
})
</script>

<template>
  <div ref="containerRef" class="electric-border" :style="{ borderRadius: borderRadius + 'px' }">
    <div class="eb-canvas-wrap">
      <canvas ref="canvasRef" class="eb-canvas" />
    </div>
    <div class="eb-glow-1" :style="{ borderColor: color + '99' }" />
    <div class="eb-glow-2" :style="{ borderColor: color }" />
    <div class="eb-content">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.electric-border {
  position: relative;
  overflow: visible;
  isolation: isolate;
}
.eb-canvas-wrap {
  position: absolute;
  top: 50%; left: 50%;
  transform: translate(-50%, -50%);
  pointer-events: none;
  z-index: 2;
}
.eb-canvas { display: block; }
.eb-content {
  position: relative;
  border-radius: inherit;
  z-index: 1;
}
.eb-glow-1,
.eb-glow-2 {
  position: absolute;
  inset: 0;
  border-radius: inherit;
  pointer-events: none;
  border: 2px solid;
}
.eb-glow-1 { filter: blur(1px); }
.eb-glow-2 { filter: blur(4px); }
</style>
