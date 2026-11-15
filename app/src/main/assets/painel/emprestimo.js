// Cartão "Shell emprestado" da aba Extras (15/11/2026; rotas em PainelShizuku.kt). O launcher empresta o shell a apps
// feitos para o Shizuku. Aqui: quem pede, quem tem acesso, permitir (pede o PIN) e revogar.
// Usa as funções globais do app.js: $, chamar, mostrar, confirmar, textoErro. Texto da TV só por textContent.
'use strict';

const MODOS_EMPRESTIMO = { DESLIGADO: 'desligado', SOB_DEMANDA: 'quando um app precisar', SEMPRE: 'sempre ligado' };
const PARADO_EMPRESTIMO = {
  DESLIGADO: 'Desligado nas Configurações da TV (Configurações do launcher, Emprestar poderes do sistema).',
  PLANO: 'Faz parte dos planos pagos.',
  SHIZUKU_OFICIAL: 'O app Shizuku está instalado nesta TV: o launcher não entra no caminho dele.',
};

document.addEventListener('aba', (ev) => { if (ev.detail === 'extras') carregarEmprestimo(); });

async function carregarEmprestimo() {
  const status = $('statusEmprestimo'), lista = $('listaEmprestimo');
  mostrar(status, '');
  let r;
  try { r = await chamar('GET', '/api/shizuku'); } catch (e) { mostrar(status, textoErro(e)); return; }
  const parado = r.parado !== 'NAO' ? ' ' + (PARADO_EMPRESTIMO[r.parado] || '') : '';
  $('estadoEmprestimo').textContent = `Modo: ${MODOS_EMPRESTIMO[r.modo] || r.modo}. ` +
    (r.rodando ? 'Rodando agora.' : 'Parado agora.') + parado;
  lista.replaceChildren();
  if (!r.apps.length) {
    lista.appendChild(Object.assign(document.createElement('p'), { className: 'aviso', textContent: 'Nenhum app instalado pede o Shizuku.' }));
    return;
  }
  for (const a of r.apps) {
    const linha = document.createElement('p');
    const nome = document.createElement('strong');
    nome.textContent = a.nome;
    const situacao = a.permitido === undefined ? ' · estado só com o empréstimo rodando'
      : (a.permitido ? ' · tem acesso' : ' · sem acesso');
    linha.append(nome, situacao, ' ');
    if (a.permitido !== undefined) {
      const acao = a.permitido ? 'revogar' : 'permitir';
      const botao = document.createElement('button');
      botao.type = 'button';
      botao.className = a.permitido ? 'botao-secundario' : 'botao-primario';
      botao.textContent = a.permitido ? 'Revogar' : 'Permitir';
      botao.addEventListener('click', async () => {
        const fazer = () => chamar('POST', '/api/shizuku/app', { pacote: a.pacote, acao });
        try {
          const ok = a.permitido ? await fazer()
            : await confirmar('Permitir o shell?', `${a.nome} vai poder fazer na TV o que o adb faz.`, 'Permitir', fazer);
          if (ok) carregarEmprestimo();
        } catch (e) { mostrar(status, textoErro(e)); }
      });
      linha.appendChild(botao);
    }
    lista.appendChild(linha);
  }
}

// Cartão "Controle de dono emprestado" (Dhizuku; rotas em PainelShizuku.kt, PainelDono). Autorizar app novo só na TV.
const PARADO_DONO = {
  DESLIGADO: 'Desligado nas Configurações da TV.',
  PLANO: 'Faz parte dos planos pagos.',
  SEM_DONO: 'O launcher não é o dono desta TV: não há o que emprestar.',
};

document.addEventListener('aba', (ev) => { if (ev.detail === 'extras') carregarDono(); });

async function carregarDono() {
  const status = $('statusDono'), lista = $('listaDono');
  mostrar(status, '');
  let r;
  try { r = await chamar('GET', '/api/dono'); } catch (e) { mostrar(status, textoErro(e)); return; }
  $('estadoDono').textContent = r.parado === 'NAO' ? 'Ligado.' : (PARADO_DONO[r.parado] || 'Desligado.');
  lista.replaceChildren();
  if (!r.apps.length) {
    lista.appendChild(Object.assign(document.createElement('p'), { className: 'aviso', textContent: 'Nenhum app autorizado. O app pede na TV na primeira vez.' }));
    return;
  }
  for (const a of r.apps) {
    const linha = document.createElement('p');
    const nome = document.createElement('strong');
    nome.textContent = a.nome;
    linha.append(nome, a.total ? ' · poder total' : ' · só o controle de dono', ' ');
    const botao = (texto, classe, acao, pergunta) => {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = classe;
      b.textContent = texto;
      b.addEventListener('click', async () => {
        const fazer = () => chamar('POST', '/api/dono/app', { pacote: a.pacote, acao });
        try {
          const ok = pergunta ? await confirmar(pergunta, `${a.nome} vai rodar como o launcher e ler os dados dele.`, texto, fazer) : await fazer();
          if (ok) carregarDono();
        } catch (e) { mostrar(status, textoErro(e)); }
      });
      return b;
    };
    linha.appendChild(a.total ? botao('Tirar poder total', 'botao-secundario', 'normal')
      : botao('Dar poder total', 'botao-secundario', 'total', 'Dar poder total?'));
    linha.append(' ');
    linha.appendChild(botao('Revogar', 'botao-secundario', 'revogar'));
    lista.appendChild(linha);
  }
}
