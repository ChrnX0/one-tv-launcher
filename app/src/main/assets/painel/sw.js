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

// Ideia 38 (14/11/2026): o toque abre o painel na aba que resolve o aviso, não na página inicial.
// Tipos de Avisos.kt -> aba do painel. Tipo desconhecido (TV mais nova que este painel) abre o início.
const ABA_DO_AVISO = {
  LEMBRETE: 'controle', AJUDA: 'controle', PRESENCA: 'controle', TV_MADRUGADA: 'controle', ALARME_PERDIDO: 'controle',
  CHUVA: 'controle', ERRO_NOVO: 'saude', DESEMPENHO: 'saude', ARMAZENAMENTO: 'saude', INTERNET_VOLTOU: 'rede',
  VERSAO_NOVA: 'inicio',
};

// A TV cifra o aviso (Web Push, RFC 8291) com a chave deste navegador; o serviço de push do Google só vê bytes.
// Toque na notificação: abre o painel na aba certa (ou foca a aba do navegador, se já estiver aberta, e troca a aba).
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
    data: { aba: ABA_DO_AVISO[d.tipo] || 'inicio' },
  }));
});

self.addEventListener('notificationclick', (ev) => {
  ev.notification.close();
  const aba = (ev.notification.data && ev.notification.data.aba) || 'inicio';
  ev.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((abas) => {
    for (const a of abas) if ('focus' in a) { a.postMessage({ aba }); return a.focus(); }   // app.js troca a aba
    return self.clients.openWindow('/#' + aba);
  }));
});
