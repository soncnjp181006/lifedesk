import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// JavaFX WebView cannot load ES modules from file/http reliably, so the build
// is a single IIFE bundle. Assets are served by ApiServer from resources/web.
export default defineConfig({
  base: './',
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/web',
    emptyOutDir: true,
    target: 'es2017',
    cssCodeSplit: false,
    assetsInlineLimit: 0,
    rollupOptions: {
      output: {
        format: 'iife',
        inlineDynamicImports: true,
        entryFileNames: 'app.js',
        assetFileNames: 'app.[ext]'
      }
    }
  }
});
