// O wrangler.toml da raiz (usado pelo Workers Builds da Cloudflare a cada merge) tem que ser igual ao da API.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const limpo = (caminho) => readFileSync(new URL(caminho, import.meta.url), 'utf8')
  .split('\n')
  .map((l) => l.replace(/\s+#.*$/, '').trimEnd())            // tira comentário no fim da linha
  .filter((l) => l && !l.startsWith('#') && !l.startsWith('main ='))
  .join('\n');

test('wrangler.toml da raiz é igual ao de server/api (menos o caminho do main)', () => {
  assert.equal(limpo('../../../wrangler.toml'), limpo('../wrangler.toml'));
  assert.match(readFileSync(new URL('../../../wrangler.toml', import.meta.url), 'utf8'), /^main = "server\/api\/src\/index\.js"$/m);
});
