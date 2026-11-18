// Lista curada de add-ons (18/11/2026). Rota pública GET /v1/addons/recommended; a TV pergunta 1 vez por dia e, se a
// resposta estragar, fica com a lista embutida no app (RecommendedAddons.kt, mesma lista). A lista só SUGERE endereços:
// a TV confere cada manifesto na instalação (sem torrent/P2P, adulto ou configuração). Critérios e motivo: docs/addons.md.
// Para mudar: editar aqui, `node --test`, `npx wrangler deploy`.
export const RECOMMENDED = Object.freeze([
  { id: 'pw.ers.netflix-catalog', kind: 'catalog', url: 'https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/manifest.json' },
  { id: 'tmdb-addon', kind: 'catalog', url: 'https://94c8cb9f702d-tmdb-addon.baby-beamup.club/manifest.json' },
  { id: 'com.stremio.mubi_app', kind: 'catalog', url: 'https://mubi2stremio.adiba.ro/manifest.json' },
  { id: 'community.anime.kitsu', kind: 'catalog', url: 'https://anime-kitsu.strem.fun/manifest.json' },
  { id: 'animes-season-addon', kind: 'catalog', url: 'https://victorgveloso.github.io/animes-season-addon/manifest.json' },
]);

/** Corpo da resposta. `version` muda quando a lista muda (a TV só guarda; serve para conferir de fora). */
export function recommendedBody() {
  return { version: 1, addons: RECOMMENDED };
}
