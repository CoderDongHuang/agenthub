import { copyFile, mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'

const projectRoot = resolve(import.meta.dirname, '..', '..')
const publicDocs = resolve(projectRoot, 'web-ui', 'public', 'docs')
const sharedDocs = [
  'developer-guide.md',
  'release-notes-v0.1.0.md',
  '实施计划.md',
  '架构设计.md',
  '项目现状与改进路线.md',
  '全链路验收与配置清单.md',
]

await mkdir(publicDocs, { recursive: true })
await Promise.all(sharedDocs.map(file => copyFile(resolve(projectRoot, 'docs', file), resolve(publicDocs, file))))
