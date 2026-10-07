# CodeGraph arayüzü

React + Vite + TypeScript, graf için Cytoscape (+dagre), kod için Prism.

```bash
npm install
npm run dev        # http://localhost:5173 — /api istekleri http://localhost:8080'e gider
CODEGRAPH_API=http://sunucu:8080 npm run dev   # başka bir backend için
npm run typecheck
npm test           # Vitest, saf modüller
npm run build      # çıktı: dist/
```

Görünümler: **Sistem** (servis grafı), **Akış izi** (ileri/geri iz), **Çağrı ağacı**, **Analiz** (başlatma ve rapor).
Her görünümün adresi paylaşılabilir (`#/trace?from=<düğüm id>&dir=backward` gibi). `/` tuşu aramayı açar.
