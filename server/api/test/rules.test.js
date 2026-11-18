// Roda com: cd server/api && node --test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  base32, idFromKey, ID_RE, newCode, CODE_RE, signedText, verifySignature, LIMITS,
} from '../src/rules.js';

const b64u = (bytes) => Buffer.from(bytes).toString('base64url');

async function aparelho() {
  const par = await crypto.subtle.generateKey({ name: 'Ed25519' }, true, ['sign', 'verify']);
  const publica = new Uint8Array(await crypto.subtle.exportKey('raw', par.publicKey));
  return { par, publica };
}

async function assinar(par, publica, method, path, time, body) {
  const texto = await signedText(method, path, time, body);
  const sig = new Uint8Array(await crypto.subtle.sign({ name: 'Ed25519' }, par.privateKey, texto));
  return new Headers({ 'x-sofaly-key': b64u(publica), 'x-sofaly-time': String(time), 'x-sofaly-signature': b64u(sig) });
}

test('base32 segue a RFC 4648 (minúsculo, sem preenchimento)', () => {
  assert.equal(base32(new TextEncoder().encode('foobar')), 'mzxw6ytboi');
});

test('id do aparelho tem 20 letras válidas num nome de DNS', async () => {
  const { publica } = await aparelho();
  const id = await idFromKey(publica);
  assert.match(id, ID_RE);
});

test('código de pareamento tem 8 letras sem 0, O, 1, I, L', () => {
  for (let i = 0; i < 200; i++) assert.match(newCode(), CODE_RE);
});

test('assinatura certa passa e devolve o id de quem assinou', async () => {
  const { par, publica } = await aparelho();
  const body = new TextEncoder().encode('envelope cifrado');
  const cab = await assinar(par, publica, 'POST', '/v1/envelope/x', 1000, body);
  const r = await verifySignature({ method: 'POST', path: '/v1/envelope/x', headers: cab, body, nowSec: 1000 });
  assert.equal(r.id, await idFromKey(publica));
});

test('corpo trocado, caminho trocado ou relógio fora = recusado', async () => {
  const { par, publica } = await aparelho();
  const body = new TextEncoder().encode('a');
  const cab = await assinar(par, publica, 'POST', '/v1/envelope/x', 1000, body);
  const base = { method: 'POST', path: '/v1/envelope/x', headers: cab, body, nowSec: 1000 };
  assert.ok((await verifySignature({ ...base, body: new TextEncoder().encode('b') })).error);
  assert.ok((await verifySignature({ ...base, path: '/v1/envelope/y' })).error);
  assert.ok((await verifySignature({ ...base, nowSec: 1000 + LIMITS.clockSkewSec + 1 })).error);
  assert.ok((await verifySignature({ ...base, headers: new Headers() })).error);
});
