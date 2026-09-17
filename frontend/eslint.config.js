import pluginVue from 'eslint-plugin-vue'
import tsParser from '@typescript-eslint/parser'
import tsPlugin from '@typescript-eslint/eslint-plugin'
import prettier from '@vue/eslint-config-prettier'
import vueParser from 'vue-eslint-parser'

export default [
  {
    ignores: ['node_modules/', 'dist/', '.vite/']
  },
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.ts'],
    languageOptions: {
      parser: tsParser,
      parserOptions: {
        ecmaVersion: 'latest',
        sourceType: 'module'
      }
    },
    plugins: {
      '@typescript-eslint': tsPlugin
    },
    rules: {
      ...tsPlugin.configs.recommended.rules,
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-unused-vars': [
        'warn',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' }
      ]
    }
  },
  {
    files: ['**/*.vue'],
    languageOptions: {
      parser: vueParser,
      parserOptions: {
        ecmaVersion: 'latest',
        sourceType: 'module',
        parser: '@typescript-eslint/parser'
      }
    },
    plugins: {
      '@typescript-eslint': tsPlugin
    },
    rules: {
      ...tsPlugin.configs.recommended.rules,
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-unused-vars': [
        'warn',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' }
      ]
    }
  },
  prettier,
  {
    rules: {
      'vue/multi-word-component-names': 'off',
      'vue/no-v-model-argument': 'off',
      'vue/require-default-prop': 'off',
      'vue/max-attributes-per-line': ['warn', { singleline: 3, multiline: 1 }],
      'vue/no-mutating-props': 'warn',
      'vue/no-v-for-template-key': 'off',
      'prettier/prettier': 'off',
      'no-console': ['warn', { allow: ['warn', 'error'] }]
    }
  }
]
