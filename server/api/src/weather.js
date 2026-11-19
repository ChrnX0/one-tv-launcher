// Previsão do tempo do plano Pro pela WeatherAPI.com (19/11/2026, regra do Paulo: grátis no Básico, pago a partir do Pro).
// Rota pública: GET /v1/weather?lat=<-90..90>&lon=<-180..180> (sempre todos os dias do plano contratado; a TV corta)
// Resposta: o JSON da WeatherAPI (forecast.json com aqi=yes), só com location, current e forecast. Quem converte para o
// formato que a TV já lê é o app (WeatherApiSource.kt).
//
// Por que pelo nosso servidor: os termos da WeatherAPI proíbem a chave dentro do app (weatherapi.com/terms.aspx). A chave
// fica só no secret WEATHERAPI_KEY do Worker; sem ele a rota responde 503 e a TV fica no MET Norway (Básico).
// Cache: o pedido é arredondado para uma grade de 0,1° (~11 km) e guardado 30 min na borda. A WeatherAPI é chamada por
// lugar, não por TV, e os termos permitem guardar a previsão até 24 h e o "agora" até 60 min.
// Contra abuso (a rota é pública e cada pedido fora do cache gasta a cota da conta): limite por IP em todo pedido e um
// limite global só para as chamadas à WeatherAPI (bindings de Rate Limiting no wrangler.toml; sem eles, sem limite).
// Estourou: 429, e a TV fica 15 min no MET Norway.
// Atribuição pedida no plano Free: a TV mostra "Powered by WeatherAPI.com" na tela de Previsão.

export const WA_BASE = 'https://api.weatherapi.com/v1/forecast.json';
export const GRID = 0.1;
export const CACHE_SEC = 30 * 60;
const USER_AGENT = 'Sofaly/1 (+https://sofaly.tv)';

/** Ponto da grade (1 casa decimal) ou null se a coordenada não vale. */
export function snap(v, limit) {
  const n = Number.parseFloat(v);
  if (!Number.isFinite(n) || Math.abs(n) > limit) return null;
  return (Math.round(n / GRID) * GRID).toFixed(1);
}

/** Dias do plano contratado (WEATHERAPI_MAX_DAYS; Free = 3), ou os pedidos se forem menos. */
export function cleanDays(days, maxDays) {
  const max = Math.max(1, Math.min(Number.parseInt(maxDays, 10) || 3, 14));
  const n = Number.parseInt(days, 10);
  return Number.isFinite(n) && n > 0 ? Math.min(n, max) : max;
}

export function upstreamUrl(key, lat, lon, days) {
  return `${WA_BASE}?key=${encodeURIComponent(key)}&q=${lat},${lon}&days=${days}&aqi=yes&alerts=no`;
}

/** Só o que a TV usa: nada da conta nem da chave volta para o aparelho. */
export function shape(data) {
  return { location: data?.location ?? null, current: data?.current ?? null, forecast: data?.forecast ?? null };
}

/** Atende /v1/weather. `fetchFn` e `cache` entram por parâmetro para os testes. */
export async function handleWeather(url, env, { fetchFn = fetch, cache = globalThis.caches?.default, ip = '' } = {}) {
  const key = env.WEATHERAPI_KEY;
  if (!key) return reply({ error: 'weather not configured' }, 503, 0);
  if (!(await allowed(env.WEATHER_IP_LIMITER, `ip:${ip}`))) return reply({ error: 'rate limited' }, 429, 0);
  const lat = snap(url.searchParams.get('lat'), 90);
  const lon = snap(url.searchParams.get('lon'), 180);
  if (lat === null || lon === null) return reply({ error: 'bad request' }, 400, 0);
  // "days" não entra: uma entrada de cache por lugar, não uma por (lugar, dias) que um script poderia multiplicar.
  const days = cleanDays(undefined, env.WEATHERAPI_MAX_DAYS);

  const cacheKey = new Request(`https://cache.sofaly.internal/weather?lat=${lat}&lon=${lon}&days=${days}`);
  const hit = cache ? await cache.match(cacheKey) : null;
  if (hit) return hit;

  if (!(await allowed(env.WEATHER_UPSTREAM_LIMITER, 'upstream'))) return reply({ error: 'rate limited' }, 429, 0);
  let res;
  try {
    res = await fetchFn(upstreamUrl(key, lat, lon, days), { headers: { 'User-Agent': USER_AGENT } });
  } catch {
    return reply({ error: 'weatherapi unreachable' }, 502, 0);
  }
  if (res.status === 401 || res.status === 403) return reply({ error: 'weatherapi refused the key' }, 502, 0);
  if (!res.ok) return reply({ error: `weatherapi ${res.status}` }, 502, 0);
  let data;
  try { data = await res.json(); } catch { return reply({ error: 'weatherapi bad json' }, 502, 0); }
  if (!data?.current || !Array.isArray(data?.forecast?.forecastday)) return reply({ error: 'weatherapi bad shape' }, 502, 0);
  const out = reply(shape(data), 200, CACHE_SEC);
  if (cache) await cache.put(cacheKey, out.clone());
  return out;
}

/** Rate Limiting binding da Cloudflare ({ limit({ key }) -> { success } }); sem binding ou se ele falhar, deixa passar. */
async function allowed(limiter, key) {
  if (!limiter) return true;
  try { return (await limiter.limit({ key })).success !== false; } catch { return true; }
}

function reply(obj, status, maxAge) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'content-type': 'application/json', 'cache-control': maxAge > 0 ? `public, max-age=${maxAge}` : 'no-store' },
  });
}
