// Painel no navegador, servido pelo próprio launcher (PainelWeb.kt). Sem framework e sem innerHTML com dados:
// nome de app vem do app (qualquer um pode instalar um app com nome "<script>"), então todo texto entra por
// textContent, que o navegador nunca interpreta como HTML.
'use strict';

const $ = (id) => document.getElementById(id);
let apps = [];

// ---------- chamadas ao launcher ----------

/** Erro que pede o PIN de novo (ação sem volta). */
class PrecisaPin extends Error {}

async function chamar(metodo, caminho, corpo, corpoCru) {
  // X-Painel: um site de fora não consegue mandar este cabeçalho (o painel não libera CORS) — barra CSRF.
  const opcoes = { method: metodo, headers: { 'X-Painel': '1' }, credentials: 'same-origin', cache: 'no-store' };
  if (corpoCru !== undefined) {
    opcoes.headers['Content-Type'] = 'application/json';
    opcoes.body = corpoCru;
  } else if (corpo !== undefined) {
    opcoes.headers['Content-Type'] = 'application/json';
    opcoes.body = JSON.stringify(corpo);
  }
  let resp;
  const fim = ocupado();
  try {
    resp = await fetch(caminho, opcoes);
  } catch (e) {
    fim();
    // O navegador só diz "Failed to fetch"/"Load failed": a porta está fechada ou a TV saiu da rede.
    throw new Error('Não consegui falar com a TV. Confira se ela está ligada e se o painel está ligado nas Configurações do launcher.');
  }
  const dados = await resp.json().catch(() => ({}));
  fim();
  // 401 em /api/entrar e /api/confirmar é "PIN errado" (a sessão continua); nas outras rotas é sessão expirada.
  if (resp.status === 401 && caminho !== '/api/entrar' && caminho !== '/api/confirmar') {
    mostrarEntrar('Sessão expirada: entre de novo.');
    throw new Error('Sessão expirada: entre de novo.');
  }
  if (resp.status === 403 && dados.precisaPin) throw new PrecisaPin(dados.erro);
  if (!resp.ok) { const e = new Error(erroAmigavel(resp.status, dados.erro)); e.dados = dados; e.tecnico = dados.erro; e.status = resp.status; throw e; }
  return dados;
}

/**
 * Barra fina de "carregando" no topo. Só aparece se o pedido passar de 350 ms (pedido rápido não pisca nada).
 * Devolve a função que encerra este pedido; a barra some quando não sobra nenhum pendente.
 */
let pendentes = 0;
let relogioOcupado = null;
function ocupado() {
  pendentes++;
  if (!relogioOcupado) relogioOcupado = setTimeout(() => document.body.classList.add('ocupado'), 350);
  let feito = false;
  return () => {
    if (feito) return;
    feito = true;
    if (--pendentes > 0) return;
    clearTimeout(relogioOcupado); relogioOcupado = null;
    document.body.classList.remove('ocupado');
  };
}

/**
 * Troca o texto cru do servidor por uma frase que a família entende. As mensagens que a TV já escreve em
 * português ("essa foto não existe") passam iguais, só com a 1ª letra maiúscula; as técnicas (nome de exceção,
 * código HTTP) viram uma explicação com o que fazer. O texto original fica em e.tecnico para o console.
 */
function erroAmigavel(status, texto) {
  const t = (texto || '').trim();
  if (status >= 500 && (!t || /^erro na TV:/i.test(t)))
    return 'A TV não conseguiu fazer isso agora. Tente de novo em alguns segundos; se repetir, veja a aba Registro.';
  if (status === 404 && (!t || t === 'não existe')) return 'Isso não existe mais na TV (talvez já tenha sido apagado).';
  if (/JSON inválido|use POST|método não suportado/i.test(t)) return 'O painel mandou um pedido que a TV não entendeu. Recarregue a página e tente de novo.';
  if (status === 421) return 'Abra o painel pelo endereço de sempre (o IP da TV ou tv:8080).';
  if (!t) return status === 0 ? 'Sem resposta da TV.' : `A TV recusou o pedido (código ${status}).`;
  return t.charAt(0).toUpperCase() + t.slice(1);
}

/**
 * O texto para mostrar de qualquer erro pego num catch. Erro do pedido (chamar) já vem amigável; erro de
 * programação do painel (TypeError: "Cannot read properties of undefined") vira uma frase com o que fazer,
 * e o detalhe vai para o console de quem quiser investigar.
 */
function textoErro(e) {
  if (!e) return 'Algo deu errado.';
  // fetch() que não chegou à TV também é TypeError ("Failed to fetch", "Load failed", "NetworkError").
  if (e instanceof TypeError && /fetch|load failed|network/i.test(e.message || ''))
    return 'Não consegui falar com a TV. Confira se ela está ligada e na mesma rede.';
  if (e instanceof TypeError || e instanceof ReferenceError || e instanceof SyntaxError || e instanceof RangeError) {
    console.warn('painel:', e);
    return 'A TV respondeu de um jeito que esta tela não esperava. Recarregue a página; se continuar, '
      + 'o launcher da TV e o painel podem estar em versões diferentes.';
  }
  return e.message || String(e);
}

function mostrar(el, texto) { el.textContent = texto; el.hidden = !texto; }

// ---------- entrar ----------

function mostrarEntrar(aviso) {
  fecharModalAberto();
  mostrar($('avisoEntrar'), aviso || '');
  $('telaPainel').hidden = true;
  $('telaEntrar').hidden = false;
  $('campoPin').value = '';
  $('campoPin').focus();
}

$('formEntrar').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const btn = $('btnEntrar');
  btn.disabled = true;
  mostrar($('erroEntrar'), '');
  const pin = $('campoPin').value;
  if (!pinValido(pin)) { mostrar($('erroEntrar'), 'O PIN tem de 6 a 12 números.'); btn.disabled = false; return; }
  try {
    await chamar('POST', '/api/entrar', { pin });
    mostrar($('avisoEntrar'), '');
    await abrirPainel();
    enviarCompartilhado();   // ideia 55: o compartilhar chegou antes do login
  } catch (e) {
    let msg = textoErro(e);
    if (e.dados && e.dados.restantes) msg += ` (mais ${e.dados.restantes} tentativa(s) antes de bloquear)`;
    mostrar($('erroEntrar'), msg);
    $('campoPin').value = '';
  } finally {
    btn.disabled = false;
  }
});

$('btnSair').addEventListener('click', async () => {
  try { await chamar('POST', '/api/sair'); } catch (e) { /* tanto faz */ }
  mostrarEntrar();
});

async function abrirPainel() {
  $('telaEntrar').hidden = true;
  $('telaPainel').hidden = false;
  await atualizarEstado();
  mudarAba(location.hash.slice(1) || 'inicio', false);
}

let ultimoEstado = {};
async function atualizarEstado() {
  const e = await chamar('GET', '/api/estado');
  if (!e.logado) { mostrarEntrar(); return; }
  ultimoEstado = e;
  aplicarContraste(e.altoContraste);
  aplicarPapel(e);
  $('nomeAparelho').textContent = e.aparelho;
  document.title = e.aparelho;
  aplicarNaTela(e.naTela);
  atualizarOutrasTvs().catch(() => {});
}

/** Mostra "o que está na TV" no topo e no Início (vem do /api/estado e dos eventos ao vivo, ui.js). */
function aplicarNaTela(naTela) {
  if (!naTela) return;
  ultimoEstado.naTela = naTela;
  $('naTela').textContent = 'TV: ' + naTela;
  $('inicioNaTela').textContent = naTela;
  $('inicioNaTela').classList.toggle('dormindo', naTela === 'em espera');
}

// Outras TVs da casa com o painel ligado (achadas por mDNS na rede). Cada uma tem o próprio PIN: o link só leva até lá.
async function atualizarOutrasTvs() {
  const r = await chamar('GET', '/api/tvs');
  const alvo = $('outrasTvs');
  alvo.textContent = '';
  if (!r.outras || !r.outras.length) { alvo.hidden = true; return; }
  alvo.append('Outras TVs: ');
  r.outras.forEach((t, i) => {
    if (i) alvo.append(' · ');
    const a = document.createElement('a');
    a.href = t.url;
    a.textContent = `${t.nome} (${t.id})`;   // textContent: o nome vem da rede, nunca vira HTML
    alvo.append(a);
  });
  alvo.hidden = false;
}

// Atualiza "o que está na TV" a cada 30 s, só com a aba do navegador visível (custo zero com a aba escondida).
setInterval(() => {
  if (document.visibilityState === 'visible' && !$('telaPainel').hidden) atualizarEstado().catch(() => {});
}, 30000);

// ---------- abas ----------

/**
 * O que carregar ao abrir cada aba. Aba nova: basta a <section id="abaNome"> + um botão data-aba="nome"; se precisar
 * carregar algo, ou põe aqui, ou escuta o evento "aba" (document.addEventListener('aba', e => e.detail === 'nome' ...)),
 * como o extras.js faz. Assim um PR novo não precisa mexer nesta função (menos conflito entre PRs).
 * Painel v3 (16/11/2026): as chaves daqui são os nomes ANTIGOS das abas ("config", "extras"...). Cada página nova diz
 * em PAGINAS quais deles ela acorda, porque os cartões de uma aba antiga foram para páginas diferentes.
 */
const AO_ABRIR = {
  apps: () => carregarApps(),
  tv: () => carregarTv(),
  controle: () => atualizarFoto(),
  clima: () => carregarClima(),
  desligar: () => carregarAutoDesligar(),
  saude: () => carregarSaude(),
  fotos: () => { carregarFotos(); carregarPerfis(); },
  rede: () => carregarRede(),
  claude: () => carregarMcp(),
  roteador: () => carregarRoteador(),
  registro: () => { carregarRegistro(); if (typeof carregarChaves === 'function') carregarChaves(); },   // + digital (chave.js)
  backup: () => { carregarCopias(); carregarNoturno(); acompanharDrive(); },
  config: () => carregarConfig(),
  arquivos: () => abrirPasta(pastaAtual),
};

/**
 * Painel v3 (16/11/2026): 5 seções na navegação (Início, Controle, Na TV, Casa e família, Ajustes) e páginas dentro
 * delas. [grupo] = a seção que fica acesa na navegação; [acorda] = os nomes antigos de aba cujo carregamento roda (AO_ABRIR
 * acima e o evento "aba" que extras.js, ideias.js, addons.js... escutam). Uma página com cartões que vieram do antigo
 * Extras acorda "extras", e assim os scripts deles continuam iguais.
 */
const PAGINAS = {
  inicio: { acorda: ['inicio'] },
  controle: { acorda: ['controle'] },
  sofy: { acorda: [] },   // recados da Sofy (sofy-page.js, estudo do painel PR 5)
  natv: { acorda: [] },
  tudo: { acorda: [] },   // celular: Na TV + Configurações numa tela (navigation.js)
  apps: { grupo: 'natv', acorda: ['apps'] },
  loja: { grupo: 'natv', acorda: ['extras'] },
  arquivos: { grupo: 'natv', acorda: ['arquivos'] },
  // Configurações: as mesmas abas da TV, na mesma ordem (ConfigLauncherActivity.abas). Pedido do Paulo (16/11/2026):
  // "o painel web tem que ser muito parecido com o painel de configurações do launcher na TV".
  ajustes: { acorda: [] },
  telainicial: { grupo: 'ajustes', acorda: ['config', 'clima', 'extras'] },
  tvfilmes: { grupo: 'ajustes', acorda: ['tv', 'config', 'extras', 'fotos'] },
  familia: { grupo: 'ajustes', acorda: ['config', 'extras'] },
  energia: { grupo: 'ajustes', acorda: ['config', 'desligar', 'fotos', 'extras'] },
  casa: { grupo: 'ajustes', acorda: ['extras'] },
  remoto: { grupo: 'ajustes', acorda: ['config', 'extras', 'claude', 'ia', 'registro'] },
  sistema: { grupo: 'ajustes', acorda: ['saude', 'extras', 'backup'] },
  avancado: { grupo: 'ajustes', acorda: ['rede', 'roteador', 'config', 'extras'] },
};
/** Nomes antigos (links salvos, avisos do celular, QR das Configurações da TV de versões anteriores). */
const APELIDOS = {
  extras: 'ajustes', config: 'ajustes', mais: 'ajustes', claude: 'remoto', ia: 'remoto', registro: 'remoto', roteador: 'avancado',
  rede: 'avancado', saude: 'sistema', backup: 'sistema', tv: 'tvfilmes', fotos: 'energia',
};

/** "arquivos" -> "abaArquivos" (o id da <section>). */
const idDaAba = (nome) => 'aba' + nome.charAt(0).toUpperCase() + nome.slice(1);

let paginaAtual = 'inicio';
/** A página aberta tem os cartões da aba antiga [antiga]? (Para os relógios que só atualizam com a aba à vista.) */
const abaAberta = (antiga) => !$('telaPainel').hidden && (PAGINAS[paginaAtual]?.acorda || []).includes(antiga);

/**
 * Mostra uma página e esconde as outras. [historico]=false quando veio do botão Voltar do navegador (não empilha de
 * novo). O endereço ganha "#nome": recarregar a página ou mandar o link abre na mesma página.
 */
// Elevação 18.1: família e visita veem só as páginas do papel (a TV também barra cada pedido, PapeisPainel.kt).
let paginasDoPapel = [];
function aplicarPapel(e) {
  paginasDoPapel = Array.isArray(e.paginas) ? e.paginas : [];
  document.body.dataset.papel = e.papel || 'dono';
  for (const b of document.querySelectorAll('.aba[data-aba]'))
    b.hidden = paginasDoPapel.length > 0 && !paginasDoPapel.includes(b.dataset.aba);
}

/** Links que abrem uma página já num cartão (QR "Adicionar add-on" da TV, 18/11/2026): nome -> [página, id do cartão]. */
const ANCORAS = { addons: ['tvfilmes', 'cartaoAddons'] };

function mudarAba(nome, historico = true) {
  const ancora = ANCORAS[nome];
  if (ancora) nome = ancora[0];
  nome = APELIDOS[nome] || nome;
  if (paginasDoPapel.length && !paginasDoPapel.includes(nome) && nome !== 'tudo') nome = paginasDoPapel[0];
  if (!nome || !PAGINAS[nome] || !$(idDaAba(nome))) nome = 'inicio';
  paginaAtual = nome;
  const secao = PAGINAS[nome].grupo || nome;
  for (const b of document.querySelectorAll('.aba')) {
    // Seção (Início, Controle, Na TV, Configurações) acende pelo grupo; página da coluna do computador, só ela.
    const ativa = b.dataset.aba === (b.classList.contains('sub') ? nome : secao);
    b.classList.toggle('ativa', ativa);
    if (ativa) b.setAttribute('aria-current', 'page'); else b.removeAttribute('aria-current');
  }
  for (const s of document.querySelectorAll('.painel-aba')) s.hidden = s.id !== idDaAba(nome);
  if (nome !== 'controle') pararFoto();
  const acorda = PAGINAS[nome].acorda;
  // Primeiro um evento com o nome da própria página (quem escuta "sair de X" para o que estava fazendo), depois os antigos.
  if (!acorda.includes(nome)) document.dispatchEvent(new CustomEvent('aba', { detail: nome }));
  for (const antiga of acorda) {
    const carregar = AO_ABRIR[antiga];
    if (carregar) Promise.resolve().then(carregar).catch((e) => console.warn(antiga, e));
    document.dispatchEvent(new CustomEvent('aba', { detail: antiga }));
  }
  if (historico && location.hash !== '#' + nome) history.pushState(null, '', '#' + nome);
  const cartao = ancora && nome === ancora[0] ? $(ancora[1]) : null;
  if (cartao) requestAnimationFrame(() => cartao.scrollIntoView({ block: 'start' })); else window.scrollTo(0, 0);
}
// Um ouvinte só, no documento: vale também para os botões criados depois (a tela "Mais", os atalhos do Início).
document.addEventListener('click', (ev) => {
  const b = ev.target.closest('[data-aba]');
  if (b && !$('telaPainel').hidden) { ev.preventDefault(); mudarAba(b.dataset.aba); }
});
// Toque num aviso do celular com o painel já aberto (sw.js, ideia 38): vai para a aba que resolve o aviso.
if ('serviceWorker' in navigator) navigator.serviceWorker.addEventListener('message', (ev) => {
  if (ev.data && typeof ev.data.aba === 'string' && !$('telaPainel').hidden) mudarAba(ev.data.aba);
});
window.addEventListener('popstate', () => { if (!$('telaPainel').hidden && !location.hash.startsWith('#qr=')) mudarAba(location.hash.slice(1), false); });

// ---------- confirmação (e PIN de novo) ----------

function pinValido(pin) { return /^[0-9]{6,12}$/.test(pin); }

let fecharModalAberto = () => {};
let modalAtual = 0;   // cada confirmar() ganha um número: resposta atrasada de um quadro antigo não mexe no novo

/**
 * Pergunta antes de uma ação sem volta. Se o launcher responder que precisa do PIN (passaram 5 min desde a
 * última vez), o mesmo quadro mostra o campo de PIN, confirma no launcher e tenta a ação de novo.
 */
/** [perigo]=false para ação que se desfaz (ocultar, parar): o botão de confirmar fica azul em vez de vermelho. */
function confirmar(titulo, texto, rotuloOk, fazer, perigo = true) {
  fecharModalAberto();
  const meu = ++modalAtual;
  return new Promise((resolve) => {
    $('tituloModal').textContent = titulo;
    $('textoModal').textContent = texto;
    $('btnOkModal').textContent = rotuloOk;
    $('btnOkModal').className = perigo ? 'botao-perigo' : 'botao-primario';
    $('pinModal').hidden = true;
    $('pinModal').value = '';
    mostrar($('erroModal'), '');
    mostrar($('progressoModal'), '');
    $('btnOkModal').disabled = false;
    $('btnCancelarModal').disabled = false;
    $('modal').hidden = false;
    $('btnOkModal').focus();

    const fechar = (resultado) => {
      if (meu !== modalAtual) return;
      modalAtual++;
      fecharModalAberto = () => {};
      $('modal').hidden = true;
      $('formModal').onsubmit = null;
      $('btnCancelarModal').onclick = null;
      resolve(resultado);
    };
    fecharModalAberto = () => fechar(null);
    $('btnCancelarModal').onclick = () => fechar(null);
    $('formModal').onsubmit = async (ev) => {
      ev.preventDefault();
      if (meu !== modalAtual) return;
      const pedirPin = !$('pinModal').hidden;
      if (pedirPin && !pinValido($('pinModal').value)) {
        mostrar($('erroModal'), 'Digite o PIN (6 a 12 números).');
        return;
      }
      // Enquanto o pedido roda, nem confirmar de novo nem cancelar: a ação já foi para a TV e não para no meio.
      $('btnOkModal').disabled = true;
      $('btnCancelarModal').disabled = true;
      mostrar($('erroModal'), '');
      try {
        if (pedirPin) {
          await chamar('POST', '/api/confirmar', { pin: $('pinModal').value });
          $('pinModal').hidden = true;   // confirmado: uma nova tentativa da ação não reenvia o PIN
        }
        const r = await fazer();
        fechar(r);
      } catch (e) {
        if (meu !== modalAtual) return;
        if (e instanceof PrecisaPin) {
          $('pinModal').hidden = false;
          $('pinModal').value = '';
          $('pinModal').focus();
          mostrar($('erroModal'), 'Por segurança, digite o PIN de novo.');
        } else {
          let msg = textoErro(e);
          if (e.dados && e.dados.restantes) msg += ` (mais ${e.dados.restantes} tentativa(s) antes de bloquear)`;
          mostrar($('erroModal'), msg);
          $('pinModal').value = '';
        }
      } finally {
        if (meu === modalAtual) {
          $('btnOkModal').disabled = false;
          $('btnCancelarModal').disabled = false;
        }
      }
    };
  });
}

/** A TV está em uso agora? Devolve o nome do que está na tela, ou '' se está em espera ou na tela inicial. */
async function tvEmUso() {
  await atualizarEstado().catch(() => {});
  const naTela = ultimoEstado.naTela || '';
  return (naTela && naTela !== 'em espera' && naTela !== 'na tela inicial' && naTela !== 'ligada') ? naTela : '';
}

// ---------- aplicativos ----------

/** As prateleiras (ids de AppShelf.Shelf, na ordem da TV) e o nome de cada uma. */
const PRATELEIRAS = new Map([
  ['entertainment', 'Filmes e séries'], ['video', 'Vídeos'], ['sports', 'Esportes'], ['kids', 'Infantil'],
  ['music_audio', 'Música e áudio'], ['news', 'Notícias'], ['games', 'Jogos'], ['education', 'Educação'],
  ['health_fitness', 'Saúde e exercício'], ['lifestyle', 'Estilo de vida'], ['tools', 'Ferramentas'],
  ['system', 'Sistema'], ['other', 'Outros'],
]);

let usoHoje = {};
async function carregarApps() {
  mostrar($('statusApps'), 'Carregando…');
  try {
    chamar('GET', '/api/uso').then((u) => { usoHoje = u.minutos || {}; desenharApps(); }).catch(() => {});
    const r = await chamar('GET', '/api/apps');
    apps = r.apps.sort((a, b) => a.nome.localeCompare(b.nome, 'pt-BR'));
    desenharApps();
  } catch (e) {
    mostrar($('statusApps'), 'Não consegui listar: ' + textoErro(e));
  }
}

$('busca').addEventListener('input', desenharApps);
// "apps do sistema" vem marcado (todos os apps) e o navegador lembra a escolha.
try { if (localStorage.getItem('mostrarSistema') === '0') $('mostrarSistema').checked = false; } catch (e) { /* sem armazenamento */ }
$('mostrarSistema').addEventListener('change', () => {
  try { localStorage.setItem('mostrarSistema', $('mostrarSistema').checked ? '1' : '0'); } catch (e) { /* tanto faz */ }
  desenharApps();
});

/** Sem "apps do sistema": só os instalados por você, os que têm tela de abrir e os ocultos (para poder reexibir). */
function visivelNaLista(a) {
  if ($('mostrarSistema').checked) return true;
  return !a.sistema || a.abrivel || a.oculto;
}

function selo(texto, classe) {
  const s = document.createElement('span');
  s.className = 'selo' + (classe ? ' ' + classe : '');
  s.textContent = texto;
  return s;
}

function desenharApps() {
  const termo = $('busca').value.trim().toLowerCase();
  const lista = $('listaApps');
  lista.replaceChildren();
  const filtrados = apps.filter((a) => visivelNaLista(a) &&
    (!termo || a.nome.toLowerCase().includes(termo) || a.pacote.toLowerCase().includes(termo)));
  mostrar($('statusApps'), filtrados.length ? '' : 'Nenhum app.');
  const sistema = apps.filter((a) => a.sistema).length;
  $('contagemApps').textContent = `${filtrados.length} de ${apps.length} apps na TV (${apps.length - sistema} instalados por você, ${sistema} do sistema)`;
  // Estudo do painel (PR 5): como na TV, os escondidos ficam numa prateleira à parte que só abre com o PIN, e os
  // congelados (desativados, fora os escondidos) numa prateleira com Descongelar. Nos outros lugares eles não aparecem.
  const escondidos = filtrados.filter((a) => a.oculto);
  const congelados = filtrados.filter((a) => !a.oculto && a.desativado);
  const resto = filtrados.filter((a) => !a.oculto && !a.desativado);
  // Com busca: lista corrida (os escondidos só com a prateleira aberta pelo PIN).
  if (termo) {
    for (const a of [...resto, ...congelados, ...(escondidosAbertos ? escondidos : [])]) lista.appendChild(linhaApp(a));
  } else {
    // Sem busca: em prateleiras, na mesma ordem e com os mesmos nomes da tela Todos os apps da TV (AppShelf, 20/11/2026).
    for (const [id, nome] of PRATELEIRAS) {
      const doGrupo = resto.filter((a) => (PRATELEIRAS.has(a.categoria) ? a.categoria : 'other') === id);
      if (!doGrupo.length) continue;
      lista.appendChild(tituloPrateleira(nome));
      for (const a of doGrupo) lista.appendChild(linhaApp(a));
    }
    if (congelados.length) {
      lista.appendChild(tituloPrateleira('Congelados'));
      for (const a of congelados) lista.appendChild(linhaApp(a));
    }
  }
  if (escondidos.length) {
    lista.appendChild(tituloPrateleira('Escondidos'));
    if (escondidosAbertos) { for (const a of escondidos) lista.appendChild(linhaApp(a)); return; }
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'botao-secundario abrir-escondidos';
    b.textContent = 'Mostrar os escondidos (pede o PIN)';
    b.addEventListener('click', async () => {
      const ok = await confirmar('Mostrar os escondidos', 'Os apps escondidos aparecem aqui até você fechar o painel.', 'Mostrar',
        () => chamar('GET', '/api/elevada'), false);
      if (ok) { escondidosAbertos = true; desenharApps(); }
    });
    lista.appendChild(b);
  }
}

let escondidosAbertos = false;
function tituloPrateleira(nome) {
  const t = document.createElement('h3');
  t.className = 'titulo-prateleira';
  t.textContent = nome;
  return t;
}


function linhaApp(a) {
  const linha = document.createElement('div');
  linha.className = 'linha-app';
  if (modoLote) {
    // Seleção de vários: app protegido (o próprio launcher, o launcherx…) não entra em nenhuma ação em lote.
    const c = document.createElement('input');
    c.type = 'checkbox'; c.className = 'marca-lote';
    c.setAttribute('aria-label', 'Selecionar ' + a.nome);
    // Essencial (Play Services, teclado, a HOME…) também fica fora: em lote é fácil marcar sem ler a ficha.
    c.disabled = !!a.protegido || a.risco === 'essencial';
    if (c.disabled) c.title = 'Este app não entra em ações em lote (abra Detalhes)';
    c.checked = selecionados.has(a.pacote);
    c.addEventListener('change', () => { if (c.checked) selecionados.add(a.pacote); else selecionados.delete(a.pacote); atualizarLote(); });
    linha.appendChild(c);
    linha.classList.toggle('marcada', c.checked);
  }

  const img = document.createElement('img');
  img.loading = 'lazy';
  img.alt = '';
  img.onerror = () => { img.style.visibility = 'hidden'; };   // sem ícone: quadrado vazio, sem o símbolo de imagem quebrada
  img.src = '/api/apps/' + encodeURIComponent(a.pacote) + '/icone';
  linha.appendChild(img);

  const info = document.createElement('div');
  info.className = 'info';
  const nome = document.createElement('span');
  nome.className = 'nome';
  nome.textContent = a.nome;
  const pacote = document.createElement('span');
  pacote.className = 'pacote';
  pacote.textContent = a.pacote + (a.versao ? ' · ' + a.versao : '') + (usoHoje[a.pacote] ? ` · hoje: ${usoHoje[a.pacote]} min` : '');
  const selos = document.createElement('div');
  selos.className = 'selos';
  if (a.oculto) selos.appendChild(selo('oculto', 'oculto'));
  if (a.desativado) selos.appendChild(selo('desativado'));
  if (a.sistema) selos.appendChild(selo('sistema'));
  if (a.ocultoPeloLauncher) selos.appendChild(selo('o launcher oculta sozinho'));
  if (a.risco) selos.appendChild(selo(a.risco, 'risco-' + a.risco.replace(/\s+/g, '-')));
  info.append(nome, pacote, selos);
  linha.appendChild(info);

  const acoes = document.createElement('div');
  acoes.className = 'acoes';
  const bp = document.createElement('button');
  bp.className = 'botao-secundario';
  bp.textContent = 'Detalhes';
  bp.addEventListener('click', () => abrirPermissoes(a));
  acoes.appendChild(bp);
  if (a.protegido) {
    const p = document.createElement('span');
    p.className = 'protegido';
    p.textContent = 'protegido';
    acoes.appendChild(p);
  } else {
    if (a.podeOcultar) {
      const b = document.createElement('button');
      b.className = 'botao-secundario';
      b.textContent = a.oculto ? 'Mostrar' : 'Ocultar';
      b.addEventListener('click', () => alternarOculto(a, b));
      acoes.appendChild(b);
    }
    if (a.desativado && !a.oculto) {
      // Congelado (pm disable-user): descongelar devolve o app com os dados, como estava (Congelar.kt).
      const b = document.createElement('button');
      b.className = 'botao-secundario';
      b.textContent = 'Descongelar';
      b.addEventListener('click', async () => {
        b.disabled = true;
        try {
          await chamar('POST', '/api/apps/' + encodeURIComponent(a.pacote) + '/descongelar', {});
          a.desativado = false;
          aviso('Descongelado.', 'ok');
          desenharApps();
        } catch (e) { aviso(textoErro(e), 'erro'); b.disabled = false; }
      });
      acoes.appendChild(b);
    }
    if (a.podeDesinstalar) {
      const b = document.createElement('button');
      b.className = 'botao-perigo';
      b.textContent = 'Desinstalar';
      b.addEventListener('click', () => desinstalar(a));
      acoes.appendChild(b);
    }
  }
  linha.appendChild(acoes);
  return linha;
}

async function alternarOculto(a, botao) {
  const fazer = () => chamar('POST', '/api/apps/' + encodeURIComponent(a.pacote) + '/ocultar', { oculto: !a.oculto });
  botao.disabled = true;
  try {
    // App que não é "seguro": mostra antes o que para de funcionar (vem da ficha).
    if (!a.oculto && a.risco && a.risco !== 'seguro') {
      const f = await chamar('GET', '/api/apps/' + encodeURIComponent(a.pacote) + '/ficha').catch(() => null);
      const emUso = await tvEmUso();
      const ok = await confirmar('Ocultar ' + a.nome + '?',
        `Risco: ${a.risco}. ${f ? f.efeito : ''}` + (emUso ? ` A TV está em uso agora (${emUso}).` : ''), 'Ocultar mesmo assim', fazer, a.risco === 'essencial');
      if (!ok) { botao.disabled = false; return; }
      a.oculto = true;
      desenharApps();
      return;
    }
    // Ocultar fecha o app na hora. Com a TV em uso, pergunta antes (a família pode estar vendo justamente ele).
    const emUso = a.oculto ? '' : await tvEmUso();
    if (emUso) {
      const ok = await confirmar('Ocultar ' + a.nome + '?',
        `A TV está em uso agora (${emUso}). Ocultar fecha o app na hora, se for ele que está na tela.`, 'Ocultar', fazer, false);
      if (!ok) { botao.disabled = false; return; }
    } else {
      try { await fazer(); }
      catch (e) {
        if (!(e instanceof PrecisaPin)) throw e;
        // Mostrar de novo um app oculto pede o PIN (o servidor exige): o modal pede e tenta outra vez.
        const ok = await confirmar('Mostrar ' + a.nome + '?', 'Para mostrar de novo um app que estava oculto, digite o PIN.', 'Mostrar', fazer, false);
        if (!ok) { botao.disabled = false; return; }
      }
    }
    a.oculto = !a.oculto;
    desenharApps();
  } catch (e) {
    mostrar($('statusApps'), `${a.nome}: ${textoErro(e)}`);
    botao.disabled = false;
  }
}

async function desinstalar(a) {
  const emUso = await tvEmUso();
  const texto = `Remove o app ${a.nome} e os dados dele da TV. Não tem volta.` +
    (emUso ? ` Atenção: a TV está em uso agora (${emUso}).` : '');
  const ok = await confirmar('Desinstalar ' + a.nome + '?', texto, 'Desinstalar',
    () => chamar('POST', '/api/apps/' + encodeURIComponent(a.pacote) + '/desinstalar', {}));
  if (ok) {
    apps = apps.filter((x) => x.pacote !== a.pacote);
    desenharApps();
    mostrar($('statusApps'), `${a.nome} desinstalado.`);
  }
}

// ---------- ações em lote (vários apps de uma vez) ----------

let modoLote = false;
const selecionados = new Set();   // pacotes marcados

function atualizarLote() {
  $('barraLote').hidden = !modoLote;
  $('contagemLote').textContent = `${selecionados.size} selecionado(s)`;
  for (const b of document.querySelectorAll('[data-lote]')) b.disabled = !selecionados.size;
  for (const c of document.querySelectorAll('.marca-lote')) c.closest('.linha-app').classList.toggle('marcada', c.checked);
}

function alternarModoLote(ligar) {
  modoLote = ligar;
  selecionados.clear();
  $('btnSelecionar').textContent = ligar ? 'Cancelar seleção' : 'Selecionar vários';
  desenharApps();
  atualizarLote();
}
$('btnSelecionar').addEventListener('click', () => alternarModoLote(!modoLote));
$('btnSairLote').addEventListener('click', () => alternarModoLote(false));

/**
 * Faz a mesma ação em cada app marcado, um por vez (a TV atende um pedido de cada vez melhor que vários juntos).
 * Uma confirmação só para o lote todo. Se a TV pedir o PIN no meio, o modal pede e repete: os que já foram ficam
 * em "feitos" e não são refeitos. No fim, um aviso com quantos deram certo e quais falharam.
 */
async function fazerLote(acao) {
  // Por que um app marcado fica de fora desta ação (null = entra). Dito no modal, para ninguém achar que sumiu.
  const motivoFora = (a) => {
    if (a.protegido || a.risco === 'essencial') return 'o sistema depende dele';
    if (acao === 'ocultar') return a.oculto ? 'já está oculto' : (!a.podeOcultar ? 'não dá para ocultar' : null);
    if (acao === 'mostrar') return a.oculto ? null : 'já aparece';
    if (acao === 'desinstalar') return a.podeDesinstalar ? null : 'veio com a TV (não se desinstala)';
    return null;
  };
  const marcados = apps.filter((a) => selecionados.has(a.pacote));
  const alvo = marcados.filter((a) => !motivoFora(a));
  const fora = marcados.filter((a) => motivoFora(a)).map((a) => `${a.nome} (${motivoFora(a)})`);
  if (!alvo.length) { aviso('Nenhum dos marcados entra nesta ação: ' + fora.join('; ') + '.', 'erro'); return; }
  const nomes = alvo.map((a) => a.nome).join(', ');
  const emUso = acao === 'mostrar' ? '' : await tvEmUso();
  const rotulo = { ocultar: 'Ocultar', mostrar: 'Mostrar', parar: 'Parar', desinstalar: 'Desinstalar' }[acao];
  const riscos = alvo.filter((a) => a.risco && a.risco !== 'seguro').map((a) => a.nome);
  const texto = `${rotulo} ${alvo.length} app(s): ${nomes}.` +
    (acao === 'desinstalar' ? ' Remove os apps e os dados deles. Não tem volta.' : '') +
    (riscos.length && acao !== 'mostrar' ? ` Com risco (veja a ficha antes): ${riscos.join(', ')}.` : '') +
    (fora.length ? ` Ficam de fora: ${fora.join('; ')}.` : '') +
    (emUso ? ` A TV está em uso agora (${emUso}).` : '');
  const feitos = new Set();
  const falhas = [];
  const ok = await confirmar(`${rotulo} ${alvo.length} app(s)?`, texto, rotulo, async () => {
    for (const a of alvo) {
      if (feitos.has(a.pacote)) continue;
      const base = '/api/apps/' + encodeURIComponent(a.pacote);
      try {
        if (acao === 'ocultar' || acao === 'mostrar') await chamar('POST', base + '/ocultar', { oculto: acao === 'ocultar' });
        else await chamar('POST', base + '/' + acao, {});
        feitos.add(a.pacote);
      } catch (e) {
        if (e instanceof PrecisaPin) throw e;   // o modal pede o PIN e chama de novo; os feitos são pulados
        feitos.add(a.pacote);
        falhas.push(`${a.nome}: ${textoErro(e)}`);
      }
    }
    return true;
  }, acao === 'desinstalar');   // só desinstalar é vermelho: ocultar, mostrar e parar se desfazem
  // Cancelou no meio (no pedido de PIN, por exemplo) depois de alguns já feitos: a lista tem de mostrar o estado real.
  if (!ok) { if (feitos.size) { aviso(`${rotulo}: ${feitos.size - falhas.length} feito(s) antes de cancelar.`); carregarApps(); } return; }
  const certos = feitos.size - falhas.length;
  aviso(`${rotulo}: ${certos} de ${alvo.length} feito(s).` + (falhas.length ? ' Falhou: ' + falhas.join('; ') : ''), falhas.length ? 'erro' : 'ok');
  alternarModoLote(false);
  carregarApps();   // estado real da TV (oculto, desinstalado) em vez de adivinhar
}
for (const b of document.querySelectorAll('[data-lote]')) b.addEventListener('click', () => fazerLote(b.dataset.lote));

// ---------- backup ----------

$('arquivoBackup').addEventListener('change', async (ev) => {
  const arquivo = ev.target.files[0];
  ev.target.value = '';
  if (!arquivo) return;
  const status = $('statusBackup');
  let texto;
  try {
    texto = await arquivo.text();
    JSON.parse(texto);   // confere antes de mandar; o launcher confere tudo de novo
  } catch (e) {
    mostrar(status, 'Esse arquivo não é um backup válido (JSON).');
    return;
  }
  const emUso = await tvEmUso();
  // Backup completo: vem trancado (CofreBackup.kt); vai com a senha para a rota própria.
  if (JSON.parse(texto).tipo === 'tv-launcher-backup-cifrado') {
    const senha = $('senhaRestaurar').value;
    if (!senha) { mostrar(status, 'Esse é um backup completo: escreva a senha dele no campo abaixo e escolha o arquivo de novo.'); return; }
    const rc = await confirmar('Restaurar backup completo?',
      `Substitui as configurações, as contas de TV ao vivo, o PIN, o Tailscale e os logins do conector pelos de "${arquivo.name}" ` +
      'e reinicia o launcher na TV.' + (emUso ? ` Atenção: a TV está em uso agora (${emUso}).` : ''),
      'Restaurar', () => chamar('POST', '/api/backup/completo/restaurar', { senha, arquivo: texto }));
    $('senhaRestaurar').value = '';
    if (!rc) return;
    mostrar(status, `Restaurado (${rc.chaves} configurações). O launcher está reiniciando…`);
    esperarVoltar(status);
    return;
  }
  const r = await confirmar('Restaurar backup?',
    `Substitui as configurações do launcher pelas de "${arquivo.name}" e reinicia o launcher na TV (a tela pisca).` +
    (emUso ? ` Atenção: a TV está em uso agora (${emUso}).` : ''),
    'Restaurar', () => chamar('POST', '/api/backup/restaurar', undefined, texto));
  if (!r) return;
  mostrar(status, `Restaurado (${r.chaves} configurações). O launcher está reiniciando…`);
  esperarVoltar(status);
});

/** Depois de restaurar, o launcher reinicia: espera o painel responder de novo e pede o PIN (as sessões caem). */
function esperarVoltar(status) {
  let tentativas = 0;
  const t = setInterval(async () => {
    tentativas++;
    try {
      const r = await fetch('/api/estado', { cache: 'no-store' });
      if (r.ok) { clearInterval(t); mostrarEntrar('Backup restaurado e launcher reiniciado. Entre de novo com o PIN.'); }
    } catch (e) {
      if (tentativas > 30) { clearInterval(t); mostrar(status, 'O launcher não voltou em 1 min. Confira a TV.'); }
    }
  }, 2000);
}

/** Baixa um arquivo pelo chamar() (que explica sessão expirada e "não existe" em português) e salva pelo navegador. */
async function baixar(caminho, nome, status) {
  mostrar(status, '');
  try {
    let resp;
    try { resp = await fetch(caminho, { credentials: 'same-origin', cache: 'no-store' }); } catch (e) { throw new Error('Não consegui falar com a TV.'); }
    if (resp.status === 401) { mostrarEntrar('Sessão expirada: entre de novo.'); return; }
    if (!resp.ok) { const d = await resp.json().catch(() => ({})); throw new Error(erroAmigavel(resp.status, d.erro)); }
    const url = URL.createObjectURL(await resp.blob());
    const a = document.createElement('a');
    a.href = url; a.download = nome;
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  } catch (e) {
    mostrar(status, textoErro(e));
  }
}
$('btnBaixar').addEventListener('click', () => baixar('/api/backup', 'launcher-backup.json', $('statusBaixar')));
// ---------- convites: família e visita de um dia (elevação 18.1; PainelWeb "/api/papeis") ----------
const NOMES_PAPEL = { familia: 'Família', convidado: 'Visita de um dia' };
async function carregarConvites() {
  const lista = $('listaConvites');
  let r;
  try { r = await chamar('GET', '/api/papeis'); } catch (e) { mostrar($('statusConvites'), textoErro(e)); return; }
  lista.replaceChildren();
  const convites = (r && r.convites) || [];
  if (!convites.length) { const p = document.createElement('p'); p.className = 'aviso'; p.textContent = 'Nenhum convite ativo.'; lista.append(p); return; }
  for (const c of convites) {
    const linha = document.createElement('div'); linha.className = 'linha-registro';
    const t = document.createElement('strong'); t.textContent = NOMES_PAPEL[c.papel] || c.papel;
    const d = document.createElement('span');
    d.textContent = c.vence ? 'Convite vence em ' + new Date(c.vence).toLocaleString(undefined, { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })
      : 'Convite criado em ' + new Date(c.criado).toLocaleDateString();
    const b = document.createElement('button'); b.type = 'button'; b.className = 'botao-secundario'; b.textContent = 'Revogar';
    b.addEventListener('click', async () => {
      const ok = await confirmar('Revogar convite?', 'Quem entrou por convite sai do painel agora.', 'Revogar',
        () => chamar('POST', '/api/papeis/revogar', { id: c.id }));
      if (ok) carregarConvites();
    });
    linha.append(t, d, b);
    lista.append(linha);
  }
}
document.addEventListener('aba', (ev) => { if (ev.detail === 'remoto' && document.body.dataset.papel === 'dono') carregarConvites(); });
async function criarConvite(papel) {
  const r = await confirmar('Criar convite?', papel === 'convidado'
    ? 'Quem abrir o link usa o controle remoto e abre apps até amanhã neste horário.'
    : 'Quem abrir o link usa o controle remoto, abre apps e manda recados e fotos, até você revogar.',
    'Criar', () => chamar('POST', '/api/papeis/convite', { papel }));
  if (!r) return;
  $('qrConvite').src = 'data:image/png;base64,' + r.qr;
  $('linkConvite').textContent = r.link;
  $('mostraConvite').hidden = false;
  carregarConvites();
}
$('btnConviteFamilia').addEventListener('click', () => criarConvite('familia'));
$('btnConviteVisita').addEventListener('click', () => criarConvite('convidado'));
$('btnCopiarConvite').addEventListener('click', async () => {
  try { await navigator.clipboard.writeText($('linkConvite').textContent); mostrar($('statusConvites'), 'Link copiado.'); }
  catch (e) { mostrar($('statusConvites'), 'Não deu para copiar: selecione o link e copie.'); }
});
$('btnFecharConvite').addEventListener('click', () => {
  $('mostraConvite').hidden = true; $('qrConvite').removeAttribute('src'); $('linkConvite').textContent = '';
});

// ---------- chave de recuperação do backup (elevação 19.6; PainelWeb "/api/backup/recuperacao") ----------
async function carregarRecuperacao() {
  try {
    const r = await chamar('GET', '/api/backup/recuperacao');
    $('estadoRecuperacao').textContent = r.tem
      ? `Chave criada em ${new Date(r.criadaEm).toLocaleDateString()} (digital ${r.digital}).` : 'Nenhuma chave criada.';
    $('btnCriarRecuperacao').textContent = r.tem ? 'Criar uma chave nova' : 'Criar chave de recuperação';
    $('btnApagarRecuperacao').hidden = !r.tem;
  } catch (e) { $('estadoRecuperacao').textContent = textoErro(e); }
}
document.addEventListener('aba', (ev) => { if (ev.detail === 'backup') carregarRecuperacao(); });

$('btnCriarRecuperacao').addEventListener('click', async () => {
  const nova = !$('btnApagarRecuperacao').hidden;
  const r = await confirmar('Criar chave de recuperação?',
    (nova ? 'A chave antiga deixa de valer para os backups NOVOS (os já feitos continuam abrindo com ela). ' : '') +
    'A chave aparece uma vez só, nesta tela.',
    'Criar', () => chamar('POST', '/api/backup/recuperacao', {}));
  if (!r) return;
  $('qrRecuperacao').src = 'data:image/png;base64,' + r.qr;
  $('codigoRecuperacao').textContent = r.codigo;
  $('mostraRecuperacao').hidden = false;
  carregarRecuperacao();
});
function fecharRecuperacao() {
  // Some da tela e da memória da página: o código não fica em lugar nenhum depois de fechado.
  $('mostraRecuperacao').hidden = true;
  $('qrRecuperacao').removeAttribute('src');
  $('codigoRecuperacao').textContent = '';
}
$('btnFecharRecuperacao').addEventListener('click', fecharRecuperacao);
document.addEventListener('aba', () => { if (paginaAtual !== 'sistema') { fecharRecuperacao(); pararQrRestaurar(); } });
$('btnImprimirRecuperacao').addEventListener('click', () => {
  document.body.classList.add('imprimindo-recuperacao');
  window.print();
  document.body.classList.remove('imprimindo-recuperacao');
});
$('btnApagarRecuperacao').addEventListener('click', async () => {
  const r = await confirmar('Esquecer a chave?', 'Backups novos voltam a abrir só com a senha.', 'Esquecer a chave',
    () => chamar('POST', '/api/backup/recuperacao', { apagar: true }));
  if (r) { fecharRecuperacao(); carregarRecuperacao(); }
});

// Restaurar: o celular lê o QR impresso e põe o código no campo da senha (o leitor de QR é o do addons.js).
let qrRestaurarFluxo = null;
function pararQrRestaurar() {
  if (qrRestaurarFluxo) { qrRestaurarFluxo.getTracks().forEach((t) => t.stop()); qrRestaurarFluxo = null; }
  $('videoQrRestaurar').srcObject = null;
  $('qrRestaurar').hidden = true;
}
$('btnFecharQrRestaurar').addEventListener('click', pararQrRestaurar);
$('btnLerRecuperacao').addEventListener('click', async () => {
  const status = $('statusBackup');
  if (!window.isSecureContext || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    mostrar(status, 'Para ler QR, abra o painel pelo endereço https (porta 8444). No http o navegador não libera a câmera.');
    return;
  }
  try { qrRestaurarFluxo = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' }, audio: false }); }
  catch (e) { mostrar(status, 'Não consegui abrir a câmera. Confira se o navegador tem permissão para usá-la.'); return; }
  const video = $('videoQrRestaurar');
  $('qrRestaurar').hidden = false;
  video.srcObject = qrRestaurarFluxo;
  await video.play().catch(() => {});
  let ler;
  try { ler = await leitorQr(video); } catch (e) { pararQrRestaurar(); mostrar(status, textoErro(e)); return; }
  const passo = async () => {
    if (!qrRestaurarFluxo) return;
    let texto = '';
    try { texto = await ler(); } catch (e) { texto = ''; }
    if (/^SOFALY-[0-9A-Z-]{40,}$/.test(texto.trim().toUpperCase())) {
      pararQrRestaurar();
      $('senhaRestaurar').value = texto.trim();
      mostrar(status, 'Chave de recuperação lida.');
      return;
    }
    setTimeout(passo, 250);
  };
  passo();
});

// ---------- levar para uma TV nova (elevação 19.7; PainelWeb "/api/mudar/enviar", MudarDeTv.kt) ----------
let qrMudarFluxo = null;
function pararQrMudar() {
  if (qrMudarFluxo) { qrMudarFluxo.getTracks().forEach((t) => t.stop()); qrMudarFluxo = null; }
  $('videoQrMudarTv').srcObject = null;
  $('qrMudarTv').hidden = true;
}
$('btnFecharQrMudarTv').addEventListener('click', pararQrMudar);
document.addEventListener('aba', () => { if (paginaAtual !== 'sistema') pararQrMudar(); });
$('btnLerMudarTv').addEventListener('click', async () => {
  const status = $('statusMudarTv');
  if (!window.isSecureContext || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
    mostrar(status, 'Para ler QR, abra o painel pelo endereço https (porta 8444). No http o navegador não libera a câmera.');
    return;
  }
  try { qrMudarFluxo = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' }, audio: false }); }
  catch (e) { mostrar(status, 'Não consegui abrir a câmera. Confira se o navegador tem permissão para usá-la.'); return; }
  const video = $('videoQrMudarTv');
  $('qrMudarTv').hidden = false;
  video.srcObject = qrMudarFluxo;
  await video.play().catch(() => {});
  let ler;
  try { ler = await leitorQr(video); } catch (e) { pararQrMudar(); mostrar(status, textoErro(e)); return; }
  const passo = async () => {
    if (!qrMudarFluxo) return;
    let texto = '';
    try { texto = await ler(); } catch (e) { texto = ''; }
    // Só o QR da tela "Trazer de outra TV" ("SOFALY-TV <ip>:<porta> <código>"); outro QR é ignorado.
    if (/^SOFALY-TV \S+ [0-9A-Z-]{16,}$/.test(texto.trim().toUpperCase())) {
      pararQrMudar();
      $('codigoMudarTv').value = texto.trim();
      mostrar(status, 'QR da TV nova lido.');
      return;
    }
    setTimeout(passo, 250);
  };
  passo();
});
$('btnEnviarMudarTv').addEventListener('click', async () => {
  const status = $('statusMudarTv');
  const codigo = $('codigoMudarTv').value.trim();
  if (codigo.length < 20) { mostrar(status, 'Digite o endereço e o código que aparecem na TV nova.'); return; }
  mostrar(status, '');
  // Leva os segredos da casa para outro aparelho: a TV pede o PIN de novo (como baixar o backup completo).
  const r = await confirmar('Levar tudo para a TV nova?',
    'Vão as configurações, o PIN, os canais e as contas desta TV, cifrados só para aquela TV. Esta TV continua como está.',
    'Enviar', () => chamar('POST', '/api/mudar/enviar', { codigo }), false);
  if (r) { $('codigoMudarTv').value = ''; mostrar(status, 'Pronto: a TV nova recebeu tudo e está reiniciando.'); }
});

$('btnBaixarCompleto').addEventListener('click', async () => {
  const status = $('statusBaixarCompleto');
  const senha = $('senhaBackupCompleto').value;
  if (senha.length < 8) { mostrar(status, 'Escolha uma senha com 8 ou mais caracteres.'); return; }
  mostrar(status, '');
  // Leva segredos: a TV pede o PIN de novo. O pedido é POST (a senha vai no corpo, nunca no endereço).
  const r = await confirmar('Baixar backup completo?',
    'O arquivo leva senhas e acessos, trancados com a senha que você escolheu. Guarde a senha: sem ela não dá para restaurar.',
    'Baixar', async () => {
      let resp;
      try {
        resp = await fetch('/api/backup/completo', { method: 'POST', credentials: 'same-origin', cache: 'no-store',
          headers: { 'X-Painel': '1', 'Content-Type': 'application/json' }, body: JSON.stringify({ senha }) });
      } catch (e) { throw new Error('Não consegui falar com a TV.'); }
      if (!resp.ok) {
        const d = await resp.json().catch(() => ({}));
        if (resp.status === 403 && d.precisaPin) throw new PrecisaPin(d.erro);
        if (resp.status === 401) { mostrarEntrar('Sessão expirada: entre de novo.'); throw new Error('Sessão expirada.'); }
        throw new Error(erroAmigavel(resp.status, d.erro));
      }
      const url = URL.createObjectURL(await resp.blob());
      const a = document.createElement('a');
      a.href = url; a.download = 'launcher-backup-completo.json';
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 10000);
      return { ok: true };
    });
  if (r) { $('senhaBackupCompleto').value = ''; mostrar(status, 'Baixado. Guarde o arquivo e a senha em lugares diferentes.'); }
});
$('btnBaixarAnterior').addEventListener('click', () => baixar('/api/backup/anterior', 'launcher-antes-de-restaurar.json', $('statusBackup')));

// ---------- cópias automáticas (BackupAutomatico.kt) ----------

/** "backup-20261105.json" -> "05/11/2026". */
function diaDaCopia(nome) {
  const m = /^backup-(\d{4})(\d{2})(\d{2})\.json$/.exec(nome);
  return m ? `${m[3]}/${m[2]}/${m[1]}` : nome;
}

async function carregarCopias() {
  const lista = $('listaCopias');
  const status = $('statusCopias');
  lista.replaceChildren();
  mostrar(status, '');
  let r;
  try { r = await chamar('GET', '/api/backup/automaticos'); } catch (e) { mostrar(status, textoErro(e)); return; }
  if (!r.copias.length) { mostrar(status, 'Nenhuma cópia ainda: a primeira sai quando a HOME abrir.'); return; }
  for (const c of r.copias) {
    const linha = document.createElement('div');
    linha.className = 'linha-registro';
    const dia = document.createElement('span');
    dia.className = 'quando';
    dia.textContent = `${diaDaCopia(c.nome)} · ${Math.max(1, Math.round(c.tamanho / 1024))} KB`;
    const baixarBtn = document.createElement('button');
    baixarBtn.type = 'button'; baixarBtn.className = 'link'; baixarBtn.textContent = 'baixar';
    baixarBtn.addEventListener('click', () => baixar(`/api/backup/automatico?nome=${encodeURIComponent(c.nome)}`, `launcher-${c.nome}`, status));
    const voltarBtn = document.createElement('button');
    voltarBtn.type = 'button'; voltarBtn.className = 'link'; voltarBtn.textContent = 'voltar a este dia';
    voltarBtn.addEventListener('click', async () => {
      const emUso = await tvEmUso();
      const ok = await confirmar('Voltar as configurações?',
        `Substitui as configurações do launcher pelas de ${diaDaCopia(c.nome)} e reinicia o launcher na TV (a tela pisca).` +
        (emUso ? ` Atenção: a TV está em uso agora (${emUso}).` : ''),
        'Voltar', () => chamar('POST', `/api/backup/automatico/restaurar?nome=${encodeURIComponent(c.nome)}`));
      if (!ok) return;
      mostrar(status, `Restaurado (${ok.chaves} configurações). O launcher está reiniciando…`);
      esperarVoltar(status);
    });
    linha.append(dia, ' ', baixarBtn, ' · ', voltarBtn);
    lista.appendChild(linha);
  }
}

// ---------- backup noturno cifrado (BackupNoturno.kt) ----------

/** 210 -> "03:30" (minuto do dia, como o <input type="time"> espera). */
function hhmmDoMinuto(m) { return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`; }

/** "onetv-backup-20261110.json" -> "10/11/2026"; outro formato fica como veio. */
function diaDaCopiaNoturna(nome) {
  const m = /(\d{4})(\d{2})(\d{2})/.exec(nome);
  return m ? `${m[3]}/${m[2]}/${m[1]}` : nome;
}

let estadoNoturno = null;   // o último estado lido: o <select> volta para ele se a troca for cancelada

function desenharNoturno(r) {
  estadoNoturno = r;
  $('estadoNoturno').textContent = !r.temSenha ? 'Desligado: defina a senha primeiro.'
    : `${r.ligado ? 'Ligado' : 'Desligado'} · senha definida · última vez: ${r.ultimo || 'nunca'}`;
  $('chkNoturnoLigado').checked = r.ligado;
  $('chkNoturnoLigado').disabled = !r.temSenha;   // ligar sem senha a TV recusa: nem deixa marcar
  $('horaNoturno').value = hhmmDoMinuto(r.minutoDoDia);
  // As opções vêm da TV: "só na TV", pendrive e cada pasta de rede cadastrada em Mídias na rede.
  const sel = $('destinoNoturno');
  sel.replaceChildren();
  const opcoes = [['', 'Nenhuma (só na TV)'], ['pendrive', 'Pendrive'], ...r.nas.map((n) => [n.valor, `Pasta da rede: ${n.nome}`])];
  // Google Drive só aparece com a conta conectada na TV (Configurações > Backup automático > Conta do Google Drive).
  if (r.drive) opcoes.push(['drive', `Google Drive (${r.drive})`]);
  // OneDrive, Dropbox e Box cadastrados em Na rede (o nome já vem pronto: "Dropbox · Ana").
  for (const n of r.nuvens || []) opcoes.push([n.valor, n.nome]);
  // Destino gravado que não está mais na lista (pasta apagada): aparece com o rótulo da TV, para não sumir calado.
  if (!opcoes.some(([v]) => v === r.destino)) opcoes.push([r.destino, r.destinoRotulo]);
  for (const [valor, rotulo] of opcoes) {
    const o = document.createElement('option');
    o.value = valor; o.textContent = rotulo; o.selected = valor === r.destino;
    sel.appendChild(o);
  }
  desenharDriveConta(r);
  const lista = $('listaNoturno');
  lista.replaceChildren();
  for (const c of r.copias) {
    const linha = document.createElement('div');
    linha.className = 'linha-registro';
    const q = document.createElement('span');
    q.className = 'quando';
    q.textContent = `${diaDaCopiaNoturna(c.nome)} · ${Math.max(1, Math.round(c.tamanho / 1024))} KB`;
    linha.append(q, ' ', c.nome);
    lista.appendChild(linha);
  }
}

async function carregarNoturno() {
  mostrar($('statusNoturno'), '');
  try { desenharNoturno(await chamar('GET', '/api/backup/noturno')); } catch (e) { mostrar($('statusNoturno'), textoErro(e)); }
}

/** Grava um campo (ligado, horário) direto: não pedem o PIN de novo. */
async function gravarNoturno(campos, ok) {
  try { desenharNoturno(await chamar('POST', '/api/backup/noturno/config', campos)); aviso(ok, 'ok'); }
  catch (e) { mostrar($('statusNoturno'), textoErro(e)); carregarNoturno(); }
}

$('chkNoturnoLigado').addEventListener('change', () => {
  const sim = $('chkNoturnoLigado').checked;
  gravarNoturno({ ligado: sim }, sim ? 'Backup noturno ligado.' : 'Backup noturno desligado.');
});
$('horaNoturno').addEventListener('change', () => {
  const [h, m] = ($('horaNoturno').value || '').split(':').map(Number);
  if (!Number.isInteger(h) || !Number.isInteger(m)) return;
  gravarNoturno({ minutoDoDia: h * 60 + m }, `Horário gravado: ${$('horaNoturno').value}.`);
});
$('destinoNoturno').addEventListener('change', async () => {
  const sel = $('destinoNoturno');
  const rotulo = sel.options[sel.selectedIndex].textContent;
  // Destino pede o PIN de novo: o confirmar() mostra o campo do PIN quando a TV pede.
  const r = await confirmar('Trocar a cópia extra?', `As próximas cópias noturnas vão também para: ${rotulo}.`,
    'Trocar', () => chamar('POST', '/api/backup/noturno/config', { destino: sel.value }), false);
  if (r) { desenharNoturno(r); aviso('Cópia extra trocada.', 'ok'); } else if (estadoNoturno) desenharNoturno(estadoNoturno);
});
$('btnSenhaNoturno').addEventListener('click', async () => {
  const status = $('statusNoturno');
  const senha = $('senhaNoturno').value;
  if (senha.length < 8) { mostrar(status, 'Escolha uma senha com 8 ou mais caracteres.'); return; }
  mostrar(status, '');
  const r = await confirmar('Guardar a senha do backup noturno?',
    'As próximas cópias saem trancadas com esta senha. Anote-a: sem ela não dá para restaurar.',
    'Guardar', () => chamar('POST', '/api/backup/noturno/config', { senha }), false);
  if (!r) return;
  $('senhaNoturno').value = '';   // a senha não fica na tela (e a TV nunca a devolve)
  desenharNoturno(r);
  aviso('Senha guardada. Guarde-a num lugar seguro.', 'ok');
});
$('btnNoturnoAgora').addEventListener('click', async () => {
  const b = $('btnNoturnoAgora');
  const status = $('statusNoturno');
  b.disabled = true;
  mostrar(status, 'Fazendo o backup… (com cópia na rede pode levar até 1 min)');
  try {
    const r = await chamar('POST', '/api/backup/noturno/agora');
    desenharNoturno(r);
    mostrar(status, r.resultado ? `Resultado: ${r.resultado}` : 'Ainda rodando na TV: o resultado aparece em "última vez" daqui a pouco.');
  } catch (e) { mostrar(status, textoErro(e)); }
  b.disabled = false;
});

// ---------- conta do Google Drive (DriveBackup.kt): o mesmo login por código da tela da TV ----------

function desenharDriveConta(r) {
  $('driveConta').textContent = r.drive || (r.google ? 'não conectada' : 'falta o login Google (aba Claude)');
  $('btnDriveConectar').hidden = !!r.drive || !r.google;
  $('btnDriveDesconectar').hidden = !r.drive;
  $('cartaoDriveRestaurar').hidden = !r.drive;
}

let timerDrive = null;
let qrDriveAtual = null;
/** Mostra o código enquanto o Google espera; termina sozinho quando a pessoa conclui no celular (ou o código vence). */
function desenharDriveLogin(l) {
  clearTimeout(timerDrive);
  const esperando = l.estado === 'esperando';
  $('driveLogin').hidden = !esperando;
  if (esperando) {
    $('driveCodigo').textContent = l.codigo;
    $('driveUrl').textContent = l.url.replace(/^https?:\/\//, '');
    $('driveUrl').href = l.url;
    if (l.codigo !== qrDriveAtual) $('driveQr').src = '/api/backup/drive/qr?t=' + Date.now();
    qrDriveAtual = l.codigo;
    // Confere a cada 3 s só com a aba à vista (a consulta renova a sessão do painel).
    if (abaAberta('backup') && document.visibilityState === 'visible') timerDrive = setTimeout(acompanharDrive, 3000);
    return;
  }
  qrDriveAtual = null;
  if (l.estado === 'ok') { aviso(`Google Drive conectado: ${l.email}. Escolha "Google Drive" em Cópia extra.`, 'ok'); carregarNoturno(); }
  else if (l.estado === 'recusado' || l.estado === 'erro') mostrar($('statusNoturno'), `Não conectou: ${l.mensagem}`);
}

async function acompanharDrive() {
  try { desenharDriveLogin(await chamar('GET', '/api/backup/drive/login')); } catch (e) { mostrar($('statusNoturno'), textoErro(e)); }
}

$('btnDriveConectar').addEventListener('click', async () => {
  mostrar($('statusNoturno'), '');
  // Pede o PIN de novo (quem conecta a conta decide para onde vão as cópias da casa).
  const r = await confirmar('Conectar o Google Drive?',
    'A TV pede um código ao Google. Depois, no celular, você entra com a conta Google da TV e digita o código. As cópias vão para a pasta OneTV-backup, trancadas com a senha do backup.',
    'Pedir código', () => chamar('POST', '/api/backup/drive/conectar'), false);
  if (r) desenharDriveLogin(r);
});
$('btnDriveCancelar').addEventListener('click', async () => {
  clearTimeout(timerDrive);
  $('driveLogin').hidden = true;
  try { desenharNoturno(await chamar('POST', '/api/backup/drive/cancelar')); } catch (e) { mostrar($('statusNoturno'), textoErro(e)); }
});
$('btnDriveDesconectar').addEventListener('click', async () => {
  const r = await confirmar('Desconectar o Google Drive?',
    `A TV deixa de enviar cópias para ${estadoNoturno?.drive || 'a conta'} e o acesso é tirado também no Google. As cópias que já estão no Drive ficam lá.`,
    'Desconectar', () => chamar('POST', '/api/backup/drive/desconectar'));
  if (r) { desenharNoturno(r); aviso('Google Drive desconectado.', 'ok'); }
});

$('btnDriveListar').addEventListener('click', async () => {
  const lista = $('listaDrive');
  const status = $('statusDrive');
  const b = $('btnDriveListar');
  lista.replaceChildren();
  b.disabled = true;
  mostrar(status, 'Perguntando ao Drive…');
  let r;
  try { r = await chamar('GET', '/api/backup/drive/copias'); } catch (e) { mostrar(status, textoErro(e)); b.disabled = false; return; }
  b.disabled = false;
  mostrar(status, r.copias.length ? '' : 'Nenhuma cópia no Drive ainda.');
  for (const c of r.copias) {
    const linha = document.createElement('div');
    linha.className = 'linha-registro';
    const q = document.createElement('span');
    q.className = 'quando';
    q.textContent = `${diaDaCopiaNoturna(c.nome)} · ${Math.max(1, Math.round(c.tamanho / 1024))} KB`;
    const voltar = document.createElement('button');
    voltar.type = 'button'; voltar.className = 'link'; voltar.textContent = 'restaurar';
    voltar.addEventListener('click', async () => {
      const senha = $('senhaDrive').value;
      if (senha.length < 8) { mostrar(status, 'Digite a senha do backup noturno (8 ou mais caracteres).'); $('senhaDrive').focus(); return; }
      const emUso = await tvEmUso();
      const ok = await confirmar('Restaurar do Google Drive?',
        `Substitui as configurações do launcher pelas da cópia de ${diaDaCopiaNoturna(c.nome)} e reinicia o launcher na TV (a tela pisca).` +
        (emUso ? ` Atenção: a TV está em uso agora (${emUso}).` : ''),
        'Restaurar', () => chamar('POST', '/api/backup/drive/restaurar', { id: c.id, senha }));
      if (!ok) return;
      $('senhaDrive').value = '';
      mostrar(status, `Restaurado (${ok.chaves} configurações). O launcher está reiniciando…`);
      esperarVoltar(status);
    });
    linha.append(q, ' ', voltar);
    lista.appendChild(linha);
  }
});

// ---------- Configurações (PainelConfig.kt): as mesmas opções das Configurações da TV ----------

let estadoConfig = null;
function desenharConfig(r) {
  estadoConfig = r;
  const tema = $('cfgTema');
  tema.replaceChildren(...r.temas.map((t) => { const o = document.createElement('option'); o.value = t.valor; o.textContent = t.nome; return o; }));
  tema.value = r.tema;
  $('cfgTamanho').value = String(r.tamanhoTexto);
  $('cfgAltoContraste').checked = !!r.altoContraste;
  aplicarContraste(r.altoContraste);
  $('cfgChuva').checked = r.avisoChuva;
  $('cfgTemperatura').checked = r.avisoTemperatura !== false;
  $('cfgAudio').value = r.audio;
  $('cfgQuadros').checked = r.taxaQuadros !== false;
  $('cfgProtetor').value = String(r.protetorMs);
  $('cfgBarra').value = r.barraProtetor;
  $('cfgParental').checked = r.parental;
  $('cfgParentalDet').textContent = `(${r.parentalCanais} canal(is) ou categoria(s) escolhidos)`;
  $('cfgInfantil').checked = r.infantil;
  $('cfgInfantilDet').textContent = `(${r.infantis} categoria(s) infantil(is))`;
  $('cfgIdadeInfantil').value = String(r.idadeInfantil ?? -1);
  $('cfgVisita').checked = r.visita;
  $('cfgUnidadeRede').checked = r.unidadeRede;
  $('cfgUnidadeRedeEndereco').textContent = r.unidadeRede ? (r.unidadeRedeEndereco || 'ligue o https (aba Extras › Certificado)') : 'desligada';
  $('cfgMtpPermitidos').textContent = (r.mtpPermitidos || []).length ? r.mtpPermitidos.join(', ') : 'nenhum ainda';
  $('btnMtpEsquecer').hidden = !(r.mtpPermitidos || []).length;
  $('cfgPinFamiliaEstado').textContent = r.pinFamilia ? 'definido' : 'não definido';
  $('btnApagarPinFamilia').hidden = !r.pinFamilia;
  $('cfgLiberarTudo').checked = r.confirmacoes.liberarTudo;
  const lista = $('cfgConfirmacoes');
  // Ideia 13 (estudo global): a lista é redesenhada a cada troca; sem isto o foco caía no começo da página.
  const focoAntes = lista.contains(document.activeElement) ? document.activeElement.getAttribute('aria-label') : null;
  lista.replaceChildren();
  for (const a of r.confirmacoes.acoes) {
    const linha = document.createElement('div');
    linha.className = 'linha-confirmacao';
    const nome = document.createElement('span');
    nome.textContent = a.titulo;
    nome.title = a.explicacao;
    const sel = document.createElement('select');
    sel.setAttribute('aria-label', a.titulo);
    for (const m of a.modos) { const o = document.createElement('option'); o.value = m.valor; o.textContent = m.nome; sel.appendChild(o); }
    sel.value = a.modo;
    sel.disabled = r.confirmacoes.liberarTudo;   // como na TV: com "Liberar tudo", as linhas não valem
    sel.addEventListener('change', () => gravarConfig({ campo: 'confirmacao', acao: a.chave, valor: sel.value }, `${a.titulo}: ${sel.options[sel.selectedIndex].textContent}.`));
    linha.append(nome, sel);
    lista.appendChild(linha);
    if (focoAntes && focoAntes === a.titulo) sel.focus();
  }
}

async function carregarConfig() {
  mostrar($('statusConfig'), '');
  try { desenharConfig(await chamar('GET', '/api/config')); } catch (e) { mostrar($('statusConfig'), textoErro(e)); }
  carregarServicos();
}

/** Grava um campo. Se a TV pedir o PIN, o confirmar() mostra o campo do PIN; cancelado, a tela volta ao estado gravado. */
async function gravarConfig(corpo, ok, titulo = 'Confirme o PIN') {
  mostrar($('statusConfig'), '');
  // Estudo do painel (PR 4): opção de liga/desliga ganha "Desfazer" no aviso; as outras seguem com o aviso simples.
  const feito = () => {
    if (typeof corpo.valor === 'boolean' && corpo.campo !== 'atalhos' && typeof avisoDesfazer === 'function') {
      avisoDesfazer(ok, null, () => gravarConfig({ ...corpo, valor: !corpo.valor }, ok, titulo));
    } else aviso(ok, 'ok');
    if (typeof desenharAtalhos === 'function' && corpo.campo !== 'atalhos') desenharAtalhos();
  };
  try { desenharConfig(await chamar('POST', '/api/config', corpo)); feito(); return true; }
  catch (e) {
    if (e instanceof PrecisaPin) {
      const r = await confirmar(titulo, 'Esta opção pede o PIN do painel.', 'Confirmar', () => chamar('POST', '/api/config', corpo), false);
      if (r) { desenharConfig(r); feito(); return true; }
    } else mostrar($('statusConfig'), textoErro(e));
    if (estadoConfig) desenharConfig(estadoConfig);
    return false;
  }
}

$('cfgTema').addEventListener('change', (e) => gravarConfig({ campo: 'tema', valor: e.target.value }, 'Tema trocado.'));
$('cfgAltoContraste').addEventListener('change', (e) => gravarConfig({ campo: 'altoContraste', valor: e.target.checked }, e.target.checked ? 'Alto contraste ligado.' : 'Alto contraste desligado.'));
$('cfgTamanho').addEventListener('change', (e) => gravarConfig({ campo: 'tamanhoTexto', valor: Number(e.target.value) }, 'Tamanho do texto gravado (vale ao abrir cada tela).'));
$('cfgChuva').addEventListener('change', (e) => gravarConfig({ campo: 'avisoChuva', valor: e.target.checked }, e.target.checked ? 'Aviso de chuva ligado.' : 'Aviso de chuva desligado.'));
$('cfgQuadros').addEventListener('change', (e) => gravarConfig({ campo: 'taxaQuadros', valor: e.target.checked }, e.target.checked ? 'Taxa de quadros do filme ligada.' : 'Taxa de quadros do filme desligada.'));
$('cfgTemperatura').addEventListener('change', (e) => gravarConfig({ campo: 'avisoTemperatura', valor: e.target.checked }, e.target.checked ? 'Aviso de calor e frio ligado.' : 'Aviso de calor e frio desligado.'));
$('cfgAudio').addEventListener('change', (e) => gravarConfig({ campo: 'audio', valor: e.target.value }, 'Áudio preferido gravado.'));
$('cfgProtetor').addEventListener('change', (e) => gravarConfig({ campo: 'protetorMs', valor: Number(e.target.value) }, 'Tempo do protetor gravado.'));
$('cfgBarra').addEventListener('change', (e) => gravarConfig({ campo: 'barraProtetor', valor: e.target.value }, 'Widgets do protetor gravados.'));
$('cfgParental').addEventListener('change', (e) => gravarConfig({ campo: 'parental', valor: e.target.checked }, e.target.checked ? 'Controle parental ligado.' : 'Controle parental desligado.'));
$('cfgIdadeInfantil').addEventListener('change', (e) => gravarConfig({ campo: 'idadeInfantil', valor: Number(e.target.value) },
  'Idade do modo infantil gravada. Filmes e séries com classificação até ela aparecem; os sem classificação, só nas categorias infantis.', 'Confirme o PIN'));
$('cfgInfantil').addEventListener('change', (e) => gravarConfig({ campo: 'infantil', valor: e.target.checked }, e.target.checked ? 'Modo infantil ligado.' : 'Modo infantil desligado.'));
$('cfgVisita').addEventListener('change', (e) => gravarConfig({ campo: 'visita', valor: e.target.checked }, e.target.checked ? 'Modo visita ligado.' : 'Modo visita desligado.'));
$('cfgUnidadeRede').addEventListener('change', (e) => gravarConfig({ campo: 'unidadeRede', valor: e.target.checked }, e.target.checked ? 'Unidade de rede ligada.' : 'Unidade de rede desligada.'));
$('btnMtpEsquecer').addEventListener('click', () => gravarConfig({ campo: 'mtpEsquecer', valor: true }, 'Computadores esquecidos: cada um pergunta de novo na TV.'));
$('cfgLiberarTudo').addEventListener('change', (e) => gravarConfig({ campo: 'liberarTudo', valor: e.target.checked }, e.target.checked ? 'Liberar tudo ligado.' : 'Liberar tudo desligado.'));
$('btnPinFamilia').addEventListener('click', async () => {
  const pin = $('cfgPinFamilia').value;
  if (!/^[0-9]{6,12}$/.test(pin)) { mostrar($('statusConfig'), 'O PIN das crianças precisa ter de 6 a 12 números.'); return; }
  if (await gravarConfig({ campo: 'pinFamilia', valor: pin }, 'PIN das crianças gravado.')) $('cfgPinFamilia').value = '';
});
$('btnApagarPinFamilia').addEventListener('click', () => gravarConfig({ campo: 'pinFamilia', valor: '' }, 'PIN das crianças apagado: agora só o PIN do painel destrava.'));
$('btnPinPainel').addEventListener('click', async () => {
  const pin = $('cfgPinNovo').value;
  if (!/^[0-9]{6,12}$/.test(pin)) { mostrar($('statusConfig'), 'O PIN precisa ter de 6 a 12 números.'); return; }
  if (pin !== $('cfgPinNovo2').value) { mostrar($('statusConfig'), 'Os dois PINs não bateram.'); return; }
  if (await gravarConfig({ campo: 'pinPainel', valor: pin }, 'PIN do painel trocado. Use o novo da próxima vez.', 'Confirme o PIN atual')) {
    $('cfgPinNovo').value = ''; $('cfgPinNovo2').value = '';
  }
});

// ---------- Serviços de filmes e séries (PainelServicos.kt): os mesmos logins da tela da TV ----------

let timerServicos = null;
const SITUACAO_SERVICO = { desligado: 'desligado', entrando: 'entrando…', conectado: 'conectado' };

function desenharServicos(r) {
  clearTimeout(timerServicos);
  const caixa = $('cfgServicos');
  caixa.replaceChildren();
  $('cfgServicosTmdb').textContent = r.servicos.some((s) => s.id === 'tmdb') ? r.atribuicaoTmdb : '';
  if (r.semChaves) { mostrar($('statusServicos'), 'Esta versão do launcher ainda não tem as chaves destes serviços. Eles aparecem depois da próxima atualização.'); return; }
  let esperando = false;
  for (const s of r.servicos) {
    const bloco = document.createElement('div');
    bloco.className = 'linha-servico';
    const topo = document.createElement('p');
    const nome = document.createElement('strong'); nome.textContent = s.nome;
    const est = document.createElement('span'); est.className = 'aviso-curto';
    est.textContent = s.id === 'tmdb' ? ' · ligado' : s.situacao === 'conectado' && s.usuario ? ` · conectado como ${s.usuario}` : ` · ${SITUACAO_SERVICO[s.situacao] || s.situacao}`;
    topo.append(nome, est);
    const frase = document.createElement('p'); frase.className = 'aviso-curto'; frase.textContent = s.frase;
    bloco.append(topo, frase);
    const botao = (rotulo, fazer, primario = true) => {
      const b = document.createElement('button'); b.type = 'button'; b.className = primario ? 'botao-secundario' : 'link'; b.textContent = rotulo;
      b.addEventListener('click', fazer); return b;
    };
    const agir = async (corpo, titulo, texto, rotulo, ok) => {
      mostrar($('statusServicos'), '');
      const resp = await confirmar(titulo, texto, rotulo, () => chamar('POST', '/api/servicos', corpo), false);
      if (resp) { desenharServicos(resp); if (ok) aviso(ok, 'ok'); }
    };
    const sessao = s.sessao;
    if (s.situacao === 'conectado' && s.id !== 'tmdb') {
      bloco.append(botao('Sair', () => agir({ acao: 'sair', servico: s.id }, `Sair do ${s.nome}?`,
        `A TV esquece a conta e para de falar com o ${s.nome}. O que está lá continua lá.`, 'Sair', `${s.nome}: saiu.`), false));
      if (s.sync) desenharSync(bloco, s, r, botao, agir);
    } else if (sessao && sessao.estado === 'esperando') {
      esperando = true;
      const passo = document.createElement('p');
      passo.className = 'aviso';
      const link = document.createElement('a');
      link.href = sessao.urlDireta; link.target = '_blank'; link.rel = 'noopener'; link.textContent = sessao.url.replace(/^https?:\/\//, '');
      passo.append('1. Toque em ', link, ` (abre o ${s.nome}). 2. Entre na sua conta e confirme o código:`);
      const cod = document.createElement('p'); cod.className = 'codigo-grande'; cod.textContent = sessao.codigo;
      const qr = document.createElement('img'); qr.alt = 'QR code do login'; qr.width = 180; qr.height = 180;
      qr.src = `/api/servicos/qr?servico=${s.id}&c=${encodeURIComponent(sessao.codigo)}`;
      const fim = document.createElement('p'); fim.className = 'aviso-curto'; fim.textContent = 'Esta página continua sozinha quando você terminar.';
      bloco.append(passo, cod, qr, fim, botao('Cancelar', async () => {
        try { desenharServicos(await chamar('POST', '/api/servicos', { acao: 'cancelar', servico: s.id })); } catch (e) { mostrar($('statusServicos'), textoErro(e)); }
      }, false));
    } else if (s.login === 'codigo') {
      if (sessao && sessao.estado === 'ok') aviso(`${s.nome} conectado${sessao.usuario ? ' como ' + sessao.usuario : ''}.`, 'ok');
      if (sessao && sessao.estado === 'erro') { const m = document.createElement('p'); m.className = 'aviso'; m.textContent = `Não deu certo: ${sessao.motivo}.`; bloco.append(m); }
      bloco.append(botao('Entrar', () => agir({ acao: 'entrar', servico: s.id }, `Entrar no ${s.nome}?`,
        `A TV pede um código ao ${s.nome}. Depois você abre o link, entra na sua conta e confirma o código.`, 'Pedir código')));
    } else if (s.login === 'chave') {
      const campo = document.createElement('input');
      campo.type = 'password'; campo.autocomplete = 'off'; campo.placeholder = 'Chave da API do MDBList'; campo.setAttribute('aria-label', 'Chave da API do MDBList');
      const ajuda = document.createElement('p'); ajuda.className = 'aviso-curto';
      const l = document.createElement('a'); l.href = 'https://mdblist.com/preferences/'; l.target = '_blank'; l.rel = 'noopener'; l.textContent = 'mdblist.com/preferences';
      ajuda.append('Opcional: suas listas do MDBList (como o Top 250) viram categorias em Filmes e Séries. 1. Abra ', l, ' e entre (ou crie uma conta grátis). 2. No fim da página, toque em API Access, gere a chave se ainda não tiver e copie. 3. Cole aqui e grave: a TV confere na hora.');
      // Opcional (a nota do Trakt já vem sem conta): 3 passos, sem digitar no controle da TV.
      const extra = document.createElement('div');
      bloco.append(extra);
      extra.append(ajuda, campo, botao('Gravar chave', () => {
        const chave = campo.value.trim();
        if (!/^[A-Za-z0-9]{16,64}$/.test(chave)) { mostrar($('statusServicos'), 'A chave do MDBList tem só letras e números.'); return; }
        agir({ acao: 'chave', servico: s.id, chave }, 'Gravar a chave do MDBList?', 'A TV confere a chave no MDBList antes de gravar.', 'Gravar', 'MDBList conectado.')
          .then(() => { campo.value = ''; });
      }));
    } else if (s.login === 'senha') {
      const u = document.createElement('input'); u.autocomplete = 'username'; u.placeholder = 'Usuário do OpenSubtitles'; u.setAttribute('aria-label', 'Usuário do OpenSubtitles');
      const p = document.createElement('input'); p.type = 'password'; p.autocomplete = 'current-password'; p.placeholder = 'Senha'; p.setAttribute('aria-label', 'Senha do OpenSubtitles');
      bloco.append(u, p, botao('Entrar', () => {
        if (!u.value.trim() || !p.value) { mostrar($('statusServicos'), 'Digite o usuário e a senha do OpenSubtitles.'); return; }
        agir({ acao: 'senha', servico: s.id, usuario: u.value.trim(), senha: p.value }, 'Entrar no OpenSubtitles?',
          'A TV entra na sua conta para conferir e guarda tudo cifrado.', 'Entrar', 'OpenSubtitles conectado.').then(() => { p.value = ''; });
      }));
    }
    caixa.appendChild(bloco);
  }
  // Confere a cada 3 s só com a aba à vista, enquanto algum login espera o celular.
  if (esperando && (abaAberta('config') || !$('telaPrimeira').hidden) && document.visibilityState === 'visible') timerServicos = setTimeout(carregarServicos, 3000);
}

// Sincronização de uma conta conectada (a mesma tela SincronizarActivity da TV). Tudo começa desligado; LIGAR pede o PIN.
const OPCOES_SYNC = [
  ['interesses', 'Interesses', 'O que você marca aqui aparece lá e o de lá aparece aqui.'],
  ['assistidos', 'Já assistidos', 'Filmes e episódios vistos ficam marcados nos dois lados.'],
  ['continuar', 'Continuar de onde parou', 'Parou no celular? A TV continua do mesmo ponto, e o contrário.'],
  ['contar', 'Contar o que assisto', 'A TV avisa o serviço enquanto você assiste. Nunca no Modo visita nem no perfil infantil.'],
];

function desenharSync(bloco, s, r, botao, agir) {
  const y = s.sync;
  const caixa = document.createElement('div'); caixa.className = 'sync-servico';
  const parado = y.parado === 'bloqueado' ? 'Parado: Modo visita ou perfil infantil ativo.'
    : y.parado === 'outroPerfil' ? 'Parado: a sincronização é de outro perfil.' : y.aviso;
  if (parado) { const p = document.createElement('p'); p.className = 'aviso'; p.textContent = parado; caixa.append(p); }
  if (s.id !== 'mdblist') {
    const quando = document.createElement('p'); quando.className = 'aviso-curto';
    quando.textContent = y.ultima ? `Última sincronização: ${new Date(y.ultima).toLocaleString('pt-BR')}.` : 'Ainda não sincronizou.';
    caixa.append(quando);
    for (const [campo, rotulo, explica] of OPCOES_SYNC) {
      const nomeOp = campo === 'interesses' && s.id === 'simkl' ? 'Interesses (Plan to Watch)' : rotulo;
      const linha = document.createElement('label'); linha.className = 'linha-check';
      const c = document.createElement('input'); c.type = 'checkbox'; c.checked = !!y.opcoes[campo];
      const t = document.createElement('span'); t.textContent = ` ${nomeOp}. `;
      const d = document.createElement('span'); d.className = 'aviso-curto'; d.textContent = explica;
      linha.append(c, t, d);
      c.addEventListener('change', async () => {
        const valor = c.checked; c.checked = !valor;   // só muda de verdade quando a TV gravar
        const corpo = { acao: 'opcao', servico: s.id, campo, valor };
        if (valor) { await agir(corpo, `Ligar "${nomeOp}"?`, `A TV passa a trocar isso com o ${s.nome}.`, 'Ligar', `${nomeOp}: ligado.`); return; }
        try { desenharServicos(await chamar('POST', '/api/servicos', corpo)); } catch (e) { mostrar($('statusServicos'), textoErro(e)); }
      });
      caixa.append(linha);
    }
    caixa.append(botao('Sincronizar agora', async (ev) => {
      const b = ev.currentTarget; b.disabled = true; mostrar($('statusServicos'), 'Sincronizando…');
      try { desenharServicos(await chamar('POST', '/api/servicos', { acao: 'sincronizar', servico: s.id })); mostrar($('statusServicos'), 'Sincronizado.'); }
      catch (e) { b.disabled = false; mostrar($('statusServicos'), textoErro(e)); }
    }, false));
  }
  if (s.id !== 'simkl') {
    const resumo = document.createElement('p'); resumo.className = 'aviso-curto';
    resumo.textContent = r.listas.length ? `Listas no catálogo: ${r.listas.map((l) => l.n).join(', ')}.` : 'Nenhuma lista no catálogo.';
    const escolha = document.createElement('div');
    caixa.append(resumo, botao('Escolher listas', async (ev) => {
      const b = ev.currentTarget; b.disabled = true; mostrar($('statusServicos'), 'Buscando as suas listas…');
      try {
        const resp = await chamar('POST', '/api/servicos', { acao: 'listasDisponiveis', servico: s.id });
        mostrar($('statusServicos'), '');
        desenharEscolhaListas(escolha, resp, agir);
      } catch (e) { mostrar($('statusServicos'), textoErro(e)); }
      b.disabled = false;
    }, false), escolha);
  }
  if (s.id === 'trakt' || s.id === 'simkl') {
    const f = document.createElement('p'); f.className = 'aviso-curto';
    f.textContent = s.id === 'trakt' ? 'Dados de Trakt.tv.' : 'Dados de Simkl.com.';
    caixa.append(f);
  }
  bloco.append(caixa);
}

function desenharEscolhaListas(escolha, resp, agir) {
  escolha.replaceChildren();
  // As já escolhidas aparecem mesmo se a rede não trouxe, para poder desmarcar.
  const todas = [...resp.listas];
  for (const l of resp.disponiveis || []) if (!todas.some((x) => x.chave === l.chave)) todas.push(l);
  if (!todas.length) { const p = document.createElement('p'); p.className = 'aviso-curto'; p.textContent = 'Não achei listas nesta conta.'; escolha.append(p); return; }
  const marcadas = new Set(resp.listas.map((l) => l.chave));
  const dica = document.createElement('p'); dica.className = 'aviso-curto';
  dica.textContent = `Cada lista vira uma categoria em Filmes e Séries (até ${resp.maxListas}). Só aparecem os títulos que estão na sua lista de canais.`;
  escolha.append(dica);
  for (const l of todas) {
    const linha = document.createElement('label'); linha.className = 'linha-check';
    const c = document.createElement('input'); c.type = 'checkbox'; c.checked = marcadas.has(l.chave);
    c.addEventListener('change', () => {
      if (c.checked && marcadas.size >= resp.maxListas) { c.checked = false; mostrar($('statusServicos'), `No máximo ${resp.maxListas} listas.`); return; }
      if (c.checked) marcadas.add(l.chave); else marcadas.delete(l.chave);
    });
    const t = document.createElement('span');
    t.textContent = ` ${l.n} · ${l.s === 'trakt' ? 'Trakt' : 'MDBList'} · ${l.t} títulos${l.d ? ' · ' + l.d : ''}`;
    linha.append(c, t); escolha.append(linha);
  }
  const gravar = document.createElement('button'); gravar.type = 'button'; gravar.className = 'botao-secundario'; gravar.textContent = 'Gravar listas';
  gravar.addEventListener('click', () => agir({ acao: 'escolherListas', listas: todas.filter((l) => marcadas.has(l.chave)) },
    'Gravar as listas?', 'As listas marcadas aparecem como categorias em Filmes e Séries.', 'Gravar', 'Listas gravadas.'));
  escolha.append(gravar);
}

async function carregarServicos() {
  try { desenharServicos(await chamar('GET', '/api/servicos')); } catch (e) { mostrar($('statusServicos'), textoErro(e)); }
}

// ---------- saúde (PainelSaude.kt) ----------

function mb(b) { if (!Number.isFinite(b)) return '—'; return `${Math.round(b / 1048576).toLocaleString('pt-BR')} MB`; }
function gb(b) { return `${(b / 1073741824).toLocaleString('pt-BR', { maximumFractionDigits: 1 })} GB`; }
function duracao(ms) {
  const min = Math.floor(ms / 60000), h = Math.floor(min / 60), d = Math.floor(h / 24);
  return d ? `${d} d ${h % 24} h` : h ? `${h} h ${min % 60} min` : `${min} min`;
}

function desenharNumeros(s, caixa = $('numerosSaude')) {
  caixa.replaceChildren();
  const item = (rotulo, valor, ruim) => {
    const d = document.createElement('div');
    d.className = 'numero' + (ruim ? ' ruim' : '');
    const v = document.createElement('strong'); v.textContent = valor;
    const r = document.createElement('span'); r.textContent = rotulo;
    d.append(v, r); caixa.appendChild(d);
  };
  // Até 1 GB em MB; acima, em GB ("1.812 MB" quebrava a linha no celular).
  const tam = (b) => (b >= 1073741824 ? gb(b) : mb(b));
  const n = (x) => Number.isFinite(x);   // campo ausente (TV antiga, leitura que falhou): pula a linha em vez de "NaN MB"
  if (n(s.ramLivre) && n(s.ramTotal)) item(`RAM livre de ${tam(s.ramTotal)}`, tam(s.ramLivre), s.poucaMemoria);   // o número grande é só o livre: cabe no celular
  if (s.swapTotal) item(`swap livre de ${mb(s.swapTotal)}`, mb(s.swapLivre), s.swapLivre < s.swapTotal * 0.1);
  if (s.discoTotal) item(`espaço livre de ${gb(s.discoTotal)}`, gb(s.discoLivre), s.discoLivre < s.discoTotal * 0.1);
  const maisQuente = (s.temperaturas || []).reduce((a, t) => (!a || t.celsius > a.celsius ? t : a), null);
  if (maisQuente) item('temperatura', `${maisQuente.celsius.toLocaleString('pt-BR')} °C`, maisQuente.celsius >= 80);
  if (s.termico) item('estado térmico', s.termico, !['normal', 'desconhecido'].includes(s.termico));
  if (n(s.ligadaHa)) item('ligada há', duracao(s.ligadaHa), false);
  // Ideia 41: tendência do espaço livre (uma medida por dia).
  if (n(s.discoDiasAteEncher)) item('o armazenamento enche, nesse ritmo', s.discoDiasAteEncher < 14 ? `em ${s.discoDiasAteEncher} dias` : `em ~${Math.round(s.discoDiasAteEncher / 7)} semanas`, s.discoDiasAteEncher <= 21);
  else if (n(s.discoMedidas) && s.discoTotal) item('espaço livre, tendência', s.discoMedidas < 5 ? 'medindo…' : 'estável', false);
  if (!caixa.children.length) item('ainda medindo', '…', false);
}

/** Nome de cada passo da manutenção da noite (ManutencaoNoturna.kt) e do resultado, em português simples. */
const PASSOS_MANUTENCAO = { acesso: 'acesso (shell e túnel)', permissoes: 'permissões', limpeza: 'limpeza', agendamentos: 'despertadores e agendamentos', espaco: 'espaço livre' };
const RESULTADOS_MANUTENCAO = { ok: 'ok', consertado: 'consertado', falhou: 'falhou', pulado: 'pulado' };

/** Cartão "Acesso remoto": shell próprio, túnel do Claude e a última manutenção (SaudeAcesso.kt). */
function desenharAcesso(a) {
  const caixa = $('numerosAcesso');
  caixa.replaceChildren();
  if (!a) { mostrar($('acaoAcesso'), 'Esta versão do launcher ainda não mostra o acesso remoto.'); return; }
  const item = (rotulo, valor, ruim) => {
    const d = document.createElement('div');
    d.className = 'numero' + (ruim ? ' ruim' : '');
    const v = document.createElement('strong'); v.textContent = valor;
    const r = document.createElement('span'); r.textContent = rotulo;
    d.append(v, r); caixa.appendChild(d);
  };
  const estado = (x, desligado) => (x.estado === 'ok' ? 'no ar'
    : x.estado === 'fora' ? `fora há ${duracao((x.foraHaMin || 0) * 60000)}` : desligado);
  item('shell do launcher', estado(a.shell, 'sem leitura ainda'), a.shell.estado === 'fora');
  item('túnel do Claude', a.tunel.faltaLogin ? 'falta entrar no Tailscale' : estado(a.tunel, 'desligado'),
    a.tunel.estado === 'fora' || !!a.tunel.faltaLogin);
  const m = a.manutencao || {};
  const falhas = (m.passos || []).filter((p) => p.resultado === 'falhou').length;
  item('manutenção da noite', m.em ? `${new Date(m.em).toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })}`
    + (falhas ? ` · ${falhas} falha(s)` : ' · ok') : 'ainda não rodou', falhas > 0);
  // O que fazer (texto da própria TV) e o erro do túnel, se houver.
  mostrar($('acaoAcesso'), [a.acao, a.tunel.erro ? `Túnel: ${a.tunel.erro}` : ''].filter(Boolean).join(' '));
  const lista = $('listaManutencao');
  lista.replaceChildren();
  for (const p of m.passos || []) {
    const linha = document.createElement('div');
    linha.className = 'linha-registro';
    const q = document.createElement('span'); q.className = 'quando';
    q.textContent = RESULTADOS_MANUTENCAO[p.resultado] || p.resultado;
    linha.append(q, ' ', PASSOS_MANUTENCAO[p.nome] || p.nome, p.detalhe ? ` · ${p.detalhe}` : '');
    lista.appendChild(linha);
  }
}

// ---------- erros do launcher -> issue no GitHub (ErrosFiltrados.kt, 11/11/2026) ----------
// A TV não guarda token do GitHub: o link abre a página de issue nova com o texto pronto, e quem envia é a pessoa.

const REPO_ISSUES = 'https://github.com/ChrnX0/sofaly/issues/new';
const MAX_URL_ISSUE = 7000;   // o GitHub recusa endereços muito longos; corta erros inteiros, nunca no meio

/** Texto da issue a partir da resposta de /api/extras/erros. Só campos já filtrados na TV. */
function textoIssue(r, limite = MAX_URL_ISSUE) {
  let corpo = `Relatado pelo painel da TV.\n\n- Versão: ${r.versao_atual}\n- Erros nesta versão: ${r.erros_nesta_versao}` +
    ` (de ${r.total_guardados} guardados)\n`;
  let incluidos = 0;
  for (const e of r.erros || []) {
    const bloco = `\n### ${e.tipo || 'erro'} (${e.dia || 'sem data'}, versão ${e.versao || '?'})\n\n\`\`\`\n` +
      (e.pilha || []).slice(0, 15).join('\n') + '\n\`\`\`\n';
    if (encodeURIComponent(corpo + bloco).length > limite) break;
    corpo += bloco; incluidos++;
  }
  const primeiro = (r.erros || [])[0];
  const titulo = `Erro na TV: ${primeiro ? (primeiro.tipo || 'erro') : 'sem erros'} (${r.versao_atual})`;
  return { titulo, corpo, incluidos };
}

async function carregarErros() {
  const resumo = $('resumoErros');
  const link = $('linkRelatar');
  try {
    const r = await chamar('GET', '/api/extras/erros');
    const n = (r.erros || []).length;
    if (n === 0) { resumo.textContent = 'Nenhum erro guardado.'; link.hidden = true; return; }
    resumo.textContent = r.erros_nesta_versao > 0
      ? `${r.erros_nesta_versao} erro(s) nesta versão, ${r.total_guardados} guardados no total.`
      : `Nenhum erro nesta versão. ${r.total_guardados} guardado(s) de versões anteriores.`;
    const t = textoIssue(r);
    link.href = `${REPO_ISSUES}?${new URLSearchParams({ title: t.titulo, body: t.corpo })}`;
    link.hidden = t.incluidos === 0;
  } catch (e) { resumo.textContent = textoErro(e); link.hidden = true; }
}

// ---------- Observador (ObserverTv.kt, fase 1: só observa e anota) ----------

/** Nome de cada achado do juiz (ObserverCore.Kind), em português simples. */
const ACHADOS_OBSERVADOR = { NO_INTERNET: 'sem internet', SLOW_INTERNET: 'internet lenta', WEAK_WIFI: 'Wi-Fi fraco',
  LOW_MEMORY: 'pouca memória', MEMORY_PRESSURE: 'memória apertada', SLOW_HOME: 'tela inicial lenta', LOW_DISK: 'pouco espaço',
  // Fase 2: dentro dos limites, mas fora do normal desta casa nesta hora (ObserverBaseline).
  UNUSUAL_LATENCY: 'internet diferente do normal', UNUSUAL_MEMORY: 'memória diferente do normal',
  UNUSUAL_WIFI: 'Wi-Fi diferente do normal', UNUSUAL_FRAMES: 'tela inicial diferente do normal' };

/** Cartão "Observador": as últimas leituras, o que está fora do normal agora e o registro (mais novo primeiro). */
async function carregarObservador() {
  const caixa = $('numerosObservador'), agora = $('agoraObservador'), lista = $('listaObservador');
  let o;
  try { o = await chamar('GET', '/api/observer'); } catch (e) {
    caixa.replaceChildren(); lista.replaceChildren();
    agora.textContent = e && e.status === 404 ? 'Esta versão do launcher ainda não tem o Observador.' : textoErro(e);
    return;
  }
  const atuais = new Set((o.now || []).map((f) => f.kind));
  desenharSofy(o.now);
  caixa.replaceChildren();
  const item = (rotulo, valor, ruim) => {
    const d = document.createElement('div');
    d.className = 'numero' + (ruim ? ' ruim' : '');
    const v = document.createElement('strong'); v.textContent = valor;
    const r = document.createElement('span'); r.textContent = rotulo;
    d.append(v, r); caixa.appendChild(d);
  };
  const s = o.signals || {};
  const num = (x) => x.value.toLocaleString('pt-BR');
  if (s.MEMORY_AVAILABLE_MB) item('memória livre', `${num(s.MEMORY_AVAILABLE_MB)} MB`, atuais.has('LOW_MEMORY'));
  if (s.LATENCY_MS) item('latência', `${num(s.LATENCY_MS)} ms`, atuais.has('SLOW_INTERNET'));
  if (s.WIFI_DBM && s.WIFI_DBM.value > -127 && s.WIFI_DBM.value < 0) item('sinal do Wi-Fi', `${s.WIFI_DBM.value} dBm`, atuais.has('WEAK_WIFI'));
  if (s.SLOW_FRAMES_PCT) item('quadros lentos', `${num(s.SLOW_FRAMES_PCT)}%`, atuais.has('SLOW_HOME'));
  if (s.DISK_FREE_MB) item('espaço livre', s.DISK_FREE_MB.value >= 1024 ? gb(s.DISK_FREE_MB.value * 1048576) : `${num(s.DISK_FREE_MB)} MB`, atuais.has('LOW_DISK'));
  if (!caixa.children.length) item('ainda medindo', '…', false);
  // Cada parte num nó próprio: o rótulo e o porquê ("latência de 450 ms (limite 400)") são traduzidos um a um (i18n P).
  agora.replaceChildren();
  if (!(o.now || []).length) agora.textContent = 'Tudo normal agora.';
  (o.now || []).forEach((f, i) => {
    if (i) agora.append(' · ');
    const nome = document.createElement('strong'); nome.textContent = ACHADOS_OBSERVADOR[f.kind] || f.kind;
    const porque = document.createElement('span'); porque.textContent = f.why;
    agora.append(nome, ' ', porque);
  });
  lista.replaceChildren();
  for (const l of o.log || []) {
    const linha = document.createElement('div');
    linha.className = 'linha-registro';
    const q = document.createElement('span'); q.className = 'quando';
    q.textContent = new Date(l.at).toLocaleString(document.documentElement.lang || 'pt-BR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
    const nome = document.createElement('strong');
    nome.textContent = EVENTOS_OBSERVADOR[l.event] || ACHADOS_OBSERVADOR[l.kind] || l.kind;
    const porque = document.createElement('span'); porque.textContent = l.why;   // na sonda: a hora aprendida ("3h")
    linha.append(q, ' ', nome, ' ', porque);
    lista.appendChild(linha);
  }
  if (!lista.children.length) lista.appendChild(Object.assign(document.createElement('p'), { className: 'aviso-curto', textContent: 'Nada fora do normal nos últimos 7 dias.' }));
}

/** A Sofy no cartão do Observador: cara do estado de agora (toca o movimento quando a cara muda) e a chave da voz. */
async function desenharSofy(agora) {
  const lugar = $('sofyObservador'), chave = $('sofyFala');
  if (!lugar || typeof Sofy === 'undefined') return;
  let d;
  try { d = await Sofy.carregar(); } catch (e) { lugar.closest('.sofy-linha').hidden = true; return; }   // versão sem a Sofy
  const expr = Sofy.expressaoDoObservador(agora);
  let svg = lugar.querySelector('svg');
  if (!svg) { svg = Sofy.criar(expr, { tamanho: 84 }); svg.setAttribute('aria-hidden', 'true'); lugar.appendChild(svg); Sofy.tocar(svg, expr); }
  else if (svg._sofy.expr !== expr) Sofy.tocar(svg, expr);
  chave.checked = !!d.voice_on;
  if (!chave.dataset.ligada) {
    chave.dataset.ligada = '1';
    chave.addEventListener('change', async () => {
      try { const r = await chamar('POST', '/api/sofy', { voice: chave.checked }); d.voice_on = r.voice_on; Sofy.tocar(svg, chave.checked ? 'HAPPY' : 'SLEEPING'); }
      catch (e) { chave.checked = !chave.checked; }
    });
  }
}

/** Linhas do registro que não são achados: a sonda da madrugada e a prova de congelamento (fase 3). */
const EVENTOS_OBSERVADOR = {
  night: 'sonda da madrugada',
  trial_start: 'congelado, em prova',
  trial_kept: 'congelado e conferido',
  trial_undone: 'congelamento desfeito',
};

async function carregarSaude() {
  carregarErros();   // em paralelo: não espera a lista de memória
  carregarObservador();   // em paralelo também: lê só memória e prefs da TV
  const status = $('statusSaude');
  const lista = $('listaMemoria');
  mostrar(status, '');
  try {
    const rapido = await chamar('GET', '/api/saude?rapido=1');   // os números na hora; a lista leva uns segundos
    desenharNumeros(rapido);
    desenharAcesso(rapido.acesso);
    lista.replaceChildren(Object.assign(document.createElement('p'), { className: 'aviso', textContent: 'Lendo…' }));
    const s = await chamar('GET', '/api/saude');
    desenharNumeros(s);
    lista.replaceChildren();
    if (s.semShell) { mostrar(status, 'O shell do launcher não respondeu: sem a lista de memória e sem liberar (a depuração sem fio está ligada?).'); return; }
    for (const a of s.apps || []) {
      const linha = document.createElement('div');
      linha.className = 'linha-registro';
      const q = document.createElement('span'); q.className = 'quando'; q.textContent = mb(a.kb * 1024);
      linha.append(q, ' ', a.nome);
      if (a.nome !== a.processo) { const p = document.createElement('span'); p.className = 'aviso-curto'; p.textContent = ` ${a.processo}`; linha.appendChild(p); }
      lista.appendChild(linha);
    }
  } catch (e) { mostrar(status, textoErro(e)); }
}

$('btnLiberar').addEventListener('click', async () => {
  const b = $('btnLiberar');
  b.disabled = true;
  mostrar($('statusSaude'), 'Liberando…');
  try {
    const r = await chamar('POST', '/api/saude/liberar');
    mostrar($('statusSaude'), r.ganho > 0 ? `Pronto: ${mb(r.ganho)} a mais de RAM livre.` : 'Pronto. Não havia muito o que fechar.');
    await carregarSaude().catch(() => {});
    if (r.ganho > 0) mostrar($('statusSaude'), `Pronto: ${mb(r.ganho)} a mais de RAM livre.`);
  } catch (e) { mostrar($('statusSaude'), textoErro(e)); }
  b.disabled = false;
});

// ---------- fotos do protetor (FotosProtetor.kt) ----------

/** Nome que a TV aceita: troca o que não for letra/número/espaço/.-_ por "_" e garante a extensão. */
function nomeDeFoto(nome) {
  const ext = (/\.(jpe?g|png|webp)$/i.exec(nome) || ['.jpg'])[0].toLowerCase();
  let base = nome.replace(/\.[^.]*$/, '').replace(/[^\p{L}\p{N} ._-]/gu, '_').replace(/^\.+/, '').slice(0, 90);
  if (base.length < 1) base = 'foto';
  return base + ext;
}

async function carregarFotos() {
  const grade = $('gradeFotos');
  grade.replaceChildren();
  let r;
  try { r = await chamar('GET', '/api/fotos'); } catch (e) { mostrar($('statusFotos'), textoErro(e)); return; }
  if (!r.fotos.length) { mostrar($('statusFotos'), 'Nenhuma foto ainda: o protetor mostra só o relógio.'); return; }
  mostrar($('statusFotos'), `${r.fotos.length} foto(s) na TV.`);
  for (const f of r.fotos) {
    const caixa = document.createElement('figure');
    caixa.className = 'foto';
    const img = document.createElement('img');
    img.loading = 'lazy'; img.alt = f.nome;
    img.src = `/api/fotos/ver?nome=${encodeURIComponent(f.nome)}`;
    const legenda = document.createElement('figcaption');
    const apagar = document.createElement('button');
    apagar.type = 'button'; apagar.className = 'link'; apagar.textContent = 'apagar';
    apagar.addEventListener('click', async () => {
      try { await chamar('POST', `/api/fotos/apagar?nome=${encodeURIComponent(f.nome)}`); carregarFotos(); }
      catch (e) { mostrar($('statusFotos'), textoErro(e)); }
    });
    legenda.append(f.nome, ' · ', apagar);
    caixa.append(img, legenda);
    grade.appendChild(caixa);
  }
}

$('arquivoFotos').addEventListener('change', async (ev) => {
  const arquivos = [...ev.target.files];
  ev.target.value = '';
  let ok = 0;
  for (const [i, a] of arquivos.entries()) {
    try {
      await enviarComProgresso(`/api/fotos/enviar?nome=${encodeURIComponent(nomeDeFoto(a.name))}`, a, (p) =>
        mostrar($('statusFotos'), `Enviando ${i + 1} de ${arquivos.length}… ${Math.round(p * 100)}%`));
      ok++;
    } catch (e) { mostrar($('statusFotos'), `${a.name}: ${textoErro(e)}`); await new Promise((r) => setTimeout(r, 1500)); }
  }
  await carregarFotos();
  if (arquivos.length) mostrar($('statusFotos'), `${ok} de ${arquivos.length} foto(s) enviada(s).`);
});

// ---------- foto do perfil (FotoPerfil.kt) ----------

async function carregarPerfis() {
  const lista = $('listaPerfis');
  let r;
  try { r = await chamar('GET', '/api/perfil'); } catch (e) { mostrar($('statusPerfil'), textoErro(e)); return; }
  lista.replaceChildren();
  $('dicaPerfilGoogle').hidden = !r.google;
  if (!r.contas.length) { mostrar($('statusPerfil'), 'A TV está sem conta Google (ou o launcher está sem a permissão de ver as contas).'); return; }
  for (const c of r.contas) {
    const q = `conta=${encodeURIComponent(c.conta)}`;
    const linha = document.createElement('div');
    linha.className = 'perfil-linha';
    let rosto;
    if (c.foto) {
      rosto = document.createElement('img');
      rosto.alt = '';
      rosto.src = `/api/perfil/foto?${q}&v=${c.foto}`;   // "v" muda quando a foto muda: o navegador não mostra a antiga
    } else {
      rosto = document.createElement('span');
      rosto.className = 'sem-foto';
      rosto.textContent = c.conta.charAt(0).toUpperCase();
    }
    const nome = document.createElement('span');
    nome.className = 'nome';
    nome.textContent = c.conta + (c.foto ? (c.manual ? ' · foto enviada por você' : ' · foto da conta Google') : ' · sem foto');
    // Nome que a TV mostra no lugar do usuário do e-mail (Perfil.nome).
    const apelido = document.createElement('input');
    apelido.type = 'text'; apelido.maxLength = 30; apelido.value = c.nome || '';
    apelido.placeholder = c.conta.split('@')[0];
    apelido.setAttribute('aria-label', 'Nome na TV');
    apelido.addEventListener('change', async () => {
      try {
        const r2 = await chamar('POST', '/api/perfil/nome', { conta: c.conta, nome: apelido.value });
        mostrar($('statusPerfil'), `Na TV este perfil aparece como “${r2.nome}”.`);
      } catch (e) { mostrar($('statusPerfil'), textoErro(e)); }
    });
    const escolher = document.createElement('input');
    escolher.type = 'file'; escolher.accept = 'image/jpeg,image/png,image/webp'; escolher.hidden = true;
    escolher.addEventListener('change', async () => {
      const a = escolher.files[0];
      escolher.value = '';
      if (!a) return;
      try {
        await enviarComProgresso(`/api/perfil/foto?${q}`, a, (p) => mostrar($('statusPerfil'), `Enviando… ${Math.round(p * 100)}%`));
        mostrar($('statusPerfil'), 'Foto trocada. Ela aparece na TV quando a tela inicial for aberta de novo.');
      } catch (e) { mostrar($('statusPerfil'), textoErro(e)); }
      carregarPerfis();
    });
    const enviar = document.createElement('button');
    enviar.type = 'button'; enviar.className = 'botao-primario'; enviar.textContent = c.foto ? 'Trocar foto…' : 'Enviar foto…';
    enviar.addEventListener('click', () => escolher.click());
    linha.append(rosto, nome, apelido, escolher, enviar);
    if (c.foto) {
      const tirar = document.createElement('button');
      tirar.type = 'button'; tirar.className = 'link'; tirar.textContent = 'remover';
      tirar.addEventListener('click', async () => {
        try { await chamar('POST', `/api/perfil/foto/apagar?${q}`); mostrar($('statusPerfil'), 'Foto removida: a bolinha volta a mostrar a inicial.'); }
        catch (e) { mostrar($('statusPerfil'), textoErro(e)); }
        carregarPerfis();
      });
      linha.append(tirar);
    }
    lista.appendChild(linha);
  }
}

// ---------- acessos ----------

async function carregarRegistro() {
  const lista = $('listaRegistro');
  lista.replaceChildren();
  try {
    const r = await chamar('GET', '/api/registro');
    if (!r.itens.length) { const p = document.createElement('p'); p.className = 'aviso'; p.textContent = 'Nenhum acesso ainda.'; lista.appendChild(p); }
    for (const i of r.itens) {
      const linha = document.createElement('div');
      linha.className = 'linha-registro';
      const quando = document.createElement('span');
      quando.className = 'quando';
      quando.textContent = new Date(i.quando).toLocaleString('pt-BR') + ' · ' + i.ip;
      const oque = document.createElement('span');
      oque.textContent = `${i.acao}: ${i.resultado}`;
      linha.append(quando, oque);
      lista.appendChild(linha);
    }
  } catch (e) {
    const p = document.createElement('p'); p.className = 'erro'; p.textContent = textoErro(e); lista.appendChild(p);
  }
}

// ---------- TV ao vivo: contas do player (PainelWeb.tvContas; passo 4 do player universal) ----------

function ajustarFormTv() {
  const xt = $('tvTipo').value === 'xtream';
  $('tvEndereco').placeholder = xt ? 'Servidor (ex.: http://servidor:8080)' : 'Link da lista (http:// ou https://)';
  $('tvUsuario').hidden = !xt;
  $('tvSenha').hidden = !xt;
  $('tvReservas').hidden = !xt;
}
$('tvTipo').addEventListener('change', ajustarFormTv);

async function carregarTv() {
  const lista = $('listaContasTv');
  lista.replaceChildren();
  try {
    const r = await chamar('GET', '/api/tv/contas');
    // Versão da Play enxuta (PlayPolicy.Power.LIVE_TV_LISTS): o cartão das contas de TV ao vivo some.
    lista.closest('.cartao').hidden = r.existe === false;
    if (r.existe === false) return;
    if (!r.contas.length) {
      const p = document.createElement('p'); p.className = 'aviso';
      p.textContent = 'Nenhuma conta ainda. Sem conta, o disco "TV ao vivo" não aparece na HOME.';
      lista.appendChild(p);
    }
    for (const c of r.contas) {
      const linha = document.createElement('div');
      linha.className = 'linha-registro';
      const info = document.createElement('span');
      // textContent: nome e servidor vêm do que foi digitado, nunca viram HTML.
      info.textContent = `${c.nome} · ${c.tipo === 'xtream' ? 'Xtream' : 'lista'} · ${c.servidor}` + (c.temGuia ? ' · guia próprio' : '') + (c.temGuiaExtra ? ' · 2º guia' : '') + (c.reservas ? ` · ${c.reservas} reserva(s)` : '');
      const apagar = document.createElement('button');
      apagar.type = 'button'; apagar.className = 'botao-perigo'; apagar.textContent = 'Apagar';
      apagar.addEventListener('click', async () => {
        const ok = await confirmar(`Apagar a conta "${c.nome}"?`, 'Os canais dela somem do player da TV. Para voltar, é preciso cadastrar de novo.',
          'Apagar', () => chamar('POST', '/api/tv/contas/apagar', { id: c.id }));
        if (ok) { mostrar($('statusTv'), 'Conta apagada.'); carregarTv(); }
      });
      linha.append(info, apagar);
      lista.appendChild(linha);
    }
    $('formContaTv').querySelector('button[type=submit]').disabled = r.contas.length >= r.maximo;
  } catch (e) {
    const p = document.createElement('p'); p.className = 'erro'; p.textContent = textoErro(e); lista.appendChild(p);
  }
}

$('formContaTv').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const corpo = {
    tipo: $('tvTipo').value, nome: $('tvNome').value.trim(), endereco: $('tvEndereco').value.trim(),
    usuario: $('tvUsuario').value.trim(), senha: $('tvSenha').value, guia: $('tvGuia').value.trim(), guiaExtra: $('tvGuiaExtra').value.trim(),
    reservas: $('tvReservas').value.trim(),
  };
  if (!corpo.endereco) { mostrar($('statusContaTv'), 'Falta o endereço.'); return; }
  const r = await confirmar('Cadastrar a conta na TV?', 'Fica cifrada na TV. Depois, use "Testar e recarregar canais" para ver se ela funciona.',
    'Cadastrar', () => chamar('POST', '/api/tv/contas', corpo));
  if (r) {
    for (const id of ['tvNome', 'tvEndereco', 'tvUsuario', 'tvSenha', 'tvGuia', 'tvGuiaExtra', 'tvReservas']) $(id).value = '';
    mostrar($('statusContaTv'), 'Conta cadastrada.');
    carregarTv();
  }
});

$('btnCarregarTv').addEventListener('click', async () => {
  const b = $('btnCarregarTv');
  b.disabled = true;
  mostrar($('statusTv'), 'Baixando a lista e o guia de cada conta (pode levar até um minuto)...');
  try {
    const r = await chamar('POST', '/api/tv/carregar');
    const semCanal = r.contas.filter((c) => c.canais === 0).length;
    mostrar($('statusTv'), `${r.canais} canais, ${r.comGuia} com programação agora (${(r.ms / 1000).toFixed(1)} s).` +
      (semCanal ? ` ${semCanal} conta(s) sem nenhum canal: confira o link, o usuário e a senha.` : ''));
  } catch (e) { mostrar($('statusTv'), textoErro(e)); }
  b.disabled = false;
});

// ---------- roteador UniFi (só leitura; UnifiCliente.kt) ----------

const TIPOS_APARELHO = { WIRED: 'cabo', WIRELESS: 'Wi-Fi', VPN: 'VPN', TELEPORT: 'Teleport' };
let certLido = null;

function desenharRoteador(r) {
  if (document.activeElement !== $('campoEnderecoRoteador')) $('campoEnderecoRoteador').value = r.endereco || '';
  $('campoChaveRoteador').value = '';
  $('chaveAtualRoteador').textContent = r.chave ? `(guardada: ${r.chave})` : '(nenhuma guardada)';
  $('certAtualRoteador').textContent = r.certificado ? `Confirmado: ${r.certificado}` : 'Nenhum certificado confirmado ainda.';
  $('chkRoteadorLigado').checked = r.ligado;
  $('chkRoteadorAviso').checked = r.avisoNovos;
  $('infoConhecidos').textContent = r.avisoNovos ? `${r.conhecidos} aparelho(s) já conhecidos.` : '';
  $('certNovoRoteador').hidden = true;
}

async function carregarRoteador() {
  try {
    const r = await chamar('GET', '/api/roteador');
    desenharRoteador(r);
    if (r.pronto) carregarAparelhos(false);
    else { $('listaAparelhos').replaceChildren(); $('estadoRoteador').textContent = faltaRoteador(r); }
  } catch (e) { mostrar($('statusRoteador'), textoErro(e)); }
}

function faltaRoteador(r) {
  if (!r.endereco) return 'Falta o endereço do roteador.';
  if (!r.chave) return 'Falta a chave de API.';
  if (!r.certificado) return 'Falta confirmar o certificado.';
  if (!r.ligado) return 'A leitura está desligada.';
  return '';
}

async function carregarAparelhos(atualizar) {
  const lista = $('listaAparelhos');
  $('estadoRoteador').textContent = 'Lendo o roteador…';
  try {
    const r = await chamar('GET', '/api/roteador/aparelhos' + (atualizar ? '?atualizar=1' : ''));
    $('estadoRoteador').textContent = `Roteador respondendo em ${r.respostaMs} ms` + (r.versao ? ` · UniFi Network ${r.versao}` : '') +
      ` · ${r.aparelhos.length} aparelho(s) · lido às ${new Date(r.em).toLocaleTimeString('pt-BR')}`;
    lista.replaceChildren();
    for (const a of r.aparelhos) {
      const linha = document.createElement('div');
      linha.className = 'linha-registro';
      const nome = document.createElement('span');
      nome.textContent = a.nome;
      const det = document.createElement('span');
      det.className = 'quando';
      const desde = a.desde ? ' · desde ' + new Date(a.desde).toLocaleString('pt-BR') : '';
      det.textContent = [a.ip, TIPOS_APARELHO[a.tipo] || a.tipo, a.mac].filter(Boolean).join(' · ') + desde;
      linha.append(nome, det);
      lista.appendChild(linha);
    }
  } catch (e) {
    lista.replaceChildren();
    $('estadoRoteador').textContent = textoErro(e);
  }
}

async function salvarRoteador(corpo, texto) {
  mostrar($('statusRoteador'), 'Salvando…');
  try {
    const r = await chamar('POST', '/api/roteador/config', corpo);
    desenharRoteador(r);
    mostrar($('statusRoteador'), texto || 'Salvo.');
    if (r.pronto) carregarAparelhos(true); else $('estadoRoteador').textContent = faltaRoteador(r);
  } catch (e) { mostrar($('statusRoteador'), textoErro(e)); carregarRoteador(); }
}

$('formRoteador').addEventListener('submit', (ev) => {
  ev.preventDefault();
  const corpo = {};
  const end = $('campoEnderecoRoteador').value.trim();
  if (end) corpo.endereco = end;
  const chave = $('campoChaveRoteador').value.trim();
  if (chave) corpo.chave = chave;
  if (!Object.keys(corpo).length) { mostrar($('statusRoteador'), 'Preencha o endereço e/ou a chave.'); return; }
  salvarRoteador(corpo);
});
$('chkRoteadorLigado').addEventListener('change', (ev) => salvarRoteador({ ligado: ev.target.checked }));
$('chkRoteadorAviso').addEventListener('change', (ev) => salvarRoteador({ avisoNovos: ev.target.checked },
  ev.target.checked ? 'Ligado. A primeira leitura só anota os aparelhos de agora; o recado vem para os que aparecerem depois.' : 'Desligado.'));
$('btnAtualizarAparelhos').addEventListener('click', () => carregarAparelhos(true));

const TEXTO_RECONECTAR = {
  esperando_tv: 'Aperte OK no controle da TV para confirmar (ou Cancelar).',
  cancelado: 'Cancelado na TV.',
  fazendo: 'Pedindo ao roteador…',
  feito: 'Feito: a TV sai do Wi-Fi e volta em alguns segundos. Depois, toque em Diagnosticar de novo.',
  falhou: 'Não deu certo',
};

$('btnReconectarTv').addEventListener('click', async () => {
  const st = $('statusReconectar');
  try {
    let r = await chamar('POST', '/api/roteador/reconectar');
    mostrar(st, TEXTO_RECONECTAR[r.estado] || r.estado);
    // Acompanha até a TV responder (a confirmação fecha sozinha em 45 s).
    for (let i = 0; i < 30 && (r.estado === 'esperando_tv' || r.estado === 'fazendo'); i++) {
      await new Promise((ok) => setTimeout(ok, 2000));
      try { r = await chamar('GET', '/api/roteador/reconectar'); } catch (e) { continue; }   // a TV pode sumir da rede por uns segundos
      mostrar(st, (TEXTO_RECONECTAR[r.estado] || r.estado) + (r.estado === 'falhou' && r.detalhe ? `: ${r.detalhe}` : ''));
    }
  } catch (e) { mostrar(st, textoErro(e)); }
});

$('btnWifiTv').addEventListener('click', async () => {
  const dicas = $('dicasWifiTv');
  dicas.replaceChildren();
  $('estadoWifiTv').textContent = 'Medindo…';
  try {
    const w = await chamar('GET', '/api/roteador/wifi-tv');
    if (!w.noWifi) { $('estadoWifiTv').textContent = 'A TV não está no Wi-Fi (no cabo ou desconectada): nada a ajustar no Wi-Fi.'; return; }
    const partes = [`Sinal ${w.dbm} dBm`];
    if (w.banda) partes.push(`${w.banda.replace('.', ',')} GHz`);
    if (w.mbps > 0) partes.push(`link ${w.mbps} Mbps`);
    if (w.pontoDeAcesso) {
      const ap = w.pontoDeAcesso;
      partes.push(`ligada em ${ap.nome}${ap.modelo ? ' (' + ap.modelo + ')' : ''}`);
      for (const r of ap.radios) {
        const det = [r.canal != null ? `canal ${r.canal}` : '', r.larguraMhz ? `${r.larguraMhz} MHz` : '',
          r.retransmissoesPct != null ? `${Math.round(r.retransmissoesPct)}% retransmissões` : ''].filter(Boolean).join(', ');
        if (det) partes.push(`${r.ghz.replace('.', ',')} GHz: ${det}`);
      }
    } else if (!w.achadaNoRoteador) partes.push('a TV não apareceu na lista do roteador (sem dados do ponto de acesso)');
    if (w.peloRoteador) {
      const r = w.peloRoteador;
      const visto = [r.sinalDbm != null ? `${r.sinalDbm} dBm` : '', r.banda ? `${r.banda.replace('.', ',')} GHz` : '',
        r.canal != null ? `canal ${r.canal}` : '', r.txMbps ? `${r.txMbps}/${r.rxMbps} Mbps` : '',
        r.satisfacao != null ? `nota ${r.satisfacao}/100` : ''].filter(Boolean).join(', ');
      if (visto) partes.push(`o roteador vê: ${visto}`);
    } else if (w.falhaApiAntiga) partes.push(`sinal pelo roteador indisponível (${w.falhaApiAntiga})`);
    $('estadoWifiTv').textContent = partes.join(' · ');
    const lista = w.dicas.length ? w.dicas : ['Wi-Fi da TV está bom: nada a ajustar.'];
    for (const d of lista) { const li = document.createElement('li'); li.textContent = d; dicas.appendChild(li); }
  } catch (e) { $('estadoWifiTv').textContent = textoErro(e); }
});

$('btnLerCert').addEventListener('click', async () => {
  $('certNovoRoteador').hidden = true;
  mostrar($('statusRoteador'), 'Lendo o certificado (a chave não vai junto)…');
  try {
    const c = await chamar('POST', '/api/roteador/certificado');
    certLido = c.impressao;
    $('certNovoTexto').textContent = `SHA-256: ${c.impressao}\nEmitido para: ${c.emitidoPara}\nVálido até: ${new Date(c.validoAte).toLocaleDateString('pt-BR')}`;
    $('certNovoRoteador').hidden = c.igualAoConfirmado;
    mostrar($('statusRoteador'), c.igualAoConfirmado ? 'É o mesmo certificado já confirmado.' : 'Confira a impressão antes de confiar.');
  } catch (e) { mostrar($('statusRoteador'), textoErro(e)); }
});

$('btnConfiarCert').addEventListener('click', async () => {
  try {
    const r = await chamar('POST', '/api/roteador/confiar', { impressao: certLido });
    desenharRoteador(r);
    mostrar($('statusRoteador'), 'Certificado confirmado.');
    if (r.pronto) carregarAparelhos(true); else $('estadoRoteador').textContent = faltaRoteador(r);
  } catch (e) { mostrar($('statusRoteador'), textoErro(e)); }
});

$('btnEsquecerRoteador').addEventListener('click', async () => {
  const r = await confirmar('Esquecer o roteador?', 'Apaga o endereço, a chave guardada, o certificado e a lista de aparelhos conhecidos.',
    'Esquecer', () => chamar('POST', '/api/roteador/esquecer'));
  if (r) { desenharRoteador(r); $('listaAparelhos').replaceChildren(); $('estadoRoteador').textContent = faltaRoteador(r); }
});

// ---------- Claude pela internet (McpServidor.kt + TunelTailscale.kt) ----------

let qrMcpAtual = null;
let timerMcp = null;
function desenharMcp(r) {
  $('chkMcpLigado').checked = r.ligado;
  $('chkMcpRede').checked = r.rede;
  if (document.activeElement !== $('campoAtalhos')) $('campoAtalhos').value = r.atalhos || '';
  $('estadoMcp').textContent = r.ligado ? 'Ligado.' : 'Desligado.';
  $('passoTitulo').textContent = r.passo.titulo;
  $('passoTexto').textContent = r.passo.texto;
  const qr = r.passo.qr;
  $('passoQr').hidden = !qr;
  $('passoLink').hidden = !qr;
  if (qr && qr !== qrMcpAtual) $('passoQr').src = '/api/mcp/qr?t=' + Date.now();
  if (qr) $('passoLink').href = qr;
  qrMcpAtual = qr;
  $('estadoGoogle').textContent = r.google ? 'Ligado: só entra conta Google de um perfil da TV.' : 'Desligado: o login usa o PIN do painel.';
  $('loginsMcp').textContent = r.logins + ' login(s) ativo(s).' + (r.conta ? ' Conta Tailscale: ' + r.conta + '.' : '');
  // Enquanto a aba está aberta e falta um passo, confere de novo a cada 3 s.
  clearTimeout(timerMcp);
  // Só com a página à vista: a consulta renova a sessão, e com a aba do navegador escondida ela nunca venceria.
  // Ao voltar a ficar visível, o visibilitychange (fim do arquivo) recomeça.
  if (abaAberta('claude') && document.visibilityState === 'visible' && r.passo.id !== 'pronto' && r.passo.id !== 'desligado') timerMcp = setTimeout(carregarMcp, 3000);
}

async function carregarMcp() {
  try { desenharMcp(await chamar('GET', '/api/mcp')); } catch (e) { $('estadoMcp').textContent = textoErro(e); }
}

async function salvarMcp(corpo, texto) {
  mostrar($('statusMcp'), 'Salvando…');
  try { desenharMcp(await chamar('POST', '/api/mcp/config', corpo)); mostrar($('statusMcp'), texto || 'Salvo.'); setTimeout(carregarMcp, 4000); }
  catch (e) { mostrar($('statusMcp'), textoErro(e)); carregarMcp(); }
}

/**
 * Ligar o que abre a TV para fora (MCP, rede Tailscale, atalhos) pede o PIN de novo (PainelWeb.mcpConfig): passa pelo
 * quadro de confirmação, que mostra o campo de PIN quando precisa. Cancelou: desmarca a caixa. Desligar não pergunta.
 */
async function abrirMcp(corpo, titulo, texto, ok, caixa) {
  mostrar($('statusMcp'), '');
  const r = await confirmar(titulo, texto, 'Ligar', () => chamar('POST', '/api/mcp/config', corpo));
  if (r) { desenharMcp(r); mostrar($('statusMcp'), ok); setTimeout(carregarMcp, 4000); }
  else { if (caixa) caixa.checked = false; carregarMcp(); }
}

$('chkMcpLigado').addEventListener('change', (ev) => {
  if (!ev.target.checked) { salvarMcp({ ligado: false }); return; }
  abrirMcp({ ligado: true }, 'Ligar o acesso pelo Claude?',
    'A TV passa a responder pela internet (Tailscale Funnel). Quem entrar precisa do PIN.', 'Ligado.', ev.target);
});
$('formAtalhos').addEventListener('submit', (ev) => {
  ev.preventDefault();
  const atalhos = $('campoAtalhos').value;
  if (!atalhos.trim()) { salvarMcp({ atalhos }, 'Atalhos apagados.'); return; }
  abrirMcp({ atalhos }, 'Salvar os atalhos?',
    'Apps da TV vão alcançar esses aparelhos pela rede Tailscale.', 'Atalhos salvos. O túnel reinicia em alguns segundos.', null);
});
$('chkMcpRede').addEventListener('change', (ev) => {
  if (!ev.target.checked) { salvarMcp({ rede: false }, 'Desligado.'); return; }
  abrirMcp({ rede: true }, 'Abrir pela rede Tailscale?',
    'O painel passa a abrir pelos aparelhos da sua conta Tailscale (ainda pede o PIN).', 'Ligado. O túnel reinicia em alguns segundos.', ev.target);
});
$('formMcp').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const chave = $('campoChaveMcp').value.trim();
  if (!chave.startsWith('tskey-')) { mostrar($('statusMcp'), 'Cole a chave inteira (começa com tskey-).'); return; }
  // Guardar a chave pede o PIN de novo (como instalar e desinstalar).
  const r = await confirmar('Guardar a chave do Tailscale?', 'A TV entra na sua rede Tailscale e publica só o MCP na internet.',
    'Guardar', () => chamar('POST', '/api/mcp/config', { chave }));
  $('campoChaveMcp').value = '';
  if (r) { desenharMcp(r); mostrar($('statusMcp'), 'Chave guardada. O túnel sobe em alguns segundos.'); setTimeout(carregarMcp, 8000); }
});
$('formGoogle').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const id = $('campoGoogleId').value.trim(), segredo = $('campoGoogleSegredo').value.trim();
  // Trocar a segurança do login pede o PIN de novo; ao ligar, os logins atuais são revogados.
  const r = await confirmar(id ? 'Ligar o login pela conta Google?' : 'Voltar ao login com PIN?',
    id ? 'Os logins atuais são revogados: cada app entra de novo, agora com a conta Google.' : 'O login volta a pedir o PIN do painel.',
    'Salvar', () => chamar('POST', '/api/mcp/config', { google_id: id, google_segredo: segredo }));
  $('campoGoogleSegredo').value = '';
  if (r) desenharMcp(r);
});
$('btnRevogarMcp').addEventListener('click', () => salvarMcp({ revogar: true }, 'Logins revogados: o Claude pede o PIN de novo.'));
$('btnEsquecerMcp').addEventListener('click', async () => {
  const r = await confirmar('Esquecer o Tailscale?', 'A TV sai da rede Tailscale. Para voltar, cole uma chave nova.',
    'Esquecer', () => chamar('POST', '/api/mcp/config', { esquecer: true }));
  if (r) desenharMcp(r);
});

// ---------- envio com progresso (instalar APK e aba Arquivos) ----------

/**
 * Manda um arquivo inteiro como corpo do POST, mostrando o progresso. fetch() não informa o progresso do envio,
 * por isso XMLHttpRequest. O arquivo vai cru (sem multipart): o launcher grava direto no disco.
 */
function enviarComProgresso(url, arquivo, aoProgresso) {
  return new Promise((resolve, reject) => {
    const x = new XMLHttpRequest();
    x.open('POST', url);
    x.setRequestHeader('X-Painel', '1');
    x.setRequestHeader('Content-Type', 'application/octet-stream');
    x.upload.onprogress = (ev) => { if (ev.lengthComputable && aoProgresso) aoProgresso(ev.loaded / ev.total); };
    x.onload = () => {
      let dados = {};
      try { dados = JSON.parse(x.responseText); } catch (e) { /* sem corpo */ }
      if (x.status === 401) { mostrarEntrar('Sessão expirada: entre de novo.'); reject(new Error('Sessão expirada.')); return; }
      if (x.status === 403 && dados.precisaPin) { reject(new PrecisaPin(dados.erro)); return; }
      if (x.status < 200 || x.status >= 300) { const e = new Error(erroAmigavel(x.status, dados.erro)); e.dados = dados; reject(e); return; }
      resolve(dados);
    };
    // A TV fecha a conexão quando recusa no meio (sem espaço, nome repetido) ou quando a rede cai.
    x.onerror = () => reject(new Error('A conexão com a TV caiu no meio do envio (rede, ou a TV recusou o arquivo).'));
    x.send(arquivo);
  });
}

/** A sessão já tem o PIN confirmado nos últimos 5 min? Senão, pede ANTES de mandar um arquivo grande à toa. */
async function exigirPinRecente() {
  await atualizarEstado();
  if (!ultimoEstado.elevada) throw new PrecisaPin('confirme o PIN');
}

function tamanhoLegivel(b) {
  if (!Number.isFinite(b) || b < 0) return '—';   // campo ausente: traço, nunca "NaN KB"
  if (b < 1024) return b + ' B';
  const u = ['KB', 'MB', 'GB', 'TB'];
  let i = -1;
  do { b /= 1024; i++; } while (b >= 1024 && i < u.length - 1);
  return b.toLocaleString('pt-BR', { maximumFractionDigits: b < 10 ? 1 : 0 }) + ' ' + u[i];
}

// ---------- instalar APK ----------

$('arquivoApk').addEventListener('change', async (ev) => {
  const arquivo = ev.target.files[0];
  ev.target.value = '';
  if (!arquivo) return;
  if (!/\.(apk|apks|xapk)$/i.test(arquivo.name)) { mostrar($('statusApps'), 'Escolha um arquivo .apk, .apks ou .xapk.'); return; }
  const emUso = await tvEmUso();
  const r = await confirmar('Instalar ' + arquivo.name + '?',
    `Instala (ou atualiza) o app na TV, sem pedir confirmação lá (${tamanhoLegivel(arquivo.size)}). ` +
    'Só instale APK de fonte confiável: o app pode pedir permissões.' +
    (emUso ? ` Atenção: a TV está em uso agora (${emUso}); atualizar um app que está aberto fecha ele.` : ''),
    'Instalar', async () => {
      await exigirPinRecente();
      return enviarComProgresso('/api/apps/instalar?nome=' + encodeURIComponent(arquivo.name), arquivo, (f) =>
        mostrar($('progressoModal'), f < 1 ? `Enviando… ${Math.round(f * 100)}%` : 'Instalando na TV…'));
    });
  if (!r) return;
  mostrar($('statusApps'), `${r.nome} ${r.versao} instalado.`);
  carregarApps().then(() => mostrar($('statusApps'), `${r.nome} ${r.versao} instalado.`));
});

// ---------- permissões ----------

let permAtual = 0;   // resposta atrasada de um app aberto antes não sobrescreve o atual

function fecharPermissoes() { permAtual++; $('modalPerm').hidden = true; }
// Esc fecha o quadro Detalhes (o de confirmar tem o próprio Cancelar e pode estar no meio de uma ação).
document.addEventListener('keydown', (ev) => { if (ev.key === 'Escape' && !$('modalPerm').hidden && $('modal').hidden) fecharPermissoes(); });
$('btnFecharPerm').addEventListener('click', fecharPermissoes);

async function abrirPermissoes(a) {
  const meu = ++permAtual;
  $('tituloPerm').textContent = a.nome;
  $('avisoPerm').textContent = 'Carregando…';
  mostrar($('statusPerm'), '');
  $('listaPerm').replaceChildren();
  $('modalPerm').hidden = false;
  try {
    const base = '/api/apps/' + encodeURIComponent(a.pacote);
    const [f, p] = await Promise.all([chamar('GET', base + '/ficha'), chamar('GET', base + '/permissoes')]);
    if (meu !== permAtual) return;
    desenharPermissoes(a, p);
    $('listaPerm').prepend(desenharFicha(f), acoesDoApp(a));
  } catch (e) {
    if (meu === permAtual) $('avisoPerm').textContent = textoErro(e);
  }
}

function linhaPerm(rotulo, nome, direita) {
  const l = document.createElement('div');
  l.className = 'linha-perm';
  const info = document.createElement('div');
  info.className = 'info';
  const n = document.createElement('span'); n.className = 'nome'; n.textContent = rotulo;
  const t = document.createElement('span'); t.className = 'pacote'; t.textContent = nome;
  info.append(n, t);
  l.append(info, direita);
  return l;
}

function estadoTexto(sim, textoSim, textoNao) {
  const s = document.createElement('span');
  s.className = sim ? 'estado-sim' : 'estado-nao';
  s.textContent = sim ? textoSim : textoNao;
  return s;
}

/** Uma caixa de escolha que, ao mudar, chama a TV; se der errado, volta ao valor de antes e mostra o motivo. */
function escolha(opcoes, valor, aoMudar, travada) {
  const sel = document.createElement('select');
  for (const [v, t] of opcoes) { const o = document.createElement('option'); o.value = v; o.textContent = t; sel.appendChild(o); }
  sel.value = valor;
  sel.disabled = !!travada;
  let anterior = valor;
  sel.addEventListener('change', async () => {
    sel.disabled = true;
    mostrar($('statusPerm'), 'Aplicando…');
    try {
      await aoMudar(sel.value);
      anterior = sel.value;
      mostrar($('statusPerm'), 'Feito.');
    } catch (e) {
      sel.value = anterior;
      mostrar($('statusPerm'), textoErro(e));
    } finally {
      sel.disabled = false;
    }
  });
  return sel;
}

/** A ficha do app: o que faz, o risco de ocultar ou desinstalar, papéis e quem depende dele. */
function desenharFicha(f) {
  const c = document.createElement('div');
  c.className = 'ficha';
  const topo = document.createElement('div');
  topo.className = 'selos';
  topo.appendChild(selo(f.risco, 'risco-' + f.risco.replace(/\s+/g, '-')));
  if (f.sistema) topo.appendChild(selo('sistema'));
  const d = document.createElement('p'); d.textContent = f.descricao;
  const q = document.createElement('p'); q.className = 'aviso';
  q.textContent = 'Se ocultar ou desinstalar: ' + f.efeito;
  c.append(topo, d, q);
  const lista = (titulo, itens) => {
    if (!itens.length) return;
    const h = document.createElement('h3'); h.textContent = titulo; c.appendChild(h);
    const ul = document.createElement('ul');
    for (const t of itens) { const li = document.createElement('li'); li.textContent = t; ul.appendChild(li); }
    c.appendChild(ul);
  };
  if (f.espaco) {
    const e = document.createElement('p'); e.className = 'aviso-curto';
    e.textContent = `Espaço: app ${tamanhoLegivel(f.espaco.app)} · dados ${tamanhoLegivel(f.espaco.dados)} (cache ${tamanhoLegivel(f.espaco.cache)})`;
    c.appendChild(e);
  }
  lista('O que ele faz nesta TV agora', f.papeis);
  lista('Quem depende dele (pela TV)', f.usadoPor.map((u) => `${u.nome} — ${u.como}`));
  lista('Quem depende dele (pela lista)', f.dependentesConhecidos);
  const fonte = document.createElement('p'); fonte.className = 'aviso-curto';
  fonte.textContent = `Fonte da descrição: ${f.fonte}. Papéis e dependências "pela TV" são lidos do aparelho agora.`;
  c.appendChild(fonte);
  return c;
}

function desenharPermissoes(a, p) {
  const lista = $('listaPerm');
  lista.replaceChildren();
  const base = '/api/apps/' + encodeURIComponent(a.pacote);
  $('avisoPerm').textContent = p.podeMudar
    ? 'Negar uma permissão fecha o app se ele estiver aberto. "O app pede" devolve a decisão para quem estiver na TV.'
    : 'Este app é protegido: o sistema ou o launcher dependem dele. As permissões aparecem só para consulta.';

  const titulo = (t) => { const h = document.createElement('h3'); h.textContent = t; lista.appendChild(h); };

  titulo(`Permissões que se trocam (${p.perigosas.length})`);
  if (!p.perigosas.length) { const x = document.createElement('p'); x.className = 'aviso'; x.textContent = 'Nenhuma: este app não pede localização, microfone, câmera, contatos etc.'; lista.appendChild(x); }
  for (const perm of p.perigosas) {
    const direita = document.createElement('div');
    direita.className = 'acoes';
    const est = estadoTexto(perm.concedida, 'concedida', 'negada');
    const sel = escolha([['padrao', 'O app pede'], ['conceder', 'Sempre permitir'], ['negar', 'Sempre negar']], perm.politica,
      async (v) => {
        // Conceder pede o PIN de novo (PainelWeb.mudarPermissao): passa pelo quadro de confirmação.
        if (v === 'conceder') {
          const ok = await confirmar('Sempre permitir?', `${perm.rotulo}: o app passa a ter isso sem perguntar.`, 'Permitir',
            () => chamar('POST', base + '/permissao', { permissao: perm.nome, acao: v }));
          if (!ok) throw new Error('Cancelado.');
        } else await chamar('POST', base + '/permissao', { permissao: perm.nome, acao: v });
        // "O app pede" não muda o que já estava concedido; "sempre" muda.
        if (v === 'conceder') { est.className = 'estado-sim'; est.textContent = 'concedida'; }
        if (v === 'negar') { est.className = 'estado-nao'; est.textContent = 'negada'; }
      }, !p.podeMudar);
    direita.append(est, sel);
    lista.appendChild(linhaPerm(perm.rotulo, perm.nome, direita));
  }

  if (p.especiais.length) {
    titulo(`Acessos especiais (${p.especiais.length})`);
    for (const esp of p.especiais) {
      const direita = document.createElement('div');
      direita.className = 'acoes';
      const est = estadoTexto(esp.estado === 'permitido', 'permitido', esp.estado === 'desconhecido' ? '?' : 'negado');
      const sel = escolha([['padrao', 'Padrão'], ['permitir', 'Permitir'], ['negar', 'Negar']],
        esp.padrao ? 'padrao' : (esp.estado === 'permitido' ? 'permitir' : 'negar'),
        async (v) => {
          // Permitir pede o PIN de novo (PainelWeb.mudarEspecial).
          if (v === 'permitir') {
            const ok = await confirmar('Permitir?', `${esp.rotulo}: é um poder grande para um app.`, 'Permitir',
              () => chamar('POST', base + '/especial', { op: esp.op, acao: v }));
            if (!ok) throw new Error('Cancelado.');
          } else await chamar('POST', base + '/especial', { op: esp.op, acao: v });
          if (v !== 'padrao') { const s = v === 'permitir'; est.className = s ? 'estado-sim' : 'estado-nao'; est.textContent = s ? 'permitido' : 'negado'; }
        }, !p.podeMudar);
      direita.append(est, sel);
      lista.appendChild(linhaPerm(esp.rotulo, esp.op, direita));
    }
  }

  if (p.outras.length) {
    const d = document.createElement('details');
    const s = document.createElement('summary');
    s.textContent = `Outras ${p.outras.length} permissões (o Android concede na instalação; só consulta)`;
    d.appendChild(s);
    for (const o of p.outras) d.appendChild(linhaPerm(o.rotulo, o.nome, estadoTexto(o.concedida, 'concedida', 'não concedida')));
    lista.appendChild(d);
  }
}

// ---------- arquivos ----------

let pastaAtual = '';
let itensPasta = [];
let livreBytes = 0;
let pastaPedido = 0;

function juntar(pasta, nome) { return pasta ? pasta + '/' + nome : nome; }

async function abrirPasta(caminho) {
  const meu = ++pastaPedido;
  mostrar($('statusArquivos'), 'Carregando…');
  try {
    const r = await chamar('GET', '/api/arquivos?caminho=' + encodeURIComponent(caminho));
    if (meu !== pastaPedido) return;
    pastaAtual = r.caminho;
    itensPasta = r.itens;
    livreBytes = r.livre;
    desenharTrilha();
    desenharArquivos(r);
  } catch (e) {
    if (meu !== pastaPedido) return;
    mostrar($('statusArquivos'), textoErro(e));
    // Pasta sumiu (apagada na TV): volta para a raiz na próxima vez.
    if (e.dados === undefined || /não existe/i.test(e.message)) pastaAtual = '';
  }
}

function desenharTrilha() {
  const t = $('trilha');
  t.replaceChildren();
  const partes = pastaAtual ? pastaAtual.split('/') : [];
  const pedacos = [['Memória interna', '']];
  partes.forEach((p, i) => pedacos.push([p, partes.slice(0, i + 1).join('/')]));
  pedacos.forEach(([nome, caminho], i) => {
    if (i > 0) { const sep = document.createElement('span'); sep.textContent = '›'; t.appendChild(sep); }
    if (i === pedacos.length - 1) {
      const s = document.createElement('span'); s.className = 'atual'; s.textContent = nome; t.appendChild(s);
    } else {
      const b = document.createElement('button'); b.textContent = nome; b.addEventListener('click', () => abrirPasta(caminho)); t.appendChild(b);
    }
  });
}

function desenharArquivos(r) {
  const lista = $('listaArquivos');
  lista.replaceChildren();
  $('espacoArquivos').textContent = `Livre na TV: ${tamanhoLegivel(r.livre)} de ${tamanhoLegivel(r.total)}`;
  mostrar($('statusArquivos'), !r.itens.length ? 'Pasta vazia.' : (r.cortado ? 'Pasta grande: mostrando os 2000 primeiros.' : ''));
  for (const it of r.itens) {
    const linha = document.createElement('div');
    linha.className = 'linha-app linha-arquivo';
    const tipo = document.createElement('span');
    tipo.className = 'tipo';
    const tp = it.pasta ? 'pasta' : tipoDoArquivo(it.nome);   // arquivos.js
    tipo.textContent = { pasta: '📁', imagem: '🖼️', video: '🎬', audio: '🎵', texto: '📝' }[tp] || '📄';
    const info = document.createElement('div');
    info.className = 'info';
    const nome = document.createElement('button');
    nome.type = 'button';
    nome.className = 'nome nome-arquivo' + (it.pasta ? ' pasta' : '') + (tp ? ' com-previa' : '');
    nome.textContent = it.nome;
    nome.title = it.pasta ? 'Abrir a pasta' : tp ? 'Ver aqui' : 'Baixar';
    nome.addEventListener('click', () => {
      if (it.pasta) abrirPasta(juntar(pastaAtual, it.nome));
      else if (tp) abrirPrevia(it);
      else location.href = '/api/arquivos/baixar?caminho=' + encodeURIComponent(juntar(pastaAtual, it.nome));
    });
    const det = document.createElement('span');
    det.className = 'pacote';
    det.textContent = (it.pasta ? 'pasta' : tamanhoLegivel(it.tamanho)) + ' · ' + new Date(it.modificado).toLocaleString('pt-BR');
    info.append(nome, det);
    const acoes = document.createElement('div');
    acoes.className = 'acoes';
    if (tp === 'video' || tp === 'audio') {
      const ver = document.createElement('button');
      ver.type = 'button'; ver.className = 'botao-secundario'; ver.textContent = 'Ver na TV';
      ver.addEventListener('click', async () => {
        try { await chamar('POST', '/api/arquivos/tocar', { caminho: juntar(pastaAtual, it.nome) }); aviso('Tocando na TV.', 'ok'); }
        catch (e) { aviso(textoErro(e), 'erro'); }
      });
      acoes.appendChild(ver);
    }
    if (!it.pasta) {
      // Link comum: o navegador baixa direto da TV (com barra de progresso dele), sem passar o arquivo pela memória.
      const a = document.createElement('a');
      a.className = 'botao-secundario';
      a.textContent = 'Baixar';
      a.href = '/api/arquivos/baixar?caminho=' + encodeURIComponent(juntar(pastaAtual, it.nome));
      a.setAttribute('download', it.nome);
      acoes.appendChild(a);
    }
    const mais = document.createElement('button');
    mais.className = 'botao-secundario';
    mais.textContent = 'Organizar';
    mais.title = 'Renomear, mover ou apagar';
    mais.addEventListener('click', () => organizarItem(it));   // arquivos.js
    acoes.appendChild(mais);
    linha.append(tipo, info, acoes);
    lista.appendChild(linha);
  }
}

async function apagarItem(it) {
  const caminho = juntar(pastaAtual, it.nome);
  const ok = await confirmar('Apagar ' + it.nome + '?',
    it.pasta ? 'Apaga a pasta e TUDO o que está dentro dela, na TV. Não tem volta.' : 'Apaga o arquivo da TV. Não tem volta.',
    'Apagar', () => chamar('POST', '/api/arquivos/apagar', { caminho }));
  if (ok) abrirPasta(pastaAtual);
}

$('formPasta').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const nome = $('nomePasta').value.trim();
  if (!nome) return;
  if (/[\/\\]/.test(nome) || nome === '.' || nome === '..') { mostrar($('statusArquivos'), 'Nome de pasta inválido.'); return; }
  try {
    await chamar('POST', '/api/arquivos/pasta', { pasta: pastaAtual, nome });
    $('nomePasta').value = '';
    abrirPasta(pastaAtual);
  } catch (e) {
    mostrar($('statusArquivos'), textoErro(e));
  }
});

$('arquivosEnviar').addEventListener('change', (ev) => {
  const arquivos = Array.from(ev.target.files);
  ev.target.value = '';
  enviarArquivos(arquivos);
});

// Arrastar arquivos do computador para a aba Arquivos envia para a pasta aberta (mesmo caminho do botão).
$('abaArquivos').addEventListener('dragover', (ev) => {
  if (![...(ev.dataTransfer?.types || [])].includes('Files')) return;
  ev.preventDefault();
  $('abaArquivos').dataset.soltar = 'Solte para enviar para ' + (pastaAtual ? pastaAtual.split('/').pop() : 'a memória interna');
  $('abaArquivos').classList.add('soltando');
});
$('abaArquivos').addEventListener('dragleave', (ev) => { if (ev.target === $('abaArquivos')) $('abaArquivos').classList.remove('soltando'); });
$('abaArquivos').addEventListener('drop', (ev) => {
  $('abaArquivos').classList.remove('soltando');
  if (!ev.dataTransfer?.files?.length) return;
  ev.preventDefault();
  // Pasta arrastada vem como "arquivo" de 0 byte sem tipo: o envio dela falharia; avisa em vez de mandar.
  const arquivos = [...ev.dataTransfer.files].filter((f) => f.size > 0 || f.type);
  if (arquivos.length < ev.dataTransfer.files.length) aviso('Pastas não são enviadas: arraste os arquivos de dentro delas.', 'erro');
  enviarArquivos(arquivos);
});

// Ideia 17 (estudo global 20/11): arrastar um vídeo, música ou foto para o Controle e ver na TV num gesto.
// Por quê: mostrar o vídeo da viagem era copiar para a TV e procurar o arquivo com a cruz. Como: foto vai como recado
// com foto (a mesma rota do cartão Foto); vídeo e música vão para a pasta Movies da TV e tocam no player do Sofaly.
const PASTA_NA_TV = 'Movies';
// Correção 20/11 (teste do Paulo): o arquivo solto FORA da seção do Controle (cabeçalho, menu, margem) caía no
// navegador, que abria o vídeo numa aba nova. Agora a página inteira recebe: na aba Arquivos, envia para a pasta
// aberta (ouvinte próprio dela); em qualquer outra aba, manda para a TV e toca.
const naAbaArquivos = (ev) => $('abaArquivos').contains(ev.target) && !$('abaArquivos').hidden;
document.addEventListener('dragover', (ev) => {
  if (![...(ev.dataTransfer?.types || [])].includes('Files')) return;
  ev.preventDefault();   // sem isto o navegador abre o arquivo ao soltar
  if (naAbaArquivos(ev)) return;
  $('abaControle').dataset.soltar = 'Solte para ver na TV';
  if (!$('abaControle').hidden) $('abaControle').classList.add('soltando');
});
document.addEventListener('dragleave', (ev) => { if (!ev.relatedTarget) $('abaControle').classList.remove('soltando'); });
document.addEventListener('drop', async (ev) => {
  if (![...(ev.dataTransfer?.types || [])].includes('Files')) return;
  ev.preventDefault();
  $('abaControle').classList.remove('soltando');
  if (naAbaArquivos(ev)) return;   // a aba Arquivos já tratou (envia para a pasta aberta)
  const f = ev.dataTransfer.files?.[0];
  if (f) await verNaTv(f);
});

async function verNaTv(f) {
  const tipo = tipoDoArquivo(f.name);   // arquivos.js
  try {
    if (tipo === 'imagem') {
      aviso('Mandando a foto…');
      await chamar('POST', '/api/extras/recado-foto', { imagem: await reduzir(f), texto: '' });   // extras.js
      aviso('A foto está na tela da TV.', 'ok');
    } else if (tipo === 'video' || tipo === 'audio') {
      try { await chamar('POST', '/api/arquivos/pasta', { pasta: '', nome: PASTA_NA_TV }); } catch (e) { /* já existe */ }
      // Sem "substituir": substituir apaga o antigo e pede o PIN de novo. Se o mesmo nome já está na TV, é o mesmo
      // vídeo arrastado de novo: toca o que já está lá, sem mandar outra vez.
      // Confere ANTES de mandar: a TV recusa nome repetido fechando a conexão no meio (vira "a conexão caiu").
      const r = await chamar('GET', '/api/arquivos?caminho=' + encodeURIComponent(PASTA_NA_TV));
      const jaEsta = (r.itens || []).some((i) => i.nome === f.name);
      if (!jaEsta) {
        const url = '/api/arquivos/enviar?pasta=' + encodeURIComponent(PASTA_NA_TV) + '&nome=' + encodeURIComponent(f.name);
        try {
          await enviarComProgresso(url, f, (p) => avisoProgresso('verNaTv', `Mandando ${f.name} · ${Math.round(p * 100)}%`, p));
        } finally { fimProgresso('verNaTv'); }
      }
      await chamar('POST', '/api/arquivos/tocar', { caminho: juntar(PASTA_NA_TV, f.name) });
      aviso('Tocando na TV.', 'ok');
    } else {
      aviso('Esse tipo de arquivo não abre na TV. Vídeo, música ou foto, sim.', 'erro');
    }
  } catch (e) { aviso(textoErro(e), 'erro'); }
}

async function enviarArquivos(arquivos) {
  if (!arquivos.length) return;
  const total = arquivos.reduce((s, f) => s + f.size, 0);
  const status = $('statusArquivos');
  if (total > livreBytes) { mostrar(status, `Não cabe: são ${tamanhoLegivel(total)} e a TV tem ${tamanhoLegivel(livreBytes)} livres.`); return; }
  const destino = pastaAtual;
  const existentes = new Set(itensPasta.map((i) => i.nome));
  const repetidos = arquivos.filter((f) => existentes.has(f.name));
  let substituir = false;
  if (repetidos.length) {
    substituir = await confirmar('Substituir?',
      `Já existe(m) na pasta: ${repetidos.map((f) => f.name).join(', ')}. Substituir pelos novos?`, 'Substituir',
      // Substituir apaga o antigo: o launcher pede o PIN de novo (PainelWeb.enviarArquivo). O quadro mostra o campo.
      async () => { await exigirPinRecente(); return true; });
    if (!substituir) return;
  }
  let feitos = 0;
  for (const [i, f] of arquivos.entries()) {
    const url = '/api/arquivos/enviar?pasta=' + encodeURIComponent(destino) + '&nome=' + encodeURIComponent(f.name) +
      (substituir && existentes.has(f.name) ? '&substituir=1' : '');
    try {
      await enviarComProgresso(url, f, (p) =>
        mostrar(status, `Enviando ${i + 1} de ${arquivos.length}: ${f.name} — ${Math.round(p * 100)}%`));
      feitos++;
    } catch (e) {
      mostrar(status, `${f.name}: ${textoErro(e)}`);
      if (pastaAtual === destino) await abrirPasta(destino);
      return;
    }
  }
  await abrirPasta(destino);
  mostrar(status, `${feitos} arquivo(s) enviado(s).`);
}

// ---------- ações do app (no Detalhes) ----------

function acoesDoApp(a) {
  const c = document.createElement('div');
  c.className = 'acoes acoes-app';
  const base = '/api/apps/' + encodeURIComponent(a.pacote);
  const botao = (texto, classe, fazer) => {
    const b = document.createElement('button');
    b.className = classe; b.textContent = texto;
    b.addEventListener('click', async () => {
      b.disabled = true;
      try { const msg = await fazer(); if (msg) mostrar($('statusPerm'), msg); } catch (e) { mostrar($('statusPerm'), textoErro(e)); } finally { b.disabled = false; }
    });
    c.appendChild(b);
  };
  if (a.abrivel) botao('Abrir na TV', 'botao-secundario', async () => {
    const emUso = await tvEmUso();
    if (emUso && !(await confirmar('Abrir ' + a.nome + '?', `A TV está em uso agora (${emUso}). Abrir troca o que está na tela.`, 'Abrir', async () => true))) return '';
    await chamar('POST', base + '/abrir', {}); return a.nome + ' aberto na TV.';
  });
  if (!a.protegido) botao('Parar', 'botao-secundario', async () => {
    const emUso = await tvEmUso();
    const ok = await confirmar('Parar ' + a.nome + '?', 'Fecha o app à força (como "Forçar parada"). Ele volta ao normal quando for aberto de novo.' +
      (emUso ? ` A TV está em uso agora (${emUso}).` : ''), 'Parar', () => chamar('POST', base + '/parar', {}), false);
    return ok ? a.nome + ' parado.' : '';
  });
  if (!a.protegido && !a.sistema) botao('Limpar dados', 'botao-perigo', async () => {
    const ok = await confirmar('Limpar os dados de ' + a.nome + '?',
      'Apaga login, configurações e downloads do app, como se tivesse acabado de instalar. Não tem volta.', 'Limpar dados',
      () => chamar('POST', base + '/limpar', {}));
    return ok ? 'Dados de ' + a.nome + ' apagados.' : '';
  });
  return c;
}

// ---------- controle: foto da tela e teclas ----------

let fotoTimer = null;
let fotoUrl = null;
let fotoPedindo = false;

let fotoRapidaAte = 0;   // logo depois de uma tecla, a foto vem mais rápido (700 ms) para ver o efeito
function pararFoto() { if (fotoTimer) { clearTimeout(fotoTimer); fotoTimer = null; } }
function fotoRapida() { fotoRapidaAte = Date.now() + 6000; }

/** Pede uma foto a cada 2 s, só com a aba Controle aberta e o navegador visível (sem ninguém olhando, custo zero). */
async function atualizarFoto() {
  pararFoto();
  if ($('abaControle').hidden || $('telaPainel').hidden) return;
  if (!$('videoControle').hidden) return;   // vídeo ao vivo no lugar da foto (controle.js): nada de foto por cima
  if (document.visibilityState === 'visible' && !fotoPedindo) {
    fotoPedindo = true;
    try {
      const r = await fetch('/api/tela/foto', { credentials: 'same-origin', cache: 'no-store' });
      if (r.status === 401) { mostrarEntrar('Sessão expirada: entre de novo.'); return; }
      if (!r.ok) { const d = await r.json().catch(() => ({})); throw new Error(erroAmigavel(r.status, d.erro)); }
      const url = URL.createObjectURL(await r.blob());
      $('fotoTela').onerror = () => {   // veio algo que não é imagem: mostra o aviso em vez do ícone quebrado
        $('fotoTela').hidden = true; $('semFoto').hidden = false; $('semFoto').textContent = 'A foto da tela não chegou inteira. Tentando de novo…';
      };
      $('fotoTela').src = url;
      $('fotoTela').hidden = false;
      $('semFoto').hidden = true;
      if (fotoUrl) URL.revokeObjectURL(fotoUrl);
      fotoUrl = url;
      $('statusTela').textContent = (Date.now() < fotoRapidaAte ? 'Atualizando rápido' : 'Foto a cada 2 s') + ' · ' + new Date().toLocaleTimeString('pt-BR');
    } catch (e) {
      // Sem foto (TV em espera, por exemplo): troca a imagem pelo motivo, em vez de mostrar uma imagem quebrada.
      $('fotoTela').hidden = true;
      $('semFoto').hidden = false;
      $('semFoto').textContent = textoErro(e);
      $('statusTela').textContent = '';
    } finally {
      fotoPedindo = false;
    }
  }
  fotoTimer = setTimeout(atualizarFoto, Date.now() < fotoRapidaAte ? 700 : 2000);
}
document.addEventListener('visibilitychange', () => {
  if (!$('abaControle').hidden) atualizarFoto();
  if (abaAberta('claude') && document.visibilityState === 'visible' && !$('telaPainel').hidden) carregarMcp();
  if (abaAberta('backup') && document.visibilityState === 'visible' && !$('telaPainel').hidden && !$('driveLogin').hidden) acompanharDrive();
});

for (const b of document.querySelectorAll('[data-tecla]')) {
  b.addEventListener('click', async () => {
    try {
      await chamar('POST', '/api/tela/tecla', { tecla: b.dataset.tecla });
      mostrar($('statusControle'), '');
      fotoRapida();
      setTimeout(atualizarFoto, 400);   // mostra logo o efeito da tecla
    } catch (e) {
      mostrar($('statusControle'), textoErro(e));
      if (typeof aviso === 'function') aviso(textoErro(e), 'erro');   // fora da aba Controle (ex.: Início) o texto acima não aparece
    }
  });
}

$('formTexto').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  if ($('aoVivo').checked) return;   // ao vivo cada letra já foi (controle.js)
  const t = paraTv($('campoTexto').value);   // ideia 56: acento vai junto (pela acessibilidade da TV)
  if (!t) { mostrar($('statusControle'), 'A TV só digita letras, números, espaço e . , @ _ -'); return; }
  try {
    await chamar('POST', '/api/tela/texto', { texto: t });
    $('campoTexto').value = '';
    mostrar($('statusControle'), '');
    setTimeout(atualizarFoto, 400);
  } catch (e) { mostrar($('statusControle'), textoErro(e)); }
});

$('formRecado').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const texto = $('campoRecado').value.trim();
  if (!texto) { mostrar($('statusControle'), 'Escreva o recado.'); return; }
  try {
    const r = await chamar('POST', '/api/recado', {
      texto, segundos: Number($('duracaoRecado').value), som: $('somRecado').checked });
    $('campoRecado').value = '';
    mostrar($('statusControle'), r.situacao === 'guardado'
      ? 'A tela da TV está apagada: o recado aparece quando ela ligar.'
      : 'Recado na tela da TV.');
    setTimeout(atualizarFoto, 600);   // a foto mostra o recado
  } catch (e) { mostrar($('statusControle'), textoErro(e)); }
});

// ---------- lugar do clima (LocalClima.kt na TV) ----------

function mostrarClima(c) {
  // A TV usa o Wi-Fi primeiro; o endereço vale quando o Wi-Fi não dá a posição (e antes do IP da internet).
  $('estadoClima').textContent = (c.fixo ? `cadastrado: ${c.fixo.descricao}` : 'nenhum cadastrado (a TV se acha pelo Wi-Fi ou pela internet)') +
    (c.usado ? ` · última posição usada: ${c.usado.lat.toFixed(3)}, ${c.usado.lon.toFixed(3)}` : '');
  if (c.fixo) { $('cepClima').value = c.fixo.cep; $('numeroClima').value = c.fixo.numero; $('cidadeClima').value = c.fixo.cep ? '' : (c.fixo.nome || ''); }
}

async function carregarClima() {
  try { mostrarClima(await chamar('GET', '/api/clima')); } catch (e) { $('estadoClima').textContent = textoErro(e); }
}

$('formClima').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  try {
    mostrar($('statusControle'), 'Procurando o endereço…');
    // Sem CEP e com cidade: busca a cidade (qualquer país, ideia 40). Com CEP, o CEP vence (é mais preciso).
    const cep = $('cepClima').value.trim(), cidade = $('cidadeClima').value.trim();
    mostrarClima(await chamar('POST', '/api/clima', !cep && cidade ? { cidade } : { cep, numero: $('numeroClima').value.trim() }));
    mostrar($('statusControle'), 'Endereço salvo.');
  } catch (e) { mostrar($('statusControle'), textoErro(e)); }
});

$('btnClimaAuto').addEventListener('click', async () => {
  try {
    mostrarClima(await chamar('POST', '/api/clima', { automatico: true }));
    $('cepClima').value = ''; $('numeroClima').value = ''; $('cidadeClima').value = '';
    mostrar($('statusControle'), 'Endereço apagado: a TV se acha só pelo Wi-Fi ou pela internet.');
  } catch (e) { mostrar($('statusControle'), textoErro(e)); }
});

// ---------- Alto contraste (estudo global 20/11/2026, ideia 3) ----------
// Por quê: só a TV tinha. Para quê: quem enxerga pouco liga uma vez (na TV ou aqui) e vale em tudo. Como: a chave
// da TV vem no /api/estado; o "mais contraste" do aparelho (prefers-contrast) também liga, pelo CSS.
function aplicarContraste(ligado) {
  if (ligado) document.documentElement.dataset.contraste = 'alto';
  else delete document.documentElement.dataset.contraste;
}

// ---------- Abas do Controle (ideia 11): uma coisa de cada vez embaixo do controle ----------
document.querySelectorAll('.abas-controle [role=tab]').forEach((aba) => {
  aba.addEventListener('click', () => {
    document.querySelectorAll('.abas-controle [role=tab]').forEach((b) => {
      const ativa = b === aba;
      b.setAttribute('aria-selected', String(ativa));
      $(b.dataset.painel).hidden = !ativa;
    });
  });
});
// Seletor de foto com a cara do painel: o nome do arquivo escolhido aparece ao lado do botão.
$('fotoExtras').addEventListener('change', (e) => {
  const f = e.target.files[0];
  $('nomeFotoExtras').textContent = f ? f.name : 'Nenhuma foto escolhida';
});

$('btnReiniciarTv').addEventListener('click', async () => {
  const emUso = await tvEmUso();
  const ok = await confirmar('Reiniciar a TV?', 'A TV desliga e liga de novo (cerca de 1 min). O painel volta sozinho depois.' +
    (emUso ? ` Atenção: a TV está em uso agora (${emUso}).` : ''), 'Reiniciar', () => chamar('POST', '/api/tv/reiniciar', {}));
  if (ok) mostrar($('statusControle'), 'Reiniciando… o painel volta em cerca de 1 min.');
});

// ---------- desligar sozinha (aba Controle) ----------

function rotuloMinutos(m) {
  if (!m) return 'Nunca';
  return m % 60 === 0 ? `Depois de ${m / 60} h sem usar` : `Depois de ${m} min sem usar`;
}

async function carregarAutoDesligar() {
  const sel = $('autoDesligar');
  try {
    const r = await chamar('GET', '/api/tv/autodesligar');
    sel.replaceChildren();
    for (const m of r.opcoes) {
      const o = document.createElement('option');
      o.value = String(m); o.textContent = rotuloMinutos(m); o.selected = m === r.minutos;
      sel.appendChild(o);
    }
    $('statusDesligar').textContent = r.ultimoDesligamento
      ? 'Última vez que desligou sozinha: ' + new Date(r.ultimoDesligamento).toLocaleString('pt-BR') : '';
  } catch (e) { $('statusDesligar').textContent = textoErro(e); }
}

$('autoDesligar').addEventListener('change', async () => {
  try {
    const r = await chamar('POST', '/api/tv/autodesligar', { minutos: Number($('autoDesligar').value) });
    $('statusDesligar').textContent = 'Gravado: ' + rotuloMinutos(r.minutos).toLowerCase() + '.';
  } catch (e) { $('statusDesligar').textContent = textoErro(e); }
});

// ---------- histórico de rede (aba Rede) ----------

let horasRede = 24;

function duracaoLegivel(ms) {
  const min = Math.round(ms / 60000);
  if (min < 1) return 'menos de 1 min';
  if (min < 60) return `${min} min`;
  const h = Math.floor(min / 60), r = min % 60;
  return r ? `${h} h ${r} min` : `${h} h`;
}

function bloco(titulo, valor, classe) {
  const d = document.createElement('div');
  d.className = 'bloco-rede' + (classe ? ' ' + classe : '');
  const v = document.createElement('strong'); v.textContent = valor;
  const t = document.createElement('span'); t.textContent = titulo;
  d.append(v, t);
  return d;
}

function linhaSimples(quando, texto) {
  const linha = document.createElement('div');
  linha.className = 'linha-registro';
  const q = document.createElement('span'); q.className = 'quando'; q.textContent = quando;
  const o = document.createElement('span'); o.textContent = texto;
  linha.append(q, o);
  return linha;
}

function vazio(lista, texto) {
  const p = document.createElement('p'); p.className = 'aviso'; p.textContent = texto; lista.appendChild(p);
}

async function carregarRede() {
  mostrar($('statusRede'), 'Carregando…');
  let r;
  try { r = await chamar('GET', '/api/rede/historico?horas=' + horasRede); } catch (e) { mostrar($('statusRede'), textoErro(e)); return; }
  mostrar($('statusRede'), r.amostras.length ? '' :
    'Ainda sem medições: o launcher mede a cada ' + r.intervaloMin + ' min. Volte daqui a pouco.');
  const s = r.resumo;
  const cob = Math.max(s.coberturaMs, 1);
  const pct = s.coberturaMs ? Math.max(0, 100 * (1 - s.foraMs / cob)) : null;
  const res = $('resumoRede');
  res.replaceChildren(
    bloco('internet no ar (no tempo medido)', pct === null ? '—' : pct.toFixed(pct >= 99.95 || pct < 10 ? 0 : 1) + '%', pct !== null && pct < 99 ? 'ruim' : ''),
    bloco('quedas de internet', String(s.quedasInternet), s.quedasInternet ? 'ruim' : ''),
    bloco('quedas só de DNS', String(s.quedasDns), s.quedasDns ? 'ruim' : ''),
    bloco('maior queda', s.maiorQuedaMs ? duracaoLegivel(s.maiorQuedaMs) : '—'),
    bloco('demora média (95% abaixo de)', s.latMedia === null ? '—' : `${s.latMedia} ms (${s.latP95} ms)`),
    bloco('sinal médio do Wi-Fi', s.dbmMedio === null ? '—' : `${s.dbmMedio} dBm`),
    bloco('tempo medido', duracaoLegivel(s.coberturaMs)),
  );
  desenharGrafico(r);

  const lq = $('listaQuedas'); lq.replaceChildren();
  if (!r.quedas.length) vazio(lq, 'Nenhuma queda no período.');
  for (const q of r.quedas.slice().reverse()) {
    const tipo = q.tipo === 'dns' ? 'DNS não resolvia nomes (internet ok)' : 'sem internet';
    const fim = q.aberta ? 'ainda agora' : new Date(q.fim).toLocaleTimeString('pt-BR');
    lq.appendChild(linhaSimples(new Date(q.inicio).toLocaleString('pt-BR') + ' até ' + fim,
      `${tipo} · ${q.aberta ? 'em andamento' : duracaoLegivel(q.fim - q.inicio)}`));
  }
  // Ideia 37: um resumo por mês, 12 meses (o mês corrente só com os dias já terminados).
  const lm = $('listaMesesRede'); lm.replaceChildren();
  if (!(r.meses || []).length) vazio(lm, 'O primeiro mês aparece amanhã: a TV fecha um dia de cada vez.');
  for (const m of r.meses || []) {
    const [ano, mes] = m.mes.split('-').map(Number);
    const nome = new Date(ano, mes - 1, 1).toLocaleDateString('pt-BR', { month: 'long', year: 'numeric' });
    const medido = m.coberturaMs ? ` · medido ${duracaoLegivel(m.coberturaMs)} em ${m.dias} dia${m.dias === 1 ? '' : 's'}` : '';
    lm.appendChild(linhaSimples(nome, `${m.quedas} queda${m.quedas === 1 ? '' : 's'} de internet, ${m.foraMs ? duracaoLegivel(m.foraMs) : '0 min'} fora` +
      (m.quedasDns ? `, ${m.quedasDns} só de DNS` : '') + medido));
  }
  const le = $('listaEventosRede'); le.replaceChildren();
  if (!r.eventos.length) vazio(le, 'Nenhum aviso no período.');
  for (const [t, txt] of r.eventos.slice().reverse().slice(0, 100)) le.appendChild(linhaSimples(new Date(t).toLocaleString('pt-BR'), txt));
}

/** Uma barra por medição: altura = demora até a internet; cor = estado. Buracos (sem medição) ficam cinza. */
function desenharGrafico(r) {
  const c = $('graficoRede');
  const dpr = window.devicePixelRatio || 1;
  const larg = c.clientWidth || 600, alt = 160;
  c.width = Math.round(larg * dpr); c.height = Math.round(alt * dpr);
  const g = c.getContext('2d');
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.clearRect(0, 0, larg, alt);
  const css = getComputedStyle(document.documentElement);
  const cor = (n) => css.getPropertyValue(n).trim();
  const topo = 4, base = alt - 22, faixa = base - topo;
  const x = (t) => ((t - r.desde) / (r.agora - r.desde)) * larg;
  // Fundo "sem dados" e a régua de horas.
  g.fillStyle = cor('--superficie2'); g.fillRect(0, base + 4, larg, 6);
  g.fillStyle = cor('--texto-fraco'); g.font = '11px system-ui, sans-serif'; g.textAlign = 'center';
  const passo = horasRede > 24 ? 24 * 3600e3 : 3 * 3600e3;
  const primeiro = Math.ceil(r.desde / passo) * passo;
  for (let t = primeiro; t <= r.agora; t += passo) {
    const d = new Date(t);
    const rot = horasRede > 24 ? d.toLocaleDateString('pt-BR', { weekday: 'short', day: '2-digit' }) : d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
    g.fillText(rot, Math.min(Math.max(x(t), 20), larg - 20), alt - 2);
  }
  const a = r.amostras;
  const buraco = 15 * 60e3;
  for (let i = 0; i < a.length; i++) {
    const [t, net, lat, dns] = a[i];
    const prox = i + 1 < a.length ? a[i + 1][0] : r.agora;
    const fim = Math.min(prox, t + buraco, r.agora);
    const x0 = x(t), w = Math.max(x(fim) - x0, 1);
    const estado = !net ? '--perigo' : dns < 0 ? '--alerta' : '--ok';
    g.fillStyle = cor(estado);
    g.fillRect(x0, base + 4, w, 6);   // faixa de estado
    const h = net ? Math.max(2, Math.min(lat, 300) / 300 * faixa) : faixa;
    g.globalAlpha = net ? 0.85 : 0.35;
    g.fillRect(x0, base - h, w, h);
    g.globalAlpha = 1;
  }
}

for (const b of document.querySelectorAll('.periodo')) {
  b.addEventListener('click', () => {
    horasRede = Number(b.dataset.horas);
    for (const o of document.querySelectorAll('.periodo')) o.classList.toggle('ativo', o === b);
    carregarRede();
  });
}
$('btnAtualizarRede').addEventListener('click', () => carregarRede());
window.addEventListener('resize', () => { if (abaAberta('rede')) carregarRede(); });

// ---------- início ----------

// Aberto pelo QR da TV (Configurações > Acesso remoto > Entrar pelo celular): o endereço traz "#qr=<código>".
// Tira o código do endereço na hora (não fica no histórico nem vira nome de aba) e entra com ele, sem PIN.
/**
 * Tira "#qr=..." do endereço (sem deixar no histórico) e devolve { codigo, ir }. Ideia 43: o QR de uma linha das
 * Configurações da TV traz "&ir=<aba>[.<id do cartão>]" (DestinoPainel.kt) para abrir já na página certa.
 */
function pegarCodigoQr() {
  if (!location.hash.startsWith('#qr=')) return null;
  const m = /^#qr=([0-9a-f]{48})(?:&ir=([a-zA-Z0-9]+(?:\.[a-zA-Z0-9]+)?))?(?:&(?:fp|ts)=[a-z0-9.-]*)*$/.exec(location.hash);
  history.replaceState(null, '', location.pathname + location.search);
  return m ? { codigo: m[1], ir: m[2] || '' } : null;
}

/** Abre a aba de [ir] ("extras.cartaoCasa") e rola até o cartão, com um realce curto para o olho achar. */
function irPara(ir) {
  if (!ir) return;
  const [aba, cartao] = ir.split('.');
  const alvo = cartao && $(cartao);
  const pagina = alvo && alvo.closest('.painel-aba');
  mudarAba(pagina ? pagina.id.slice(3).charAt(0).toLowerCase() + pagina.id.slice(4) : aba);
  if (!alvo) return;
  setTimeout(() => {
    alvo.scrollIntoView({ behavior: 'smooth', block: 'start' });
    alvo.classList.add('realce');
    setTimeout(() => alvo.classList.remove('realce'), 2400);
  }, 150);
}

async function entrarPeloQr(qr) {
  try {
    await chamar('POST', '/api/entrar-qr', { codigo: qr.codigo });
    await abrirPainel();
    aviso('Entrou pelo QR da TV. Para instalar ou desinstalar, o painel ainda pede o PIN.', 'ok');
    irPara(qr.ir);
    return true;
  } catch (e) {
    // Código vencido ou já usado: cai na entrada normal (se já estiver logado, segue logado).
    aviso(textoErro(e), 'erro');
    return false;
  }
}

// O leitor de QR pode abrir o link numa aba que já está no painel: aí só o fragmento muda, sem recarregar a página.
window.addEventListener('hashchange', () => {
  const qr = pegarCodigoQr();
  if (qr) entrarPeloQr(qr);
  else if (!$('telaPainel').hidden && location.hash === '') mudarAba('inicio', false);
});

/**
 * Ideia 55: "Enviar para a TV" pelo compartilhar do celular (manifest.webmanifest, share_target). O Chrome abre
 * "/?titulo=…&texto=…&url=…"; com o painel aberto, confirma e manda para /api/compartilhar (vídeo do YouTube abre no
 * YouTube da TV, outro link abre na TV, texto vira recado). A barra de endereço é limpa para não reenviar ao recarregar.
 */
async function enviarCompartilhado() {
  const q = new URLSearchParams(location.search);
  const dados = { titulo: q.get('titulo') || '', texto: q.get('texto') || '', url: q.get('url') || '' };
  if (!dados.titulo && !dados.texto && !dados.url) return;
  history.replaceState(null, '', '/' + location.hash);
  const resumo = (dados.url || dados.texto || dados.titulo).slice(0, 160);
  const ok = await confirmar('Mandar para a TV?', resumo, 'Mandar', async () => {
    const r = await chamar('POST', '/api/compartilhar', dados);
    aviso(r.feito === 'recado' ? 'Recado na TV.' : (r.feito === 'youtube' ? 'Abrindo no YouTube da TV.' : 'Abrindo na TV.'), 'ok');
  }, false);
  return ok;
}

(async () => {
  if (location.hash.startsWith('#primeira=')) return;   // ideia 97: o assistente da 1ª configuração (primeira.js) cuida
  // Elevação 18.1: link de convite (família ou visita). O código sai do endereço na hora (não fica no histórico).
  if (location.hash.startsWith('#convite=')) {
    const codigo = location.hash.slice('#convite='.length);
    history.replaceState(null, '', location.pathname);
    try { await chamar('POST', '/api/convite/entrar', { codigo }); await abrirPainel(); return; }
    catch (e) { mostrarEntrar(textoErro(e)); return; }
  }
  const qr = pegarCodigoQr();
  if (qr && await entrarPeloQr(qr)) return;
  try {
    const e = await chamar('GET', '/api/estado');
    if (e.logado) { await abrirPainel(); if (qr) irPara(qr.ir); enviarCompartilhado(); } else mostrarEntrar();
  } catch (e) {
    mostrarEntrar();
  }
})();
