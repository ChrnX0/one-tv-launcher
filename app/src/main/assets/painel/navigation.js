// Navegação do painel (PR 2 do estudo "painel com a identidade Sofaly", 20/11/2026). Regras do estudo:
//   1. Nada some: toda página continua no mesmo lugar; isto só acrescenta caminhos.
//   2. Dois toques até qualquer ajuste: barra lateral + página, ou a busca (busca.js), que acha cada bloco.
//   8. Barra lateral sem rolar: o índice de blocos abre SÓ na página aberta, e a barra cabe num notebook de 768 px.
// O que este arquivo faz:
//   - Índice da página: os blocos (cartões com título) da página aberta, embaixo dela na barra lateral do computador
//     e numa fileira de atalhos no celular. Acende o bloco que está na tela enquanto a pessoa rola.
//   - Rótulo da cruz: o "‹ Configurações" do topo vira "CONFIGURAÇÕES  4 / 8", como o rótulo laranja dos mundos na TV
//     ("ASSISTIR 2 de 8"). Só números, para não precisar de frase montada nas 14 línguas.
//   - Aba Tudo (celular): as páginas de "Na TV" e "Configurações" numa tela só, com a busca no alto. A barra de baixo
//     fica Início · Controle · Apps · Tudo (o estudo pede a Sofy entre Apps e Tudo; ela entra no PR 5).
// Usa as globais do app.js ($, PAGINAS, mudarAba, paginaAtual, paginasDoPapel) e abrirBusca do busca.js.
// Texto vindo da página entra por textContent; a tradução (i18n.js) já trocou os títulos antes de serem copiados.
'use strict';

/** Os blocos de uma página: cartões visíveis com título próprio (h2 filho direto), sem cartão dentro de cartão. */
function blocosDe(secao) {
  return [...secao.querySelectorAll('.cartao')].filter((c) =>
    !c.hidden && !c.parentElement.closest('.cartao') && c.querySelector(':scope > h2') && !c.closest('[hidden]:not(.painel-aba)'));
}

/**
 * Todo bloco com título ganha um id estável (se ainda não tem), para o índice e a busca levarem até ele.
 * O id usa a página e o texto do título em português (antes da tradução não importa: só precisa ser único).
 */
function garantirIdsDosBlocos() {
  for (const secao of document.querySelectorAll('.painel-aba')) {
    let n = 0;
    for (const c of secao.querySelectorAll('.cartao')) {
      if (c.id || !c.querySelector(':scope > h2')) continue;
      c.id = 'bloco-' + secao.id.slice(3).toLowerCase() + '-' + (++n);
    }
  }
}

// ---------- índice da página ----------

let assinaturaIndice = '';
let vigia = null;   // IntersectionObserver do bloco que está na tela

function irAoBloco(cartao) {
  cartao.scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'start' });
  cartao.classList.add('realce');
  setTimeout(() => cartao.classList.remove('realce'), 1600);
  // Leva o foco junto (teclado e leitor de tela): o título do bloco, sem pular a página de novo.
  const h = cartao.querySelector(':scope > h2');
  if (h) { h.tabIndex = -1; h.focus({ preventScroll: true }); }
}

function botaoDoIndice(cartao, classe) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = classe;
  b.dataset.bloco = cartao.id;
  b.textContent = cartao.querySelector(':scope > h2').textContent.trim();
  b.addEventListener('click', () => irAoBloco(cartao));
  return b;
}

/** Monta (ou tira) o índice da página aberta. Não refaz se os blocos são os mesmos (o observador chama muito). */
function montarIndice() {
  const secao = $(idDaAba(paginaAtual));
  // A Início e a Sofy não têm índice: páginas calmas, com poucos blocos e as portas no lugar do índice.
  const blocos = secao && !secao.classList.contains('hub') && paginaAtual !== 'inicio' && paginaAtual !== 'sofy' ? blocosDe(secao) : [];
  garantirIdsDosBlocos();
  const assinatura = paginaAtual + '|' + blocos.map((c) => c.id + ':' + c.querySelector(':scope > h2').textContent).join('|');
  if (assinatura === assinaturaIndice) return;
  assinaturaIndice = assinatura;
  document.querySelectorAll('.indice-pagina, .indice-chips').forEach((e) => e.remove());
  if (vigia) { vigia.disconnect(); vigia = null; }
  if (blocos.length < 2) return;   // um bloco só: o índice não ajuda

  // Computador: embaixo da página aberta na barra lateral (o botão .aba.sub dela).
  const aba = document.querySelector(`nav.abas .aba.sub[data-aba="${paginaAtual}"]`);
  if (aba) {
    const lista = document.createElement('div');
    lista.className = 'indice-pagina';
    lista.setAttribute('role', 'group');
    lista.setAttribute('aria-label', 'Nesta página');
    for (const c of blocos) lista.appendChild(botaoDoIndice(c, 'indice-item'));
    aba.after(lista);
  }
  // Celular: uma fileira de atalhos logo abaixo do título da página.
  const cabeca = secao.querySelector(':scope > .cabeca-pagina') || secao.querySelector(':scope > .titulo-pagina');
  if (cabeca) {
    const fileira = document.createElement('nav');
    fileira.className = 'indice-chips';
    fileira.setAttribute('aria-label', 'Nesta página');
    for (const c of blocos) fileira.appendChild(botaoDoIndice(c, 'chip-indice'));
    cabeca.after(fileira);
  }

  // Acende o bloco que está na faixa de cima da tela (20% a 35% da altura): é o que a pessoa está lendo.
  vigia = new IntersectionObserver((entradas) => {
    for (const e of entradas) {
      if (!e.isIntersecting) continue;
      for (const b of document.querySelectorAll('[data-bloco]')) {
        const ativo = b.dataset.bloco === e.target.id;
        b.classList.toggle('atual', ativo);
        if (ativo) b.setAttribute('aria-current', 'location'); else b.removeAttribute('aria-current');
        if (ativo && b.classList.contains('chip-indice')) b.scrollIntoView({ block: 'nearest', inline: 'nearest' });
      }
    }
  }, { rootMargin: '-20% 0px -65% 0px' });
  blocos.forEach((c) => vigia.observe(c));
}

// Cartões que os outros scripts criam depois (extras.js, addons.js...) entram no índice: observa a página aberta.
let esperaIndice = 0;
const pedirIndice = () => { clearTimeout(esperaIndice); esperaIndice = setTimeout(montarIndice, 120); };
const observador = new MutationObserver((mudancas) => {
  // O próprio índice mexe na página (a fileira do celular): isso não conta.
  if (mudancas.every((m) => m.target.closest?.('.indice-chips, .indice-pagina'))) return;
  pedirIndice();
});
function observarPaginaAberta() {
  observador.disconnect();
  const secao = $(idDaAba(paginaAtual));
  if (secao) observador.observe(secao, { childList: true, subtree: true, attributes: true, attributeFilter: ['hidden'] });
}

// ---------- rótulo da cruz ----------

/** "4 / 8" ao lado do voltar: a posição da página entre as irmãs, na ordem da barra lateral (a mesma da TV). */
function numerarPaginas() {
  const grupos = {};
  for (const [nome, p] of Object.entries(PAGINAS)) if (p.grupo) (grupos[p.grupo] = grupos[p.grupo] || []).push(nome);
  for (const [grupo, nomes] of Object.entries(grupos)) {
    nomes.forEach((nome, i) => {
      const voltar = document.querySelector(`#${idDaAba(nome)} .voltar-pagina[data-aba="${grupo}"]`);
      if (!voltar || voltar.querySelector('.posicao')) return;
      const pos = document.createElement('span');
      pos.className = 'posicao';
      pos.textContent = `${i + 1} / ${nomes.length}`;
      voltar.appendChild(pos);
    });
  }
}

// ---------- aba Tudo (celular) ----------

function permitida(nome) {
  return !paginasDoPapel.length || paginasDoPapel.includes(nome);
}

/** Monta a página Tudo copiando as listas de "Na TV" e "Configurações" (um lugar só para mudar cada linha). */
function montarTudo() {
  const secao = $('abaTudo');
  if (!secao || secao.dataset.montada) return;
  secao.dataset.montada = '1';
  garantirIdsDosBlocos();
  let paginas = 0, blocos = 0;
  const grupos = document.createElement('div');
  grupos.className = 'grupos-tudo';
  for (const origem of ['abaNatv', 'abaAjustes']) {
    const de = $(origem);
    const lista = de?.querySelector('.lista-hub');
    if (!lista) continue;
    const titulo = document.createElement('h2');
    titulo.className = 'titulo-grupo';
    titulo.textContent = de.querySelector('.titulo-pagina').textContent;
    const copia = lista.cloneNode(true);
    for (const linha of copia.querySelectorAll('.linha-hub')) {
      if (!permitida(linha.dataset.aba)) { linha.remove(); continue; }
      paginas++;
      const pagina = $(idDaAba(linha.dataset.aba));
      if (pagina) blocos += pagina.querySelectorAll('.cartao[id] > h2').length;
    }
    if (copia.children.length) grupos.append(titulo, copia);
  }
  $('contaPaginas').textContent = String(paginas);
  $('contaBlocos').textContent = String(blocos);
  secao.appendChild(grupos);
}

/** Na barra de baixo do celular: Apps acende na página Apps; Tudo, em qualquer página de Na TV ou Configurações. */
function acenderBarraDoCelular() {
  const grupo = PAGINAS[paginaAtual]?.grupo || paginaAtual;
  for (const b of document.querySelectorAll('nav.abas .so-celular')) {
    const ativa = b.dataset.aba === 'apps' ? paginaAtual === 'apps'
      : paginaAtual === 'tudo' || (paginaAtual !== 'apps' && (grupo === 'natv' || grupo === 'ajustes'));
    b.classList.toggle('ativa', ativa);
    if (ativa) b.setAttribute('aria-current', 'page'); else b.removeAttribute('aria-current');
  }
  for (const b of document.querySelectorAll('nav.abas .so-celular')) {
    b.hidden = paginasDoPapel.length > 0 && (b.dataset.aba === 'tudo'
      ? !(permitida('natv') || permitida('ajustes') || PAGINAS_TUDO.some(permitida)) : !permitida(b.dataset.aba));
  }
}
const PAGINAS_TUDO = Object.keys(PAGINAS).filter((n) => ['natv', 'ajustes'].includes(PAGINAS[n].grupo));

$('btnBuscaTudo')?.addEventListener('click', () => abrirBusca());

document.addEventListener('aba', () => {
  acenderBarraDoCelular();
  if (paginaAtual === 'tudo') montarTudo();
  observarPaginaAberta();
  pedirIndice();
});
numerarPaginas();
acenderBarraDoCelular();
observarPaginaAberta();
pedirIndice();
