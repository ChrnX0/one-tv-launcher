// Ideias de 12/11/2026 na aba Extras: avisos no celular, Meu time, mural, presença, semana e modo simples.
// Rotas em /api/extras/ideias/ (PainelIdeias.kt). Todo texto entra por textContent (nomes vêm da TV e do roteador).
'use strict';

document.addEventListener('aba', (ev) => { if (ev.detail === 'extras') carregarIdeias(); });

function el(tag, classe, texto) {
  const e = document.createElement(tag);
  if (classe) e.className = classe;
  if (texto !== undefined) e.textContent = texto;
  return e;
}

function botao(texto, fazer) {
  const b = el('button', 'botao-secundario', texto);
  b.type = 'button';
  b.addEventListener('click', fazer);
  return b;
}

const ideias = (metodo, rota, corpo) => chamar(metodo, '/api/extras/ideias/' + rota, corpo);

async function carregarIdeias() {
  try {
    const e = await ideias('GET', 'estado');
    desenharAvisos(e.avisos);
    desenharLembretes(e.lembretes);
    desenharMural(e.mural);
    desenharSemana(e.retrospectiva, e.desempenho);
    desenharSimples(e.simples);
    presencaEscolhidos = new Set(e.presenca);
  } catch (err) { mostrar($('statusAvisos'), textoErro(err)); }
}

// ---------- avisos no celular (Web Push) ----------

let chaveVapid = null;

function desenharAvisos(a) {
  chaveVapid = a.chave;
  const lista = $('listaAvisos');
  lista.replaceChildren();
  if (!a.aparelhos.length) lista.append(el('p', 'aviso', 'Nenhum celular recebe avisos ainda.'));
  for (const ap of a.aparelhos) {
    const linha = el('div', 'linha-registro');
    linha.append(el('strong', '', ap.nome), el('span', 'quando', ap.servico + ' · desde ' + new Date(ap.desde).toLocaleDateString()),
      botao('Remover', async () => {
        try { desenharAvisos(await ideias('POST', 'avisos/remover', { id: ap.id })); } catch (err) { mostrar($('statusAvisos'), textoErro(err)); }
      }));
    lista.append(linha);
  }
  const tipos = $('tiposAvisos');
  tipos.replaceChildren();
  for (const t of a.tipos) {
    const rotulo = el('label');
    const caixa = el('input'); caixa.type = 'checkbox'; caixa.checked = t.ligado;
    caixa.addEventListener('change', async () => {
      try { await ideias('POST', 'avisos/tipo', { tipo: t.tipo, ligado: caixa.checked }); }
      catch (err) { caixa.checked = !caixa.checked; mostrar($('statusAvisos'), textoErro(err)); }
    });
    rotulo.append(caixa, ' ' + t.rotulo);
    tipos.append(rotulo);
  }
}

/** base64url -> bytes (a chave VAPID que o navegador pede). */
function deB64url(s) {
  const b = atob(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4));
  return Uint8Array.from(b, (c) => c.charCodeAt(0));
}

$('btnAvisosInscrever').addEventListener('click', async () => {
  const st = $('statusAvisos');
  if (!window.isSecureContext || !('serviceWorker' in navigator) || !('PushManager' in window)) {
    mostrar(st, 'Este navegador não recebe avisos por aqui. Abra o painel com cadeado (https, porta 8444) no Chrome do celular: veja Extras, "Instalar como app".');
    return;
  }
  if (!chaveVapid) { mostrar(st, 'A TV não criou a chave dos avisos. Tente de novo.'); return; }
  try {
    if (await Notification.requestPermission() !== 'granted') { mostrar(st, 'Permissão de notificação negada no navegador.'); return; }
    const reg = await navigator.serviceWorker.register('/sw.js', { scope: '/' });
    await navigator.serviceWorker.ready;
    let ins = await reg.pushManager.getSubscription();
    if (!ins) ins = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: deB64url(chaveVapid) });
    const nome = $('nomeAvisos').value.trim() || 'celular';
    desenharAvisos(await ideias('POST', 'avisos/inscrever', { inscricao: ins.toJSON(), nome }));
    mostrar(st, 'Pronto: este aparelho recebe os avisos da TV.');
  } catch (err) { mostrar(st, textoErro(err)); }
});

$('btnAvisosTestar').addEventListener('click', async () => {
  try { mostrar($('statusAvisos'), 'Teste: ' + (await ideias('POST', 'avisos/testar')).resultado); }
  catch (err) { mostrar($('statusAvisos'), textoErro(err)); }
});

// ---------- Meu time e lembretes ----------

function desenharLembretes(l) {
  $('palavrasMeuTime').value = l.palavras || '';
  const lista = $('listaLembretes');
  lista.replaceChildren();
  if (!l.lembretes.length) lista.append(el('p', 'aviso', 'Nenhum lembrete nas próximas 24 h.'));
  for (const x of l.lembretes) {
    const linha = el('div', 'linha-registro');
    linha.append(el('strong', '', x.hora + ' · ' + x.titulo),
      el('span', 'quando', x.canal + (x.palavra ? ' · Meu time: ' + x.palavra : ' · marcado à mão')),
      botao('Apagar', async () => {
        try { desenharLembretes(await ideias('POST', 'lembretes/apagar', { chave: x.chave })); }
        catch (err) { mostrar($('statusLembretes'), textoErro(err)); }
      }));
    lista.append(linha);
  }
}

$('btnPalavrasMeuTime').addEventListener('click', async () => {
  try {
    desenharLembretes(await ideias('POST', 'palavras', { texto: $('palavrasMeuTime').value }));
    mostrar($('statusLembretes'), 'Salvo. A TV procura no guia em alguns segundos; abra esta aba de novo para ver os lembretes.');
  } catch (err) { mostrar($('statusLembretes'), textoErro(err)); }
});

// ---------- mural ----------

function desenharMural(m) {
  const lista = $('itensMural');
  lista.replaceChildren();
  const grupos = [['compras', 'Compras', m.compras], ['recados', 'Recados', m.recados]];
  for (const [lista_, nome, itens] of grupos) {
    if (!itens.length) continue;
    lista.append(el('strong', '', nome));
    for (const t of itens) {
      const linha = el('div', 'linha-registro');
      linha.append(el('span', '', t), botao('Tirar', async () => {
        try { desenharMural(await ideias('POST', 'mural', { lista: lista_, acao: 'remover', texto: t })); } catch (_) {}
      }));
      lista.append(linha);
    }
  }
  if (!m.compras.length && !m.recados.length) lista.append(el('p', 'aviso', 'Mural vazio.'));
}

$('formMural').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const texto = $('textoMural').value.trim();
  if (!texto) return;
  try {
    desenharMural(await ideias('POST', 'mural', { lista: $('listaMural').value, acao: 'adicionar', texto }));
    $('textoMural').value = '';
  } catch (_) {}
});

// ---------- presença ----------

let presencaEscolhidos = new Set();

$('btnPresencaVer').addEventListener('click', async () => {
  const st = $('statusPresenca');
  mostrar(st, 'Perguntando ao roteador…');
  try {
    const r = await ideias('GET', 'presenca');
    mostrar(st, r.erro ? 'Roteador: ' + r.erro : '');
    presencaEscolhidos = new Set(r.escolhidos);
    const lista = $('listaPresenca');
    lista.replaceChildren();
    for (const a of (r.aparelhos || [])) {
      const rotulo = el('label');
      const caixa = el('input'); caixa.type = 'checkbox'; caixa.checked = a.conta; caixa.value = a.mac;
      caixa.addEventListener('change', () => { if (caixa.checked) presencaEscolhidos.add(a.mac); else presencaEscolhidos.delete(a.mac); });
      rotulo.append(caixa, ' ' + (a.nome || 'sem nome') + ' (' + a.mac + ')');
      lista.append(rotulo);
    }
    $('btnPresencaSalvar').hidden = !(r.aparelhos || []).length;
  } catch (err) { mostrar(st, textoErro(err)); }
});

$('btnPresencaSalvar').addEventListener('click', async () => {
  try {
    const r = await ideias('POST', 'presenca', { macs: [...presencaEscolhidos] });
    mostrar($('statusPresenca'), r.escolhidos.length + ' celular(es) contam como "alguém em casa".');
  } catch (err) { mostrar($('statusPresenca'), textoErro(err)); }
});

// ---------- semana e desempenho ----------

function desenharSemana(r, d) {
  $('textoSemana').textContent = r && r.linhas ? 'Última semana: ' + r.linhas.filter(Boolean).join(' · ') : 'A semana ainda não foi montada (domingo, 20h).';
  $('textoDesempenho').textContent = d && d.situacao ? 'Desenho da HOME nesta versão: ' + d.situacao : '';
}

$('btnSemanaMontar').addEventListener('click', async () => {
  try { await ideias('POST', 'retrospectiva/montar'); carregarIdeias(); } catch (err) { $('textoSemana').textContent = textoErro(err); }
});

// ---------- modo simples ----------

function desenharSimples(s) {
  const marcados = new Set(s.apps);
  const lista = $('appsSimples');
  lista.replaceChildren();
  for (const a of s.instalados) {
    const rotulo = el('label');
    const caixa = el('input'); caixa.type = 'checkbox'; caixa.checked = marcados.has(a.pacote); caixa.value = a.pacote;
    rotulo.append(caixa, ' ' + a.nome);
    lista.append(rotulo);
  }
}

$('btnSimplesSalvar').addEventListener('click', async () => {
  const apps = [...$('appsSimples').querySelectorAll('input:checked')].map((c) => c.value);
  if (apps.length > 6) { mostrar($('statusSimples'), 'No máximo 6 apps.'); return; }
  try {
    const r = await ideias('POST', 'simples', { apps });
    mostrar($('statusSimples'), r.apps.length ? r.apps.length + ' app(s) salvos.' : 'Sem apps marcados: vão os mais usados.');
  } catch (err) { mostrar($('statusSimples'), textoErro(err)); }
});
