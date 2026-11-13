// Aba "Extras" do painel (ideias de 10/11/2026; rotas em PainelExtras.kt). Arquivo separado do app.js para não
// conflitar com os PRs abertos. Usa as funções globais do app.js: $, chamar, mostrar, confirmar, PrecisaPin.
// Todo texto vindo da TV entra por textContent (nunca innerHTML).
'use strict';

const NOMES_CHAVES = {
  JANELA_OTA: 'Atualização do sistema só de madrugada (3h às 5h)',
  TRAVAR_CRITICOS: 'Travar "Forçar parada" e "Limpar dados" do launcher e da VPN',
  PIN_HOME: 'Pedir o PIN para mudar a organização da HOME',
  FILTRO_NOTURNO: 'Filtro noturno (tela mais quente no horário abaixo)',
  COR_DO_FUNDO: 'Cor de destaque tirada do fundo (tema Galeria)',
  DIARIO_DNS: 'Diário de DNS por app (fica só na TV, 7 dias)',
  DEBLOAT_POS_OTA: 'Refazer a limpeza de apps depois de atualizar o sistema',
  HORA_DORMIR: 'Hora de dormir (só apps liberados e volume com teto)',
  TOCANDO_AGORA: 'Tocando agora (lê o que está tocando na TV)',
  MODO_LEVE: 'Modo leve (animações do sistema mais curtas; desligar volta como era)',
  SOM_NAVEGACAO: 'Som de navegação nas telas do launcher',
  RECADO_LINK: 'Recado por link (Home Assistant, TvOverlay)',
  VER_TELA: 'Ver a tela pelo Claude (MCP ver_tela; a TV avisa cada vez)',
  SONO_SUAVE: 'Desligar suave (o timer escurece a tela e baixa o volume nos últimos 5 min)',
  MURAL: 'Mural da família no protetor de tela',
  PRESENCA: 'Presença: apagar a TV quando todos saírem (roteador UniFi)',
  HOJE_NA_TV: 'Hoje na TV (sugestões na tela Buscar)',
  RETROSPECTIVA: 'Sua semana na TV (domingo, 20h)',
  MODO_SIMPLES: 'Modo simples (cards grandes no lugar da HOME)',
};

const hhmm = (m) => String(Math.floor(m / 60)).padStart(2, '0') + ':' + String(m % 60).padStart(2, '0');
const minutos = (t) => { const [h, m] = (t || '0:0').split(':').map(Number); return h * 60 + m; };

/** Ação que pode pedir o PIN: o quadro do app.js pede o PIN e tenta de novo. */
const comPin = (titulo, texto, ok, fazer) => confirmar(titulo, texto, ok, fazer);

// ---------- aba ----------

// O mudarAba do app.js avisa com o evento "aba": aqui carrega a nossa, e para o vídeo ao sair dela.
document.addEventListener('aba', (ev) => {
  if (ev.detail === 'extras') carregarExtras(); else pararVideo();
});

async function carregarExtras() {
  await Promise.all([carregarChaves(), carregarCenas(), carregarLoja()]).catch((e) => mostrar($('statusExtras'), textoErro(e)));
}

// ---------- chaves ----------

async function carregarChaves(dados) {
  const e = dados || await chamar('GET', '/api/extras/estado');
  const lista = $('chavesExtras');
  lista.replaceChildren();
  for (const [chave, ligado] of Object.entries(e.chaves)) {
    const rotulo = document.createElement('label');
    const caixa = document.createElement('input');
    caixa.type = 'checkbox';
    caixa.checked = ligado;
    caixa.addEventListener('change', async () => {
      const mudar = () => chamar('POST', '/api/extras/chave', { chave, ligado: caixa.checked });
      try {
        const r = e.comPin.includes(chave)
          ? await comPin('Mudar chave', NOMES_CHAVES[chave] || chave, caixa.checked ? 'Ligar' : 'Desligar', mudar)
          : await mudar();
        if (!r) { caixa.checked = !caixa.checked; return; }   // cancelou
        carregarChaves(r);
      } catch (err) { caixa.checked = !caixa.checked; mostrar($('statusExtras'), textoErro(err)); }
    });
    rotulo.append(caixa, ' ' + (NOMES_CHAVES[chave] || chave) + (e.comPin.includes(chave) ? ' (PIN)' : ''));
    lista.append(rotulo);
  }
  $('filtroInicio').value = hhmm(e.filtro.inicio);
  $('filtroFim').value = hhmm(e.filtro.fim);
  $('filtroForca').value = e.filtro.forca;
  $('macWol').value = e.mac || '';
  mostrar($('otaExtras'), e.otaPendente ? 'Há uma atualização do sistema esperando a janela das 3h.' : '');
  if (e.diarioErro) mostrar($('statusExtras'), 'Diário de DNS: ' + e.diarioErro);
  // 2ª leva (10/11/2026)
  $('dormirInicio').value = hhmm(e.dormir.inicio);
  $('dormirFim').value = hhmm(e.dormir.fim);
  $('dormirTeto').value = e.dormir.teto;
  $('dormirApps').value = e.dormir.apps;
  desenharDias(e.dormir.dias || []);
  mostrar($('livreExtras'), 'Espaço livre na TV: ' + e.livre);
  if (e.chaves.TOCANDO_AGORA) verTocando();
}

$('btnFiltroExtras').addEventListener('click', async () => {
  try {
    await carregarChaves(await chamar('POST', '/api/extras/filtro', {
      inicio: minutos($('filtroInicio').value), fim: minutos($('filtroFim').value), forca: Number($('filtroForca').value),
    }));
    mostrar($('statusExtras'), 'Filtro salvo.');
  } catch (e) { mostrar($('statusExtras'), textoErro(e)); }
});

// ---------- tela ao vivo (H.264 cru em quadros [4 bytes de tamanho][NAL], ver VideoAoVivo.kt) ----------

let video = null;   // { leitor, jmuxer, alvo }

// Onde o vídeo aparece: na aba Extras ou na aba Controle (no lugar da foto). Só um de cada vez (a TV só grava um).
const ALVO_EXTRAS = { node: 'videoExtras', status: 'statusVideoExtras', parar: 'btnPararVideoExtras' };

async function verAoVivo(alvo = ALVO_EXTRAS) {
  pararVideo();
  mostrar($(alvo.status), 'Ligando o vídeo na TV...');
  const jmuxer = new JMuxer({ node: alvo.node, mode: 'video', flushingTime: 0, fps: 30, debug: false });
  let resp;
  try {
    resp = await fetch('/api/extras/video', { credentials: 'same-origin', cache: 'no-store' });
  } catch (e) { jmuxer.destroy(); mostrar($(alvo.status), 'Não consegui falar com a TV.'); if (alvo.fim) alvo.fim(); return; }
  if (!resp.ok) { jmuxer.destroy(); mostrar($(alvo.status), `A TV recusou (${resp.status}).`); if (alvo.fim) alvo.fim(); return; }
  const leitor = resp.body.getReader();
  video = { leitor, jmuxer, alvo };
  $(alvo.parar).hidden = false;
  mostrar($(alvo.status), '');
  let pendente = new Uint8Array(0);
  let chegou = false;
  try {
    for (;;) {
      const { value, done } = await leitor.read();
      if (done) break;
      const junto = new Uint8Array(pendente.length + value.length);
      junto.set(pendente); junto.set(value, pendente.length);
      let i = 0;
      while (junto.length - i >= 4) {
        const n = ((junto[i] << 24) >>> 0) + (junto[i + 1] << 16) + (junto[i + 2] << 8) + junto[i + 3];
        if (junto.length - i - 4 < n) break;
        jmuxer.feed({ video: junto.subarray(i + 4, i + 4 + n) });
        chegou = true;
        i += 4 + n;
      }
      pendente = junto.slice(i);
    }
  } catch (e) { /* conexão fechada: fim */ }
  mostrar($(alvo.status), chegou ? 'O vídeo terminou (2 min). Clique em "Ver ao vivo" para continuar.'
    : 'Não chegou vídeo: o shell próprio da TV pode estar desligado, ou outra pessoa já está vendo.');
  $(alvo.parar).hidden = true;
  if (alvo.fim) alvo.fim();
}

function pararVideo() {
  if (!video) return;
  const { alvo } = video;
  try { video.leitor.cancel(); } catch (e) {}
  try { video.jmuxer.destroy(); } catch (e) {}
  video = null;
  $(alvo.parar).hidden = true;
}

$('btnVideoExtras').addEventListener('click', () => verAoVivo());
$('btnPararVideoExtras').addEventListener('click', pararVideo);

// ---------- recado com foto (a página reduz a foto antes de mandar) ----------

async function reduzir(arquivo, lado = 960) {
  const bmp = await createImageBitmap(arquivo);   // sem <img>: a CSP do painel não deixa abrir imagem de blob:
  const k = Math.min(1, lado / Math.max(bmp.width, bmp.height));
  const c = document.createElement('canvas');
  c.width = Math.round(bmp.width * k); c.height = Math.round(bmp.height * k);
  c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
  return c.toDataURL('image/jpeg', 0.82).split(',')[1];
}

$('formFotoExtras').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const f = $('fotoExtras').files[0];
  if (!f) { mostrar($('statusFotoExtras'), 'Escolha uma foto.'); return; }
  try {
    mostrar($('statusFotoExtras'), 'Mandando...');
    await chamar('POST', '/api/extras/recado-foto', { imagem: await reduzir(f), texto: $('textoFotoExtras').value });
    mostrar($('statusFotoExtras'), 'Na tela da TV.');
  } catch (e) { mostrar($('statusFotoExtras'), textoErro(e)); }
});

// ---------- cenas ----------

async function carregarCenas() {
  const r = await chamar('GET', '/api/extras/cenas');
  const lista = $('listaCenas');
  lista.replaceChildren();
  if (!r.cenas.length) { const p = document.createElement('p'); p.className = 'aviso'; p.textContent = 'Nenhuma cena ainda.'; lista.append(p); }
  for (const c of r.cenas) {
    const linha = document.createElement('div'); linha.className = 'linha-registro';
    const nome = document.createElement('strong'); nome.textContent = c.nome;
    const apagar = document.createElement('button'); apagar.type = 'button'; apagar.className = 'botao-secundario'; apagar.textContent = 'Apagar';
    apagar.addEventListener('click', async () => {
      try {
        if (await comPin('Apagar cena', `Apagar "${c.nome}"? O link dela para de funcionar.`, 'Apagar',
          () => chamar('POST', '/api/extras/cenas/apagar', { nome: c.nome }))) carregarCenas();
      } catch (e) { mostrar($('statusCena'), textoErro(e)); }
    });
    linha.append(nome, apagar);
    lista.append(linha);
  }
}

$('formCena').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const nome = $('nomeCena').value.trim();
  let acoes;
  try { acoes = JSON.parse($('acoesCena').value); } catch (e) { mostrar($('statusCena'), 'As ações precisam ser um JSON {"acoes":[...]}.'); return; }
  try {
    const r = await comPin('Criar cena', `Criar a cena "${nome}"?`, 'Criar', () => chamar('POST', '/api/extras/cenas/criar', { nome, acoes }));
    if (!r) return;
    mostrar($('statusCena'), `Link (guarde agora, não aparece de novo): POST ${location.origin}/cena/${r.token}`);
    carregarCenas();
  } catch (e) { mostrar($('statusCena'), textoErro(e)); }
});

// ---------- loja curada ----------

async function carregarLoja() {
  const r = await chamar('GET', '/api/extras/loja');
  const lista = $('listaLoja');
  lista.replaceChildren();
  for (const a of r.apps) {
    const linha = document.createElement('div'); linha.className = 'linha-registro';
    const nome = document.createElement('strong'); nome.textContent = a.nome + (a.instalado ? ' (instalado)' : '');
    const desc = document.createElement('span'); desc.className = 'quando'; desc.textContent = `${a.descricao} · github.com/${a.repo}`;
    const botao = document.createElement('button'); botao.type = 'button'; botao.className = 'botao-secundario';
    botao.textContent = a.instalado ? 'Atualizar' : 'Instalar';
    botao.addEventListener('click', async () => {
      try {
        const res = await comPin(botao.textContent, `Baixar ${a.nome} do GitHub e instalar na TV? Pode levar alguns minutos.`, botao.textContent,
          () => chamar('POST', '/api/extras/loja/instalar', { id: a.id }));
        if (res) { mostrar($('statusLoja'), res.resultado); carregarLoja(); }
      } catch (e) { mostrar($('statusLoja'), textoErro(e)); }
    });
    linha.append(nome, desc, botao);
    lista.append(linha);
  }
}

// ---------- acordar o computador ----------

$('formWol').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  try { await chamar('POST', '/api/extras/wol', { mac: $('macWol').value }); mostrar($('statusWol'), 'MAC guardado na TV.'); }
  catch (e) { mostrar($('statusWol'), textoErro(e)); }
});
$('btnAcordar').addEventListener('click', async () => {
  try { await chamar('POST', '/api/extras/acordar'); mostrar($('statusWol'), 'Pacote enviado. O PC precisa estar no cabo e com Wake-on-LAN ligado.'); }
  catch (e) { mostrar($('statusWol'), textoErro(e)); }
});

// ---------- testador de teclas e diário de DNS ----------

$('btnTeclas').addEventListener('click', async () => {
  try {
    await chamar('POST', '/api/extras/teclas/armar');
    const saida = $('saidaExtras');
    mostrar(saida, 'Armado. Aperte os botões do controle...');
    const fim = Date.now() + 120000;
    const ler = async () => {
      const r = await chamar('GET', '/api/extras/teclas');
      saida.textContent = r.teclas.length ? r.teclas.map((t) => `${t.nome} (${t.codigo}) ${t.acao}${t.remapeada ? ' remapeada' : ''} · ${t.aparelho}`).join('\n')
        : 'Armado. Aperte os botões do controle...';
      if (Date.now() < fim && !$('abaExtras').hidden) setTimeout(ler, 1000);
    };
    ler();
  } catch (e) { mostrar($('saidaExtras'), textoErro(e)); }
});

$('btnDiario').addEventListener('click', async () => {
  try {
    const r = await comPin('Diário de DNS', 'Ver os nomes que cada app consultou hoje?', 'Ver', () => chamar('GET', '/api/extras/diario'));
    if (!r) return;
    mostrar($('saidaExtras'), r.linhas.length ? r.linhas.map((l) => `${String(l.vezes).padStart(5)}  ${l.nome}  (${l.app})`).join('\n')
      : (r.erro || 'Nada registrado hoje. A chave do diário está ligada?'));
  } catch (e) { mostrar($('saidaExtras'), textoErro(e)); }
});

$('btnApagarDiario').addEventListener('click', async () => {
  try {
    if (await comPin('Apagar diário', 'Apagar todo o diário de DNS guardado na TV?', 'Apagar', () => chamar('POST', '/api/extras/diario/apagar')))
      mostrar($('saidaExtras'), 'Diário apagado.');
  } catch (e) { mostrar($('saidaExtras'), textoErro(e)); }
});

// ---------- 2ª leva (10/11/2026): hora de dormir, tocando agora, recado por link, caches, consumo ----------

const DIAS = ['Domingo', 'Segunda', 'Terça', 'Quarta', 'Quinta', 'Sexta', 'Sábado'];

/** Uma linha por dia, com início e fim; os dois vazios = segue o padrão. */
function desenharDias(dias) {
  const lista = $('dormirDias');
  lista.replaceChildren();
  DIAS.forEach((nome, d) => {
    const linha = document.createElement('p');
    linha.className = 'aviso';
    const ini = document.createElement('input'); ini.type = 'time'; ini.dataset.dia = d; ini.dataset.campo = 'inicio';
    const fim = document.createElement('input'); fim.type = 'time'; fim.dataset.dia = d; fim.dataset.campo = 'fim';
    ini.setAttribute('aria-label', nome + ': início'); fim.setAttribute('aria-label', nome + ': fim');
    if (dias[d]) { ini.value = hhmm(dias[d].inicio); fim.value = hhmm(dias[d].fim); }
    linha.append(nome.padEnd(8, ' ') + ' ', ini, ' até ', fim);
    lista.append(linha);
  });
}

/** Os 7 dias para mandar: os dois horários preenchidos = horário próprio; senão null (segue o padrão). */
function lerDias() {
  return DIAS.map((_, d) => {
    const i = document.querySelector(`#dormirDias input[data-dia="${d}"][data-campo="inicio"]`).value;
    const f = document.querySelector(`#dormirDias input[data-dia="${d}"][data-campo="fim"]`).value;
    return i && f ? { inicio: minutos(i), fim: minutos(f) } : null;
  });
}

$('btnDormir').addEventListener('click', async () => {
  const corpo = { inicio: minutos($('dormirInicio').value), fim: minutos($('dormirFim').value),
    teto: Number($('dormirTeto').value), apps: $('dormirApps').value, dias: lerDias() };
  try {
    const r = await comPin('Hora de dormir', 'Salvar o horário, o teto do volume e os apps liberados', 'Salvar',
      () => chamar('POST', '/api/extras/dormir', corpo));
    if (r) { carregarChaves(r); mostrar($('statusDormir'), 'Salvo.'); }
  } catch (e) { mostrar($('statusDormir'), textoErro(e)); }
});

async function verTocando() {
  try {
    const t = await chamar('GET', '/api/extras/tocando');
    $('tocandoExtras').textContent = !t.ligado ? 'Ligue a chave "Tocando agora" para ver o que está tocando.'
      : t.nada ? (t.acesso ? 'Nada tocando.' : 'A TV ainda não deu acesso às sessões de mídia (o shell do launcher precisa estar de pé).')
      : `${t.tocando ? '▶' : '⏸'} ${t.titulo || '(sem título)'}${t.artista ? ' — ' + t.artista : ''} · ${t.app}`;
  } catch (e) { $('tocandoExtras').textContent = textoErro(e); }
}
$('btnTocando').addEventListener('click', verTocando);
for (const b of document.querySelectorAll('[data-tocando]')) {
  b.addEventListener('click', async () => {
    try { await chamar('POST', '/api/extras/tocando', { acao: b.dataset.tocando }); setTimeout(verTocando, 800); }
    catch (e) { $('tocandoExtras').textContent = textoErro(e); }
  });
}

$('btnRecadoLink').addEventListener('click', async () => {
  try {
    const r = await comPin('Recado por link', 'Gerar um link novo (o anterior para de funcionar)', 'Gerar',
      () => chamar('POST', '/api/extras/recado-link'));
    if (!r) return;
    const url = `${location.origin}/recado/${r.token}`;
    // Exemplo pronto para o Home Assistant (rest_command) e para o curl.
    mostrar($('saidaRecadoLink'), `${url}\n\nHome Assistant (configuration.yaml):\nrest_command:\n  recado_tv:\n    url: "${url}"\n` +
      `    method: POST\n    content_type: "application/json"\n    payload: '{"title":"Casa","message":"{{ texto }}","duration":15}'\n\n` +
      `curl -X POST -H "Content-Type: application/json" -d '{"message":"Jantar pronto!"}' ${url}`);
  } catch (e) { mostrar($('saidaRecadoLink'), textoErro(e)); }
});

$('btnCaches').addEventListener('click', async () => {
  mostrar($('saidaManutencao'), 'Limpando (pode levar alguns segundos)...');
  try { const r = await chamar('POST', '/api/extras/caches/limpar'); mostrar($('saidaManutencao'), r.resultado); carregarChaves(); }
  catch (e) { mostrar($('saidaManutencao'), textoErro(e)); }
});

$('btnConsumo').addEventListener('click', async () => {
  try {
    const r = await chamar('GET', '/api/extras/consumo?dias=' + $('diasConsumo').value);
    mostrar($('saidaManutencao'), r.erro ? 'Erro: ' + r.erro
      : `Total: ${r.total}\n` + (r.apps.map((a) => `${a.texto.padStart(10)}  ${a.nome}`).join('\n') || 'nada medido ainda'));
  } catch (e) { mostrar($('saidaManutencao'), textoErro(e)); }
});

// ---------- casa (Home Assistant; PainelExtras "casa/*", CasaHA.kt) ----------

async function carregarCasa() {
  const r = await chamar('GET', '/api/extras/casa');
  $('casaEndereco').value = r.endereco || '';
  $('casaSensor').value = r.campainha || '';
  $('casaCamera').value = r.camera || '';
  const lista = $('listaCasa');
  lista.replaceChildren();
  if (r.erroCasa) mostrar($('statusCasa'), r.erroCasa);
  if (!r.configurado) { const p = document.createElement('p'); p.className = 'aviso'; p.textContent = 'Ainda não configurado.'; lista.append(p); return; }
  for (const a of (r.aparelhos || [])) {
    const linha = document.createElement('div'); linha.className = 'linha-registro';
    const nome = document.createElement('strong'); nome.textContent = `${a.nome} · ${a.estado}`;
    const d = a.id.split('.')[0];
    const acao = (d === 'scene' || d === 'script') ? 'ligar' : 'alternar';
    const b = document.createElement('button'); b.type = 'button'; b.className = 'botao-secundario';
    b.textContent = acao === 'ligar' ? 'Rodar' : 'Ligar/desligar';
    b.addEventListener('click', async () => {
      try { await chamar('POST', '/api/extras/casa/acionar', { id: a.id, acao }); carregarCasa(); }
      catch (e) { mostrar($('statusCasa'), textoErro(e)); }
    });
    linha.append(nome, b);
    lista.append(linha);
  }
}

$('formCasa').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const endereco = $('casaEndereco').value, token = $('casaToken').value;
  try {
    if (await comPin('Home Assistant', 'Guardar o endereço e o token na TV?', 'Guardar',
      () => chamar('POST', '/api/extras/casa/config', { endereco, token }))) {
      $('casaToken').value = ''; mostrar($('statusCasa'), 'Guardado.'); carregarCasa();
    }
  } catch (e) { mostrar($('statusCasa'), textoErro(e)); }
});

$('formCampainha').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  try {
    if (await comPin('Campainha', 'Mostrar a câmera na TV quando a campainha tocar?', 'Salvar',
      () => chamar('POST', '/api/extras/casa/campainha', { sensor: $('casaSensor').value, camera: $('casaCamera').value })))
      mostrar($('statusCasa'), 'Campainha salva.');
  } catch (e) { mostrar($('statusCasa'), textoErro(e)); }
});

$('btnCasaEsquecer').addEventListener('click', async () => {
  try {
    if (await comPin('Esquecer', 'Apagar o endereço e o token do Home Assistant da TV?', 'Apagar',
      () => chamar('POST', '/api/extras/casa/esquecer'))) carregarCasa();
  } catch (e) { mostrar($('statusCasa'), textoErro(e)); }
});

document.addEventListener('aba', (ev) => {
  if (ev.detail === 'extras') carregarCasa().catch((e) => mostrar($('statusCasa'), textoErro(e)));
});
