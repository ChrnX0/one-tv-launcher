package br.paulo.tvlauncher

/**
 * Pergunta única da primeira abertura (T3, 17/11/2026). Antes eram duas perguntas parecidas ("Como esta TV vai ser
 * usada?" e "Para quem é esta TV?") e mais o "Modo simples" (hoje "Tela fácil"), três usos da palavra "simples".
 * Agora uma pergunta só, "Para quem é esta TV?": cada resposta escolhe JUNTAS a profundidade do menu ([UsoTv]) e,
 * quando houver, a receita pronta ([Receitas.Receita]). A receita ainda passa pela confirmação dela (mostra o que
 * muda, pede PIN se precisar e tem Desfazer). Pura: testada em PrimeiraPerguntaTest.
 */
object PrimeiraPergunta {
    enum class Resposta(val uso: UsoTv, val receita: Receitas.Receita?) {
        CASA(UsoTv.COMPLETO, null),                         // para a casa toda: tudo, como sempre foi
        ESSENCIAL(UsoTv.SIMPLES, null),                     // só a tela bonita, menu enxuto
        VO(UsoTv.CUIDA, Receitas.Receita.VO),               // a TV de quem você cuida de longe, com a Tela fácil
        CRIANCAS(UsoTv.SIMPLES, Receitas.Receita.CRIANCAS),
        GAMER(UsoTv.COMPLETO, Receitas.Receita.GAMER),
        ALUGUEL(UsoTv.SIMPLES, Receitas.Receita.ALUGUEL),
    }
}
