package br.paulo.tvlauncher

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import br.paulo.tvlauncher.network.DiarioDns

/**
 * Faz o launcher poder ser "device owner" (dono do dispositivo): o nível mais alto de gerenciamento do Android,
 * o mesmo que empresas usam. Depois de ser device owner, o app pode, via DevicePolicyManager, instalar/desinstalar
 * apps em silêncio, conceder permissões a outros apps, travar configurações, etc.
 *
 * O Android só exige que esta classe exista e esteja declarada no manifesto (receiver com BIND_DEVICE_ADMIN +
 * res/xml/device_admin.xml). Desde 10/11/2026 ela também recebe os lotes do registro de rede (ver DiarioDns).
 *
 * Ativar (uma vez, pelo computador; o Android recusa se houver contas na TV):
 *   adb shell dpm set-device-owner tv.sofaly.launcher/br.paulo.tvlauncher.AdminReceiver
 * (o app antigo era br.paulo.tvlauncher/.AdminReceiver; desde 19/11/2026 o pacote é tv.sofaly.launcher, ver AppIdentity)
 * Desfazer: o APK é "testOnly" (ver o manifesto), então vale
 *   adb shell dpm remove-active-admin tv.sofaly.launcher/br.paulo.tvlauncher.AdminReceiver
 */
class AdminReceiver : DeviceAdminReceiver() {
    /**
     * Troca de pacote (19/11/2026, PackageMigration.kt): o outro app nosso nos passou o Device Owner. Exige
     * <support-transfer-ownership/> em res/xml/device_admin.xml.
     */
    override fun onTransferOwnershipComplete(context: Context, bundle: android.os.PersistableBundle?) {
        PackageMigration.onOwnershipReceived(context, bundle)
    }

    override fun onNetworkLogsAvailable(context: Context, intent: Intent, batchToken: Long, networkLogsCount: Int) {
        val app = context.applicationContext
        val pendente = goAsync()   // ler e gravar o lote leva mais que o tempo de um broadcast comum
        Fundo.rodar {
            try { DiarioDns.receberLote(app, batchToken) } finally { pendente.finish() }
        }
    }
}
