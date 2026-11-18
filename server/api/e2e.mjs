// Teste de ponta a ponta contra o API rodando (local ou de verdade). Não roda no `node --test`.
//   npx wrangler dev --local --port 8787 &   (dentro de server/api; fica fora de test/ porque precisa do API de pé)
//   node e2e.mjs http://127.0.0.1:8787
import assert from 'node:assert/strict';
import { signedText, idFromKey } from './src/rules.js';

const BASE = process.argv[2] ?? 'http://127.0.0.1:8787';

async function novoAparelho() {
  const par = await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify']);
  const publica = new Uint8Array(await crypto.subtle.exportKey('raw', par.publicKey));
  const id = await idFromKey(publica);
  async function cabecalhos(metodo, caminho, corpo) {
    const hora = Math.floor(Date.now() / 1000);
    const sig = await crypto.subtle.sign({ name: 'Ed25519' }, par.privateKey, await signedText(metodo, caminho, hora, corpo));
    return {
      'x-sofaly-key': Buffer.from(publica).toString('base64url'),
      'x-sofaly-time': String(hora),
      'x-sofaly-signature': Buffer.from(new Uint8Array(sig)).toString('base64url'),
    };
  }
  async function pedir(metodo, caminho, corpoTexto = '') {
    const corpo = new TextEncoder().encode(corpoTexto);
    const r = await fetch(BASE + caminho, {
      method: metodo, headers: await cabecalhos(metodo, caminho, corpo), body: metodo === 'GET' ? undefined : corpo,
    });
    return { status: r.status, corpo: await r.json() };
  }
  return { id, pedir, cabecalhos };
}

const tv = await novoAparelho();
const celular = await novoAparelho();

// 1. Encontro: a TV pede o código, o celular usa com um envelope de apresentação.
const { corpo: { code: codigo } } = await tv.pedir('POST', '/v1/pairing');
assert.match(codigo, /^[A-Z2-9]{8}$/);
const uso = await celular.pedir('POST', `/v1/pairing/${codigo}`, 'ola-cifrado');
assert.equal(uso.corpo.tv, tv.id);
assert.equal((await celular.pedir('POST', `/v1/pairing/${codigo}`, 'x')).status, 404, 'código é de uso único');

// 2. Caixa postal: a apresentação está esperando a TV.
let { corpo: { envelopes } } = await tv.pedir('GET', '/v1/inbox/pending');
assert.equal(envelopes.length, 1);
assert.equal(envelopes[0].from, celular.id);
assert.equal(Buffer.from(envelopes[0].data, 'base64').toString(), 'ola-cifrado');
await tv.pedir('POST', '/v1/inbox/ack', JSON.stringify({ ids: [envelopes[0].id] }));
({ corpo: { envelopes } } = await tv.pedir('GET', '/v1/inbox/pending'));
assert.equal(envelopes.length, 0, 'confirmado = apagado');

// 3. WebSocket: com a TV conectada, o envelope chega na hora.
const ws = new WebSocket(BASE.replace('http', 'ws') + '/v1/inbox', {
  headers: await tv.cabecalhos('GET', '/v1/inbox', new Uint8Array()),
});
await new Promise((ok, erro) => { ws.onopen = ok; ws.onerror = erro; });
const chegou = new Promise((ok) => { ws.onmessage = (m) => ok(JSON.parse(m.data)); });
await celular.pedir('POST', `/v1/envelope/${tv.id}`, 'pausar-em-00:42:10');
const msg = await chegou;
assert.equal(Buffer.from(msg.data, 'base64').toString(), 'pausar-em-00:42:10');
ws.send(JSON.stringify({ ack: [msg.id] }));
ws.close();

// 4. Desafio do certificado: a TV grava, qualquer um lê (é o que o DNS público mostraria).
const valor = 'a'.repeat(43);
assert.equal((await tv.pedir('PUT', '/v1/dns/txt', JSON.stringify({ value: valor }))).status, 200);
const txt = await (await fetch(`${BASE}/v1/dns/txt/${tv.id}`)).json();
assert.deepEqual(txt.values, [valor]);

// 5. Sem assinatura = recusado.
assert.equal((await fetch(`${BASE}/v1/envelope/${tv.id}`, { method: 'POST', body: 'x' })).status, 401);

console.log('e2e: all good');
