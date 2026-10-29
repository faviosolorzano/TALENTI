import { readFileSync } from 'node:fs'
import react from '@vitejs/plugin-react'
import type { Plugin } from 'vite'
import { defineConfig } from 'vitest/config'

/**
 * Exam proctoring (spec 014, amendment 1): the MediaPipe WASM runtime is served from our own origin under
 * /mediapipe/, copied from the exact npm package version (no CDN at run time). The models live in public/models/.
 */
const MEDIAPIPE_WASM = [
  'vision_wasm_internal.js',
  'vision_wasm_internal.wasm',
  'vision_wasm_nosimd_internal.js',
  'vision_wasm_nosimd_internal.wasm',
]
const wasmFile = (name: string) =>
  new URL('./node_modules/@mediapipe/tasks-vision/wasm/' + name, import.meta.url)
function mediapipeWasm(): Plugin {
  return {
    name: 'talenti-mediapipe-wasm',
    configureServer(server) {
      server.middlewares.use('/mediapipe/', (req, res, next) => {
        const name = (req.url ?? '').split('?')[0]!.replace(/^\//, '')
        if (!MEDIAPIPE_WASM.includes(name)) return next()
        res.setHeader(
          'Content-Type',
          name.endsWith('.wasm') ? 'application/wasm' : 'text/javascript',
        )
        res.end(readFileSync(wasmFile(name)))
      })
    },
    generateBundle() {
      for (const name of MEDIAPIPE_WASM)
        this.emitFile({
          type: 'asset',
          fileName: 'mediapipe/' + name,
          source: readFileSync(wasmFile(name)),
        })
    },
  }
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), mediapipeWasm()],
  build: {
    // The CSP (default-src 'self') does not allow fonts as data: URLs. Never inline font
    // files, however small; they must be real files served from the same origin.
    assetsInlineLimit: (file) => (/\.(woff2?|ttf|otf|eot)$/i.test(file) ? false : undefined),
  },
  server: {
    port: 5173,
    // In development the API is served by the backend container/process.
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    // The screens with forms type whole sentences with userEvent; with every test file running
    // at the same time that can pass the 5 s default on a slow machine.
    testTimeout: 20000,
  },
})
