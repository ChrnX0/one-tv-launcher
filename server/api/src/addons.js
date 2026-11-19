// Lista curada de add-ons (18/11/2026). Rota pública GET /v1/addons/recommended; a TV pergunta 1 vez por dia e, se a
// resposta estragar, fica com a lista embutida no app (RecommendedAddons.kt, mesma lista). A lista só SUGERE endereços:
// a TV confere cada manifesto na instalação (sem torrent/P2P, adulto ou configuração). Critérios e motivo: docs/addons.md.
// Para mudar: editar aqui, `node --test`, `npx wrangler deploy`.
// group = seção na TV/painel: movies_series, anime, tv, subtitles, music, other (RecommendedAddons.GROUPS).
export const RECOMMENDED = Object.freeze([
  { id: 'pw.ers.netflix-catalog', kind: 'catalog', group: 'movies_series', url: 'https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/manifest.json' },
  { id: 'tmdb-addon', kind: 'catalog', group: 'movies_series', url: 'https://94c8cb9f702d-tmdb-addon.baby-beamup.club/manifest.json' },
  { id: 'com.stremio.mubi_app', kind: 'catalog', group: 'movies_series', url: 'https://mubi2stremio.adiba.ro/manifest.json' },
  // Nosso (src/publicdomain.js): longas de domínio público do Internet Archive, vídeo direto do archive.org.
  { id: 'tv.sofaly.publicdomain', kind: 'stream', group: 'movies_series', url: 'https://api.sofaly.tv/v1/addons/public-domain/manifest.json' },
  { id: 'community.anime.kitsu', kind: 'catalog', group: 'anime', url: 'https://anime-kitsu.strem.fun/manifest.json' },
  { id: 'animes-season-addon', kind: 'catalog', group: 'anime', url: 'https://victorgveloso.github.io/animes-season-addon/manifest.json' },
  { id: 'org.stremio.opensubtitlesv3', kind: 'subtitles', group: 'subtitles', url: 'https://opensubtitles-v3.strem.io/manifest.json' },
  // Canais grátis da Pluto TV e outros por país, via redirecionador de terceiro (i.mjh.nz). kind 'stream' = a TV da
  // versão da Play não mostra (decisão do Paulo 19/11/2026: canais só fora da Play).
  { id: 'community.live-channels', kind: 'stream', group: 'tv', url: 'https://jantoom.github.io/live-channels/manifest.json' },
]);

/** Corpo da resposta. `version` muda quando a lista muda (a TV só guarda; serve para conferir de fora). */
export function recommendedBody() {
  return { version: 2, addons: RECOMMENDED };
}
