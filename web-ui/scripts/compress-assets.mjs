import { brotliCompress, constants } from 'node:zlib'
import { promisify } from 'node:util'
import { readdir, readFile, writeFile } from 'node:fs/promises'
import { extname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const compress = promisify(brotliCompress)
const compressible = new Set(['.css', '.html', '.js', '.json', '.svg'])

async function visit(directory) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) {
      await visit(path)
      continue
    }
    if (!compressible.has(extname(entry.name))) continue
    const source = await readFile(path)
    if (source.length < 10_240) continue
    const output = await compress(source, {
      params: { [constants.BROTLI_PARAM_QUALITY]: 11 },
    })
    await writeFile(`${path}.br`, output)
  }
}

await visit(fileURLToPath(new URL('../dist', import.meta.url)))
