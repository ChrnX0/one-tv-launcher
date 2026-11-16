// "Cuidar de longe" (ideias 77, 78, 79; 15/11/2026). Rotas em /api/cuidar (PainelCuidar.kt).
// Cartão de configuração na aba Extras e, na aba Controle, o pedido de ajuda das últimas 24 h com a foto.
// Usa as globais do app.js: $, chamar, mostrar, confirmar, PrecisaPin, textoErro. Texto da TV entra por textContent.
'use strict';

(function () {
  const NOMES_AVISOS = {
    AJUDA: 'Pedido de ajuda (segurar a tecla 3 s), com a foto da tela',
    LEMBRETE: 'Lembrete não confirmado no prazo',
    NAO_LIGOU: 'TV não ligada até a hora de costume + margem',
  };
  let atual = null;
  const doisDigitos = (n) => String(n).padStart(2, '0');
  const hhmm = (min) => doisDigitos(Math.floor(min / 60)) + ':' + doisDigitos(min % 60);
  const cuidar = (metodo, rota, corpo) => chamar(metodo, '/api/cuidar' + rota, corpo);

  // Escritas pedem o PIN de novo (o servidor exige: é o que decide para quem vão fotos da tela).
  async function comPin(titulo, fazer) {
    try { return await fazer(); } catch (e) {
      if (!(e instanceof PrecisaPin)) throw e;
      let r = null;
      const ok = await confirmar(titulo, 'Digite o PIN do painel para continuar.', 'Continuar', async () => { r = await fazer(); }, false);
      return ok ? r : null;
    }
  }

  function opcoes(sel, lista, valor) {
    sel.textContent = '';
    for (const [v, t] of lista) {
      const o = document.createElement('option');
      o.value = String(v); o.textContent = t; if (v === valor) o.selected = true;
      sel.appendChild(o);
    }
  }

  function caixa(rotulo, marcada, valor) {
    const l = document.createElement('label');
    const c = document.createElement('input');
    c.type = 'checkbox'; c.checked = marcada; c.value = valor;
    l.append(c, ' ' + rotulo);
    const d = document.createElement('div'); d.appendChild(l);
    return d;
  }

  function desenhar(e) {
    atual = e;
    let estado;
    if (!e.plano) estado = 'Disponível no plano Ultra.';
    else if (e.consentido) estado = 'Ligado: ' + e.nome + ' recebe os avisos (aceito na TV em ' + new Date(e.consentidoEm).toLocaleDateString() + ').';
    else if (e.falta === 'NOME') estado = 'Desligado. Falta o nome de quem recebe.';
    else if (e.falta === 'APARELHO') estado = 'Desligado. Falta escolher o celular que recebe.';
    else if (e.falta === 'AVISO') estado = 'Desligado. Falta escolher os avisos.';
    else estado = 'Pronto, mas desligado: toque em "Perguntar na TV". Quem mora aí aceita pelo controle.';
    $('estadoCuidar').textContent = estado;
    if (document.activeElement !== $('nomeCuidar')) $('nomeCuidar').value = e.nome || '';
    $('teclaCuidar').value = e.tecla;

    const ap = $('aparelhosCuidar'); ap.textContent = '';
    if (!e.disponiveis.length) ap.appendChild(Object.assign(document.createElement('p'), { className: 'aviso', textContent: 'Nenhum celular inscrito nos avisos ainda.' }));
    for (const a of e.disponiveis) ap.appendChild(caixa('Recebe: ' + a.nome, e.aparelhos.includes(a.id), a.id));

    const av = $('avisosCuidar'); av.textContent = '';
    for (const k of Object.keys(NOMES_AVISOS)) if (e.familia !== false || k === 'AJUDA') av.appendChild(caixa(NOMES_AVISOS[k], e.avisos.includes(k), k));

    opcoes($('prazoCuidar'), [10, 15, 20, 30, 60].map((m) => [m, m + ' min']), e.prazo);
    const aprendida = e.costume != null && e.horaFixa < 0 ? 'Aprender (' + hhmm(e.costume) + ')' : 'Aprender (' + e.amostras + ' de 5 dias)';
    const horas = [[-1, aprendida]];
    for (let m = 5 * 60; m <= 14 * 60; m += 30) horas.push([m, hhmm(m)]);
    opcoes($('horaCuidar'), horas, e.horaFixa);
    opcoes($('margemCuidar'), [60, 120, 180, 240].map((m) => [m, (m / 60) + ' h']), e.margem);
    $('acenderCuidar').checked = !!e.acender;

    const lc = $('lembretesCuidar'); lc.textContent = '';
    const hoje = new Map((e.lembretesHoje || []).map((x) => [x.id + '@' + x.hora, x]));
    for (const l of e.lembretes) {
      const linha = document.createElement('div'); linha.className = 'linha-registro';
      const h = hhmm(l.hora * 60 + l.minuto);
      const situacao = hoje.get(l.id + '@' + h);
      let txt = h + '  ' + l.texto;
      if (situacao) txt += situacao.confirmado ? '  · feito às ' + new Date(situacao.confirmado).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
        : situacao.avisado ? '  · não confirmado (avisado)' : '  · esperando o Feito';
      linha.appendChild(Object.assign(document.createElement('span'), { textContent: txt }));
      const b = document.createElement('button'); b.type = 'button'; b.className = 'botao-secundario botao-pequeno'; b.textContent = 'Apagar';
      b.addEventListener('click', async () => {
        try { const r = await comPin('Apagar lembrete', () => cuidar('POST', '/lembrete', { acao: 'remover', id: l.id })); if (r) desenhar(r); }
        catch (err) { mostrar($('statusCuidar'), textoErro(err)); }
      });
      linha.appendChild(b);
      lc.appendChild(linha);
    }
    desenharAjuda(e);
  }

  function marcadas(id) { return [...$(id).querySelectorAll('input[type=checkbox]')].filter((c) => c.checked).map((c) => c.value); }

  async function carregar() {
    try { desenhar(await cuidar('GET', '')); } catch (err) { mostrar($('statusCuidar'), textoErro(err)); }
  }

  $('btnGravarCuidar').addEventListener('click', async () => {
    const corpo = {
      nome: $('nomeCuidar').value.trim(), tecla: $('teclaCuidar').value, aparelhos: marcadas('aparelhosCuidar'),
      avisos: marcadas('avisosCuidar'), prazo: +$('prazoCuidar').value, horaFixa: +$('horaCuidar').value,
      margem: +$('margemCuidar').value, acender: $('acenderCuidar').checked,
    };
    try {
      const r = await comPin('Gravar o Cuidar de longe', () => cuidar('POST', '', corpo));
      if (!r) return;
      desenhar(r);
      mostrar($('statusCuidar'), r.consentido ? 'Gravado.' : 'Gravado. Para ligar, toque em "Perguntar na TV": quem mora aí aceita pelo controle.');
    } catch (err) { mostrar($('statusCuidar'), textoErro(err)); }
  });

  $('btnPerguntarCuidar').addEventListener('click', async () => {
    try {
      const r = await comPin('Perguntar na TV', () => cuidar('POST', '/perguntar'));
      if (r) { desenhar(r); mostrar($('statusCuidar'), 'A pergunta está na TV. Só vale o aceite pelo controle da TV, não por este painel.'); }
    } catch (err) { mostrar($('statusCuidar'), textoErro(err)); }
  });

  $('btnTestarCuidar').addEventListener('click', async () => {
    try { const r = await cuidar('POST', '/testar'); desenhar(r); mostrar($('statusCuidar'), 'Teste: ' + r.teste + '.'); }
    catch (err) { mostrar($('statusCuidar'), textoErro(err)); }
  });

  $('btnDesligarCuidar').addEventListener('click', async () => {
    const ok = await confirmar('Desligar o Cuidar de longe?', 'Os celulares recebem um último aviso dizendo que foi desligado. Para ligar de novo, a TV pergunta outra vez.',
      'Desligar', async () => { desenhar(await cuidar('POST', '/desligar')); });
    if (ok) mostrar($('statusCuidar'), 'Desligado.');
  });

  $('formLembreteCuidar').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const [h, m] = ($('horaLembreteCuidar').value || '').split(':').map(Number);
    const texto = $('textoLembreteCuidar').value.trim();
    if (!texto || Number.isNaN(h)) return;
    try {
      const r = await comPin('Novo lembrete', () => cuidar('POST', '/lembrete', { acao: 'salvar', hora: h, minuto: m, texto }));
      if (r) { desenhar(r); $('textoLembreteCuidar').value = ''; }
    } catch (err) { mostrar($('statusCuidar'), textoErro(err)); }
  });

  // ---------- aba Controle: o pedido de ajuda das últimas 24 h ----------

  function desenharAjuda(e) {
    const a = e && e.ajuda;
    $('ajudaCuidar').hidden = !a;
    if (!a) return;
    $('tituloAjudaCuidar').textContent = 'Pedido de ajuda às ' + new Date(a.em).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    const img = $('fotoAjudaCuidar');
    img.hidden = !a.foto;
    if (a.foto) img.src = '/api/cuidar/foto?em=' + a.em;   // ?em= troca a foto quando há pedido novo
  }

  $('formRespostaCuidar').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const texto = $('textoRespostaCuidar').value.trim();
    if (!texto) return;
    try { await cuidar('POST', '/responder', { texto }); $('textoRespostaCuidar').value = ''; mostrar($('statusAjudaCuidar'), 'Apareceu na TV.'); }
    catch (err) { mostrar($('statusAjudaCuidar'), textoErro(err)); }
  });

  document.addEventListener('aba', (ev) => { if (ev.detail === 'extras' || ev.detail === 'controle') carregar(); });
})();
