import { fileURLToPath } from 'node:url'
import { mergeConfig, defineConfig, configDefaults } from 'vitest/config'
import viteConfig from './vite.config.js'

export default defineConfig((env) =>
  mergeConfig(
    viteConfig({ ...env, mode: 'test' }),
    defineConfig({
      test: {
        environment: 'jsdom',
        include: ['tests/unit/**/*.test.js'],
        exclude: [...configDefaults.exclude, 'tests/e2e/**'],
        setupFiles: ['tests/unit/setup.js'],
        root: fileURLToPath(new URL('./', import.meta.url)),
      },
    }),
  ),
)
