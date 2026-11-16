package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.util.Log
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

    private fun cliente(ctx: Context): BillingClient = cliente ?: synchronized(this) {
        cliente ?: BillingClient.newBuilder(ctx.applicationContext)
            .setListener { r, compras -> if (r.responseCode == BillingClient.BillingResponseCode.OK) tratar(ctx.applicationContext, compras.orEmpty()) }
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

    /** Compras que chegaram (da consulta ou da tela de compra): reconhece as novas e recalcula o plano. */
    private fun tratar(ctx: Context, compras: List<Purchase>) {
        val ativas = compras.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        for (p in ativas) if (!p.isAcknowledged) {
            cliente?.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { r ->
                Log.i(TAG, "reconhecida: ${r.responseCode}")
            }
        }
        val plano = planoDasCompras(ativas.flatMap { it.products })
        guardar(ctx, plano)
        aoMudar?.invoke(plano)
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
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).setOfferToken(oferta).build())).build()
        val r = cliente(a).launchBillingFlow(a, params)
        return r.responseCode == BillingClient.BillingResponseCode.OK
    }
}
