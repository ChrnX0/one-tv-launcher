// Service worker do painel (só existe em https: ui.js registra quando a página é contexto seguro).
// 1) App instalável: o navegador pede um service worker para oferecer "Instalar app".
// 2) Tela "A TV não respondeu" (offline.html) quando o app abre e a TV está fora do ar, no lugar do erro do navegador.
// 3) Avisos da TV no celular (Web Push, 12/11/2026): só recebe o push e mostra a notificação.
// Nada do painel fica guardado além da tela de fora do ar: dados e páginas sempre vêm da TV (Cache-Control no-store).
const CACHE = 'onetv-fora-do-ar-1';
const FORA_DO_AR = ['/offline.html', '/style.css', '/icone-192.png', '/manrope-400.woff2', '/manrope-600.woff2'];

self.addEventListener('install', (ev) => {
  ev.waitUntil(caches.open(CACHE).then((c) => c.addAll(FORA_DO_AR)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (ev) => {
  ev.waitUntil(caches.keys()
    .then((nomes) => Promise.all(nomes.filter((n) => n !== CACHE).map((n) => caches.delete(n))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', (ev) => {
  const req = ev.request;
  if (req.method !== 'GET') return;
  if (req.mode === 'navigate') {
    // Página: sempre da TV; sem rede, a tela de fora do ar.
    ev.respondWith(fetch(req).catch(() => caches.match('/offline.html')));
    return;
  }
  // A tela de fora do ar precisa do CSS, da fonte e do ícone mesmo sem a TV: da rede primeiro, do cache se falhar.
  const url = new URL(req.url);
  if (url.origin === location.origin && FORA_DO_AR.includes(url.pathname)) {
    ev.respondWith(fetch(req).catch(() => caches.match(url.pathname)));
  }
});

// A TV cifra o aviso (Web Push, RFC 8291) com a chave deste navegador; o serviço de push do Google só vê bytes.
// Toque na notificação: abre o painel (ou foca a aba dele, se já estiver aberta).
self.addEventListener('push', (ev) => {
  let d = {};
  try { d = ev.data ? ev.data.json() : {}; } catch (_) { d = { titulo: 'TV', texto: ev.data ? ev.data.text() : '' }; }
  const titulo = (d.tv ? d.tv + ': ' : '') + (d.titulo || 'Aviso da TV');
  ev.waitUntil(self.registration.showNotification(titulo, {
    body: d.texto || '',
    icon: '/icone-192.png',
    badge: '/icone-192.png',
    tag: d.tipo || 'tv',          // o mesmo tipo troca a notificação anterior em vez de empilhar
    renotify: true,
    data: { url: d.tipo === 'DIGITAR' ? '/#controle' : '/' },   // ideia 57: "Digitar na TV" abre o Controle
  }));
});

self.addEventListener('notificationclick', (ev) => {
  ev.notification.close();
  const url = (ev.notification.data && ev.notification.data.url) || '/';
  ev.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((abas) => {
    for (const a of abas) if ('focus' in a) {
      if (url !== '/' && 'navigate' in a) return a.navigate(url).then((c) => c && c.focus());
      return a.focus();
    }
    return self.clients.openWindow(url);
  }));
});
