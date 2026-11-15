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
