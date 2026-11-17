// Elevação 18.4 (17/11/2026): o painel web num navegador de verdade (Chromium), sem TV.
//
// Serve app/src/main/assets/painel/ com uma TV de mentira (/api/* responde com dados mínimos), entra direto
// (/api/estado diz logado) e passa por TODAS as páginas do painel em:
//   - computador (1280x800) e celular (390x844, toque);
//   - as 14 línguas do painel (pt + as 13 de i18n-*.js; árabe da direita para a esquerda).
// Em cada página confere:
//   1. sem rolagem para o lado (a página cabe na largura);
//   2. acessibilidade básica: todo botão, link, campo e seleção visível tem nome (texto, aria-label, title ou
//      <label>); toda imagem visível tem alt; o idioma da página (lang) e a direção (dir) estão certos;
//   3. nenhum texto visível da tabela de tradução ficou em português (fora do pt);
//   4. nenhum erro de script de verdade (SyntaxError/ReferenceError). TypeError por causa da TV de mentira (dado
//      faltando) só vai para o relatório.
// Uso: node scripts/painel-navegador.mjs [--linguas en,ar] [--chrome /caminho]  (sai 1 se algo falhou)
// No CI (painel.yml) usa o Chrome que já vem no runner (playwright-core, sem baixar navegador).
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
let pw;
try { pw = require('playwright'); } catch { pw = require('playwright-core'); }

const RAIZ = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const PAINEL = path.join(RAIZ, 'app/src/main/assets/painel');
const args = process.argv.slice(2);
const arg = (n) => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : null; };
const TODAS = ['pt', 'en', 'es', 'fr', 'de', 'it', 'pl', 'ru', 'tr', 'ar', 'hi', 'id', 'ja', 'ko', 'zh'];
const LINGUAS = arg('--linguas') ? arg('--linguas').split(',') : TODAS;

// ---------- a TV de mentira ----------
const TIPOS = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png',
  '.woff2': 'font/woff2', '.webmanifest': 'application/manifest+json', '.txt': 'text/plain' };
const RESPOSTAS = {
  '/api/estado': { logado: true, aparelho: 'TV de teste', naTela: 'Tela inicial' },
  '/api/apps': { apps: [{ pacote: 'com.exemplo.video', nome: 'Vídeo', oculto: false, sistema: false }] },
  '/api/tvs': { tvs: [] },
  '/api/extras/diario-tv': { itens: [], webhook: '', origens: [], ultimo: '', passos: [] },
  '/api/papeis': { convites: [{ id: 'ab12cd34', papel: 'convidado', criado: 1795000000000, vence: 1795086400000 }, { id: 'ef56ab78', papel: 'familia', criado: 1795000000000, vence: null }] },
};
const servidor = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname.startsWith('/api/')) {
    if (url.pathname === '/api/eventos') { res.writeHead(204); res.end(); return; }
    const corpo = RESPOSTAS[url.pathname] || {};
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(corpo));
    return;
  }
  const nome = url.pathname === '/' ? 'index.html' : url.pathname.slice(1);
  const arq = path.join(PAINEL, path.normalize(nome).replace(/^(\.\.[/\\])+/, ''));
  if (!arq.startsWith(PAINEL) || !fs.existsSync(arq) || fs.statSync(arq).isDirectory()) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'Content-Type': TIPOS[path.extname(arq)] || 'application/octet-stream' });
  fs.createReadStream(arq).pipe(res);
});
await new Promise((r) => servidor.listen(0, '127.0.0.1', r));
const BASE = `http://127.0.0.1:${servidor.address().port}/`;

// Textos do painel que precisam de tradução: as chaves de TODAS as tabelas (texto em português) que alguma língua
// traduz para algo diferente, menos as que a língua [l] mantém iguais de propósito ("Backup", "Wi-Fi").
// Texto que sumiu da tabela de uma língua só também aparece aqui (vem das outras).
function tabela(l) {
  const t = fs.readFileSync(path.join(PAINEL, `i18n-${l}.js`), 'utf8');
  const m = new Map();
  for (const linha of t.split('\n')) {
    const r = linha.match(/^("(?:[^"\\]|\\.)*"):("(?:[^"\\]|\\.)*"),?$/);
    if (r) m.set(JSON.parse(r[1]), JSON.parse(r[2]));
  }
  return m;
}
const TABELAS = Object.fromEntries(TODAS.filter((l) => l !== 'pt').map((l) => [l, tabela(l)]));
const TRADUZIVEIS = new Set(Object.values(TABELAS).flatMap((m) => [...m].filter(([k, v]) => k !== v).map(([k]) => k)));
function chavesTraduzidas(l) {
  const propria = TABELAS[l];
  // Fora também o texto que já É uma tradução desta língua: em espanhol "Desligar" vira "Apagar", que por acaso é
  // outra chave em português.
  const valores = new Set(propria.values());
  return new Set([...TRADUZIVEIS].filter((k) => propria.get(k) !== k && !valores.has(k)));
}

const VISOES = [
  { nome: 'computador', viewport: { width: 1280, height: 800 } },
  { nome: 'celular', viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 1 },
];

const chrome = arg('--chrome');
const navegador = await pw.chromium.launch(chrome ? { executablePath: chrome } : (process.env.CI ? { channel: 'chrome' } : {}));
const falhas = [];
const avisos = new Set();
let conferidas = 0;

for (const l of LINGUAS) {
  const chaves = l === 'pt' ? new Set() : chavesTraduzidas(l);
  for (const v of VISOES) {
    const ctx = await navegador.newContext({ ...v, locale: l === 'pt' ? 'pt-BR' : l, serviceWorkers: 'block' });
    const page = await ctx.newPage();
    const erros = [];
    page.on('pageerror', (e) => erros.push(e));
    await page.goto(BASE, { waitUntil: 'load' });
    await page.waitForFunction(() => !document.getElementById('telaPainel').hidden, null, { timeout: 15000 });
    const paginas = await page.evaluate(() => Object.keys(PAGINAS));
    for (const p of paginas) {
      await page.evaluate((n) => mudarAba(n, false), p);
      await page.waitForTimeout(120);
      const r = await page.evaluate(({ traduzidas, lingua }) => {
        const visivel = (el) => { const s = getComputedStyle(el); const b = el.getBoundingClientRect();
          return s.display !== 'none' && s.visibility !== 'hidden' && b.width > 0 && b.height > 0; };
        const nomeDe = (el) => (el.getAttribute('aria-label') || el.getAttribute('title') || el.textContent ||
          (el.id && document.querySelector(`label[for="${CSS.escape(el.id)}"]`)?.textContent) ||
          el.closest('label')?.textContent || el.getAttribute('placeholder') || el.value || '').trim();
        const semNome = [];
        for (const el of document.querySelectorAll('button, a[href], input:not([type=hidden]), select, textarea, [role=button]')) {
          if (!visivel(el)) continue;
          if (el.matches('input[type=checkbox], input[type=radio]') && el.closest('label')) continue;
          if (!nomeDe(el)) semNome.push(el.outerHTML.slice(0, 90));
        }
        const semAlt = [...document.querySelectorAll('img')].filter((i) => visivel(i) && !i.hasAttribute('alt')).map((i) => i.src.slice(-40));
        const largura = document.documentElement.scrollWidth - window.innerWidth;
        // Quem passa da largura: o elemento mais de dentro que sai pela direita (ou esquerda, no árabe).
        const culpados = largura > 1 ? [...document.querySelectorAll('body *')].filter((el) => {
          if (!visivel(el)) return false;
          const b = el.getBoundingClientRect();
          const fora = (x) => x.right > window.innerWidth + 1 || x.left < -1;
          return fora(b) && ![...el.children].some((c) => visivel(c) && fora(c.getBoundingClientRect()));
        }).slice(0, 3).map((el) => el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + (el.className && typeof el.className === 'string' ? '.' + el.className.split(' ')[0] : '') + ' "' + (el.textContent || '').trim().slice(0, 40) + '"') : [];
        const sobra = [];
        if (lingua !== 'pt') {
          const set = new Set(traduzidas);
          const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
          for (let n; (n = w.nextNode());) {
            const t = n.nodeValue.replace(/\s+/g, ' ').trim();
            if (t && set.has(t) && n.parentElement && visivel(n.parentElement) && !n.parentElement.closest('textarea, pre, [contenteditable]')) sobra.push(t);
          }
        }
        return { semNome, semAlt, largura, culpados, sobra, lang: document.documentElement.lang, dir: document.documentElement.dir };
      }, { traduzidas: [...chaves], lingua: l });
      conferidas++;
      const onde = `${l}/${v.nome}/${p}`;
      if (r.largura > 1) falhas.push(`${onde}: rolagem para o lado (${r.largura}px a mais): ${r.culpados.join(' | ')}`);
      if (r.semNome.length) falhas.push(`${onde}: sem nome acessível: ${r.semNome.slice(0, 3).join(' | ')}`);
      if (r.semAlt.length) falhas.push(`${onde}: imagem sem alt: ${r.semAlt.slice(0, 3).join(' | ')}`);
      if (r.sobra.length) falhas.push(`${onde}: ficou em português: ${[...new Set(r.sobra)].slice(0, 3).join(' | ')}`);
      if (l !== 'pt' && r.lang !== l) falhas.push(`${onde}: lang="${r.lang}"`);
      if ((l === 'ar') !== (r.dir === 'rtl')) falhas.push(`${onde}: dir="${r.dir}"`);
    }
    for (const e of erros) {
      if (/SyntaxError|ReferenceError/.test(e.name + e.message)) falhas.push(`${l}/${v.nome}: erro de script: ${e.name}: ${e.message}`);
      else avisos.add(`${e.name}: ${e.message}`.slice(0, 160));
    }
    await ctx.close();
  }
}
await navegador.close();
servidor.close();

const linhas = [`## Painel no navegador`, '', `${conferidas} páginas conferidas (${LINGUAS.length} línguas × computador e celular).`, ''];
if (falhas.length) linhas.push(`### ${falhas.length} problema(s)`, '', ...falhas.slice(0, 200).map((f) => '- ' + f), '');
else linhas.push('Nenhum problema.', '');
if (avisos.size) linhas.push('### Avisos (dado faltando na TV de mentira, não reprovam)', '', ...[...avisos].slice(0, 30).map((a) => '- ' + a));
const relatorio = linhas.join('\n');
console.log(relatorio);
if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, relatorio + '\n');
process.exit(falhas.length ? 1 : 0);
