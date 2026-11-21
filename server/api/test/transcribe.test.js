import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cleanLang, looksBase64, cleanSegments, parseSummary, handleTranscribe, MAX_B64 } from '../src/transcribe.js';

test('idioma e base64', () => {
  assert.equal(cleanLang('pt-BR'), 'pt');
  assert.equal(cleanLang('x1'), '');
  assert.equal(looksBase64('SUQzBAAA'), true);
  assert.equal(looksBase64('SUQz BAA'), false);
  assert.equal(looksBase64('abc'), false);
});

test('trechos do whisper', () => {
  const r = cleanSegments({ segments: [{ start: 0, end: 2.5, text: ' Olá ' }, { start: 3, end: 4, text: '' }, { start: 'x', end: 1, text: 'a' }] });
  assert.deepEqual(r, [{ start: 0, end: 2.5, text: 'Olá' }]);
  assert.deepEqual(cleanSegments(null), []);
});

test('resumo e propaganda', () => {
  const r = parseSummary({ response: 'Claro! {"summary":"  Fala de  vacinas. ","ads":[[10,40],[50,20],[0,900],["a",1]]}' });
  assert.equal(r.summary, 'Fala de vacinas.');
  assert.deepEqual(r.ads, [[10, 40]]);
  assert.deepEqual(parseSummary('sem json'), { summary: '', ads: [] });
});

test('rota: sem IA, grande demais, base64 ruim, cota', async () => {
  const url = new URL('https://api.sofaly.tv/v1/transcribe?lang=pt');
  const req = (body) => new Request(url, { method: 'POST', body });
  assert.equal((await handleTranscribe('transcribe', req('SUQz'), url, {}, { ip: '1' })).status, 503);
  const ok = { AI: { run: async () => ({ segments: [{ start: 1, end: 2, text: 'oi' }] }) } };
  assert.equal((await handleTranscribe('transcribe', req('a'.repeat(MAX_B64 + 4)), url, ok, { ip: '1' })).status, 413);
  assert.equal((await handleTranscribe('transcribe', req('não é base64'), url, ok, { ip: '1' })).status, 400);
  const r = await handleTranscribe('transcribe', req('SUQzBAAA'), url, ok, { ip: '1' });
  assert.deepEqual(await r.json(), { segments: [{ start: 1, end: 2, text: 'oi' }] });
  const cota = { AI: { run: async () => { throw new Error('daily neuron quota exceeded'); } } };
  assert.equal((await handleTranscribe('transcribe', req('SUQzBAAA'), url, cota, { ip: '1' })).status, 429);
  const limitado = { ...ok, TRANSCRIBE_IP_LIMITER: { limit: async () => ({ success: false }) } };
  assert.equal((await handleTranscribe('transcribe', req('SUQzBAAA'), url, limitado, { ip: '1' })).status, 429);
});
