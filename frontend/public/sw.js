// Service worker minimo: o bastante para o app ser instalavel e abrir offline
// com o shell em cache. Estrategia network-first para a API (dinheiro nao se
// le de cache) e cache-first para os assets do shell.
// Versao do cache: subir sempre que SHELL mudar. O activate apaga todo cache
// com nome diferente deste, e e so assim que quem ja instalou recebe o shell novo.
const CACHE = 'banco-shell-v4';
const SHELL = [
  '/',
  '/index.html',
  '/icon.svg',
  '/icon-192.png',
  '/icon-512.png',
  '/manifest.webmanifest',
];

self.addEventListener('install', (event) => {
  // `cache: 'reload'` faz cada requisicao ignorar o cache HTTP do navegador.
  //
  // Sem isso, `addAll` grava no cache do service worker o que o navegador ja
  // tiver guardado - e se essa copia estiver velha, o shell nasce envenenado e
  // continua servindo a versao antiga mesmo com o servidor ja correto. Foi
  // exatamente o que aconteceu aqui: o manifest no cache tinha um icone, o
  // servidor servia quatro.
  event.waitUntil(
    caches.open(CACHE).then((c) =>
      Promise.all(
        SHELL.map((caminho) =>
          fetch(caminho, { cache: 'reload' }).then((resposta) => {
            if (!resposta.ok) throw new Error('precache falhou em ' + caminho);
            return c.put(caminho, resposta);
          })
        )
      )
    )
  );
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

  // Navegacao: rede primeiro, cache so como rede de seguranca offline.
  //
  // Cache-first aqui significaria servir o index.html antigo ate alguem lembrar
  // de subir CACHE a cada deploy - e foi exatamente assim que um deploy "nao
  // pegou": o shell em cache continuou apontando para a versao anterior, com o
  // servidor ja servindo a nova. Rede primeiro tira essa obrigacao de cima de
  // quem faz deploy.
  if (event.request.mode === 'navigate') {
    event.respondWith(
      fetch(event.request)
        .then((resposta) => {
          const copia = resposta.clone();
          caches.open(CACHE).then((c) => c.put('/index.html', copia));
          return resposta;
        })
        .catch(() => caches.match('/index.html'))
    );
    return;
  }

  event.respondWith(
    caches.match(event.request).then((cacheado) => cacheado || fetch(event.request))
  );
});
