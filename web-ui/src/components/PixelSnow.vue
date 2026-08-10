<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import {
  Color, Mesh, OrthographicCamera, PlaneGeometry, Scene,
  ShaderMaterial, Vector2, Vector3, WebGLRenderer
} from 'three'

const props = defineProps({
  color: { type: String, default: '#818cf8' },
  flakeSize: { type: Number, default: 0.014 },
  minFlakeSize: { type: Number, default: 1.25 },
  pixelResolution: { type: Number, default: 260 },
  speed: { type: Number, default: 1.2 },
  depthFade: { type: Number, default: 8 },
  farPlane: { type: Number, default: 30 },
  brightness: { type: Number, default: 0.9 },
  gamma: { type: Number, default: 0.4545 },
  density: { type: Number, default: 0.25 },
  direction: { type: Number, default: 125 },
  variant: { type: String, default: 'square' },
})

const containerRef = ref(null)
let renderer = null
let material = null
let scene = null
let animId = 0
let resizeTimer = null

const variantVal = { square: 0, round: 1, snowflake: 2 }[props.variant] || 0

const vertexShader = `void main() { gl_Position = vec4(position, 1.0); }`

const fragmentShader = `
precision mediump float;
uniform float uTime, uFlakeSize, uMinFlakeSize, uPixelResolution, uSpeed, uDepthFade, uFarPlane, uBrightness, uGamma, uDensity, uVariant, uDirection;
uniform vec2 uResolution;
uniform vec3 uColor;
#define PI 3.14159265
#define PI_OVER_6 0.5235988
#define PI_OVER_3 1.0471976
#define M1 1597334677U
#define M2 3812015801U
#define M3 3299493293U
#define F0 2.3283064e-10
#define hash(n) (n * (n ^ (n >> 15)))
#define coord3(p) (uvec3(p).x * M1 ^ uvec3(p).y * M2 ^ uvec3(p).z * M3)
const vec3 camK = vec3(0.57735027,0.57735027,0.57735027);
const vec3 camI = vec3(0.70710678,0.0,-0.70710678);
const vec3 camJ = vec3(-0.40824829,0.81649658,-0.40824829);
const vec2 b1d = vec2(0.574,0.819);

vec3 hash3(uint n) { return vec3(hash(n) * uvec3(1U,511U,262143U)) * F0; }

float snowflakeDist(vec2 p) {
  float r = length(p), a = atan(p.y, p.x);
  a = abs(mod(a + PI_OVER_6, PI_OVER_3) - PI_OVER_6);
  vec2 q = r * vec2(cos(a), sin(a));
  float dMain = max(abs(q.y), max(-q.x, q.x - 1.0));
  float b1t = clamp(dot(q - vec2(0.4,0.0), b1d), 0.0, 0.4);
  float dB1 = length(q - vec2(0.4,0.0) - b1t * b1d);
  float b2t = clamp(dot(q - vec2(0.7,0.0), b1d), 0.0, 0.25);
  float dB2 = length(q - vec2(0.7,0.0) - b2t * b1d);
  return min(dMain, min(dB1, dB2)) * 10.0;
}

void main() {
  float invPixelRes = 1.0 / uPixelResolution;
  float pixelSize = max(1.0, floor(uResolution.x * invPixelRes + 0.5));
  float invPixelSize = 1.0 / pixelSize;
  vec2 fragCoord = floor(gl_FragCoord.xy * invPixelSize);
  vec2 res = uResolution * invPixelSize;
  float invResX = 1.0 / res.x;
  vec3 ray = normalize((vec3(fragCoord - res * 0.5, 0) * invResX) + vec3(0,0,1));
  ray = ray.x * camI + ray.y * camJ + ray.z * camK;
  float timeSpeed = uTime * uSpeed;
  float windX = cos(uDirection) * 0.4, windY = sin(uDirection) * 0.4;
  vec3 camPos = (windX * camI + windY * camJ + 0.1 * camK) * timeSpeed;
  vec3 pos = camPos;
  vec3 absRay = max(abs(ray), vec3(0.001));
  vec3 strides = 1.0 / absRay;
  vec3 raySign = step(ray, vec3(0.0));
  vec3 phase = fract(pos) * strides;
  phase = mix(strides - phase, phase, raySign);
  float rayDotCamK = dot(ray, camK);
  float invRayDotCamK = 1.0 / rayDotCamK;
  float halfInvResX = 0.5 * invResX;
  vec3 timeAnim = timeSpeed * 0.1 * vec3(7,8,5);
  float t = 0.0;
  float invDepthFade = 1.0 / uDepthFade;

  for (int i = 0; i < 100; i++) {
    if (t >= uFarPlane) break;
    vec3 fpos = floor(pos);
    uint cellCoord = coord3(fpos);
    float cellHash = hash3(cellCoord).x;
    if (cellHash < uDensity) {
      vec3 h = hash3(cellCoord);
      vec3 sinArg1 = fpos.yzx * 0.073, sinArg2 = fpos.zxy * 0.27;
      vec3 flakePos = 0.5 - 0.5 * cos(4.0 * sin(sinArg1) + 4.0 * sin(sinArg2) + 2.0 * h + timeAnim);
      flakePos = flakePos * 0.8 + 0.1 + fpos;
      float toIntersection = dot(flakePos - pos, camK) * invRayDotCamK;
      if (toIntersection > 0.0) {
        vec3 testPos = pos + ray * toIntersection - flakePos;
        float testX = dot(testPos, camI), testY = dot(testPos, camJ);
        vec2 testUV = abs(vec2(testX, testY));
        float depth = dot(flakePos - camPos, camK);
        float flakeSize = max(uFlakeSize, uMinFlakeSize * depth * halfInvResX);
        float dist;
        if (uVariant < 0.5) dist = max(testUV.x, testUV.y);
        else if (uVariant < 1.5) dist = length(testUV);
        else {
          float invFlakeSize = 1.0 / flakeSize;
          dist = snowflakeDist(vec2(testX, testY) * invFlakeSize) * flakeSize;
        }
        if (dist < flakeSize) {
          float ratio = uFlakeSize / flakeSize;
          float intensity = exp2(-(t + toIntersection) * invDepthFade) * min(1.0, ratio * ratio) * uBrightness;
          gl_FragColor = vec4(uColor * pow(vec3(intensity), vec3(uGamma)), 1.0);
          return;
        }
      }
    }
    float nextStep = min(min(phase.x, phase.y), phase.z);
    vec3 sel = step(phase, vec3(nextStep));
    phase = phase - nextStep + strides * sel;
    t += nextStep;
    pos = mix(pos + ray * nextStep, floor(pos + ray * nextStep + 0.5), sel);
  }
  gl_FragColor = vec4(0.0);
}`

const init = () => {
  const el = containerRef.value
  if (!el) return
  const w = el.offsetWidth, h = el.offsetHeight

  renderer = new WebGLRenderer({ antialias: false, alpha: true, powerPreference: 'high-performance', stencil: false, depth: false })
  renderer.setPixelRatio(Math.min(devicePixelRatio, 2))
  renderer.setSize(w, h)
  renderer.setClearColor(0, 0)
  el.appendChild(renderer.domElement)

  const cam = new OrthographicCamera(-1, 1, 1, -1, 0, 1)
  scene = new Scene()

  const color3 = new Color(props.color)
  material = new ShaderMaterial({
    vertexShader, fragmentShader,
    uniforms: {
      uTime: { value: 0 },
      uResolution: { value: new Vector2(w, h) },
      uFlakeSize: { value: props.flakeSize },
      uMinFlakeSize: { value: props.minFlakeSize },
      uPixelResolution: { value: props.pixelResolution },
      uSpeed: { value: props.speed },
      uDepthFade: { value: props.depthFade },
      uFarPlane: { value: props.farPlane },
      uColor: { value: new Vector3(color3.r, color3.g, color3.b) },
      uBrightness: { value: props.brightness },
      uGamma: { value: props.gamma },
      uDensity: { value: props.density },
      uVariant: { value: variantVal },
      uDirection: { value: props.direction * Math.PI / 180 },
    },
    transparent: true,
  })

  scene.add(new Mesh(new PlaneGeometry(2, 2), material))
  const start = performance.now()

  const animate = () => {
    animId = requestAnimationFrame(animate)
    material.uniforms.uTime.value = (performance.now() - start) * 0.001
    renderer.render(scene, cam)
  }
  animate()
}

const cleanup = () => {
  cancelAnimationFrame(animId)
  if (resizeTimer) clearTimeout(resizeTimer)
  if (renderer) {
    renderer.dispose()
    renderer.forceContextLoss()
    if (containerRef.value?.contains(renderer.domElement)) {
      containerRef.value.removeChild(renderer.domElement)
    }
  }
  if (material) material.dispose()
}

const updateUniforms = () => {
  if (!material) return
  const c = new Color(props.color)
  material.uniforms.uColor.value.set(c.r, c.g, c.b)
  material.uniforms.uFlakeSize.value = props.flakeSize
  material.uniforms.uPixelResolution.value = props.pixelResolution
  material.uniforms.uSpeed.value = props.speed
  material.uniforms.uDensity.value = props.density
  material.uniforms.uVariant.value = variantVal
  material.uniforms.uDirection.value = props.direction * Math.PI / 180
  material.uniforms.uBrightness.value = props.brightness
}

let observer = null
onMounted(() => {
  init()
  window.addEventListener('resize', () => {
    if (resizeTimer) clearTimeout(resizeTimer)
    resizeTimer = setTimeout(() => {
      if (!renderer || !containerRef.value) return
      const w = containerRef.value.offsetWidth, h = containerRef.value.offsetHeight
      renderer.setSize(w, h)
      material.uniforms.uResolution.value.set(w, h)
    }, 100)
  })
})

onUnmounted(() => {
  cleanup()
  window.removeEventListener('resize', () => {})
  observer?.disconnect()
})
</script>

<template>
  <div ref="containerRef" class="pixel-snow">
    <slot />
  </div>
</template>

<style scoped>
.pixel-snow {
  position: absolute;
  inset: 0;
  overflow: hidden;
}
.pixel-snow :deep(canvas) {
  display: block;
  width: 100%;
  height: 100%;
}
</style>
