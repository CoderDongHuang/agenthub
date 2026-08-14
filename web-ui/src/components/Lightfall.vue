<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { Renderer, Program, Mesh, Triangle } from 'ogl'

const props = defineProps({
  colors: { type: Array, default: () => ['#A6C8FF', '#5227FF', '#FF9FFC'] },
  backgroundColor: { type: String, default: '#0a0a1a' },
  speed: { type: Number, default: 0.5 },
  streakCount: { type: Number, default: 2 },
  streakWidth: { type: Number, default: 1 },
  streakLength: { type: Number, default: 1 },
  glow: { type: Number, default: 1 },
  density: { type: Number, default: 0.6 },
  twinkle: { type: Number, default: 1 },
  zoom: { type: Number, default: 3 },
  backgroundGlow: { type: Number, default: 0.5 },
  opacity: { type: Number, default: 1 },
})

const containerRef = ref(null)
let renderer, program, mesh, raf, ro, intersectionObserver
let mouseTarget = [0, 0]
let lastTime = 0
let lastFrame = 0
let isVisible = true
let isDocumentVisible = !document.hidden
let disposed = false
let onMove

const lowPowerDevice = (navigator.hardwareConcurrency || 4) <= 4
const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
const maxDpr = lowPowerDevice ? 1.25 : 1.5
const frameInterval = reducedMotion ? 1000 / 12 : lowPowerDevice ? 1000 / 30 : 1000 / 60

const hexToRGB = (hex) => {
  const c = hex.replace('#', '').padEnd(6, '0')
  return [parseInt(c.slice(0,2),16)/255, parseInt(c.slice(2,4),16)/255, parseInt(c.slice(4,6),16)/255]
}

const vertex = `attribute vec2 position; attribute vec2 uv; varying vec2 vUv;
void main() { vUv = uv; gl_Position = vec4(position, 0.0, 1.0); }`

const fragment = `precision highp float;
uniform vec3 iResolution, iMouse; uniform float iTime;
uniform vec3 uColor0,uColor1,uColor2,uColor3,uColor4,uColor5,uColor6,uColor7,uBgColor,uMouseColor;
uniform int uColorCount; uniform float uSpeed,uStreakCount,uStreakWidth,uStreakLength;
uniform float uGlow,uDensity,uTwinkle,uZoom,uBgGlow,uOpacity;
varying vec2 vUv;

vec3 palette(float h) {
  int c = uColorCount; if(c<1) c=1;
  int i = int(floor(clamp(h,0.0,0.999999)*float(c)));
  if(i<=0) return uColor0; if(i==1) return uColor1; if(i==2) return uColor2; if(i==3) return uColor3;
  if(i==4) return uColor4; if(i==5) return uColor5; if(i==6) return uColor6; return uColor7;
}

vec3 tanhv(vec3 x) { vec3 e = exp(-2.0*x); return (1.0-e)/(1.0+e); }

vec2 sceneC(vec2 frag, vec2 r) {
  vec2 P = (frag+frag-r)/r.x;
  float z=0.0, d=1e3;
  vec4 O=vec4(0.0);
  for(int k=0;k<39;k++) {
    if(d<=1e-4) break;
    O=z*normalize(vec4(P,uZoom,0.0))-vec4(0.0,4.0,1.0,0.0)/4.5;
    d=1.0-sqrt(length(O*O)); z+=d;
  }
  return vec2(O.x,atan(O.z,O.y));
}

void mainImage(out vec4 o, vec2 C) {
  vec2 r=iResolution.xy;
  vec2 uv0=(C+C-r)/r.x;
  float T=0.1*iTime*uSpeed+9.0;
  float angRings=max(1.0,floor(6.28318530718*max(uDensity,0.05)+0.5));
  vec2 Y=vec2(5e-3,6.28318530718/angRings);
  vec2 c0=sceneC(C,r), cdx=sceneC(C+vec2(1.0,0.0),r), cdy=sceneC(C+vec2(0.0,1.0),r);
  vec2 dCx=cdx-c0, dCy=cdy-c0;
  dCx.y-=6.28318530718*floor(dCx.y/6.28318530718+0.5);
  dCy.y-=6.28318530718*floor(dCy.y/6.28318530718+0.5);
  vec2 fw=abs(dCx)+abs(dCy); C=c0;
  vec2 P=vec2(2.0,1.0)*uv0-(r/r.x)*vec2(0.0,1.0);
  vec4 O=vec4(uBgColor*90.0*uBgGlow/(1e3*dot(P,P)+6.0),0.0);
  float zr=5e-4*uStreakWidth;
  vec2 rr=vec2(max(length(fw),1e-5));
  float tail=19.0/max(uStreakLength,0.05);
  int sc=int(uStreakCount);
  for(int m=0;m<16;m++) {
    if(m>=sc) break;
    float jf=float(m)+1.0;
    float ic=fract(sin(dot(vec2(jf,floor(C.x/Y.x+0.5)),vec2(7.0,11.0))*73.0));
    vec2 Pp=C-(T+T*ic)*vec2(0.0,1.0); Pp-=floor(Pp/Y+0.5)*Y;
    float h=fract(8663.0*ic);
    vec3 col=palette(h);
    float weight=mix(1.5,1.0+sin(T+7.0*h+4.0),uTwinkle);
    vec2 inner=vec2(length(max(Pp,vec2(-1.0,0.0))),length(Pp)-zr)-zr;
    vec2 sm=vec2(1.0)-smoothstep(-rr,rr,inner);
    O.rgb+=dot(sm,vec2(exp(tail*Pp.y),3.0))*col*weight;
    C.x+=Y.x/8.0;
  }
  vec3 colr=sqrt(tanhv(max(O.rgb*uGlow-vec3(0.04,0.08,0.02),0.0)));
  o=vec4(colr,uOpacity);
}

void main() { vec4 c; mainImage(c,vUv*iResolution.xy); gl_FragColor=c; }`

onMounted(() => {
  const el = containerRef.value
  if (!el) return

  renderer = new Renderer({
    dpr: Math.min(devicePixelRatio || 1, maxDpr),
    alpha: true,
    antialias: !lowPowerDevice,
  })
  const gl = renderer.gl
  const canvas = gl.canvas
  canvas.style.cssText = 'width:100%;height:100%;display:block;pointer-events:auto'
  el.appendChild(canvas)

  const { colors: cols } = props
  const arr = []
  const count = Math.min(cols.length, 8)
  for (let i = 0; i < 8; i++) arr.push(hexToRGB(cols[Math.min(i, cols.length - 1)]))
  const avg = [0, 0, 0]
  for (let i = 0; i < count; i++) { avg[0] += arr[i][0]; avg[1] += arr[i][1]; avg[2] += arr[i][2] }
  avg[0] /= count; avg[1] /= count; avg[2] /= count

  program = new Program(gl, {
    vertex, fragment,
    uniforms: {
      iResolution: { value: [gl.drawingBufferWidth, gl.drawingBufferHeight, 1] },
      iMouse: { value: [0, 0] }, iTime: { value: 0 },
      uColor0: { value: arr[0] }, uColor1: { value: arr[1] }, uColor2: { value: arr[2] },
      uColor3: { value: arr[3] }, uColor4: { value: arr[4] }, uColor5: { value: arr[5] },
      uColor6: { value: arr[6] }, uColor7: { value: arr[7] }, uColorCount: { value: count },
      uBgColor: { value: hexToRGB(props.backgroundColor) }, uMouseColor: { value: avg },
      uSpeed: { value: props.speed }, uStreakCount: { value: Math.max(1, Math.min(16, Math.round(props.streakCount))) },
      uStreakWidth: { value: props.streakWidth }, uStreakLength: { value: props.streakLength },
      uGlow: { value: props.glow }, uDensity: { value: props.density }, uTwinkle: { value: props.twinkle },
      uZoom: { value: props.zoom }, uBgGlow: { value: props.backgroundGlow }, uOpacity: { value: props.opacity },
      uMouseEnabled: { value: 1 }, uMouseStrength: { value: 1.8 }, uMouseRadius: { value: 2.2 },
    }
  })

  mesh = new Mesh(gl, { geometry: new Triangle(gl), program })

  const resize = () => {
    const rect = el.getBoundingClientRect()
    renderer.setSize(rect.width, rect.height)
    program.uniforms.iResolution.value = [gl.drawingBufferWidth, gl.drawingBufferHeight, 1]
  }
  resize()
  ro = new ResizeObserver(resize)
  ro.observe(el)

  onMove = (e) => {
    const rect = canvas.getBoundingClientRect()
    const scale = renderer.dpr || 1
    mouseTarget = [(e.clientX - rect.left) * scale, (rect.height - (e.clientY - rect.top)) * scale]
  }
  canvas.addEventListener('pointermove', onMove)

  const loop = (t) => {
    raf = undefined
    if (disposed || !isVisible || !isDocumentVisible) return
    raf = requestAnimationFrame(loop)
    if (t - lastFrame < frameInterval) return
    lastFrame = t
    program.uniforms.iTime.value = t * 0.001
    if (!lastTime) lastTime = t
    const dt = (t - lastTime) / 1000
    lastTime = t
    const tau = 0.15
    let factor = 1 - Math.exp(-dt / tau)
    if (factor > 1) factor = 1
    const cur = program.uniforms.iMouse.value
    cur[0] += (mouseTarget[0] - cur[0]) * factor
    cur[1] += (mouseTarget[1] - cur[1]) * factor
    renderer.render({ scene: mesh })
  }
  const resume = () => {
    if (!disposed && isVisible && isDocumentVisible && !raf) {
      lastTime = 0
      lastFrame = 0
      raf = requestAnimationFrame(loop)
    }
  }
  const onVisibilityChange = () => {
    isDocumentVisible = !document.hidden
    if (!isDocumentVisible && raf) {
      cancelAnimationFrame(raf)
      raf = undefined
    }
    resume()
  }
  document.addEventListener('visibilitychange', onVisibilityChange)
  intersectionObserver = new IntersectionObserver(([entry]) => {
    isVisible = entry?.isIntersecting ?? true
    if (!isVisible && raf) {
      cancelAnimationFrame(raf)
      raf = undefined
    }
    resume()
  }, { threshold: 0.01 })
  intersectionObserver.observe(el)
  resume()

  el.__lightfallCleanup = () => document.removeEventListener('visibilitychange', onVisibilityChange)
})

onUnmounted(() => {
  disposed = true
  if (raf) cancelAnimationFrame(raf)
  ro?.disconnect()
  intersectionObserver?.disconnect()
  if (renderer) {
    const canvas = renderer.gl.canvas
    if (onMove) canvas.removeEventListener('pointermove', onMove)
    if (canvas.parentElement === containerRef.value) containerRef.value.removeChild(canvas)
    if (renderer.gl) { const ext = renderer.gl.getExtension('WEBGL_lose_context'); if (ext) ext.loseContext(); }
  }
  containerRef.value?.__lightfallCleanup?.()
})
</script>

<template>
  <div ref="containerRef" class="lightfall" />
</template>

<style scoped>
.lightfall { position: absolute; inset: 0; overflow: hidden; z-index: 0; }
</style>
