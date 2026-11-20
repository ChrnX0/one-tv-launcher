// Cartão "Pen drive e armazenamento" da aba Sistema (20/11/2026; rotas em PainelStorage.kt). O mesmo da tela da TV:
// modo do pen drive (comum, misto, memória da TV), recomendação e apps que podem ir para ele. Mover e formatar pedem
// o PIN e rodam na TV em segundo plano (minutos); o cartão se atualiza sozinho enquanto há tarefa.
// Usa as funções globais do app.js: $, chamar, mostrar, confirmar, textoErro, tamanhoLegivel. Texto da TV só por textContent.
'use strict';

const MODO_STORAGE = { COMUM: 'Pen drive comum', MISTO: 'Misto', MEMORIA_TV: 'Memória da TV', DESCONHECIDO: 'Sem formato' };
const MOTIVO_STORAGE = {
  PEN_LENTO: 'este pen drive é lento, os apps ficariam travando',
  PEN_PEQUENO: 'menos de 8 GB, pequeno demais para ajudar',
  TV_FOLGADA: 'a TV ainda tem espaço, não vale apagar o pen drive',
  PEN_GRANDE: 'metade para apps, metade para gravações e arquivos',
  TV_APERTADA: 'a TV está quase cheia',
};
let timerStorage = null;

document.addEventListener('aba', (ev) => { if (ev.detail === 'sistema') carregarStorage(); else pararStorage(); });

function pararStorage() { if (timerStorage) { clearTimeout(timerStorage); timerStorage = null; } }

function botaoStorage(texto, perigo, acao) {
  const b = Object.assign(document.createElement('button'), { type: 'button', textContent: texto,
    className: perigo ? 'botao-perigo' : 'botao-secundario' });
  b.addEventListener('click', acao);
  return b;
}

async function carregarStorage() {
  pararStorage();
  const status = $('statusStorage');
  let r;
  try { r = await chamar('GET', '/api/storage'); } catch (e) { mostrar(status, textoErro(e)); return; }
  if (!r.shell) { mostrar(status, 'Precisa do shell próprio da TV (Configurações > Avançado).'); return; }
  $('storageTv').textContent = `Memória da TV: ${tamanhoLegivel(r.livreTv)} livres de ${tamanhoLegivel(r.totalTv)}`;
  if (r.tarefa) mostrar(status, 'A TV está trabalhando nisso. Pode levar alguns minutos.');
  else if (r.ultimo && r.ultimo.motivo === 'PEN_LENTO') mostrar(status, 'Não deu: este pen drive grava devagar demais (menos de 10 MB/s) para virar memória da TV. Use como pen drive comum.');
  else if (r.ultimo) mostrar(status, r.ultimo.ok ? 'Pronto.' : `Não deu certo: ${r.ultimo.motivo}`);
  else mostrar(status, '');
  let medindoStorage = false;
  const pens = $('storagePens');
  pens.replaceChildren();
  if (!r.pens.length) {
    pens.appendChild(Object.assign(document.createElement('p'), { className: 'aviso-curto', textContent: 'Nenhum pen drive ligado na TV.' }));
  }
  for (const p of r.pens) {
    const bloco = document.createElement('div');
    bloco.className = 'linha-lista';
    // Partes separadas (span por span): cada uma é uma frase fixa que o i18n traduz sozinha.
    const span = (texto, classe) => Object.assign(document.createElement('span'), { textContent: texto, className: classe || '' });
    const t = document.createElement('p');
    t.append(span(`Pen drive · ${tamanhoLegivel(p.tamanho)}`), ' · ', span(MODO_STORAGE[p.modo] || p.modo));
    // Velocidade medida pela TV (a recomendação sai dela); sem medida ainda, a TV mede agora e o cartão relê.
    if (p.velocidade != null) t.append(' · ', `${p.velocidade.toFixed(1)} MB/s`);
    else if (p.medindo) { t.append(' · ', span('Medindo a velocidade…')); medindoStorage = true; }
    const rec = document.createElement('p');
    rec.className = 'aviso-curto';
    rec.append(span('Recomendado:'), ' ', span(MODO_STORAGE[p.recomendado]), ' · ', span(MOTIVO_STORAGE[p.motivo] || ''));
    const botoes = document.createElement('div');
    botoes.className = 'botoes-linha';
    const formatar = (modo, comum) => async () => {
      try {
        // Misto e memória da TV: a TV mede a velocidade antes e recusa pen drive lento (PEN_LENTO no resultado).
        const ok = await confirmar('Apagar o pen drive?', modo === 'COMUM'
          ? 'Tudo o que está nele será apagado. Como memória da TV, ele só funciona nesta TV. Se ele já tem apps, eles voltam para a TV antes.'
          : 'Tudo o que está nele será apagado. Como memória da TV, ele só funciona nesta TV e precisa ficar sempre ligado: se falhar ou for tirado, a TV pode não ligar até você desconectá-lo. Se ele já tem apps, eles voltam para a TV antes.',
          'Apagar e formatar', () => chamar('POST', '/api/storage/formatar', { disco: p.disco, modo, comum }));
        if (ok) carregarStorage();
      } catch (e) { mostrar(status, textoErro(e)); }
    };
    if (p.modo !== 'COMUM') botoes.appendChild(botaoStorage(p.adotado ? 'Voltar a ser pen drive comum' : 'Usar como pen drive comum', true, formatar('COMUM', 100)));
    botoes.appendChild(botaoStorage('Usar como misto (metade comum)', true, formatar('MISTO', 50)));
    if (p.modo !== 'MEMORIA_TV') botoes.appendChild(botaoStorage('Usar como memória da TV', true, formatar('MEMORIA_TV', 0)));
    bloco.append(t, rec, botoes);
    pens.appendChild(bloco);
  }
  const temAdotado = r.pens.some((p) => p.adotado);
  const apps = $('storageApps');
  apps.replaceChildren();
  $('storageAppsTitulo').hidden = !temAdotado;
  if (temAdotado) {
    for (const a of r.apps) {
      const linha = document.createElement('div');
      linha.className = 'linha-lista';
      const nome = document.createElement('span');
      nome.append(Object.assign(document.createElement('span'), { textContent: a.nome }), ` · ${tamanhoLegivel(a.bytes)}`);
      if (a.noPen) nome.append(' · ', Object.assign(document.createElement('span'), { textContent: 'no pen drive' }));
      const b = botaoStorage(a.noPen ? 'Trazer para a TV' : 'Levar para o pen drive', false, async () => {
        try {
          // App grande (a.grande): aviso forte; a TV só aceita com confirmar=true.
          const ok = await confirmar(a.noPen ? 'Trazer para a TV?' : 'Levar para o pen drive?',
            a.grande ? 'App grande: em pen drive lento a mudança pode passar de 10 minutos e já travou uma TV no meio. O app fecha enquanto é movido. Só continue com ninguém assistindo.'
              : `${a.nome} fecha enquanto é movido. Dá para desfazer depois.`, 'Mover',
            () => chamar('POST', '/api/storage/app', { pacote: a.pacote, externo: !a.noPen, confirmar: true }), a.grande);
          if (ok) carregarStorage();
        } catch (e) { mostrar(status, textoErro(e)); }
      });
      linha.append(nome, ' ', b);
      apps.appendChild(linha);
    }
  }
  // Enquanto a TV trabalha, confere de novo a cada 5 s (só com a aba aberta).
  if (r.tarefa || medindoStorage) timerStorage = setTimeout(carregarStorage, 5000);
}
