import { defineConfig, globalIgnores } from 'eslint/config'
import globals from 'globals'
import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import pluginVitest from '@vitest/eslint-plugin'
import pluginOxlint from 'eslint-plugin-oxlint'
import skipFormatting from 'eslint-config-prettier/flat'

export default defineConfig([
  {
    name: 'app/files-to-lint',
    files: ['**/*.{vue,js,mjs,jsx}'],
  },

  globalIgnores(['**/dist/**', '**/dist-ssr/**', '**/coverage/**', 'public/mockServiceWorker.js']),

  {
    languageOptions: {
      globals: {
        ...globals.browser,
      },
    },
  },

  {
    name: 'app/node-config-files',
    files: ['*.config.js'],
    languageOptions: { globals: { ...globals.node } },
  },

  js.configs.recommended,
  ...pluginVue.configs['flat/recommended'],

  {
    name: 'app/vue-rules',
    files: ['**/*.vue'],
    rules: {
      // ทีมใช้ <script setup> + Composition API เท่านั้น
      'vue/component-api-style': ['error', ['script-setup']],
      'vue/no-mutating-props': 'error',
      'vue/require-default-prop': 'off',
      'vue/block-order': ['error', { order: ['script', 'template', 'style'] }],
    },
  },

  {
    name: 'app/layering',
    files: ['src/shared/**/*.{js,vue}'],
    rules: {
      // shared ไม่รู้จักชั้นบน (ดู frontend-structure.md ข้อ 2)
      'no-restricted-imports': [
        'error',
        { patterns: ['@/features/*', '@/entities/*', '@/app/*', '@/mocks/*'] },
      ],
    },
  },
  {
    name: 'app/layering-entities',
    files: ['src/entities/**/*.{js,vue}'],
    rules: {
      'no-restricted-imports': ['error', { patterns: ['@/features/*', '@/app/*'] }],
    },
  },

  {
    ...pluginVitest.configs.recommended,
    files: ['tests/unit/**/*.js'],
  },

  ...pluginOxlint.buildFromOxlintConfigFile('.oxlintrc.json'),

  skipFormatting,
])
