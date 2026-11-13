// Service worker minimo: o bastante para o app ser instalavel e abrir offline
// com o shell em cache. Estrategia network-first para a API (dinheiro nao se
// le de cache) e cache-first para os assets do shell.
const CACHE = 'banco-shell-v1';
const SHELL = ['/', '/index.html', '/icon.svg', '/manifest.webmanifest'];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((chaves) =>
      Promise.all(chaves.filter((c) => c !== CACHE).map((c) => caches.delete(c)))
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  // Saldo e extrato NUNCA saem do cache: um saldo velho na tela e pior do que
  // uma tela de erro.
  if (url.pathname.startsWith('/api')) return;

  event.respondWith(
    caches.match(event.request).then((cacheado) => cacheado || fetch(event.request))
  );
});
