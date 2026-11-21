package br.paulo.tvlauncher

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import br.paulo.tvlauncher.privilege.DonoEmprestado
import br.paulo.tvlauncher.privilege.FiltroDpm
import br.paulo.tvlauncher.privilege.RegrasDono
import com.rosan.dhizuku.aidl.IDhizukuClient
import com.rosan.dhizuku.server_api.DhizukuProcess
import com.rosan.dhizuku.server_api.DhizukuProvider
import com.rosan.dhizuku.server_api.DhizukuService
import com.rosan.dhizuku.server_api.UserService

/**
 * Porta de entrada dos apps feitos para o Dhizuku (ver DonoEmprestado). Authority `<pacote>.dhizuku_server.provider`,
 * que é onde a Dhizuku-API procura quando o dono da TV não é o app Dhizuku oficial. Exportado sem permissão (qualquer
 * app chega até aqui), mas cada chamada passa por [ServicoDhizuku.checkCallingPermission].
 */
class ProvedorDhizuku : DhizukuProvider() {
    override fun onCreate(): Boolean {
        context?.let { DhizukuProcess.init(it, ServicoUsuarioDhizuku::class.java.name) }
        return true
    }

    override fun onCreateService(client: IDhizukuClient?): DhizukuService? {
        val ctx = context ?: return null
        if (DonoEmprestado.parado(ctx) != RegrasDono.Parado.NAO) return null
        return ServicoDhizuku(ctx, ComponentName(ctx, AdminReceiver::class.java), client)
    }
}

/** O serviço que cada app cliente recebe. Confere a autorização em TODA chamada (desligar vale na hora). */
class ServicoDhizuku(ctx: Context, admin: ComponentName, client: IDhizukuClient?) : DhizukuService(ctx, admin, client) {
    override fun checkCallingPermission(func: String?, callingUid: Int, callingPid: Int): Boolean {
        if (DonoEmprestado.parado(mContext) != RegrasDono.Parado.NAO) return false
        val app = DonoEmprestado.porUid(mContext, callingUid)
        val ok = RegrasDono.permite(func, app != null, app?.total == true)
        if (!ok && func != null && app != null) DonoEmprestado.negado(mContext, callingUid, "$func (sem poder total)")
        return ok
    }

    /** Toda chamada ao sistema como o launcher passa pelo filtro (RegrasDono.transacao); negada = SecurityException. */
    override fun onRemoteTransact(binder: IBinder?, code: Int, data: Parcel?, reply: Parcel?, flags: Int): Boolean {
        enforceCallingPermission("remote_transact")
        if (binder == null || data == null) return false
        val uid = Binder.getCallingUid()
        val total = uid == Process.myUid() || DonoEmprestado.porUid(mContext, uid)?.total == true
        val (v, nome) = FiltroDpm.veredito(binder, code, data, mContext.packageName, total)
        if (v != RegrasDono.Veredito.PASSA) {
            DonoEmprestado.negado(mContext, uid, "${nome ?: "código $code"} (${v.name.lowercase()})")
            throw SecurityException("One TV: ${nome ?: "this call"} is not allowed for borrowed Device Owner")
        }
        return super.onRemoteTransact(binder, code, data, reply, flags)
    }
}

/**
 * Hospeda os "serviços do usuário" do Dhizuku (classe do app cliente carregada e rodando no uid do launcher), num
 * processo separado do launcher. Só com poder total (bind_user_service). Não exportado: só o próprio launcher liga.
 */
class ServicoUsuarioDhizuku : Service() {
    private var servico: UserService? = null
    override fun onBind(intent: Intent?): IBinder = servico ?: UserService(applicationContext).also { servico = it }
}
