import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Quinoa Vite defaults: empty base so assets resolve when served from Quarkus.
// https://docs.quarkiverse.io/quarkus-quinoa/dev/web-frameworks.html
export default defineConfig({
  base: '',
  plugins: [react()],
})
