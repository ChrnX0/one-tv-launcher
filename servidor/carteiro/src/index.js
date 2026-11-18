// Carteiro da Sofaly (Cloudflare Worker). Roteia cada pedido para a caixa postal (Durable Object) certa.
// Rotas (todas assinadas pelo aparelho com Ed25519, menos as marcadas "pública"):
//   POST   /v1/envelope/<id>       manda um envelope cifrado para a caixa do aparelho <id>
//   GET    /v1/caixa               WebSocket: recebe envelopes na hora (o aparelho confirma com {"confirmar":[ids]})
//   GET    /v1/caixa/pendentes     o mesmo, sem WebSocket
//   POST   /v1/caixa/confirmar     {"ids":[...]} apaga o que já foi recebido
//   POST   /v1/encontro            (TV) pede um código de encontro
//   POST   /v1/encontro/<codigo>   (celular) usa o código; corpo = envelope de apresentação para a TV
//   PUT    /v1/dns/txt             (TV) {"valor": "..."} desafio DNS-01 do certificado dela
//   DELETE /v1/dns/txt             (TV) apaga os desafios
//   GET    /v1/dns/txt/<id>        pública: as máquinas de DNS leem o desafio daqui
//   GET    /v1/saude               pública
import { conferirAssinatura, ID_RE, CODIGO_RE, novoCodigo } from './regras.js';
export { Caixa } from './caixa.js';
export { Encontro } from './encontro.js';

const json = (obj, status = 200) => new Response(JSON.stringify(obj), {
  status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' },
});
const falha = (r) => json({ erro: r.erro }, r.status ?? 400);

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const caminho = url.pathname;
    // Chave de desligar tudo (variável DESLIGADO=1 no painel da Cloudflare): o app segue funcionando em casa.
    if (env.DESLIGADO === '1') return json({ erro: 'carteiro desligado' }, 503);
    if (caminho === '/v1/saude') return json({ ok: true });

    const txtPublico = caminho.match(/^\/v1\/dns\/txt\/([a-z2-7]{20})$/);
    if (txtPublico && req.method === 'GET') {
      return json({ valores: await caixa(env, txtPublico[1]).lerTxt() });
    }

    // Daqui para baixo, só quem assinou. O corpo é lido inteiro porque entra na assinatura (máx. 64 KiB + folga).
    const corpo = new Uint8Array(await req.arrayBuffer());
    if (corpo.byteLength > 70 * 1024) return json({ erro: 'grande demais' }, 413);
    const quem = await conferirAssinatura({
      metodo: req.method, caminho, cabecalhos: req.headers, corpo, agoraSeg: Math.floor(Date.now() / 1000),
    });
    if (quem.erro) return json({ erro: quem.erro }, 401);

    const envelope = caminho.match(/^\/v1\/envelope\/([a-z2-7]{20})$/);
    if (envelope && req.method === 'POST') {
      const r = await caixa(env, envelope[1]).entregar(quem.id, corpo.buffer);
      return r.erro ? falha(r) : json(r, 201);
    }

    if (caminho === '/v1/caixa' && req.headers.get('upgrade') === 'websocket') {
      // Encaminha o pedido inteiro: o Durable Object é quem segura o WebSocket.
      return caixaStub(env, quem.id).fetch(new Request('https://caixa/conectar', req));
    }
    if (caminho === '/v1/caixa/pendentes' && req.method === 'GET') {
      return json({ envelopes: await caixa(env, quem.id).pendentes() });
    }
    if (caminho === '/v1/caixa/confirmar' && req.method === 'POST') {
      const { ids } = lerJson(corpo);
      if (!Array.isArray(ids)) return json({ erro: 'ids' }, 400);
      await caixa(env, quem.id).confirmar(ids);
      return json({ ok: true });
    }

    if (caminho === '/v1/encontro' && req.method === 'POST') {
      for (let tentativa = 0; tentativa < 3; tentativa++) {
        const codigo = novoCodigo();
        const r = await encontro(env, codigo).abrir(quem.id);
        if (!r.erro) return json({ codigo }, 201);
      }
      return json({ erro: 'tente de novo' }, 503);
    }
    const usar = caminho.match(/^\/v1\/encontro\/([A-Z2-9]{8})$/);
    if (usar && req.method === 'POST' && CODIGO_RE.test(usar[1])) {
      const r = await encontro(env, usar[1]).usar();
      if (r.erro) return falha(r);
      const entrega = await caixa(env, r.tv).entregar(quem.id, corpo.buffer);
      return entrega.erro ? falha(entrega) : json({ tv: r.tv });
    }

    if (caminho === '/v1/dns/txt' && req.method === 'PUT') {
      const r = await caixa(env, quem.id).definirTxt(String(lerJson(corpo).valor ?? ''));
      return r.erro ? falha(r) : json({ ok: true });
    }
    if (caminho === '/v1/dns/txt' && req.method === 'DELETE') {
      await caixa(env, quem.id).apagarTxt();
      return json({ ok: true });
    }

    return json({ erro: 'rota desconhecida' }, 404);
  },
};

// O id do Durable Object vem do id do aparelho: a mesma caixa sempre, em qualquer lugar do mundo.
function caixaStub(env, id) {
  if (!ID_RE.test(id)) throw new Error('id inválido');
  return env.CAIXA.get(env.CAIXA.idFromName(id));
}
const caixa = caixaStub; // chamadas RPC (entregar, pendentes...) usam o mesmo objeto
const encontro = (env, codigo) => env.ENCONTRO.get(env.ENCONTRO.idFromName(codigo));

function lerJson(corpo) {
  try { return JSON.parse(new TextDecoder().decode(corpo)); } catch { return {}; }
}
