import js from '@eslint/js'
import prettier from 'eslint-config-prettier'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import { defineConfig, globalIgnores } from 'eslint/config'
import globals from 'globals'
import tseslint from 'typescript-eslint'

export default defineConfig([
  globalIgnores(['dist', 'coverage']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
      prettier,
    ],
    languageOptions: {
      ecmaVersion: 2023,
      globals: globals.browser,
    },
  },
  {
    // computeCompatibility is only a demonstration for the landing page (spec 003-13): the real
    // percentage comes from the backend. Nothing else may import it.
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/features/landing/**', 'src/features/compatibility/**'],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            {
              group: [
                '**/features/compatibility',
                '**/features/compatibility/**',
                '**/compatibility/**',
              ],
              message:
                'computeCompatibility is a demo for the landing page only. The real percentage comes from the backend (RN-01).',
            },
          ],
        },
      ],
    },
  },
  {
    files: ['*.config.{js,ts}'],
    languageOptions: { globals: globals.node },
  },
])
