import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handlePublicDomain, parseExtra, searchUrl, archiveId, toStreams, isPublicDomain, MANIFEST, BASE_PATH } from '../src/publicdomain.js';

const fakeFetch = (bodies) => async (url) => {
  for (const [k, v] of Object.entries(bodies)) if (url.includes(k)) return new Response(JSON.stringify(v));
  return new Response('{}', { status: 404 });
};
const meta = (over = {}) => ({
  metadata: { mediatype: 'movies', licenseurl: 'http://creativecommons.org/publicdomain/mark/1.0/', collection: ['feature_films'], title: 'His Girl Friday', year: '1940', ...over },
  files: [
    { name: 'a_512kb.mp4', format: '512Kb MPEG4', height: '240' },
    { name: 'a.mp4', format: 'h.264', height: '480' },
    { name: 'a.ogv', format: 'Ogg Video' },
  ],
});

test('manifesto sem configuração e sem P2P', async () => {
  const r = await handlePublicDomain(`${BASE_PATH}/manifest.json`, fakeFetch({}));
  const m = await r.json();
  assert.equal(m.id, MANIFEST.id);
  assert.equal(m.behaviorHints.configurationRequired, false);
  assert.equal(r.headers.get('access-control-allow-origin'), '*');
});

test('extras do Stremio e busca segura', () => {
  assert.deepEqual(parseExtra('skip=100&search=Nosferatu%20(1922)'), { skip: 100, search: 'Nosferatu 1922' });
  assert.deepEqual(parseExtra('search=%E0%A4%A'), { skip: 0, search: '' });
  const u = new URL(searchUrl({ skip: 100, search: 'a OR b' }));
  assert.equal(u.searchParams.get('page'), '3');
  assert.match(u.searchParams.get('q'), /licenseurl:\(\*publicdomain\*\)/);
  assert.match(u.searchParams.get('q'), /title:\(a OR b\)$/);
});

test('id: só pd:<identificador válido>, fora os bloqueados', () => {
  assert.equal(archiveId('pd%3Ahis_girl_friday'), 'his_girl_friday');
  assert.equal(archiveId('tt0111161'), null);
  assert.equal(archiveId('pd:../x'), null);
  assert.equal(archiveId('pd:sex_madness'), null);
});

test('stream: só MP4, do melhor para o pior', () => {
  const s = toStreams('a', meta());
  assert.deepEqual(s.map((x) => x.url), ['https://archive.org/download/a/a.mp4', 'https://archive.org/download/a/a_512kb.mp4']);
});

test('confere de novo a licença na ficha', async () => {
  assert.equal(isPublicDomain(meta()), true);
  assert.equal(isPublicDomain(meta({ licenseurl: 'http://creativecommons.org/licenses/by-nc/3.0/' })), false);
  assert.equal(isPublicDomain(meta({ collection: ['opensource_movies'] })), false);
  const r = await handlePublicDomain(`${BASE_PATH}/stream/movie/pd:a.json`, fakeFetch({ '/metadata/a': meta({ licenseurl: '' }) }));
  assert.equal(r.status, 404);
  assert.deepEqual(await r.json(), { streams: [] });
});

test('catálogo e ficha', async () => {
  const f = fakeFetch({
    advancedsearch: { response: { docs: [{ identifier: 'a', title: 'A <b>film</b>', year: 1940 }, { identifier: 'sex_madness', title: 'x' }] } },
    '/metadata/a': meta(),
  });
  const c = await (await handlePublicDomain(`${BASE_PATH}/catalog/movie/pd/skip=50.json`, f)).json();
  assert.deepEqual(c.metas.map((m) => [m.id, m.name, m.releaseInfo]), [['pd:a', 'A film', '1940']]);
  const m = await (await handlePublicDomain(`${BASE_PATH}/meta/movie/pd:a.json`, f)).json();
  assert.equal(m.meta.year, 1940);
  const s = await (await handlePublicDomain(`${BASE_PATH}/stream/movie/pd:a.json`, f)).json();
  assert.equal(s.streams.length, 2);
});

test('Archive fora do ar = 502, sem quebrar', async () => {
  const r = await handlePublicDomain(`${BASE_PATH}/catalog/movie/pd.json`, async () => { throw new Error('x'); });
  assert.equal(r.status, 502);
});
