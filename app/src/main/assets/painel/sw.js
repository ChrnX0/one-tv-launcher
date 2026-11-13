// Avisos da TV no celular (12/11/2026): o service worker só recebe o push e mostra a notificação.
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
    data: { url: '/' },
  }));
});

self.addEventListener('notificationclick', (ev) => {
  ev.notification.close();
  ev.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((abas) => {
    for (const a of abas) if ('focus' in a) return a.focus();
    return self.clients.openWindow('/');
  }));
});
