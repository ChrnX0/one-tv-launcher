// Roda com: cd servidor/carteiro && node --test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  base32, idDaChave, ID_RE, novoCodigo, CODIGO_RE, textoAssinado, conferirAssinatura, LIMITES,
} from '../src/regras.js';

const b64u = (bytes) => Buffer.from(bytes).toString('base64url');

async function aparelho() {
  const par = await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify']);
  const publica = new Uint8Array(await crypto.subtle.exportKey('raw', par.publicKey));
  return { par, publica };
}

async function assinar(par, publica, metodo, caminho, hora, corpo) {
  const texto = await textoAssinado(metodo, caminho, hora, corpo);
  const sig = new Uint8Array(await crypto.subtle.sign({ name: 'Ed25519' }, par.privateKey, texto));
  return new Headers({ 'x-sofaly-chave': b64u(publica), 'x-sofaly-hora': String(hora), 'x-sofaly-assinatura': b64u(sig) });
}

test('base32 segue a RFC 4648 (minúsculo, sem preenchimento)', () => {
  assert.equal(base32(new TextEncoder().encode('foobar')), 'mzxw6ytboi');
});

test('id do aparelho tem 20 letras válidas num nome de DNS', async () => {
  const { publica } = await aparelho();
  const id = await idDaChave(publica);
  assert.match(id, ID_RE);
});

test('código de encontro tem 8 letras sem 0, O, 1, I, L', () => {
  for (let i = 0; i < 200; i++) assert.match(novoCodigo(), CODIGO_RE);
});

test('assinatura certa passa e devolve o id de quem assinou', async () => {
  const { par, publica } = await aparelho();
  const corpo = new TextEncoder().encode('envelope cifrado');
  const cab = await assinar(par, publica, 'POST', '/v1/envelope/x', 1000, corpo);
  const r = await conferirAssinatura({ metodo: 'POST', caminho: '/v1/envelope/x', cabecalhos: cab, corpo, agoraSeg: 1000 });
  assert.equal(r.id, await idDaChave(publica));
});

test('corpo trocado, caminho trocado ou relógio fora = recusado', async () => {
  const { par, publica } = await aparelho();
  const corpo = new TextEncoder().encode('a');
  const cab = await assinar(par, publica, 'POST', '/v1/envelope/x', 1000, corpo);
  const base = { metodo: 'POST', caminho: '/v1/envelope/x', cabecalhos: cab, corpo, agoraSeg: 1000 };
  assert.ok((await conferirAssinatura({ ...base, corpo: new TextEncoder().encode('b') })).erro);
  assert.ok((await conferirAssinatura({ ...base, caminho: '/v1/envelope/y' })).erro);
  assert.ok((await conferirAssinatura({ ...base, agoraSeg: 1000 + LIMITES.folgaRelogioSeg + 1 })).erro);
  assert.ok((await conferirAssinatura({ ...base, cabecalhos: new Headers() })).erro);
});
