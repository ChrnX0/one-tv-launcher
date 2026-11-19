// API da Sofaly (Cloudflare Worker, api.sofaly.tv). Roteia cada pedido para a caixa postal (Durable Object) certa.
// Rotas (todas assinadas pelo aparelho com Ed25519, menos as marcadas "pública"):
//   POST   /v1/envelope/<id>       manda um envelope cifrado para a caixa do aparelho <id>
//   GET    /v1/inbox               WebSocket: recebe envelopes na hora (o aparelho confirma com {"ack":[ids]})
//   GET    /v1/inbox/pending       o mesmo, sem WebSocket
//   POST   /v1/inbox/ack           {"ids":[...]} apaga o que já foi recebido
//   POST   /v1/pairing             (TV) pede um código de pareamento
//   POST   /v1/pairing/<code>      (celular) usa o código; corpo = envelope de apresentação para a TV
//   PUT    /v1/dns/txt             (TV) {"value": "..."} desafio DNS-01 do certificado dela
//   DELETE /v1/dns/txt             (TV) apaga os desafios
//   GET    /v1/dns/txt/<id>        pública: as máquinas de DNS leem o desafio daqui
//   GET    /v1/health              pública
//   GET    /v1/addons/recommended  pública: lista curada de add-ons (src/addons.js)
//   GET    /v1/podcasts/search     pública: busca no Podcast Index (src/podcasts.js), ?q=&lang=&max=
//   GET    /v1/podcasts/trending   pública: em alta no idioma, ?lang=&max=
import { verifySignature, ID_RE, CODE_RE, newCode } from './rules.js';
import { recommendedBody } from './addons.js';
import { handlePodcasts } from './podcasts.js';
export { Mailbox } from './mailbox.js';
export { Pairing } from './pairing.js';

const json = (obj, status = 200) => new Response(JSON.stringify(obj), {
  status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' },
});
const fail = (r) => json({ error: r.error }, r.status ?? 400);

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const path = url.pathname;
    // Chave de desligar tudo (variável DISABLED=1 no painel da Cloudflare): o app segue funcionando em casa.
    if (env.DISABLED === '1') return json({ error: 'api disabled' }, 503);
    if (path === '/v1/health') return json({ ok: true });
    if (path === '/v1/addons/recommended' && req.method === 'GET') {
      // Igual para todos e muda raramente: a borda da Cloudflare pode guardar 1 h.
      return new Response(JSON.stringify(recommendedBody()), {
        headers: { 'content-type': 'application/json', 'cache-control': 'public, max-age=3600' },
      });
    }

    const podcasts = path.match(/^\/v1\/podcasts\/(search|trending)$/);
    if (podcasts && req.method === 'GET') return handlePodcasts(podcasts[1], url, env);

    const publicTxt = path.match(/^\/v1\/dns\/txt\/([a-z2-7]{20})$/);
    if (publicTxt && req.method === 'GET') {
      return json({ values: await mailbox(env, publicTxt[1]).readTxt() });
    }

    // Daqui para baixo, só quem assinou. O corpo é lido inteiro porque entra na assinatura (máx. 64 KiB + folga).
    const body = new Uint8Array(await req.arrayBuffer());
    if (body.byteLength > 70 * 1024) return json({ error: 'too large' }, 413);
    const who = await verifySignature({
      method: req.method, path, headers: req.headers, body, nowSec: Math.floor(Date.now() / 1000),
    });
    if (who.error) return json({ error: who.error }, 401);

    const envelope = path.match(/^\/v1\/envelope\/([a-z2-7]{20})$/);
    if (envelope && req.method === 'POST') {
      const r = await mailbox(env, envelope[1]).deliver(who.id, body.buffer);
      return r.error ? fail(r) : json(r, 201);
    }

    if (path === '/v1/inbox' && req.headers.get('upgrade') === 'websocket') {
      // Encaminha o pedido inteiro: o Durable Object é quem segura o WebSocket.
      return mailbox(env, who.id).fetch(new Request('https://mailbox/connect', req));
    }
    if (path === '/v1/inbox/pending' && req.method === 'GET') {
      return json({ envelopes: await mailbox(env, who.id).pending() });
    }
    if (path === '/v1/inbox/ack' && req.method === 'POST') {
      const { ids } = parseJson(body);
      if (!Array.isArray(ids)) return json({ error: 'ids' }, 400);
      await mailbox(env, who.id).ack(ids);
      return json({ ok: true });
    }

    if (path === '/v1/pairing' && req.method === 'POST') {
      for (let attempt = 0; attempt < 3; attempt++) {
        const code = newCode();
        const r = await pairing(env, code).open(who.id);
        if (!r.error) return json({ code }, 201);
      }
      return json({ error: 'try again' }, 503);
    }
    const use = path.match(/^\/v1\/pairing\/([A-Z2-9]{8})$/);
    if (use && req.method === 'POST' && CODE_RE.test(use[1])) {
      const r = await pairing(env, use[1]).use();
      if (r.error) return fail(r);
      const delivery = await mailbox(env, r.tv).deliver(who.id, body.buffer);
      return delivery.error ? fail(delivery) : json({ tv: r.tv });
    }

    if (path === '/v1/dns/txt' && req.method === 'PUT') {
      const r = await mailbox(env, who.id).setTxt(String(parseJson(body).value ?? ''));
      return r.error ? fail(r) : json({ ok: true });
    }
    if (path === '/v1/dns/txt' && req.method === 'DELETE') {
      await mailbox(env, who.id).deleteTxt();
      return json({ ok: true });
    }

    return json({ error: 'unknown route' }, 404);
  },
};

// O id do Durable Object vem do id do aparelho: a mesma caixa sempre, em qualquer lugar do mundo.
function mailbox(env, id) {
  if (!ID_RE.test(id)) throw new Error('invalid id');
  return env.MAILBOX.get(env.MAILBOX.idFromName(id));
}
const pairing = (env, code) => env.PAIRING.get(env.PAIRING.idFromName(code));

function parseJson(body) {
  try { return JSON.parse(new TextDecoder().decode(body)); } catch { return {}; }
}
