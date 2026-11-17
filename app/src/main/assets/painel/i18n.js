// Painel em outras línguas (ideia 76, 14/11/2026; 14 línguas no painel v3, 16/11/2026).
// O painel é escrito em português. Fora do português, este arquivo escolhe a língua do navegador entre as do app
// e carrega SÓ o arquivo dela (i18n-<língua>.js, 115 a 180 KB), que chama traduzirPainelCom({ T, P }):
//   T = texto em português -> texto traduzido (frase inteira, como aparece na tela);
//   P = [regex, troca] para frases montadas com partes que mudam ("Instalado: X. ..."); $1, $2 = as partes.
// Língua sem arquivo = inglês. Texto novo no painel sem entrada em T (ou padrão em P) aparece em português:
// ao criar um texto, ponha a tradução nos 13 arquivos (o PainelI18nTest confere que todos têm as mesmas chaves).
// Como troca: cada texto da página (e o que os scripts escrevem depois, por um MutationObserver). Só textos
// visíveis, placeholder, aria-label, title e alt; nunca o que a pessoa digitou. Mensagens de erro que vêm da TV
// (servidor) também estão na tabela (15/11/2026).
(function () {
  'use strict';
  const idioma = (navigator.languages && navigator.languages[0]) || navigator.language || 'pt';
  if (/^pt/i.test(idioma)) return;
  // As línguas do app (res/values-*). "in" é o código antigo do indonésio no Android.
  const LINGUAS = ['en', 'es', 'fr', 'de', 'it', 'pl', 'ru', 'tr', 'ar', 'hi', 'id', 'ja', 'ko', 'zh'];
  let lingua = idioma.toLowerCase().split(/[-_]/)[0];
  if (lingua === 'in') lingua = 'id';
  if (!LINGUAS.includes(lingua)) lingua = 'en';
  document.documentElement.lang = lingua;
  if (lingua === 'ar') document.documentElement.dir = 'rtl';

  window.traduzirPainelCom = function (dados) {
    const T = dados.T;
    const P = dados.P.map(([r, e]) => [new RegExp(r), e]);
    // Pedaços longos para textos montados por partes ("… a cada " + n + " min"): troca dentro do texto.
    const PEDACOS = Object.keys(T).filter((k) => k.length >= 18 && k.includes(' ')).sort((a, b) => b.length - a.length);
    const memo = new Map();
    // O que este arquivo escreveu não é traduzido de novo: trocar o texto dispara o observador, e um padrão em
    // português ("(.+) de (.+)") casaria com a frase já em espanhol ou francês e a estragaria. Guardar por nó (e não
    // por texto) evita outra armadilha: "Apagar" em espanhol é "Desligar", e em português é "Excluir".
    const escrito = new WeakMap();
    function traduzir(bruto) {
      const t = bruto.replace(/\s+/g, ' ').trim();
      if (!t || !/[A-Za-zÀ-ú]/.test(t)) return null;
      if (memo.has(t)) return memo.get(t);
      let r = T[t] || null;
      if (!r) for (const [rx, e] of P) { if (rx.test(t)) { r = t.replace(rx, e); break; } }
      if (!r) {
        let s = t;
        for (const k of PEDACOS) if (s.includes(k)) s = s.split(k).join(T[k]);
        if (s !== t) r = s;
      }
      if (memo.size > 5000) memo.clear();
      memo.set(t, r);
      return r;
    }
    function trocarTexto(n) {
      const p = n.parentNode;
      if (!p || /^(SCRIPT|STYLE|TEXTAREA)$/.test(p.nodeName) || p.isContentEditable) return;
      if (escrito.get(n) === n.nodeValue) return;
      const r = traduzir(n.nodeValue);
      if (r == null) return;
      const ini = n.nodeValue.match(/^\s*/)[0], fim = n.nodeValue.match(/\s*$/)[0];
      const novo = ini + r + fim;
      if (novo !== n.nodeValue) { n.nodeValue = novo; escrito.set(n, novo); }
    }
    const ATRS = ['placeholder', 'aria-label', 'title', 'alt'];
    function trocarAtributos(el) {
      for (const a of ATRS) {
        const v = el.getAttribute && el.getAttribute(a);
        if (!v) continue;
        const ja = escrito.get(el);
        if (ja && ja[a] === v) continue;
        const r = traduzir(v);
        if (r != null && r !== v) { el.setAttribute(a, r); escrito.set(el, Object.assign(ja || {}, { [a]: r })); }
      }
    }
    function percorrer(raiz) {
      if (raiz.nodeType === 3) { trocarTexto(raiz); return; }
      if (raiz.nodeType !== 1 && raiz.nodeType !== 9) return;
      if (raiz.nodeType === 1) trocarAtributos(raiz);
      const w = document.createTreeWalker(raiz, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT);
      for (let n = w.nextNode(); n; n = w.nextNode()) n.nodeType === 3 ? trocarTexto(n) : trocarAtributos(n);
    }
    percorrer(document.body || document.documentElement);
    const tt = traduzir(document.title); if (tt) document.title = tt;
    new MutationObserver((lista) => {
      for (const m of lista) {
        if (m.type === 'characterData') trocarTexto(m.target);
        else if (m.type === 'attributes') trocarAtributos(m.target);
        else m.addedNodes.forEach(percorrer);
      }
    }).observe(document.documentElement, { subtree: true, childList: true, characterData: true, attributes: true, attributeFilter: ATRS });
    window.traduzirPainel = traduzir;   // para os testes e para o console
  };

  // Carrega a língua já, antes do app.js (que vem logo depois no index.html): o document.write de um <script src>
  // durante a leitura da página faz o navegador esperar por ele, então o painel não pisca em português.
  // A CSP aceita, porque o arquivo vem do próprio painel (script-src 'self').
  document.write('<script src="i18n-' + lingua + '.js"><\/script>');
})();
