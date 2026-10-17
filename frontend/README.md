# CodeGraph arayüzü

React + Vite + TypeScript, graf için Cytoscape (+dagre), kod için Prism.

```bash
npm install
npm run dev        # http://localhost:5173 — /api istekleri http://localhost:8080'e gider
CODEGRAPH_API=http://sunucu:8080 npm run dev   # başka bir backend için
npm run typecheck
npm test           # Vitest, saf modüller
npm run build      # çıktı: dist/
npm run preview    # http://localhost:4173 — derlenmiş sürüm, /api yine 8080'e gider
```

Günlük kullanım için `npm run build && npm run preview` önerilir. `npm run dev` geliştirme modudur: React StrictMode
her bileşeni iki kez kurar (graf ve yerleşimi dahil) ve kod derlenmemiş olarak yüklenir; bu yüzden daha ağırdır.

Görünümler: **Sistem** (servis grafı), **Akış izi** (ileri/geri iz), **Çağrı ağacı**, **Analiz** (başlatma ve rapor).
Her görünümün adresi paylaşılabilir (`#/trace?from=<düğüm id>&dir=backward` gibi). `/` tuşu aramayı açar.
