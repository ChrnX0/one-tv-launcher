// Painel web contra a TV DE VERDADE (21/11/2026, teste da Sala depois da refatoração C).
//
// O painel-navegador.mjs usa uma TV de mentira (dados mínimos). Este abre o painel da TV na rede de casa num Chromium,
// entra pela própria API do painel (a função chamar() do app.js, o mesmo caminho do botão Entrar) e passa por TODAS
// as páginas em computador e celular. Em cada página anota:
//   - erro de script (pageerror) e erro no console;
//   - pedido à TV que voltou com erro (status >= 400) ou falhou;
//   - rolagem para o lado;
//   - uma foto (pasta --fotos), para olhar depois.
// Só LÊ: troca de aba e espera carregar; não aperta botão nenhum dentro das páginas.
// Uso (no computador da casa): PAINEL_PIN=... node scripts/painel-tv-real.mjs --tv http://192.168.x.y:8080/ --fotos pasta
// O PIN vem só da variável de ambiente: nunca do repositório nem da linha de comando (fica no histórico do terminal).
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
let pw;
try { pw = require('playwright'); } catch { pw = require('playwright-core'); }

const args = process.argv.slice(2);
const arg = (n) => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : null; };
const BASE = arg('--tv');
const FOTOS = arg('--fotos');
const PIN = process.env.PAINEL_PIN;
if (!BASE || !PIN) { console.error('uso: PAINEL_PIN=... node scripts/painel-tv-real.mjs --tv http://<tv>:8080/ [--fotos pasta] [--chrome caminho]'); process.exit(2); }
if (FOTOS) fs.mkdirSync(FOTOS, { recursive: true });

const VISOES = [
  { nome: 'computador', viewport: { width: 1280, height: 800 } },
  { nome: 'celular', viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 1 },
];

const chrome = arg('--chrome');   // um Chromium já instalado (playwright-core não baixa navegador)
const navegador = await pw.chromium.launch(chrome ? { executablePath: chrome } : {});
const achados = [];
let paginasVistas = 0;

for (const v of VISOES) {
  const ctx = await navegador.newContext({ ...v, locale: 'pt-BR', serviceWorkers: 'block' });
  const page = await ctx.newPage();
  let onde = `${v.nome}/entrada`;
  page.on('pageerror', (e) => achados.push(`${onde}: erro de script: ${e.name}: ${e.message}`.slice(0, 220)));
  page.on('console', (m) => { if (m.type() === 'error') achados.push(`${onde}: console: ${m.text()}`.slice(0, 220)); });
  page.on('response', (r) => {
    const u = new URL(r.url());
    if (r.status() >= 400 && u.pathname !== '/api/eventos') achados.push(`${onde}: ${r.request().method()} ${u.pathname} -> ${r.status()}`);
  });
  page.on('requestfailed', (r) => {
    const u = new URL(r.url());
    if (u.pathname !== '/api/eventos') achados.push(`${onde}: pedido falhou ${u.pathname}: ${r.failure()?.errorText}`);
  });
  await page.goto(BASE, { waitUntil: 'load' });
  await page.waitForFunction(() => typeof chamar === 'function', null, { timeout: 15000 });
  await page.evaluate((pin) => chamar('POST', '/api/entrar', { pin }), PIN);
  await page.goto(BASE, { waitUntil: 'load' });
  await page.waitForFunction(() => !document.getElementById('telaPainel').hidden, null, { timeout: 20000 });
  const paginas = await page.evaluate(() => Object.keys(PAGINAS));
  for (const p of paginas) {
    onde = `${v.nome}/${p}`;
    await page.evaluate((n) => mudarAba(n, false), p);
    await page.waitForLoadState('networkidle', { timeout: 8000 }).catch(() => {});
    await page.waitForTimeout(600);
    const largura = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    if (largura > 1) achados.push(`${onde}: rolagem para o lado (${largura}px)`);
    if (FOTOS) await page.screenshot({ path: path.join(FOTOS, `${v.nome}-${p}.png`) });
    paginasVistas++;
  }
  await ctx.close();
}
await navegador.close();

console.log(`## Painel da TV de verdade\n\n${paginasVistas} páginas vistas (computador e celular).\n`);
console.log(achados.length ? [...new Set(achados)].map((a) => '- ' + a).join('\n') : 'Nenhum problema.');
process.exit(achados.length ? 1 : 0);
