// Roda com: cd server/api && node --test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { RECOMMENDED, recommendedBody } from '../src/addons.js';

test('lista curada: https, manifest.json, ids únicos, nada de torrent', () => {
  const ids = new Set();
  for (const a of RECOMMENDED) {
    assert.match(a.url, /^https:\/\/[^\s]+\/manifest\.json$/);
    assert.ok(!/torrent/i.test(a.url + a.id), a.id);
    assert.ok(['catalog', 'subtitles', 'stream'].includes(a.kind), a.id);
    assert.ok(!ids.has(a.id), `repetido: ${a.id}`);
    ids.add(a.id);
  }
  assert.ok(RECOMMENDED.length > 0 && RECOMMENDED.length <= 30);
  assert.deepEqual(recommendedBody().addons, RECOMMENDED);
});

test('a lista do servidor é a mesma embutida no app (RecommendedAddons.BUILT_IN)', () => {
  const kt = readFileSync(new URL('../../../app/src/main/kotlin/br/paulo/tvlauncher/RecommendedAddons.kt', import.meta.url), 'utf8');
  const noApp = [...kt.matchAll(/Entry\("([^"]+)", "([^"]+)", "([^"]+)"\)/g)].map((m) => ({ id: m[1], url: m[2], kind: m[3] }));
  assert.deepEqual(noApp, RECOMMENDED.map(({ id, url, kind }) => ({ id, url, kind })));
});
