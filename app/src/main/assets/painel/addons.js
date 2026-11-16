// Cartão "Addons" da aba Configurações (16/11/2026; rotas em PainelAddons.kt). Addons no formato do Stremio, só vídeo
// HTTP: torrent e P2P são recusados pela TV. Instalar e remover pedem o PIN; instalar pede o aceite dos termos.
// Usa as funções globais do app.js: $, chamar, mostrar, confirmar, textoErro. Texto da TV/addon só por textContent.
'use strict';

const MOTIVO_ADDON = {
  P2P: 'Este addon usa torrent/P2P. A TV só aceita addons com vídeo por link direto (HTTP).',
  ADULTO: 'Addon marcado como conteúdo adulto: não é aceito.',
  PRECISA_CONFIGURAR: 'Este addon precisa ser configurado antes. Configure no site dele e cole o endereço que ele der.',
  SEM_RECURSO_UTIL: 'Este addon não oferece catálogo, ficha, vídeo ou legenda.',
  INVALIDO: 'O endereço não tem um addon válido (manifest.json).',
  ENDERECO: 'Endereço inválido. Cole o link que termina em manifest.json.',
  CHEIO: 'Limite de addons atingido. Remova um para instalar outro.',
  SEM_REDE: 'O addon não respondeu. Confira o endereço e tente de novo.',
  SEM_ACEITE: 'Aceite os termos para instalar.',
};

const TERMOS_ADDON = 'Addons são feitos por terceiros. A Sofaly não cria, não hospeda e não confere o que eles mostram. ' +
  'Quem instala um addon responde pelo conteúdo dele e por ter direito de assistir. A Sofaly só toca vídeo por link ' +
  'direto: fontes de torrent e P2P são ignoradas.';

document.addEventListener('aba', (ev) => { if (ev.detail === 'config') carregarAddons(); });

function erroAddon(e) {
  const m = e && e.dados && e.dados.motivo;
  return (m && MOTIVO_ADDON[m]) || textoErro(e);
}

async function carregarAddons() {
  const status = $('statusAddons'), lista = $('listaAddons');
  mostrar(status, '');
  let r;
  try { r = await chamar('GET', '/api/addons'); } catch (e) { mostrar(status, textoErro(e)); return; }
  $('termosAddons').textContent = TERMOS_ADDON;
  $('aceiteAddons').checked = !!r.aceite;
  lista.replaceChildren();
  if (!r.addons.length) {
    lista.appendChild(Object.assign(document.createElement('p'), { className: 'aviso-curto', textContent: 'Nenhum addon instalado.' }));
  }
  for (const a of r.addons) {
    const linha = document.createElement('p');
    const nome = document.createElement('strong');
    nome.textContent = a.nome;
    const det = document.createElement('span');
    det.className = 'aviso-curto';
    det.textContent = ` · ${a.versao || ''} · ${a.base}`;
    const botao = document.createElement('button');
    botao.type = 'button';
    botao.className = 'botao-secundario';
    botao.textContent = 'Remover';
    botao.addEventListener('click', async () => {
      try {
        const ok = await confirmar('Remover addon?', `${a.nome} sai da TV.`, 'Remover',
          () => chamar('POST', '/api/addons/remover', { base: a.base }));
        if (ok) carregarAddons();
      } catch (e) { mostrar(status, erroAddon(e)); }
    });
    linha.append(nome, det, ' ', botao);
    lista.appendChild(linha);
  }
}

document.addEventListener('DOMContentLoaded', () => {
  const form = $('formAddon');
  if (!form) return;
  form.addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const status = $('statusAddons');
    const url = $('urlAddon').value.trim();
    if (!url) return;
    if (!$('aceiteAddons').checked) { mostrar(status, MOTIVO_ADDON.SEM_ACEITE); return; }
    mostrar(status, '');
    const fazer = async () => {
      try { return await chamar('POST', '/api/addons/instalar', { url, aceite: true }); }
      catch (e) { if (e instanceof PrecisaPin) throw e; throw new Error(erroAddon(e)); }
    };
    try {
      const r = await confirmar('Instalar addon?', TERMOS_ADDON, 'Instalar', fazer, false);
      if (r) { $('urlAddon').value = ''; mostrar(status, `Instalado: ${r.instalado}. Aparece na TV no card Addons.`); carregarAddons(); }
    } catch (e) { mostrar(status, erroAddon(e)); }
  });
});
