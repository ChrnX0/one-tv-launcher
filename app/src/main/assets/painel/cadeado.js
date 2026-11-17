// Cadeado automático (16/11/2026). O painel abre em http (porta 8080) em qualquer aparelho; com cadeado (https, porta
// 8444) só nos aparelhos que instalaram o certificado da TV (/certificado). Este arquivo descobre sozinho se ESTE
// aparelho já confia no certificado e, se confia, leva para o https. Assim o https vira o padrão sem ninguém digitar
// endereço: quem instalou o certificado sempre cai no cadeado; quem não instalou continua no http como antes.
//
// Como testa: pede um arquivo pequeno do painel no endereço https com fetch "no-cors". Se o navegador não confia no
// certificado (ou o nome não existe na rede), o fetch falha; se confia, resolve. Testa o NOME primeiro (ex.: tv-sala)
// e depois o IP, porque há aparelhos que não acham o nome (DNS privado no celular) mas abrem pelo IP.
// A CSP do painel deixa conectar em https://*:8444 só para isto (PainelHttp.CABECALHOS_SEGURANCA).

const cadeado = (() => {
  const ESPERA_MS = 2500;   // um https da rede de casa responde em bem menos; o nome inexistente falha na hora

  // Um endereço responde com certificado confiável? true/false, nunca lança.
  async function confia(origem) {
    const ctl = new AbortController();
    const t = setTimeout(() => ctl.abort(), ESPERA_MS);
    try {
      await fetch(origem.replace(/\/$/, '') + '/icone-192.png?cadeado=' + Date.now(),
        { mode: 'no-cors', cache: 'no-store', credentials: 'omit', signal: ctl.signal });
      return true;
    } catch (_) {
      return false;
    } finally {
      clearTimeout(t);
    }
  }

  // O estado do https da TV (livre, sem login: o certificado é público).
  async function estado() {
    const r = await fetch('/api/certificado', { cache: 'no-store' });
    if (!r.ok) throw new Error('sem estado');
    return r.json();
  }

  // O primeiro endereço https em que este aparelho confia (nome, depois IP), ou null.
  async function testar(e) {
    e = e || await estado();
    if (!e.pronto || !e.ligado) return null;
    for (const origem of [e.endereco, e.enderecoIp]) {
      if (origem && await confia(origem)) return origem.replace(/\/$/, '');
    }
    return null;
  }

  // Estamos no http da rede de casa? (No https, ou no endereço do Tailscale, não há o que fazer.)
  const noHttpDeCasa = () => location.protocol === 'http:' && !location.hostname.endsWith('.ts.net');

  // ?http=1 deixa ficar no http nesta aba (ex.: para comparar ou se o https der algum defeito).
  function ficarNoHttp() {
    try {
      if (new URLSearchParams(location.search).has('http')) sessionStorage.setItem('ficarNoHttp', '1');
      return sessionStorage.getItem('ficarNoHttp') === '1';
    } catch (_) { return false; }
  }

  return { testar, estado, noHttpDeCasa, ficarNoHttp };
})();

// Painel (index.html): no http, se este aparelho já confia, troca para o https na mesma página. Sem aviso: é o mesmo
// painel; o login é pedido de novo uma vez (o cookie do outro endereço é outro).
if (document.body && document.body.dataset.pagina !== 'certificado' && cadeado.noHttpDeCasa() && !cadeado.ficarNoHttp()) {
  cadeado.testar().then((origem) => {
    if (origem) location.replace(origem + location.pathname + location.hash);
  }).catch(() => {});
}

// Página do passo a passo (certificado.html): mostra se este aparelho já confia e confere de novo a cada 3 s, para a
// pessoa instalar o certificado e voltar aqui sem precisar recarregar nada.
(function paginaCertificado() {
  const caixa = document.getElementById('estadoCadeado');
  if (!caixa) return;
  const texto = caixa.querySelector('.texto');
  const botao = caixa.querySelector('a');
  caixa.hidden = false;
  if (!cadeado.noHttpDeCasa()) {
    texto.textContent = 'Você já está com cadeado. Pode instalar o painel como app (passo 3).';
    caixa.classList.add('ok');
    return;
  }
  let vez = 0;
  async function conferir() {
    vez++;
    let origem = null;
    try { origem = await cadeado.testar(); } catch (_) {}
    if (origem) {
      texto.textContent = 'Pronto! Este aparelho já confia no certificado.';
      botao.href = origem + '/';
      botao.textContent = 'Abrir o painel com cadeado';
      botao.hidden = false;
      caixa.classList.add('ok');
      return;
    }
    texto.textContent = vez === 1
      ? 'Este aparelho ainda não confia no certificado. Siga os passos abaixo e volte para esta página: ela confere sozinha.'
      : 'Ainda não. Depois de instalar, volte para esta página (ela confere a cada 3 segundos).';
    setTimeout(conferir, 3000);
  }
  conferir();
})();
