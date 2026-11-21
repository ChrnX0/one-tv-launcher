// Roda com: cd server/api && node --test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { authHeaders, cleanCats, cleanLang, cleanMax, cleanQuery, upstreamUrl, shapeFeeds, handlePodcasts, PI_BASE } from '../src/podcasts.js';

test('assinatura do Podcast Index = sha1(chave + segredo + hora)', async () => {
  const h = await authHeaders('KEY', 'SECRET', 1763500000);
  assert.equal(h['X-Auth-Key'], 'KEY');
  assert.equal(h['X-Auth-Date'], '1763500000');
  assert.equal(h.Authorization, createHash('sha1').update('KEYSECRET1763500000').digest('hex'));
  assert.ok(h['User-Agent'].startsWith('Sofaly/'));
});

test('limpa idioma, máximo e termo', () => {
  assert.equal(cleanLang('PT-BR'), 'pt-br');
  assert.equal(cleanLang('pt'), 'pt');
  assert.equal(cleanLang('pt_BR;drop'), '');
  assert.equal(cleanMax('5'), 5);
  assert.equal(cleanMax('500'), 30);
  assert.equal(cleanMax('x'), 30);
  assert.equal(cleanQuery('  café   da manhã '), 'café da manhã');
  assert.equal(cleanQuery('x'.repeat(300)).length, 100);
});

test('endereços no Podcast Index', () => {
  const p = (s) => new URLSearchParams(s);
  assert.equal(upstreamUrl('search', p('q=caf%C3%A9&lang=pt&max=10')), `${PI_BASE}/search/byterm?q=caf%C3%A9&max=20`);
  assert.equal(upstreamUrl('search', p('q=a')), null);   // termo curto demais
  assert.equal(upstreamUrl('trending', p('lang=pt-br&max=10')), `${PI_BASE}/podcasts/trending?max=10&lang=pt%2Cpt-br`);
  assert.equal(upstreamUrl('trending', p('lang=pt')), `${PI_BASE}/podcasts/trending?max=30&lang=pt`);
  assert.equal(upstreamUrl('trending', p('')), `${PI_BASE}/podcasts/trending?max=30`);
  assert.equal(upstreamUrl('trending', p('lang=pt&cat=News,Society %26 Culture')), `${PI_BASE}/podcasts/trending?max=30&lang=pt&cat=News%2CSociety%20%26%20Culture`);
  assert.equal(upstreamUrl('outro', p('q=abc')), null);
});

test('formato da resposta: só feed bom, sem repetir, idioma do pedido primeiro', () => {
  const data = { feeds: [
    { url: 'https://a/en', title: 'English', author: 'A', artwork: 'https://a/en.jpg', explicit: false, language: 'en' },
    { url: 'https://a/pt', title: 'Português', ownerName: 'B', image: 'https://a/pt.jpg', explicit: true, language: 'pt-BR' },
    { url: 'https://a/pt', title: 'repetido', language: 'pt' },
    { url: 'ftp://x', title: 'ruim' },
    { url: 'https://a/sem-nome', title: '' },
    { url: 'https://a/morto', title: 'Morto', dead: 1 },
  ] };
  const r = shapeFeeds(data, 'pt', 30);
  assert.deepEqual(r.feeds.map((f) => f.title), ['Português', 'English']);
  assert.deepEqual(r.feeds[0], { url: 'https://a/pt', title: 'Português', author: 'B', image: 'https://a/pt.jpg', explicit: true, language: 'pt-br', categories: [] });
  assert.equal(shapeFeeds(data, '', 1).feeds.length, 1);
  assert.deepEqual(shapeFeeds(null).feeds, []);
});

test('sem os secrets: 503 e não chama o Podcast Index', async () => {
  let chamou = false;
  const r = await handlePodcasts('trending', new URL('https://api/v1/podcasts/trending?lang=pt'), {}, { fetchFn: () => { chamou = true; }, cache: null });
  assert.equal(r.status, 503);
  assert.equal(chamou, false);
});

test('com os secrets: assina, devolve o formato do app e guarda no cache', async () => {
  const env = { PODCASTINDEX_API_KEY: 'K', PODCASTINDEX_API_SECRET: 'S' };
  const store = new Map();
  const cache = { match: async (k) => store.get(k.url)?.clone(), put: async (k, v) => { store.set(k.url, v); } };
  let pedidos = 0;
  const fetchFn = async (u, init) => {
    pedidos++;
    assert.ok(u.startsWith(`${PI_BASE}/search/byterm?q=nerd`));
    assert.equal(init.headers['X-Auth-Key'], 'K');
    assert.equal(init.headers.Authorization, createHash('sha1').update(`KS${init.headers['X-Auth-Date']}`).digest('hex'));
    return new Response(JSON.stringify({ feeds: [{ url: 'https://n/feed', title: 'NerdCast', author: 'Jovem Nerd', image: 'https://n/c.jpg', language: 'pt' }] }));
  };
  const url = new URL('https://api/v1/podcasts/search?q=nerd&lang=pt');
  const r1 = await handlePodcasts('search', url, env, { fetchFn, cache, nowSec: 1 });
  assert.equal(r1.status, 200);
  assert.match(r1.headers.get('cache-control'), /max-age=21600/);
  assert.equal((await r1.json()).feeds[0].title, 'NerdCast');
  const r2 = await handlePodcasts('search', new URL('https://api/v1/podcasts/search?q=nerd&lang=PT&extra=1'), env, { fetchFn, cache, nowSec: 2 });
  assert.equal((await r2.json()).feeds.length, 1);
  assert.equal(pedidos, 1, 'a segunda pergunta igual sai do cache');
});

test('chave recusada ou fora do ar: 502, sem guardar no cache', async () => {
  const env = { PODCASTINDEX_API_KEY: 'K', PODCASTINDEX_API_SECRET: 'S' };
  const store = new Map();
  const cache = { match: async (k) => store.get(k.url), put: async (k, v) => { store.set(k.url, v); } };
  const url = new URL('https://api/v1/podcasts/trending');
  assert.equal((await handlePodcasts('trending', url, env, { fetchFn: async () => new Response('', { status: 401 }), cache })).status, 502);
  assert.equal((await handlePodcasts('trending', url, env, { fetchFn: async () => { throw new Error('rede'); }, cache })).status, 502);
  assert.equal(store.size, 0);
  assert.equal((await handlePodcasts('search', new URL('https://api/v1/podcasts/search?q=a'), env, { cache })).status, 400);
});

test('limite por IP e global devolvem 429 sem chamar o Podcast Index', async () => {
  const env = { PODCASTINDEX_API_KEY: 'k', PODCASTINDEX_API_SECRET: 's' };
  let chamou = false;
  const fetchFn = async () => { chamou = true; return new Response('{"feeds":[]}'); };
  const nao = { limit: async () => ({ success: false }) };
  const url = new URL('https://api/v1/podcasts/search?q=nerd');
  assert.equal((await handlePodcasts('search', url, { ...env, PODCASTS_IP_LIMITER: nao }, { fetchFn, cache: null, ip: '1.2.3.4' })).status, 429);
  assert.equal((await handlePodcasts('search', url, { ...env, PODCASTS_UPSTREAM_LIMITER: nao }, { fetchFn, cache: null })).status, 429);
  assert.equal(chamou, false);
  // Global com erro: recusa (protege a chave).
  const quebrado = { limit: async () => { throw new Error('x'); } };
  assert.equal((await handlePodcasts('search', url, { ...env, PODCASTS_UPSTREAM_LIMITER: quebrado }, { fetchFn, cache: null })).status, 429);
});

test('feed sem o campo explicit volta explicit null (desconhecido)', () => {
  const r = shapeFeeds({ feeds: [{ url: 'https://a/x', title: 'X' }] });
  assert.equal(r.feeds[0].explicit, null);
});

test('assuntos: limpa o cat e devolve as categorias de cada feed', () => {
  assert.equal(cleanCats(' News , Comedy,x,<script>,Society   & Culture,A,B,C,D'), 'News,Comedy,Society & Culture');
  assert.equal(cleanCats(null), '');
  const r = shapeFeeds({ feeds: [{ url: 'https://a/x', title: 'X', categories: { 55: 'News', 59: 'Politics' } }] });
  assert.deepEqual(r.feeds[0].categories, ['News', 'Politics']);
});
