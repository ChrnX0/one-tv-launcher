// Busca rápida (10/11/2026): Ctrl+K (ou "/") abre uma caixa que acha aba, app ou ação pelo nome.
// Enter faz a primeira; setas escolhem outra. Usa as globais do app.js ($, chamar, apps, mudarAba, abrirPermissoes).
// Texto vindo da TV (nome de app) entra por textContent.
'use strict';

/** Tira acento e caixa: "Saúde" acha com "saude". */
const semAcento = (t) => (t || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();

// Ações fixas: teclas que valem em qualquer lugar. O rótulo é o que a pessoa digitaria.
const ACOES_BUSCA = [
  ['Acordar a TV', 'ligar', '☀'], ['Dormir (apagar a TV)', 'apagar', '☾'], ['Mudo', 'mudo', '⊘'],
  ['Aumentar o volume', 'volume+', '+'], ['Baixar o volume', 'volume-', '−'], ['Tocar/pausar', 'play', '▶'],
  ['Voltar', 'voltar', '↩'], ['Tela inicial da TV', 'inicio', '⌂'],
];

let itensBusca = [];
let escolhido = 0;

/** Monta a lista de tudo que dá para achar: abas (da navegação), ações e apps (se a lista já veio). */
function todosOsItens() {
  const r = [];
  for (const b of document.querySelectorAll('nav.abas .aba')) {
    if (b.dataset.aba === 'mais') continue;
    const nome = b.querySelector('span:not(.ic)')?.textContent || b.textContent;
    r.push({ tipo: 'Aba', nome, icone: b.querySelector('.ic')?.textContent || '›', fazer: () => mudarAba(b.dataset.aba) });
  }
  for (const [nome, tecla, icone] of ACOES_BUSCA) {
    r.push({ tipo: 'Ação', nome, icone, fazer: async () => { await chamar('POST', '/api/tela/tecla', { tecla }); aviso(nome + ': feito.', 'ok'); } });
  }
  // Um app = uma linha: Enter abre na TV (se tiver tela); o botão "Detalhes" da linha abre a ficha no painel.
  for (const a of (typeof apps !== 'undefined' ? apps : [])) {
    const detalhes = () => { mudarAba('apps'); abrirPermissoes(a); };
    r.push(a.abrivel
      ? { tipo: 'Abrir na TV', nome: a.nome, pacote: a.pacote, detalhes, fazer: async () => {
        await chamar('POST', '/api/apps/' + encodeURIComponent(a.pacote) + '/abrir', {}); aviso(a.nome + ' aberto na TV.', 'ok');
      } }
      : { tipo: 'Detalhes', nome: a.nome, pacote: a.pacote, fazer: detalhes });
  }
  return r;
}

/** Nota de quanto o item combina com o texto: começo do nome vale mais que meio; sem combinar = -1. */
function nota(item, q) {
  if (!q) return item.tipo === 'Aba' ? 1 : -1;   // caixa vazia: só as abas, como atalho de navegação
  const n = semAcento(item.nome), p = semAcento(item.pacote || '');
  if (n.startsWith(q)) return 3;
  if (n.split(/\s+/).some((w) => w.startsWith(q))) return 2;
  if (n.includes(q) || p.includes(q)) return 1;
  return -1;
}

function desenharBusca() {
  const q = semAcento($('campoBusca').value.trim());
  const todos = todosOsItens();
  itensBusca = todos.map((it, i) => ({ it, n: nota(it, q), i })).filter((x) => x.n >= 0)
    .sort((a, b) => b.n - a.n || a.i - b.i).slice(0, 12).map((x) => x.it);
  escolhido = 0;
  const lista = $('resultadosBusca');
  lista.replaceChildren();
  itensBusca.forEach((it, i) => {
    const li = document.createElement('li');
    li.id = 'busca-' + i;
    li.setAttribute('role', 'option');
    const ic = document.createElement('span'); ic.className = 'ic';
    if (it.pacote) {
      const img = document.createElement('img'); img.alt = ''; img.loading = 'lazy';
      img.onerror = () => { ic.textContent = (it.nome || '?').trim().charAt(0).toUpperCase(); ic.classList.add('letra'); };   // sem ícone: a inicial
      img.src = '/api/apps/' + encodeURIComponent(it.pacote) + '/icone';
      ic.appendChild(img);
    } else ic.textContent = it.icone;
    const nome = document.createElement('span'); nome.className = 'nome'; nome.textContent = it.nome;
    const tipo = document.createElement('span'); tipo.className = 'tipo'; tipo.textContent = it.tipo;
    li.append(ic, nome, tipo);
    if (it.detalhes) {
      const d = document.createElement('button');
      d.type = 'button'; d.className = 'busca-detalhes'; d.textContent = 'Detalhes';
      d.addEventListener('click', (ev) => { ev.stopPropagation(); fecharBusca(); it.detalhes(); });
      li.appendChild(d);
    }
    li.addEventListener('mousemove', () => marcar(i));
    li.addEventListener('click', () => executarBusca(i));
    lista.appendChild(li);
  });
  if (!itensBusca.length) {
    const li = document.createElement('li'); li.className = 'vazio';
    li.textContent = (typeof apps !== 'undefined' && apps.length) ? 'Nada com esse nome.' : 'Nada com esse nome. (Os apps aparecem aqui depois que a aba Apps carrega uma vez.)';
    lista.appendChild(li);
  }
  marcar(0);
}

function marcar(i) {
  escolhido = i;
  for (const [j, li] of [...$('resultadosBusca').children].entries()) li.classList.toggle('marcado', j === i);
  const li = $('busca-' + i);
  if (li) { li.scrollIntoView({ block: 'nearest' }); $('campoBusca').setAttribute('aria-activedescendant', li.id); }
}

async function executarBusca(i) {
  const it = itensBusca[i];
  if (!it) return;
  fecharBusca();
  try { await it.fazer(); } catch (e) { aviso(textoErro(e), 'erro'); }
}

function abrirBusca() {
  if ($('telaPainel').hidden) return;
  const d = $('caixaBusca');
  if (d.open) return;
  $('campoBusca').value = '';
  d.showModal();
  desenharBusca();
  $('campoBusca').focus();
  // A lista de apps ainda não veio (o painel abriu em outra aba)? Pede em segundo plano e redesenha.
  if (typeof apps !== 'undefined' && !apps.length) {
    chamar('GET', '/api/apps').then((r) => { if (!apps.length) { apps = r.apps || []; if (d.open) desenharBusca(); } }).catch(() => {});
  }
}

function fecharBusca() { if ($('caixaBusca').open) $('caixaBusca').close(); }

$('campoBusca').addEventListener('input', desenharBusca);
$('campoBusca').addEventListener('keydown', (ev) => {
  const n = itensBusca.length;
  if (ev.key === 'ArrowDown') { ev.preventDefault(); if (n) marcar((escolhido + 1) % n); }
  else if (ev.key === 'ArrowUp') { ev.preventDefault(); if (n) marcar((escolhido - 1 + n) % n); }
  else if (ev.key === 'Enter') { ev.preventDefault(); executarBusca(escolhido); }
  // Campo "search": o 1º Esc do navegador só limpa o texto e não fecha a caixa. Aqui o Esc sempre fecha.
  else if (ev.key === 'Escape') { ev.preventDefault(); fecharBusca(); }
});
// Clique fora da caixa (no fundo escuro do <dialog>) fecha.
$('caixaBusca').addEventListener('click', (ev) => { if (ev.target === $('caixaBusca')) fecharBusca(); });
$('btnBusca').addEventListener('click', abrirBusca);

document.addEventListener('keydown', (ev) => {
  const digitando = /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement?.tagName || '') || document.activeElement?.isContentEditable;
  if ((ev.key === 'k' || ev.key === 'K') && (ev.ctrlKey || ev.metaKey)) { ev.preventDefault(); if ($('modal').hidden && !$('modalPerm').hidden) fecharPermissoes(); abrirBusca(); }
  else if (ev.key === '/' && !digitando && !document.querySelector('dialog[open]') && $('modal').hidden) { ev.preventDefault(); if (!$('modalPerm').hidden) fecharPermissoes(); abrirBusca(); }
});
