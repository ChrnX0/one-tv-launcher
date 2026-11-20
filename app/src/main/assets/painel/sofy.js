// Sofy no painel e no celular (20/11/2026): a mesma TV-sofá da TV (SofyDrawable.kt), em SVG, com os mesmos movimentos.
// Os movimentos não são copiados aqui: a TV manda em /api/sofy (SofyMotion.toJson), então TV, painel e celular nunca
// divergem. Cada fala toca o movimento da expressão uma vez (até 2,5 s) e para. Com "reduzir movimento" no aparelho,
// só o rosto muda (mesma regra da TV).
'use strict';

const Sofy = (() => {
  const NS = 'http://www.w3.org/2000/svg';
  const LARANJA = '#FF6A2B', CREME = '#FFF3E6', TELA = '#1C2140', LARANJA_CLARO = '#CE3D00';
  let dados = null, pedido = null;

  /** Os movimentos e a chave da voz, lidos uma vez da TV. */
  function carregar() {
    if (dados) return Promise.resolve(dados);
    if (!pedido) pedido = chamar('GET', '/api/sofy').then((d) => (dados = d)).catch((e) => { pedido = null; throw e; });
    return pedido;
  }

  const el = (nome, attrs, pai) => {
    const n = document.createElementNS(NS, nome);
    for (const [k, v] of Object.entries(attrs || {})) n.setAttribute(k, v);
    if (pai) pai.appendChild(n);
    return n;
  };

  // ---- a conta do movimento (igual ao SofyMotion.kt: sample, ease, poseAt, faceAt)
  function ease(e, t) {
    if (e === 'IN_OUT') return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    if (e === 'BOUNCE') { const c1 = 1.70158, c3 = c1 + 1; return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2); }
    return 1 - Math.pow(1 - t, 3);
  }
  function sample(keys, t) {
    if (!keys.length) return [t, 0, 0, 0, 1, 1];
    if (t <= keys[0][0]) return keys[0];
    const ult = keys[keys.length - 1];
    if (t >= ult[0]) return ult;
    let i = 0; while (i < keys.length - 1 && keys[i + 1][0] <= t) i++;
    const a = keys[i], b = keys[i + 1], u = b[0] === a[0] ? 1 : (t - a[0]) / (b[0] - a[0]);
    return a.map((x, j) => x + (b[j] - x) * u);
  }
  function poseAt(clip, parte, ms) {
    let rot = 0, tx = 0, ty = 0, sx = 1, sy = 1;
    for (const [p, ini, dur, curva, keys] of clip.tracks) {
      if (p !== parte) continue;
      const f = Math.min(1, Math.max(0, (ms - ini) / dur));
      const k = sample(keys, ease(curva, f));
      rot += k[1]; tx += k[2]; ty += k[3]; sx *= k[4]; sy *= k[5];
    }
    return { rot, tx, ty, sx, sy };
  }
  function faceAt(clip, ms) {
    let f = clip.faces[0];
    for (const x of clip.faces) if (x[0] <= ms) f = x;
    return { eyes: f[1], mouth: f[2] };
  }
  function transform(parte, k) {
    const [px, py] = dados.parts[parte];
    return `translate(${k.tx} ${k.ty}) rotate(${k.rot} ${px} ${py}) translate(${px} ${py}) scale(${k.sx} ${k.sy}) translate(${-px} ${-py})`;
  }

  /**
   * Cria a Sofy (um <svg>) com a [expr] já na pose final. [balao]: '“ ”' (recado de outra pessoa) ou '♪' (música).
   * Precisa de carregar() antes. [rotulo] = texto para leitor de tela (a fala, quando houver).
   */
  function criar(expr, { tamanho = 72, balao = null, rotulo = 'Sofy' } = {}) {
    const svg = el('svg', { viewBox: '-30 -30 180 150', width: tamanho, height: Math.round(tamanho * 150 / 180), role: 'img', 'aria-label': rotulo, class: 'sofy' });
    const corpo = el('g', {}, svg);
    const antE = el('line', { x1: 60, y1: 36, stroke: LARANJA, 'stroke-width': 8.5, 'stroke-linecap': 'round' }, corpo);
    const antD = el('path', { fill: 'none', stroke: LARANJA, 'stroke-width': 8.5, 'stroke-linecap': 'round', 'stroke-linejoin': 'round' }, corpo);
    el('path', { d: 'M51 38a9 9 0 0 1 18 0z', fill: LARANJA }, corpo);   // o pino das antenas
    const peE = el('path', { d: 'M12.5 92H24.5L17 108.8Q16 111 13.6 111H11.4Q9 111 9.3 108.6Z', fill: LARANJA }, corpo);
    const peD = el('path', { d: 'M107.5 92H95.5L103 108.8Q104 111 106.4 111H108.6Q111 111 110.7 108.6Z', fill: LARANJA }, corpo);
    el('rect', { x: 12, y: 86, width: 96, height: 13.5, rx: 5, fill: LARANJA }, corpo);
    el('rect', { x: 23.75, y: 35.25, width: 72.5, height: 57.5, rx: 15.75, fill: TELA, stroke: LARANJA, 'stroke-width': 13.5 }, corpo);
    const texto = { 'font-family': 'ui-monospace, Menlo, Consolas, monospace', 'font-weight': 700, fill: CREME, 'text-anchor': 'middle' };
    const olhos = el('text', { ...texto, x: 60, 'font-size': 22, 'letter-spacing': 2 }, corpo);
    const boca = el('text', { ...texto, x: 60, y: 82, 'font-size': 12 }, corpo);
    const bracoE = el('rect', { x: 3.5, y: 59, width: 20.5, height: 40.5, rx: 9.5, fill: LARANJA }, corpo);
    const bracoD = el('rect', { x: 96, y: 59, width: 20.5, height: 40.5, rx: 9.5, fill: LARANJA }, corpo);
    if (balao) {
      const w = Math.max(30, balao.length * 10 + 16);
      el('rect', { x: 100 - w / 2, y: -20, width: w, height: 24, rx: 12, fill: CREME }, svg);
      el('path', { d: 'M94 3 92 12 101 3z', fill: CREME }, svg);
      el('text', { ...texto, x: 100, y: -2.5, 'font-size': 15, fill: LARANJA_CLARO }, svg).textContent = balao;
    }
    svg._sofy = { expr, corpo, antE, antD, peE, peD, olhos, boca, bracoE, bracoD, raf: 0 };
    desenhar(svg, expr, Infinity);
    return svg;
  }

  function desenhar(svg, expr, ms) {
    const s = svg._sofy, clip = dados.clips[expr];
    if (!clip) return;
    const t = Math.min(ms, clip.length_ms);
    const a = clip.antennas;
    s.antE.setAttribute('x2', a[0]); s.antE.setAttribute('y2', a[1]);
    s.antD.setAttribute('d', a[4] ? 'M60 36Q74 6 84 12Q92 18 84 24Q78 28 80 34' : `M60 36L${a[2]} ${a[3]}`);
    s.corpo.setAttribute('transform', transform('BODY', poseAt(clip, 'BODY', t)));
    s.antE.setAttribute('transform', transform('ANTENNA_LEFT', poseAt(clip, 'ANTENNA_LEFT', t)));
    s.antD.setAttribute('transform', transform('ANTENNA_RIGHT', poseAt(clip, 'ANTENNA_RIGHT', t)));
    s.bracoE.setAttribute('transform', transform('ARM_LEFT', poseAt(clip, 'ARM_LEFT', t)));
    s.bracoD.setAttribute('transform', transform('ARM_RIGHT', poseAt(clip, 'ARM_RIGHT', t)));
    s.peE.setAttribute('transform', transform('FOOT_LEFT', poseAt(clip, 'FOOT_LEFT', t)));
    s.peD.setAttribute('transform', transform('FOOT_RIGHT', poseAt(clip, 'FOOT_RIGHT', t)));
    const f = faceAt(clip, t);
    s.olhos.textContent = f.eyes;
    s.olhos.setAttribute('font-size', f.eyes.length > 3 ? 20 : 22);
    s.olhos.setAttribute('y', f.mouth ? 67 : 70);
    s.boca.textContent = f.mouth;
  }

  const semMovimento = () => window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;

  /** Troca a expressão e toca o movimento dela uma vez. Sem movimento no aparelho: só o rosto muda. */
  function tocar(svg, expr) {
    const s = svg._sofy;
    if (!s || !dados.clips[expr]) return;
    s.expr = expr;
    cancelAnimationFrame(s.raf);
    if (semMovimento()) { desenhar(svg, expr, Infinity); return; }
    const inicio = performance.now(), fim = dados.clips[expr].length_ms;
    const passo = (agora) => {
      const ms = agora - inicio;
      desenhar(svg, expr, ms);
      if (ms < fim && svg.isConnected) s.raf = requestAnimationFrame(passo);
    };
    s.raf = requestAnimationFrame(passo);
  }

  /** A expressão que combina com o estado do Observador agora (mesma ideia do SofyPolicy.expressionFor). */
  function expressaoDoObservador(agora) {
    const sev = (agora || []).map((f) => f.severity);
    if (sev.includes('CRITICAL')) return 'SURPRISED';
    if (sev.includes('WARNING')) return 'WORRIED';
    return (agora || []).length ? 'ATTENTIVE' : 'HAPPY';
  }

  return { carregar, criar, tocar, expressaoDoObservador, get dados() { return dados; } };
})();
