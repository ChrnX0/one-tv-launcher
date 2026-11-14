// Aba "Conectar uma IA" (10/11/2026): endereço do MCP, QR e o passo a passo de cada app de IA.
// Arquivo separado de propósito (o app.js e a navegação estão sendo refeitos por outra frente): usa só o $ e o
// chamar() globais do app.js e mostra/esconde a própria aba ao clicar nos botões de aba.
(function () {
  const CLIENTES = [
    { nome: 'Claude (claude.ai, app do celular ou do PC)', passos: [
      'Configurações › Conectores › Adicionar conector personalizado.',
      'Nome: TV. URL: cole o endereço. Salvar e Conectar.',
      'Na tela da TV, entre com o PIN deste painel. Pronto: peça "abre o YouTube na TV".'] },
    { nome: 'ChatGPT', passos: [
      'Configurações › Aplicativos e conectores › Avançado › ligue o Modo desenvolvedor.',
      'Criar conector: URL do servidor MCP = o endereço; autenticação OAuth.',
      'Entre com o PIN. Na conversa, escolha o conector TV no botão +.'] },
    { nome: 'Claude Code (terminal)', passos: ['Rode o comando abaixo e depois /mcp para fazer o login.'],
      comando: e => 'claude mcp add --transport http tv ' + e },
    { nome: 'Codex (terminal)', passos: ['Rode o comando abaixo; o login abre no navegador.'],
      comando: e => 'codex mcp add tv --url ' + e + ' && codex mcp login tv' },
    { nome: 'Cursor, VS Code e outros editores', passos: ['Cole no arquivo de servidores MCP (mcp.json) e entre com o PIN quando pedir.'],
      comando: e => JSON.stringify({ mcpServers: { tv: { url: e } } }, null, 2) },
    { nome: 'Perplexity, Grok, Antigravity', passos: [
      'Procure "Conectores" ou "MCP" nas configurações, adicione um servidor remoto com o endereço e entre com o PIN.'] },
  ];
  const EXEMPLOS = [
    '"O que está passando na TV?" (precisa de "Ver a tela pelo Claude" ligado em Extras)',
    '"Coloca o último episódio de Round 6 na Netflix."',
    '"Manda um recado na TV: o jantar está pronto."',
    '"Cria um despertador às 6:30 nos dias úteis."',
    '"Instala este APK: https://..." e "Atualiza o launcher."',
    '"Faz um checkup da TV e da internet."',
    '"Põe a TV para dormir." / "Aumenta o volume."',
  ];

  let endereco = '';
  function el(tag, txt, cls) { const e = document.createElement(tag); if (txt) e.textContent = txt; if (cls) e.className = cls; return e; }

  async function copiar(texto, botao) {
    try { await navigator.clipboard.writeText(texto); }
    catch (_) {   // HTTP sem HTTPS: o navegador pode bloquear o clipboard; cai no jeito antigo
      const t = el('textarea'); t.value = texto; document.body.appendChild(t); t.select();
      try { document.execCommand('copy'); } catch (_) {} t.remove();
    }
    const antes = botao.textContent; botao.textContent = 'Copiado!'; setTimeout(() => { botao.textContent = antes; }, 1500);
  }

  function desenharClientes() {
    const caixa = $('iaClientes'); caixa.textContent = '';
    for (const c of CLIENTES) {
      const d = el('details'); d.appendChild(el('summary', c.nome));
      const ol = el('ol', '', 'aviso-curto'); for (const p of c.passos) ol.appendChild(el('li', p)); d.appendChild(ol);
      if (c.comando) {
        const texto = c.comando(endereco || 'https://<endereço da TV>/mcp');
        d.appendChild(el('pre', texto));
        const b = el('button', 'Copiar', 'botao-secundario'); b.type = 'button'; b.onclick = () => copiar(texto, b); d.appendChild(b);
      }
      caixa.appendChild(d);
    }
  }

  async function carregar() {
    try {
      const r = await chamar('GET', '/api/mcp');
      endereco = r.endereco || '';
      $('iaEstado').textContent = !r.ligado ? 'O acesso pela internet está desligado: ligue na aba Claude.'
        : endereco ? 'Ligado. Este é o endereço da sua TV:' : 'Ligado, esperando o Tailscale criar o endereço (veja a aba Claude).';
      $('iaEndereco').textContent = endereco;
      $('iaCopiar').hidden = !endereco;
      $('iaQr').hidden = !endereco;
      if (endereco) $('iaQr').src = '/api/mcp/qr-endereco?t=' + Date.now();
    } catch (e) { $('iaEstado').textContent = e.message; }
    desenharClientes();
  }

  // Ideia 61: domínios de clientes de IA liberados pelo dono (o login MCP aceita o redirect https deles).
  let hosts = [];
  function desenharHosts() {
    const ul = $('iaHosts'); ul.replaceChildren();
    for (const h of hosts) {
      const li = el('li', h + ' ');
      const b = el('button', 'Tirar', 'botao-secundario'); b.type = 'button';
      b.onclick = () => salvarHosts(hosts.filter((x) => x !== h));
      li.appendChild(b); ul.appendChild(li);
    }
  }
  async function carregarHosts() {
    try { hosts = (await chamar('GET', '/api/extras/mcp/hosts')).hosts; desenharHosts(); } catch (e) { mostrar($('iaHostsStatus'), e.message); }
  }
  async function salvarHosts(nova) {
    try {
      const r = await confirmar('Apps de IA liberados', nova.length ? nova.join(', ') : 'Nenhum domínio extra', 'Salvar',
        () => chamar('POST', '/api/extras/mcp/hosts', { hosts: nova }));
      if (r) { hosts = r.hosts; desenharHosts(); mostrar($('iaHostsStatus'), 'Salvo.'); $('iaHostNovo').value = ''; }
    } catch (e) { mostrar($('iaHostsStatus'), e.message); }
  }

  function iniciar() {
    if (!$('abaIa')) return;
    $('iaHostAdicionar').onclick = () => {
      const h = $('iaHostNovo').value.trim().toLowerCase().replace(/^https?:\/\//, '').replace(/\/.*$/, '');
      if (h && !hosts.includes(h)) salvarHosts(hosts.concat(h));
    };
    const ul = $('iaExemplos'); for (const x of EXEMPLOS) ul.appendChild(el('li', x));
    $('iaCopiar').onclick = () => copiar(endereco, $('iaCopiar'));
    // Mostrar/esconder a aba é com o mudarAba do app.js (vale para a barra, a tela "Mais" e o #ia no endereço);
    // aqui só carrega o conteúdo quando ela abre.
    document.addEventListener('aba', (e) => { if (e.detail === 'ia') { carregar(); carregarHosts(); } });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', iniciar); else iniciar();
})();
