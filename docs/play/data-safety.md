# Formulário "Segurança dos dados" (rascunho para o Paulo conferir antes de enviar)

Base: o app **não tem servidor**. O que vai para fora vai direto da TV ao serviço que a pessoa escolheu.
Pela regra da Play, dado que sai do aparelho para terceiro conta como "compartilhado" mesmo sem nós vermos; dado que só
passa pelo aparelho não conta como "coletado". Marquei de forma conservadora.

| Pergunta | Resposta | Por quê |
|---|---|---|
| Coleta ou compartilha dados? | **Sim** | login Google opcional, avisos à família por push, legendas com login do OpenSubtitles |
| Dados criptografados em trânsito? | **Sim** | só HTTPS/TLS (rede.xml proíbe texto puro fora da LAN) |
| A pessoa pode pedir para apagar? | **Sim** | apagar dados do app / desinstalar; contas são removidas nas Configurações |
| Localização aproximada | Compartilhada com o serviço de clima (coordenada da cidade) — funcionalidade do app, opcional? **Não** (sem ela não há clima) | MET Norway/Open-Meteo recebem a coordenada |
| Localização precisa | **Não** coletada (a TV usa a do Android só no aparelho; o clima recebe a da cidade) | no build da Play a coordenada sai arredondada para 2 casas (~1 km), `Localizacao.atual` |
| Informações pessoais: nome, e-mail | Coletados, opcional, funcionalidade do app (login Google) | ficam na TV |
| Fotos e vídeos | Não | a foto da tela do pedido de ajuda vai ao celular da família pelo painel da própria casa, não a terceiros |
| Atividade no app / histórico | Não | sem análise de uso; o "Uso da TV" fica na TV |
| Identificadores do aparelho | Não | |
| Diagnóstico / falhas | Não | o registro de erros fica na TV; "Relatar problema" só se a pessoa enviar |
| Mensagens (push à família) | Compartilhado, opcional | texto curto do aviso vai pelo serviço de push do navegador do celular |
| Compras | Coletado pela Google Play (não por nós) | o app só lê o plano |

Política de privacidade: [privacidade-sofaly.md](privacidade-sofaly.md) publicada no site antes de enviar.
