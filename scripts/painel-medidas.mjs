// Pente fino (19/11/2026): MEDE cada elemento visível de cada página do painel web, num navegador de verdade.
//
// Usa a mesma TV de mentira do painel-navegador.mjs (servidor local com /api/* mínimo) e passa por TODAS as páginas
// em computador (1280x800) e celular (390x844, toque), nos temas claro e escuro. Em cada página mede:
//   - CONTRASTE (WCAG 2.1 AA 1.4.3): cor do texto contra o fundo de verdade (o primeiro fundo opaco subindo pelos
//     pais, com transparência composta). Letra grande (24 px, ou 18,66 px em negrito) pede 3:1; o resto 4,5:1.
//   - LETRA: menor que 12 px (ilegível) e, no celular, campo de digitar com menos de 16 px (o iPhone dá zoom sozinho).
//   - ALVO DE TOQUE (celular): botão, link, campo menor que 24x24 px (WCAG 2.2 AA 2.5.8) e menor que 44x44 (2.5.5 AAA,
//     o que a Apple e o Google pedem).
//   - FOCO VISÍVEL (2.4.7): passa o Tab por cada controle e confere se o contorno ou a sombra muda.
//   - GRADE DE 4 px: margens, paddings e gaps que não são múltiplos de 4 (inteiros, depois de arredondar).
//   - ESCALA: quantos tamanhos de letra diferentes a página usa (mais de 6 é escala solta).
// Uso: node scripts/painel-medidas.mjs [--saida pasta]   (não reprova: grava medidas.json, relatorio.md e fotos)
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
const SAIDA = path.resolve(args[args.indexOf('--saida') + 1] && args.includes('--saida') ? args[args.indexOf('--saida') + 1] : path.join(RAIZ, 'app/build/painel-medidas'));
fs.mkdirSync(SAIDA, { recursive: true });

const TIPOS = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png',
  '.woff2': 'font/woff2', '.webmanifest': 'application/manifest+json', '.txt': 'text/plain' };
const RESPOSTAS = {
  '/api/estado': { logado: true, aparelho: 'TV de teste', naTela: 'Tela inicial' },
  '/api/apps': { apps: [{ pacote: 'com.exemplo.video', nome: 'Vídeo', oculto: false, sistema: false }] },
  '/api/tvs': { tvs: [] },
  '/api/addons': { addons: [] },
  '/api/extras/diario-tv': { itens: [], webhook: '', origens: [], ultimo: '', passos: [] },
};
const servidor = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://x');
  if (url.pathname.startsWith('/api/')) {
    if (url.pathname === '/api/eventos') { res.writeHead(204); res.end(); return; }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(RESPOSTAS[url.pathname] || {}));
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

const VISOES = [
  { nome: 'computador', viewport: { width: 1280, height: 800 } },
  { nome: 'celular', viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 },
];
const TEMAS = ['dark', 'light'];

// Roda dentro da página: devolve os achados de medida.
function medirPagina(celular) {
  const vis = (el) => { const s = getComputedStyle(el); const b = el.getBoundingClientRect();
    return s.display !== 'none' && s.visibility !== 'hidden' && +s.opacity > 0 && b.width > 0 && b.height > 0; };
  const rgba = (t) => { const m = t.match(/rgba?\(([^)]+)\)/); if (!m) return null; const p = m[1].split(/[ ,/]+/).filter(Boolean).map(Number);
    return [p[0], p[1], p[2], p.length > 3 ? p[3] : 1]; };
  const lum = ([r, g, b]) => { const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b); };
  const sobre = (fg, bg) => [0, 1, 2].map((i) => fg[i] * fg[3] + bg[i] * (1 - fg[3]));
  const contraste = (a, b) => { const x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
  const hex = (c) => '#' + c.slice(0, 3).map((v) => Math.round(v).toString(16).padStart(2, '0')).join('');
  // Fundo de verdade: compõe as camadas de fundo, de cima (o pai mais distante) para baixo.
  const fundoDe = (el) => {
    const camadas = []; let temImagem = false;
    for (let e = el; e; e = e.parentElement) {
      const s = getComputedStyle(e);
      if (s.backgroundImage && s.backgroundImage !== 'none') temImagem = true;
      const c = rgba(s.backgroundColor); if (c && c[3] > 0) camadas.push(c);
      if (c && c[3] >= 1) break;
    }
    let bg = [255, 255, 255];
    for (const c of camadas.reverse()) bg = sobre(c, bg);
    return { bg, temImagem };
  };
  const nome = (el) => (el.getAttribute('aria-label') || el.textContent || el.getAttribute('placeholder') || el.tagName).replace(/\s+/g, ' ').trim().slice(0, 40);
  const achados = []; const tamanhos = new Set(); const foraGrade = {};
  const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  const vistos = new Set();
  for (let n; (n = w.nextNode());) {
    const el = n.parentElement; if (!el || vistos.has(el) || !n.nodeValue.trim() || !vis(el)) continue;
    if (el.closest('svg, script, style, noscript')) continue;
    vistos.add(el);
    const s = getComputedStyle(el); const px = parseFloat(s.fontSize); tamanhos.add(px);
    const fg = rgba(s.color); const { bg, temImagem } = fundoDe(el);
    if (fg && !temImagem) {
      const c = contraste(sobre(fg, bg), bg); const negrito = +s.fontWeight >= 700;
      const min = px >= 24 || (negrito && px >= 18.66) ? 3 : 4.5;
      if (c < min) achados.push(`CONTRASTE ${c.toFixed(2)}:1 (mín. ${min}) "${nome(el)}" ${hex(sobre(fg, bg))} sobre ${hex(bg)}, ${px}px`);
    }
    if (px < 12) achados.push(`LETRA PEQUENA ${px}px "${nome(el)}"`);
  }
  const controles = [...document.querySelectorAll('button, a[href], input:not([type=hidden]), select, textarea, [role=button], [tabindex]:not([tabindex="-1"])')].filter(vis);
  for (const el of controles) {
    const b = el.getBoundingClientRect();
    if (celular) {
      if (b.width < 24 || b.height < 24) achados.push(`ALVO MENOR QUE 24px (2.5.8 AA) "${nome(el)}" ${Math.round(b.width)}x${Math.round(b.height)}`);
      else if (b.width < 44 || b.height < 44) achados.push(`ALVO MENOR QUE 44px (2.5.5) "${nome(el)}" ${Math.round(b.width)}x${Math.round(b.height)}`);
      if (el.matches('input:not([type=checkbox]):not([type=radio]):not([type=range]), textarea, select') && parseFloat(getComputedStyle(el).fontSize) < 16)
        achados.push(`CAMPO COM LETRA < 16px (o iPhone dá zoom) "${nome(el)}" ${getComputedStyle(el).fontSize}`);
    }
  }
  for (const el of document.querySelectorAll('body *')) {
    if (!vis(el)) continue;
    const s = getComputedStyle(el);
    for (const p of ['marginTop', 'marginRight', 'marginBottom', 'marginLeft', 'paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft', 'rowGap', 'columnGap']) {
      const v = parseFloat(s[p]); if (!(v > 0)) continue;
      const r = Math.round(v); if (Math.abs(v - r) < 0.05 && r % 4 !== 0 && r > 1) foraGrade[r] = (foraGrade[r] || 0) + 1;
    }
  }
  if (Object.keys(foraGrade).length) achados.push('FORA DA GRADE DE 4px: ' + Object.entries(foraGrade).map(([k, v]) => `${k}px ×${v}`).join(', '));
  const t = [...tamanhos].sort((a, b) => a - b);
  if (t.length > 6) achados.push(`ESCALA SOLTA: ${t.length} tamanhos de letra (${t.join(', ')} px)`);
  return { achados, tamanhos: t, controles: controles.length };
}

const navegador = await pw.chromium.launch();
const tudo = {};
const md = ['# Painel: medidas por página', ''];
for (const tema of TEMAS) for (const v of VISOES) {
  const ctx = await navegador.newContext({ ...v, locale: 'pt-BR', colorScheme: tema, serviceWorkers: 'block' });
  const page = await ctx.newPage();
  await page.goto(BASE, { waitUntil: 'load' });
  await page.waitForFunction(() => !document.getElementById('telaPainel').hidden, null, { timeout: 15000 });
  const paginas = await page.evaluate(() => Object.keys(PAGINAS));
  for (const p of paginas) {
    await page.evaluate((n) => mudarAba(n, false), p);
    await page.waitForTimeout(150);
    const r = await page.evaluate(medirPagina, v.nome === 'celular');
    // Foco visível: Tab até 25 controles e compara contorno/sombra com e sem foco.
    const semFoco = [];
    await page.evaluate(() => document.activeElement?.blur());
    for (let i = 0; i < 25; i++) {
      await page.keyboard.press('Tab');
      const f = await page.evaluate(() => {
        const el = document.activeElement; if (!el || el === document.body) return null;
        const s = getComputedStyle(el);
        const visto = (s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0) || (s.boxShadow && s.boxShadow !== 'none');
        return { visto, nome: (el.getAttribute('aria-label') || el.textContent || el.tagName).replace(/\s+/g, ' ').trim().slice(0, 40) };
      });
      if (!f) break;
      if (!f.visto) semFoco.push(f.nome);
    }
    if (semFoco.length) r.achados.push(`FOCO INVISÍVEL ao usar Tab (2.4.7): ${[...new Set(semFoco)].slice(0, 6).map((n) => `"${n}"`).join(', ')}`);
    const chave = `${tema}/${v.nome}/${p}`;
    tudo[chave] = r;
    await page.screenshot({ path: path.join(SAIDA, `${tema}-${v.nome}-${p}.png`), fullPage: true });
  }
  await ctx.close();
}
await navegador.close();
servidor.close();

// Relatório: agrupa o mesmo achado que aparece em várias páginas.
const porAchado = new Map();
for (const [k, r] of Object.entries(tudo)) for (const a of r.achados) {
  const base = a.replace(/^(FORA DA GRADE|ESCALA SOLTA).*/, '$1');
  if (!porAchado.has(a)) porAchado.set(a, []);
  porAchado.get(a).push(k);
}
md.push(`${Object.keys(tudo).length} páginas medidas (claro e escuro, computador e celular).`, '');
for (const [a, ks] of [...porAchado].sort((x, y) => y[1].length - x[1].length)) md.push(`- ${a}  \n  em ${ks.length}: ${ks.slice(0, 6).join(', ')}${ks.length > 6 ? '…' : ''}`);
fs.writeFileSync(path.join(SAIDA, 'medidas.json'), JSON.stringify(tudo, null, 1));
fs.writeFileSync(path.join(SAIDA, 'relatorio.md'), md.join('\n') + '\n');
console.log(md.slice(0, 120).join('\n'));
