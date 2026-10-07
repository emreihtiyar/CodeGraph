import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Geliştirmede /api istekleri backend'e (varsayılan :8080) yönlendirilir; CODEGRAPH_API ile değiştirilebilir.
const backend = process.env.CODEGRAPH_API ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  // Cytoscape + dagre tek başına ~500 kB; uygulama tek sayfa olduğu için bölmek kazanç sağlamıyor.
  build: { chunkSizeWarningLimit: 1000 },
  server: {
    port: 5173,
    proxy: { '/api': backend },
  },
  test: {
    environment: 'node',
  },
});
