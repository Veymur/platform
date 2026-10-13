import js from '@eslint/js';
import tseslint from 'typescript-eslint';

export default [
  { ignores: ['android/**', 'ios/**', 'node_modules/**', '.expo/**', '**/build/**', '**/.gradle/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended.map(config => ({ ...config, files: ['**/*.ts', '**/*.tsx'] })),
  {
    files: ['**/*.ts', '**/*.tsx'],
    languageOptions: { globals: { console: 'readonly' } },
  },
  {
    files: ['**/*.cjs'],
    languageOptions: { sourceType: 'commonjs', globals: { module: 'readonly', require: 'readonly', __dirname: 'readonly', process: 'readonly' } },
  },
];
