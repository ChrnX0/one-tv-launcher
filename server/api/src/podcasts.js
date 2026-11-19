// Busca de podcasts e "Em alta no seu país" pelo Podcast Index (19/11/2026). Rotas públicas:
//   GET /v1/podcasts/search?q=<termo>&lang=<pt>&max=30
//   GET /v1/podcasts/trending?lang=<pt>&max=30
// Resposta: {"feeds":[{url,title,author,image,explicit,language}]} (o PodcastBusca.kt do app lê isso).
// A chave e o segredo ficam só nos secrets do Worker (PODCASTINDEX_API_KEY, PODCASTINDEX_API_SECRET), nunca no git
// nem no APK: cada pedido ao Podcast Index é assinado aqui. Sem os secrets, as rotas respondem 503 e a TV esconde a busca.
// A mesma pergunta fica guardada na borda da Cloudflare (busca 6 h, em alta 1 h): menos pedidos ao Podcast Index.
// Atribuição pedida pelos termos: a TV mostra "Busca: Podcast Index".

export const PI_BASE = 'https://api.podcastindex.org/api/1.0';
export const MAX = 30;
const USER_AGENT = 'Sofaly/1 (+https://sofaly.tv)';
export const CACHE_SEC = { search: 6 * 3600, trending: 3600 };

/** Cabeçalhos de autenticação do Podcast Index: Authorization = SHA-1(chave + segredo + hora em segundos), em hex. */
export async function authHeaders(key, secret, nowSec) {
  const date = String(nowSec);
  const hash = new Uint8Array(await crypto.subtle.digest('SHA-1', new TextEncoder().encode(key + secret + date)));
  return {
    'User-Agent': USER_AGENT,
    'X-Auth-Key': key,
    'X-Auth-Date': date,
    Authorization: [...hash].map((b) => b.toString(16).padStart(2, '0')).join(''),
  };
}

/** Idioma no formato "pt" ou "pt-br"; qualquer outra coisa vira "" (sem filtro de idioma). */
export function cleanLang(lang) {
  const l = String(lang ?? '').trim().toLowerCase();
  return /^[a-z]{2}(-[a-z]{2})?$/.test(l) ? l : '';
}

export function cleanMax(max) {
  const n = Number.parseInt(max, 10);
  return Number.isFinite(n) && n > 0 ? Math.min(n, MAX) : MAX;
}

/** Termo da busca: sem espaço sobrando, até 100 letras. Vazio = pedido inválido. */
export function cleanQuery(q) {
  return String(q ?? '').replace(/\s+/g, ' ').trim().slice(0, 100);
}

/** Endereço no Podcast Index para cada rota (null = pedido inválido). */
export function upstreamUrl(kind, params) {
  const max = cleanMax(params.get('max'));
  const lang = cleanLang(params.get('lang'));
  if (kind === 'search') {
    const q = cleanQuery(params.get('q'));
    if (q.length < 2) return null;
    // Pede mais que o máximo: o filtro de idioma é feito aqui depois (o byterm não filtra por idioma).
    return `${PI_BASE}/search/byterm?q=${encodeURIComponent(q)}&max=${Math.min(max * 2, 60)}`;
  }
  if (kind === 'trending') {
    // "lang" do trending aceita o código curto; "pt-br" e "pt" entram juntos.
    const base = lang.split('-')[0];
    const langs = [...new Set([base, lang].filter(Boolean))].join(',');
    return `${PI_BASE}/podcasts/trending?max=${max}${langs ? `&lang=${encodeURIComponent(langs)}` : ''}`;
  }
  return null;
}

const httpUrl = (u) => typeof u === 'string' && /^https?:\/\/[^\s]+$/i.test(u.trim());

/**
 * Do formato do Podcast Index para o nosso: só feed com endereço http(s) e nome, sem repetir; idioma do pedido
 * primeiro (na busca, os de outro idioma vêm depois, não somem: nome de podcast estrangeiro também se procura).
 */
export function shapeFeeds(data, lang = '', max = MAX) {
  const feeds = Array.isArray(data?.feeds) ? data.feeds : [];
  const seen = new Set();
  const out = [];
  for (const f of feeds) {
    const url = String(f?.url ?? '').trim();
    const title = String(f?.title ?? '').trim();
    if (!httpUrl(url) || !title || seen.has(url) || f?.dead === 1) continue;
    seen.add(url);
    const image = [f.artwork, f.image].map((x) => String(x ?? '').trim()).find(httpUrl) ?? '';
    out.push({
      url, title, author: String(f.author ?? f.ownerName ?? '').trim(), image,
      explicit: f.explicit === true || f.explicit === 1, language: String(f.language ?? '').trim().toLowerCase(),
    });
  }
  const base = cleanLang(lang).split('-')[0];
  const sameLang = (x) => base && x.language.split('-')[0] === base;
  const sorted = base ? [...out.filter(sameLang), ...out.filter((x) => !sameLang(x))] : out;
  return { feeds: sorted.slice(0, cleanMax(max)) };
}

/** Atende /v1/podcasts/search e /v1/podcasts/trending. `fetchFn` e `cache` entram por parâmetro para os testes. */
export async function handlePodcasts(kind, url, env, { fetchFn = fetch, cache = globalThis.caches?.default, nowSec } = {}) {
  const key = env.PODCASTINDEX_API_KEY;
  const secret = env.PODCASTINDEX_API_SECRET;
  if (!key || !secret) return reply({ error: 'podcasts not configured' }, 503, 0);
  const upstream = upstreamUrl(kind, url.searchParams);
  if (!upstream) return reply({ error: 'bad request' }, 400, 0);

  // A chave do cache é o pedido já limpo (maiúsculas, espaços e parâmetros extras não multiplicam as entradas).
  const lang = cleanLang(url.searchParams.get('lang'));
  const max = cleanMax(url.searchParams.get('max'));
  const cacheKey = new Request(`https://cache.sofaly.internal/podcasts/${kind}?u=${encodeURIComponent(upstream)}&lang=${lang}&max=${max}`);
  const hit = cache ? await cache.match(cacheKey) : null;
  if (hit) return hit;

  let res;
  try {
    res = await fetchFn(upstream, { headers: await authHeaders(key, secret, nowSec ?? Math.floor(Date.now() / 1000)) });
  } catch {
    return reply({ error: 'podcast index unreachable' }, 502, 0);
  }
  if (res.status === 401 || res.status === 403) return reply({ error: 'podcast index refused the key' }, 502, 0);
  if (!res.ok) return reply({ error: `podcast index ${res.status}` }, 502, 0);
  let data;
  try { data = await res.json(); } catch { return reply({ error: 'podcast index bad json' }, 502, 0); }
  const out = reply(shapeFeeds(data, lang, max), 200, CACHE_SEC[kind]);
  if (cache) await cache.put(cacheKey, out.clone());
  return out;
}

function reply(obj, status, maxAge) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { 'content-type': 'application/json', 'cache-control': maxAge > 0 ? `public, max-age=${maxAge}` : 'no-store' },
  });
}
