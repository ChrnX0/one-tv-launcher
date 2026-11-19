// Add-on "Public Domain Movies" da Sofaly (19/11/2026, decisão do Paulo: além da lista curada, um add-on NOSSO de
// vídeo 100% legal). Fala o protocolo de add-ons do Stremio, então qualquer app compatível (e a nossa TV) instala por
// https://api.sofaly.tv/v1/addons/public-domain/manifest.json. Rotas públicas, sem conta e sem chave:
//   GET <base>/manifest.json
//   GET <base>/catalog/movie/pd.json             mais vistos; extras skip=<n> e search=<termo> no formato do Stremio
//   GET <base>/meta/movie/pd:<identificador>.json
//   GET <base>/stream/movie/pd:<identificador>.json   link direto do MP4 no Internet Archive
//
// Fonte: Internet Archive, coleção curada "feature_films" (longas), só itens com licença de domínio público
// (licenseurl publicdomain: Public Domain Mark ou o antigo CC "publicdomain"). O vídeo sai direto do archive.org; o
// Worker só monta as listas. Risco conhecido: a licença no Archive é declarada por quem enviou. Se alguém reclamar de um
// título, ele entra em BLOCKED (e `npx wrangler deploy`).
// As respostas do Archive ficam guardadas na borda da Cloudflare (CACHE_SEC) para não pesar no Archive.

export const BASE_PATH = '/v1/addons/public-domain';
export const PREFIX = 'pd:';
export const PAGE = 50;
export const CACHE_SEC = 6 * 3600;
const USER_AGENT = 'Sofaly/1 (+https://sofaly.tv)';
const IA = 'https://archive.org';

/** Títulos tirados da lista (reclamação de direitos ou conteúdo impróprio para a sala). */
export const BLOCKED = new Set(['sex_madness']);

/** Formatos que a TV toca (MP4/H.264), do melhor para o pior. */
export const PLAYABLE = ['h.264 HD', 'h.264', 'h.264 IA', 'MPEG4', '512Kb MPEG4'];

export const MANIFEST = Object.freeze({
  id: 'tv.sofaly.publicdomain',
  version: '1.0.0',
  name: 'Public Domain Movies',
  description: 'Public domain feature films from the Internet Archive, played straight from archive.org. By Sofaly.',
  logo: 'https://archive.org/images/logo.jpg',
  types: ['movie'],
  resources: ['catalog', 'meta', 'stream'],
  idPrefixes: [PREFIX],
  catalogs: [{
    type: 'movie', id: 'pd', name: 'Public domain films',
    extra: [{ name: 'skip', isRequired: false }, { name: 'search', isRequired: false }],
  }],
  behaviorHints: { configurable: false, configurationRequired: false },
});

const ID_RE = /^[A-Za-z0-9._-]{1,100}$/;

/** Termo de busca seguro para a consulta do Archive (só letras, números e espaço; até 60). Vazio = sem busca. */
export function cleanSearch(s) {
  return String(s ?? '').normalize('NFKC').replace(/[^\p{L}\p{N} ]+/gu, ' ').replace(/\s+/g, ' ').trim().slice(0, 60);
}

/** Lê os extras do caminho do Stremio ("skip=50&search=nosferatu"). */
export function parseExtra(seg) {
  const out = { skip: 0, search: '' };
  if (!seg) return out;
  for (const part of seg.split('&')) {
    const i = part.indexOf('=');
    if (i < 0) continue;
    const k = part.slice(0, i);
    let v;
    try { v = decodeURIComponent(part.slice(i + 1)); } catch { continue; }
    if (k === 'skip') out.skip = Math.max(0, Math.min(5000, Number.parseInt(v, 10) || 0));
    if (k === 'search') out.search = cleanSearch(v);
  }
  return out;
}

/** Endereço da busca no Archive para uma página do catálogo. */
export function searchUrl({ skip = 0, search = '' } = {}) {
  let q = 'collection:(feature_films) AND mediatype:(movies) AND licenseurl:(*publicdomain*)'
    + ' AND format:("h.264" OR "h.264 HD" OR "h.264 IA" OR "MPEG4" OR "512Kb MPEG4")'
    + ' AND -title:(sex OR nudist OR burlesque OR striptease) AND -subject:(sex OR nudity OR exploitation)';
  if (search) q += ` AND title:(${search})`;
  const p = new URLSearchParams({ q, rows: String(PAGE), page: String(Math.floor(skip / PAGE) + 1), output: 'json' });
  for (const f of ['identifier', 'title', 'year', 'description']) p.append('fl[]', f);
  if (!search) p.append('sort[]', 'downloads desc');
  return `${IA}/advancedsearch.php?${p}`;
}

const text = (v) => (Array.isArray(v) ? v[0] : v) == null ? '' : String(Array.isArray(v) ? v[0] : v);
const plain = (html) => text(html).replace(/<[^>]*>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim();
const poster = (id) => `${IA}/services/img/${id}`;

/** Resposta da busca do Archive -> metas do catálogo. */
export function toMetas(json) {
  const docs = json?.response?.docs;
  if (!Array.isArray(docs)) return [];
  return docs.filter((d) => ID_RE.test(text(d.identifier)) && !BLOCKED.has(text(d.identifier))).map((d) => {
    const id = text(d.identifier);
    const year = text(d.year);
    return {
      id: PREFIX + id, type: 'movie', name: plain(d.title) || id, poster: poster(id), posterShape: 'poster',
      ...(year ? { releaseInfo: year } : {}),
      description: plain(d.description).slice(0, 500),
    };
  });
}

/** O identificador do Archive dentro do id do Stremio ("pd:his_girl_friday"), ou null. */
export function archiveId(stremioId) {
  let s;
  try { s = decodeURIComponent(stremioId); } catch { return null; }
  if (!s.startsWith(PREFIX)) return null;
  const id = s.slice(PREFIX.length);
  return ID_RE.test(id) && !BLOCKED.has(id) ? id : null;
}

/** Item ainda é domínio público e está liberado? (confere de novo na ficha: a busca pode estar velha) */
export function isPublicDomain(meta) {
  const m = meta?.metadata;
  if (!m || m.mediatype !== 'movies') return false;
  if (!/publicdomain/i.test(text(m.licenseurl))) return false;
  const coll = Array.isArray(m.collection) ? m.collection : [m.collection];
  return coll.includes('feature_films');
}

/** Ficha do Archive -> meta do Stremio. */
export function toMeta(id, meta) {
  const m = meta.metadata;
  const year = text(m.year) || text(m.date).slice(0, 4);
  return {
    id: PREFIX + id, type: 'movie', name: plain(m.title) || id, poster: poster(id), background: poster(id),
    posterShape: 'poster', description: plain(m.description).slice(0, 2000),
    ...(/^\d{4}$/.test(year) ? { releaseInfo: year, year: Number(year) } : {}),
    ...(text(m.runtime) ? { runtime: text(m.runtime) } : {}),
    links: [{ name: 'Internet Archive', category: 'source', url: `${IA}/details/${id}` }],
  };
}

/** Os MP4 tocáveis da ficha, do melhor formato (e maior altura) para o pior. No máximo 3. */
export function toStreams(id, meta) {
  const files = Array.isArray(meta?.files) ? meta.files : [];
  const rank = (f) => PLAYABLE.indexOf(f.format);
  return files
    .filter((f) => rank(f) >= 0 && typeof f.name === 'string' && /\.(mp4|m4v)$/i.test(f.name))
    .sort((a, b) => rank(a) - rank(b) || (Number(b.height) || 0) - (Number(a.height) || 0))
    .slice(0, 3)
    .map((f) => ({
      name: 'Internet Archive',
      title: `${f.height ? `${f.height}p · ` : ''}Public domain`,
      url: `${IA}/download/${id}/${f.name.split('/').map(encodeURIComponent).join('/')}`,
      behaviorHints: { notWebReady: false },
    }));
}

const reply = (obj, status = 200) => new Response(JSON.stringify(obj), {
  status,
  headers: {
    'content-type': 'application/json', 'access-control-allow-origin': '*',
    'cache-control': status === 200 ? `public, max-age=${CACHE_SEC}` : 'no-store',
  },
});

async function getJson(url, fetcher) {
  const r = await fetcher(url, {
    headers: { 'User-Agent': USER_AGENT, Accept: 'application/json' },
    cf: { cacheTtl: CACHE_SEC, cacheEverything: true },
  });
  if (!r.ok) throw new Error(`archive ${r.status}`);
  return r.json();
}

/** Atende tudo o que começa com [BASE_PATH]. [fetcher] = fetch (os testes trocam por um falso). */
export async function handlePublicDomain(path, fetcher = fetch) {
  const rest = path.slice(BASE_PATH.length);
  if (rest === '/manifest.json') return reply(MANIFEST);
  try {
    const cat = rest.match(/^\/catalog\/movie\/pd(?:\/([^/]+))?\.json$/);
    if (cat) return reply({ metas: toMetas(await getJson(searchUrl(parseExtra(cat[1])), fetcher)) });

    const one = rest.match(/^\/(meta|stream)\/movie\/([^/]+)\.json$/);
    if (one) {
      const id = archiveId(one[2]);
      const empty = one[1] === 'meta' ? { meta: null } : { streams: [] };
      if (!id) return reply(empty, 404);
      const meta = await getJson(`${IA}/metadata/${id}`, fetcher);
      if (!isPublicDomain(meta)) return reply(empty, 404);
      return reply(one[1] === 'meta' ? { meta: toMeta(id, meta) } : { streams: toStreams(id, meta) });
    }
  } catch {
    return reply({ error: 'archive unavailable' }, 502);
  }
  return reply({ error: 'not found' }, 404);
}
