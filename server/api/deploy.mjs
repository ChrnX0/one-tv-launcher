// Publica a API (api.sofaly.tv) do jeito seguro: `npm run deploy` dentro de server/api.
// Por que existe (19/11/2026): `wrangler deploy` rodado FORA desta pasta não acha o wrangler.toml e a
// autoconfiguração do wrangler 4 sobe a pasta atual como site estático com o nome que vier em --name.
// Isso derrubou a API duas vezes (tudo 404) e apagou os secrets. Aqui o --config aponta sempre para o arquivo certo,
// e depois de publicar a própria API é testada; se falhar, o comando termina com erro.
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const pasta = dirname(fileURLToPath(import.meta.url));
const config = join(pasta, 'wrangler.toml');
const BASE = 'https://api.sofaly.tv';

function rodar(cmd, args) {
  // No Windows (Dell) o npx é um .cmd e só roda via shell; aí cada argumento vai entre aspas (pasta com espaço).
  const win = process.platform === 'win32';
  const r = win
    ? spawnSync([cmd, ...args.map((a) => `"${a}"`)].join(' '), { cwd: pasta, stdio: 'inherit', shell: true })
    : spawnSync(cmd, args, { cwd: pasta, stdio: 'inherit' });
  if (r.status !== 0) { console.error(`falhou: ${cmd} ${args.join(' ')}`); process.exit(r.status ?? 1); }
}

if (!existsSync(config) || !/^name = "sofaly-api"$/m.test(readFileSync(config, 'utf8'))) {
  console.error(`wrangler.toml da API não encontrado em ${config}. Nada foi publicado.`);
  process.exit(1);
}

rodar('node', ['--test']);                                        // regras puras antes de publicar
rodar('npx', ['--yes', 'wrangler@4', 'deploy', '--config', config]);

// O domínio próprio pode levar alguns segundos para apontar para a versão nova.
let ok = false;
for (let i = 0; i < 10 && !ok; i++) {
  try { ok = (await (await fetch(`${BASE}/v1/health`)).json()).ok === true; } catch { /* tenta de novo */ }
  if (!ok) await new Promise((r) => setTimeout(r, 3000));
}
if (!ok) { console.error(`${BASE}/v1/health não respondeu {"ok":true} depois de publicar.`); process.exit(1); }
rodar('node', ['e2e.mjs', BASE]);
console.log('publicado e testado:', BASE);
