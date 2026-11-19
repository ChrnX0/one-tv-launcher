// Roda com: cd server/api && node --test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { snap, cleanDays, upstreamUrl, shape, handleWeather, WA_BASE } from '../src/weather.js';

class FakeCache {
  constructor() { this.m = new Map(); }
  async match(req) { return this.m.get(req.url)?.clone(); }
  async put(req, res) { this.m.set(req.url, res); }
}

const sample = {
  location: { name: 'X', tz_id: 'America/Sao_Paulo' },
  current: { temp_c: 25, condition: { code: 1000 } },
  forecast: { forecastday: [{ date: '2026-11-19', day: {}, hour: [] }] },
  extra: 'não vai para a TV',
};

test('grade de 0,1° e coordenada inválida', () => {
  assert.equal(snap('-23.5505', 90), '-23.6');
  assert.equal(snap('-46.6333', 180), '-46.6');
  assert.equal(snap('0.04', 90), '0.0');
  assert.equal(snap('91', 90), null);
  assert.equal(snap('abc', 90), null);
  assert.equal(snap(null, 90), null);
});

test('dias limitados ao plano contratado', () => {
  assert.equal(cleanDays('7', undefined), 3);    // Free: 3 dias
  assert.equal(cleanDays('7', '7'), 7);
  assert.equal(cleanDays('2', '7'), 2);
  assert.equal(cleanDays('x', '7'), 7);
  assert.equal(cleanDays('30', '99'), 14);
});

test('endereço na WeatherAPI pede qualidade do ar e nada de alertas', () => {
  assert.equal(upstreamUrl('K', '-23.6', '-46.6', 3), `${WA_BASE}?key=K&q=-23.6,-46.6&days=3&aqi=yes&alerts=no`);
});

test('resposta só com location, current e forecast', () => {
  assert.deepEqual(Object.keys(shape(sample)), ['location', 'current', 'forecast']);
});

test('sem chave: 503 (a TV fica no MET Norway)', async () => {
  const r = await handleWeather(new URL('https://api.sofaly.tv/v1/weather?lat=1&lon=1'), {});
  assert.equal(r.status, 503);
});

test('pedido válido vai uma vez à WeatherAPI e depois sai do cache', async () => {
  const cache = new FakeCache();
  let calls = 0; let seen = '';
  const fetchFn = async (u) => { calls++; seen = u; return new Response(JSON.stringify(sample)); };
  const env = { WEATHERAPI_KEY: 'SEGREDO' };
  const a = await handleWeather(new URL('https://api.sofaly.tv/v1/weather?lat=-23.58&lon=-46.63'), env, { fetchFn, cache });
  const b = await handleWeather(new URL('https://api.sofaly.tv/v1/weather?lat=-23.56&lon=-46.61'), env, { fetchFn, cache });
  assert.equal(a.status, 200);
  assert.equal(b.status, 200);
  assert.equal(calls, 1);   // mesmo quadradinho da grade
  assert.ok(seen.includes('q=-23.6,-46.6'));
  const body = await b.json();
  assert.equal(body.extra, undefined);
  assert.ok(!JSON.stringify(body).includes('SEGREDO'));
  assert.match(a.headers.get('cache-control'), /max-age=1800/);
});

test('chave recusada e formato estranho viram 502', async () => {
  const env = { WEATHERAPI_KEY: 'K' };
  const u = new URL('https://api.sofaly.tv/v1/weather?lat=1&lon=1');
  assert.equal((await handleWeather(u, env, { fetchFn: async () => new Response('', { status: 401 }), cache: null })).status, 502);
  assert.equal((await handleWeather(u, env, { fetchFn: async () => new Response('{}'), cache: null })).status, 502);
  assert.equal((await handleWeather(new URL('https://api.sofaly.tv/v1/weather?lat=x&lon=1'), env, { cache: null })).status, 400);
});

test('limites: 429 por IP e para a WeatherAPI; sem binding passa', async () => {
  const body = JSON.stringify({ location: {}, current: { temp_c: 1 }, forecast: { forecastday: [] } });
  const fetchFn = async () => new Response(body);
  const u = new URL('https://api.sofaly.tv/v1/weather?lat=1&lon=1&days=1');
  const nao = { limit: async () => ({ success: false }) };
  const sim = { limit: async () => ({ success: true }) };
  assert.equal((await handleWeather(u, { WEATHERAPI_KEY: 'K', WEATHER_IP_LIMITER: nao }, { fetchFn, cache: null, ip: '1.2.3.4' })).status, 429);
  assert.equal((await handleWeather(u, { WEATHERAPI_KEY: 'K', WEATHER_IP_LIMITER: sim, WEATHER_UPSTREAM_LIMITER: nao }, { fetchFn, cache: null })).status, 429);
  const quebrado = { limit: async () => { throw new Error('x'); } };
  assert.equal((await handleWeather(u, { WEATHERAPI_KEY: 'K', WEATHER_IP_LIMITER: quebrado }, { fetchFn, cache: null })).status, 200);
  assert.equal((await handleWeather(u, { WEATHERAPI_KEY: 'K', WEATHER_UPSTREAM_LIMITER: quebrado }, { fetchFn, cache: null })).status, 429);
});

test('days do pedido não muda o que vai à WeatherAPI (uma entrada de cache por lugar)', async () => {
  const urls = [];
  const fetchFn = async (x) => { urls.push(x); return new Response(JSON.stringify({ current: {}, forecast: { forecastday: [] } })); };
  for (const d of ['1', '2', '3', '9']) await handleWeather(new URL(`https://api.sofaly.tv/v1/weather?lat=1&lon=1&days=${d}`), { WEATHERAPI_KEY: 'K', WEATHERAPI_MAX_DAYS: '7' }, { fetchFn, cache: null });
  assert.equal(new Set(urls).size, 1);
  assert.match(urls[0], /days=7&/);
});
