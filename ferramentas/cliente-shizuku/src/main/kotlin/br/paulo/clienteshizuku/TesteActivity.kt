package br.paulo.clienteshizuku

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/**
 * Cliente de teste do Shizuku embutido no launcher.
 * Ao abrir: espera o binder; se ainda não tem permissão, pede (o launcher mostra a tela Permitir/Negar);
 * com permissão, roda `id` pelo servidor do Shizuku e mostra/loga a saída (tag TesteShizuku).
 */
class TesteActivity : Activity() {
    private lateinit var saida: TextView
    private val codigo = 7

    private fun mostrar(t: String) {
        Log.i("TesteShizuku", t)
        runOnUiThread { saida.append(t + "\n") }
    }

    private val aoReceberBinder = Shizuku.OnBinderReceivedListener { mostrar("binder recebido (servidor uid=${Shizuku.getUid()}, versao=${Shizuku.getVersion()})"); verificar() }
    private val aoResponder = Shizuku.OnRequestPermissionResultListener { req, res ->
        mostrar("resposta do pedido $req: ${if (res == PackageManager.PERMISSION_GRANTED) "PERMITIDO" else "NEGADO"}")
        if (res == PackageManager.PERMISSION_GRANTED) rodarId()
    }

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        saida = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER; setPadding(60, 60, 60, 60)
        }
        setContentView(saida)
        mostrar("Teste do Shizuku: esperando o binder…")
        Shizuku.addRequestPermissionResultListener(aoResponder)
        Shizuku.addBinderReceivedListenerSticky(aoReceberBinder)
        thread { testarDhizuku() }
    }

    // ---- Dhizuku (dono emprestado). Cada linha diz o ESPERADO; nada aqui faz mal se o filtro falhar
    // (setApplicationHidden com hidden=false só mostra o que já está à mostra).
    private fun testarDhizuku() {
        val ok = try { com.rosan.dhizuku.api.Dhizuku.init(this) } catch (e: Throwable) { mostrar("dhizuku init erro: $e"); false }
        mostrar("dhizuku init: $ok (esperado true; false = provider invisivel ou emprestimo desligado)")
        if (!ok) return
        if (com.rosan.dhizuku.api.Dhizuku.isPermissionGranted()) return testesDono()
        mostrar("dhizuku: pedindo permissao…")
        com.rosan.dhizuku.api.Dhizuku.requestPermission(object : com.rosan.dhizuku.api.DhizukuRequestPermissionListener() {
            override fun onRequestPermission(grantResult: Int) {
                mostrar("dhizuku resposta: ${if (grantResult == PackageManager.PERMISSION_GRANTED) "PERMITIDO" else "NEGADO"}")
                if (grantResult == PackageManager.PERMISSION_GRANTED) thread { testesDono() }
            }
        })
    }

    private fun testesDono() {
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
            val dono = com.rosan.dhizuku.api.Dhizuku.getOwnerComponent()
            val ctx = createPackageContext(dono.packageName, 0)
            val dpm = ctx.getSystemService(android.app.admin.DevicePolicyManager::class.java)
            val campo = android.app.admin.DevicePolicyManager::class.java.getDeclaredField("mService").apply { isAccessible = true }
            val original = campo.get(dpm) as android.os.IInterface
            val embrulhado = com.rosan.dhizuku.api.Dhizuku.binderWrapper(original.asBinder())
            val novo = Class.forName("android.app.admin.IDevicePolicyManager\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, embrulhado)
            campo.set(dpm, novo)
            fun t(nome: String, esperado: String, f: () -> Any?) = try { mostrar("$nome: ${f()} (esperado $esperado)") }
                catch (e: Throwable) { mostrar("$nome: ${e.javaClass.simpleName} ${e.message} (esperado $esperado)") }
            t("D1 isApplicationHidden(vending)", "false") { dpm.isApplicationHidden(dono, "com.android.vending") }
            t("D2 setApplicationHidden(eu mesmo,false)", "true") { dpm.setApplicationHidden(dono, packageName, false) }
            t("D3 setApplicationHidden(launcher,false)", "SecurityException") { dpm.setApplicationHidden(dono, dono.packageName, false) }
            t("D4 newProcess(id)", "SecurityException (sem poder total)") {
                com.rosan.dhizuku.api.Dhizuku.newProcess(arrayOf("id"), null, null).inputStream.bufferedReader().readText().trim()
            }
        } catch (e: Throwable) { mostrar("dhizuku testes erro: $e") }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(aoReceberBinder)
        Shizuku.removeRequestPermissionResultListener(aoResponder)
    }

    private fun verificar() {
        when {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> { mostrar("ja tenho permissao"); rodarId() }
            Shizuku.shouldShowRequestPermissionRationale() -> mostrar("permissao NEGADA antes (revogue em Configuracoes do launcher)")
            else -> { mostrar("pedindo permissao…"); Shizuku.requestPermission(codigo) }
        }
    }

    /** Roda `id` no servidor. newProcess é privado na API 13 (o demo oficial também usa por reflexão). */
    private fun rodarId() = thread {
        try {
            val m = Shizuku::class.java.getDeclaredMethod("newProcess",
                Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("id"), null, null) as Process
            val texto = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            mostrar("id pelo Shizuku: $texto")
        } catch (e: Throwable) {
            mostrar("falhou: ${e.cause ?: e}")
        }
    }
}
