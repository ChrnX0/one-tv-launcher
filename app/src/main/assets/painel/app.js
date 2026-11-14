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
  if (!resp.ok) { const e = new Error(erroAmigavel(resp.status, dados.erro)); e.dados = dados; e.tecnico = dados.erro; throw e; }
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
 */
const AO_ABRIR = {
  apps: () => carregarApps(),
  tv: () => carregarTv(),
  controle: () => { atualizarFoto(); carregarClima(); carregarAutoDesligar(); },
  saude: () => carregarSaude(),
  fotos: () => { carregarFotos(); carregarPerfis(); },
  rede: () => carregarRede(),
  claude: () => carregarMcp(),
  roteador: () => carregarRoteador(),
  registro: () => carregarRegistro(),
  backup: () => { carregarCopias(); carregarNoturno(); },
  arquivos: () => abrirPasta(pastaAtual),
};

/** "arquivos" -> "abaArquivos" (o id da <section>). */
const idDaAba = (nome) => 'aba' + nome.charAt(0).toUpperCase() + nome.slice(1);

/**
 * Mostra uma aba e esconde as outras. [historico]=false quando veio do botão Voltar do navegador (não empilha de
 * novo). O endereço ganha "#nome": recarregar a página ou mandar o link abre na mesma aba.
 */
function mudarAba(nome, historico = true) {
  if (!nome || !$(idDaAba(nome))) nome = 'inicio';
  for (const b of document.querySelectorAll('.aba')) {
    const ativa = b.dataset.aba === nome
      // no celular, "Mais" fica aceso enquanto se está numa aba que mora dentro dele
      || (b.dataset.aba === 'mais' && !document.querySelector(`.aba.principal[data-aba="${nome}"]`));
    b.classList.toggle('ativa', ativa);
    if (ativa) b.setAttribute('aria-current', 'page'); else b.removeAttribute('aria-current');
  }
  for (const s of document.querySelectorAll('.painel-aba')) s.hidden = s.id !== idDaAba(nome);
  if (nome !== 'controle') pararFoto();
  const carregar = AO_ABRIR[nome];
  if (carregar) Promise.resolve().then(carregar).catch((e) => console.warn(nome, e));
  document.dispatchEvent(new CustomEvent('aba', { detail: nome }));
  if (historico && location.hash !== '#' + nome) history.pushState(null, '', '#' + nome);
  window.scrollTo(0, 0);
}
// Um ouvinte só, no documento: vale também para os botões criados depois (a tela "Mais", os atalhos do Início).
document.addEventListener('click', (ev) => {
  const b = ev.target.closest('[data-aba]');
  if (b && !$('telaPainel').hidden) { ev.preventDefault(); mudarAba(b.dataset.aba); }
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
  for (const a of filtrados) lista.appendChild(linhaApp(a));
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
        `Risco: ${a.risco}. ${f ? f.efeito : ''}` + (emUso ? ` A TV está em uso agora (${emUso}).` : ''), 'Ocultar mesmo assim', fazer, false);
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
  // Destino gravado que não está mais na lista (pasta apagada): aparece com o rótulo da TV, para não sumir calado.
  if (!opcoes.some(([v]) => v === r.destino)) opcoes.push([r.destino, r.destinoRotulo]);
  for (const [valor, rotulo] of opcoes) {
    const o = document.createElement('option');
    o.value = valor; o.textContent = rotulo; o.selected = valor === r.destino;
    sel.appendChild(o);
  }
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
  if (!caixa.children.length) item('números', '—', false);
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

const REPO_ISSUES = 'https://github.com/ChrnX0/one-tv-launcher/issues/new';
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

async function carregarSaude() {
  carregarErros();   // em paralelo: não espera a lista de memória
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
    linha.append(rosto, nome, escolher, enviar);
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
  if (!$('abaClaude').hidden && document.visibilityState === 'visible' && r.passo.id !== 'pronto' && r.passo.id !== 'desligado') timerMcp = setTimeout(carregarMcp, 3000);
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
  if (!/\.apk$/i.test(arquivo.name)) { mostrar($('statusApps'), 'Escolha um arquivo .apk (.apks e .xapk ainda não são aceitos).'); return; }
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
  if (!$('abaClaude').hidden && document.visibilityState === 'visible' && !$('telaPainel').hidden) carregarMcp();
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
  const t = paraTv($('campoTexto').value);   // tira acento: "ação" vai como "acao"
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
  if (c.fixo) { $('cepClima').value = c.fixo.cep; $('numeroClima').value = c.fixo.numero; }
}

async function carregarClima() {
  try { mostrarClima(await chamar('GET', '/api/clima')); } catch (e) { $('estadoClima').textContent = textoErro(e); }
}

$('formClima').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  try {
    mostrar($('statusControle'), 'Procurando o endereço…');
    mostrarClima(await chamar('POST', '/api/clima', { cep: $('cepClima').value.trim(), numero: $('numeroClima').value.trim() }));
    mostrar($('statusControle'), 'Endereço salvo.');
  } catch (e) { mostrar($('statusControle'), textoErro(e)); }
});

$('btnClimaAuto').addEventListener('click', async () => {
  try {
    mostrarClima(await chamar('POST', '/api/clima', { automatico: true }));
    $('cepClima').value = ''; $('numeroClima').value = '';
    mostrar($('statusControle'), 'Endereço apagado: a TV se acha só pelo Wi-Fi ou pela internet.');
  } catch (e) { mostrar($('statusControle'), textoErro(e)); }
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
window.addEventListener('resize', () => { if (!$('abaRede').hidden) carregarRede(); });

// ---------- início ----------

// Aberto pelo QR da TV (Configurações > Acesso remoto > Entrar pelo celular): o endereço traz "#qr=<código>".
// Tira o código do endereço na hora (não fica no histórico nem vira nome de aba) e entra com ele, sem PIN.
/** Tira "#qr=..." do endereço (sem deixar no histórico) e devolve o código, se for um código bem formado. */
function pegarCodigoQr() {
  if (!location.hash.startsWith('#qr=')) return null;
  const m = /^#qr=([0-9a-f]{48})$/.exec(location.hash);
  history.replaceState(null, '', location.pathname + location.search);
  return m ? m[1] : null;
}

async function entrarPeloQr(codigo) {
  try {
    await chamar('POST', '/api/entrar-qr', { codigo });
    await abrirPainel();
    aviso('Entrou pelo QR da TV. Para instalar ou desinstalar, o painel ainda pede o PIN.', 'ok');
    return true;
  } catch (e) {
    // Código vencido ou já usado: cai na entrada normal (se já estiver logado, segue logado).
    aviso(textoErro(e), 'erro');
    return false;
  }
}

// O leitor de QR pode abrir o link numa aba que já está no painel: aí só o fragmento muda, sem recarregar a página.
window.addEventListener('hashchange', () => {
  const codigo = pegarCodigoQr();
  if (codigo) entrarPeloQr(codigo);
  else if (!$('telaPainel').hidden && location.hash === '') mudarAba('inicio', false);
});

(async () => {
  const codigo = pegarCodigoQr();
  if (codigo && await entrarPeloQr(codigo)) return;
  try {
    const e = await chamar('GET', '/api/estado');
    if (e.logado) await abrirPainel(); else mostrarEntrar();
  } catch (e) {
    mostrarEntrar();
  }
})();
