package br.paulo.onetv.celular

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanOptions
import com.journeyapps.barcodescanner.CaptureActivity
import java.net.Inet4Address

/**
 * Tela inicial do app: as TVs salvas (toque abre o painel), as achadas na rede (mDNS) e "Ler o QR da TV".
 * Também recebe o "Compartilhar" do Android: com uma TV só, manda direto; com várias, pergunta qual.
 */
class TvsActivity : Activity() {
    companion object {
        private const val PEDIDO_QR = 7
        /** Serviço que o launcher anuncia (PainelVizinhos.TIPO). */
        private const val TIPO_MDNS = "_tvlauncher._tcp."
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var lista: LinearLayout
    private val achadas = linkedMapOf<String, String>()   // ip -> nome anunciado
    private var nsd: NsdManager? = null
    private var busca: NsdManager.DiscoveryListener? = null
    private var compartilhado: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Visual.FUNDO
        window.navigationBarColor = Visual.FUNDO
        if (intent?.action == Intent.ACTION_SEND) compartilhado = intent
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Visual.FUNDO)
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        raiz.addView(Visual.texto(this, getString(if (compartilhado != null) R.string.cel_mandar_para else R.string.cel_titulo), 26f, negrito = true))
        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        raiz.addView(ScrollView(this).apply { addView(lista) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) })
        raiz.addView(Visual.botao(this, getString(R.string.cel_ler_qr)) { lerQr() }, LinearLayout.LayoutParams(-1, -2))
        setContentView(raiz)
        // Uma TV só e veio um "compartilhar": vai direto, sem perguntar.
        val salvas = Tvs.lista(this)
        if (compartilhado != null && salvas.size == 1) { abrir(salvas[0]); finish(); return }
    }

    override fun onResume() { super.onResume(); desenhar(); procurar() }
    override fun onPause() { parar(); super.onPause() }

    private fun dp(v: Int) = Visual.dp(this, v)

    private fun desenhar() {
        lista.removeAllViews()
        val salvas = Tvs.lista(this)
        if (salvas.isEmpty()) lista.addView(Visual.texto(this, getString(R.string.cel_vazio), 17f, Visual.TEXTO_2))
        for (tv in salvas) lista.addView(linha(tv.nome.ifEmpty { tv.ip },
            getString(if (tv.fp.isNotEmpty()) R.string.cel_pareada_segura else R.string.cel_pareada_http, tv.ip),
            { abrir(tv) }, { esquecer(tv) }))
        val novas = achadas.filterKeys { ip -> salvas.none { it.ip == ip } }
        if (novas.isNotEmpty()) {
            lista.addView(Visual.texto(this, getString(R.string.cel_na_rede), 15f, Visual.TEXTO_2).apply { setPadding(0, dp(18), 0, dp(6)) })
            for ((ip, nome) in novas) lista.addView(linha(nome, getString(R.string.cel_achada, ip), {
                // Achada sem parear: abre o painel por http (entra com o PIN). Para o https fixado, ler o QR.
                AlertDialog.Builder(this).setTitle(nome).setMessage(R.string.cel_achada_explica)
                    .setPositiveButton(R.string.cel_ler_qr) { _, _ -> lerQr() }
                    .setNegativeButton(R.string.cel_abrir_pin) { _, _ -> abrir(Pareamento.Tv(nome, ip)) }
                    .show()
            }, null))
        }
    }

    private fun linha(titulo: String, detalhe: String, toque: () -> Unit, segurar: (() -> Unit)?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = Visual.cartao(context)
        setPadding(dp(18), dp(14), dp(18), dp(14))
        addView(Visual.texto(context, titulo, 19f, negrito = true))
        addView(Visual.texto(context, detalhe, 14f, Visual.TEXTO_2))
        isClickable = true; isFocusable = true
        setOnClickListener { toque() }
        if (segurar != null) setOnLongClickListener { segurar(); true }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
    }

    private fun esquecer(tv: Pareamento.Tv) {
        AlertDialog.Builder(this).setTitle(tv.nome.ifEmpty { tv.ip }).setMessage(R.string.cel_esquecer_explica)
            .setPositiveButton(R.string.cel_esquecer) { _, _ -> Tvs.esquecer(this, tv.ip); desenhar() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun abrir(tv: Pareamento.Tv, lido: Pareamento.Lido? = null) {
        val i = Intent(this, PainelActivity::class.java).putExtra(PainelActivity.EXTRA_IP, tv.ip)
        if (lido != null) i.putExtra(PainelActivity.EXTRA_FRAGMENTO, Pareamento.fragmento(lido))
        compartilhado?.let { c ->
            i.putExtra(PainelActivity.EXTRA_TITULO, c.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty())
            i.putExtra(PainelActivity.EXTRA_TEXTO, c.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
            compartilhado = null
        }
        startActivity(i)
    }

    // ---------------------------------------------------------------- QR

    private fun lerQr() {
        val op = ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
            .setPrompt(getString(R.string.cel_aponte)).setOrientationLocked(true)
        startActivityForResult(op.createScanIntent(this).setClass(this, CaptureActivity::class.java), PEDIDO_QR)
    }

    @Deprecated("Activity simples, sem AndroidX")
    override fun onActivityResult(pedido: Int, resultado: Int, dados: Intent?) {
        super.onActivityResult(pedido, resultado, dados)
        if (pedido != PEDIDO_QR || resultado != RESULT_OK) return
        val lido = Pareamento.ler(dados?.getStringExtra(Intents.Scan.RESULT))
        if (lido == null) {
            AlertDialog.Builder(this).setMessage(R.string.cel_qr_invalido).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val antiga = Tvs.porIp(this, lido.ip)
        val nome = achadas[lido.ip] ?: antiga?.nome ?: "TV"
        // Lido na tela da TV: este é o certificado em que o app passa a confiar (e só nele).
        val tv = Pareamento.Tv(nome, lido.ip, lido.fp.ifEmpty { antiga?.fp.orEmpty() }, lido.ts.ifEmpty { antiga?.ts.orEmpty() })
        Tvs.guardar(this, tv)
        abrir(tv, lido)
    }

    // ---------------------------------------------------------------- mDNS

    private fun procurar() {
        val m = getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = m
        val b = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                m.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        val ip = (if (Build.VERSION.SDK_INT >= 34) s.hostAddresses.firstOrNull { it is Inet4Address } else @Suppress("DEPRECATION") s.host)
                            ?.hostAddress ?: return
                        if (!Pareamento.privado(ip)) return
                        ui.post { achadas[ip] = s.serviceName; desenhar() }
                    }
                })
            }
        }
        try { m.discoverServices(TIPO_MDNS, NsdManager.PROTOCOL_DNS_SD, b); busca = b } catch (_: Exception) {}
    }

    private fun parar() {
        val b = busca ?: return
        busca = null
        try { nsd?.stopServiceDiscovery(b) } catch (_: Exception) {}
    }
}
