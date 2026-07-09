import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// The launcher renderer (React + Tailwind). Built to ../renderer-dist and loaded
// by Electron via file://, so base must be relative and assets self-contained.
export default defineConfig({
  root: 'renderer',
  base: './',
  plugins: [react(), tailwindcss()],
  build: {
    outDir: '../renderer-dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 2000,
  },
});
