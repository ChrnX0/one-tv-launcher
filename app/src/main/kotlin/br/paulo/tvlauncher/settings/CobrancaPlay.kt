package br.paulo.tvlauncher.settings

import android.app.Activity
import android.content.Context
import android.util.Log
import br.paulo.tvlauncher.BuildConfig
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * Cobrança da Play (16/11/2026, decisão do Paulo em 16/11 08:41Z: Pro e Ultra vendidos DENTRO do app pela cobrança da
 * Play; nada de pagamento no site). Só existe no build da Play ([BuildConfig.PLAY]); o APK do site nunca liga o cliente.
 *
 * Produtos (a criar no Play Console, ver docs/play/cobranca.md): assinaturas [PRODUTO_PRO] e [PRODUTO_ULTRA], cada uma
 * com os planos-base que o Paulo escolher (mensal/anual). Preço NÃO mora no código: vem da Play, já na moeda do país.
 *
 * O plano vale pela compra que a Play devolve ([atualizar], a cada abertura e ao voltar da compra) e fica guardado nas
 * prefs "cobranca" para a TV sem internet não cair para o Básico. Sem resposta da Play por [VALIDADE_MS], volta ao
 * Básico. Toda compra nova é RECONHECIDA (acknowledge) na hora: sem isso a Play devolve o dinheiro em 3 dias.
 */
object CobrancaPlay {
    private const val TAG = "TvLauncher.Cobranca"
    const val PRODUTO_PRO = "sofaly_pro"
    const val PRODUTO_ULTRA = "sofaly_ultra"
    private const val PREFS = "cobranca"
    /** Quanto tempo o plano guardado vale sem conseguir falar com a Play (TV sem internet, Play fora do ar). */
    const val VALIDADE_MS = 14L * 24 * 3600 * 1000

    // ---------------------------------------------------------------- lógica pura (CobrancaPlayTest)

    /** O maior plano entre as compras ATIVAS (compradas, não pendentes). Nada = Básico. */
    fun planoDasCompras(produtosComprados: Collection<String>): Planos.Plano = when {
        PRODUTO_ULTRA in produtosComprados -> Planos.Plano.ULTRA
        PRODUTO_PRO in produtosComprados -> Planos.Plano.PRO
        else -> Planos.Plano.BASICO
    }

    /** O plano guardado ainda vale? [conferidoMs] = última vez que a Play respondeu. */
    fun guardadoValido(conferidoMs: Long, agoraMs: Long): Boolean = conferidoMs > 0 && agoraMs - conferidoMs in 0..VALIDADE_MS

    // ---------------------------------------------------------------- estado

    fun planoGuardado(ctx: Context): Planos.Plano {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!guardadoValido(p.getLong("conferido", 0), System.currentTimeMillis())) return Planos.Plano.BASICO
        return runCatching { Planos.Plano.valueOf(p.getString("plano", "BASICO")!!) }.getOrDefault(Planos.Plano.BASICO)
    }

    private fun guardar(ctx: Context, plano: Planos.Plano) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("plano", plano.name).putLong("conferido", System.currentTimeMillis()).apply()
        Log.i(TAG, "plano: $plano")
    }

    // ---------------------------------------------------------------- cliente da Play

    @Volatile private var cliente: BillingClient? = null
    @Volatile private var detalhes: Map<String, ProductDetails> = emptyMap()
    @Volatile private var aoMudar: ((Planos.Plano) -> Unit)? = null
    /** As assinaturas ATIVAS da última consulta completa (para trocar de plano em vez de abrir uma 2ª assinatura). */
    @Volatile private var ativas: List<Purchase> = emptyList()

    private fun cliente(ctx: Context): BillingClient = cliente ?: synchronized(this) {
        cliente ?: BillingClient.newBuilder(ctx.applicationContext)
            // A lista do listener é PARCIAL (só a compra que acabou de acontecer): reconhece e então pergunta tudo de
            // novo. Antes o plano era calculado dela e uma compra pendente (ou um Pro comprado por quem tem Ultra)
            // rebaixava quem já paga.
            .setListener { r, compras ->
                if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                    reconhecer(compras.orEmpty())
                    atualizar(ctx.applicationContext)   // tratar() avisa a tela (aoMudar)
                }
            }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build().also { cliente = it }
    }

    private fun conectado(ctx: Context, depois: (BillingClient) -> Unit) {
        val c = cliente(ctx)
        if (c.isReady) { depois(c); return }
        c.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (r.responseCode == BillingClient.BillingResponseCode.OK) depois(c)
                else Log.w(TAG, "Play indisponível: ${r.responseCode} ${r.debugMessage}")
            }
            override fun onBillingServiceDisconnected() { /* enableAutoServiceReconnection reconecta sozinho */ }
        })
    }

    /** Pergunta à Play o que está comprado (e os preços). Chamar ao abrir o launcher e a tela dos planos. No-op fora da Play. */
    fun atualizar(ctx: Context, fim: ((Planos.Plano) -> Unit)? = null) {
        if (!BuildConfig.PLAY) return
        val app = ctx.applicationContext
        agendarRepeticao(app)
        conectado(app) { c ->
            c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { r, compras ->
                if (r.responseCode == BillingClient.BillingResponseCode.OK) tratar(app, compras)
                fim?.invoke(planoGuardado(app))
            }
            val produtos = listOf(PRODUTO_PRO, PRODUTO_ULTRA).map {
                QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build()
            }
            c.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(produtos).build()) { r, res ->
                if (r.responseCode == BillingClient.BillingResponseCode.OK) detalhes = res.productDetailsList.associateBy { it.productId }
            }
        }
    }

    private val principal by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    @Volatile private var repetindo = false
    private const val REPETIR_MS = 6 * 3_600_000L

    /**
     * O launcher (HOME, dono, acessibilidade) fica semanas no ar: consultar só ao abrir o processo deixava o plano
     * guardado vencer (14 dias sem conferir = Básico para quem paga) e um reembolso valendo até reiniciar. Repete a
     * cada 6 h enquanto o processo vive.
     */
    private fun agendarRepeticao(app: Context) {
        if (repetindo) return
        repetindo = true
        principal.postDelayed(object : Runnable {
            override fun run() { atualizar(app); principal.postDelayed(this, REPETIR_MS) }
        }, REPETIR_MS)
    }

    /** Compras que chegaram (da consulta ou da tela de compra): reconhece as novas e recalcula o plano. */
    private fun tratar(ctx: Context, compras: List<Purchase>) {
        val ativasAgora = reconhecer(compras)
        ativas = ativasAgora
        val plano = planoDasCompras(ativasAgora.flatMap { it.products })
        guardar(ctx, plano)
        aoMudar?.invoke(plano)
    }

    /** Reconhece (acknowledge) as compras novas. Devolve as ativas (compradas, não pendentes). */
    private fun reconhecer(compras: List<Purchase>): List<Purchase> {
        val ativasAgora = compras.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        for (p in ativasAgora) if (!p.isAcknowledged) reconhecerUma(p.purchaseToken, 0)
        return ativasAgora
    }

    /**
     * Sem acknowledge em 3 dias a Play devolve o dinheiro e tira o plano. Uma falha (Wi-Fi caiu, serviço fora) tenta de
     * novo com espera crescente (1, 2, 4... até 30 min, 8 vezes); depois disso, a repetição de 6 h tenta outra vez.
     */
    private fun reconhecerUma(token: String, tentativa: Int) {
        val c = cliente ?: return
        c.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()) { r ->
            Log.i(TAG, "reconhecida: ${r.responseCode}")
            if (r.responseCode != BillingClient.BillingResponseCode.OK && tentativa < 8)
                principal.postDelayed({ reconhecerUma(token, tentativa + 1) }, minOf(60_000L shl tentativa, 30 * 60_000L))
        }
    }

    /** Preço do primeiro plano-base do produto, já formatado pela Play ("R$ 9,90"); null antes da Play responder. */
    fun preco(produto: String): String? = detalhes[produto]?.subscriptionOfferDetails?.firstOrNull()
        ?.pricingPhases?.pricingPhaseList?.lastOrNull()?.formattedPrice

    /**
     * Abre a tela de compra da Play para [produto]. false = a Play ainda não mandou os detalhes (sem internet, produto
     * não criado no Console). [mudou] recebe o plano novo quando a compra termina.
     */
    fun comprar(a: Activity, produto: String, mudou: (Planos.Plano) -> Unit): Boolean {
        if (!BuildConfig.PLAY) return false
        val d = detalhes[produto] ?: return false
        val oferta = d.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return false
        aoMudar = { p -> a.runOnUiThread { mudou(p) } }
        val construtor = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).setOfferToken(oferta).build()))
        // Já assina o OUTRO plano: é troca, não uma 2ª assinatura (antes, Pro -> Ultra cobrava os dois).
        // Subir para o Ultra: vale na hora, pagando a diferença; descer para o Pro: vale na próxima renovação.
        ativas.firstOrNull { produto !in it.products && it.products.any { p -> p == PRODUTO_PRO || p == PRODUTO_ULTRA } }?.let { velha ->
            val modo = if (produto == PRODUTO_ULTRA) BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.CHARGE_PRORATED_PRICE
                else BillingFlowParams.SubscriptionUpdateParams.ReplacementMode.DEFERRED
            construtor.setSubscriptionUpdateParams(BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                .setOldPurchaseToken(velha.purchaseToken).setSubscriptionReplacementMode(modo).build())
        }
        val params = construtor.build()
        val r = cliente(a).launchBillingFlow(a, params)
        return r.responseCode == BillingClient.BillingResponseCode.OK
    }
}
