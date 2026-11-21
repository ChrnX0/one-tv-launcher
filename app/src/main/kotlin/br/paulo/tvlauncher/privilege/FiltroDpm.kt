package br.paulo.tvlauncher.privilege

import android.content.ComponentName
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import br.paulo.tvlauncher.home.MenuApp
import br.paulo.tvlauncher.player.item
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Lê uma chamada AIDL que um app emprestado quer fazer ao DevicePolicyManager COMO o launcher (Dhizuku remote_transact)
 * e decide se passa (regras em RegrasDono.transacao). 15/11/2026.
 *
 * O app manda um Parcel cru com um código de transação. Para saber o que ele é:
 *  - nome do método: os campos `TRANSACTION_<nome>` de `IDevicePolicyManager$Stub` (gerados pelo AIDL do próprio
 *    Android desta TV, então batem com a versão dela);
 *  - argumentos: os tipos vêm do método da interface (reflexão) e são lidos de uma CÓPIA do Parcel na mesma ordem
 *    em que o Proxy do AIDL os escreve. Só os textos, listas de texto e ComponentName importam (o pacote alvo).
 * Essas classes são API escondida: a HiddenApiBypass (LSPosed, Apache-2.0) libera a reflexão só para elas.
 * Qualquer coisa que não dê para ler = null = a chamada é negada (falha segura).
 */
object FiltroDpm {
    private const val TAG = "TvLauncher.Dono"

    private class Mapa(val nomes: Map<Int, String>, val metodos: Map<String, Method>)

    @Volatile private var mapa: Mapa? = null
    @Volatile private var falhou = false

    private fun carregar(): Mapa? {
        mapa?.let { return it }
        if (falhou) return null
        return try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/app/admin/IDevicePolicyManager")
            val stub = Class.forName(RegrasDono.DPM + "\$Stub")
            val nomes = HashMap<Int, String>()
            for (f in stub.declaredFields) {
                if (!f.name.startsWith("TRANSACTION_") || !Modifier.isStatic(f.modifiers) || f.type != Int::class.javaPrimitiveType) continue
                f.isAccessible = true
                nomes[f.getInt(null)] = f.name.removePrefix("TRANSACTION_")
            }
            val metodos = Class.forName(RegrasDono.DPM).methods.associateBy { it.name }
            if (nomes.isEmpty()) error("sem TRANSACTION_")
            Mapa(nomes, metodos).also { mapa = it; Log.i(TAG, "mapa do DPM: ${nomes.size} métodos") }
        } catch (e: Throwable) {
            falhou = true
            Log.w(TAG, "sem mapa do DPM (chamadas filtradas serão negadas): $e")
            null
        }
    }

    /** O mapa existe nesta TV? (diagnóstico: painel/adb) */
    fun mapaOk(): Boolean = carregar() != null

    /** Nome do método por trás de [codigo] (null se não houver mapa ou o código não existir). */
    fun nome(codigo: Int): String? = carregar()?.nomes?.get(codigo)

    fun veredito(binder: IBinder, codigo: Int, dados: Parcel, nosso: String, poderTotal: Boolean): Pair<RegrasDono.Veredito, String?> {
        val descritor = try { binder.interfaceDescriptor } catch (_: Throwable) { null }
        if (descritor != RegrasDono.DPM) return RegrasDono.transacao(descritor, null, null, nosso, poderTotal) to descritor
        val metodo = nome(codigo)
        val ler = metodo != null && !poderTotal && (RegrasDono.mexeEmPacote(metodo) || RegrasDono.mexeEmAjuste(metodo))
        val args = if (ler) carregar()?.metodos?.get(metodo)?.let { ler(it, dados) } else null
        return RegrasDono.transacao(descritor, metodo, args?.let { RegrasDono.alvos(it) }, nosso, poderTotal,
            args?.let { RegrasDono.textos(it) }, MenuApp.PROTEGIDOS_FIXOS) to metodo
    }

    /** Argumentos de [m] lidos de uma cópia de [dados]; null se algum tipo não for conhecido. */
    private fun ler(m: Method, dados: Parcel): List<RegrasDono.Arg>? {
        val p = Parcel.obtain()
        return try {
            p.appendFrom(dados, 0, dados.dataSize())
            p.setDataPosition(0)
            p.enforceInterface(RegrasDono.DPM)
            val tipos = m.parameterTypes
            val genericos = m.genericParameterTypes
            tipos.indices.map { i -> lerArg(p, tipos[i], genericos[i]) ?: return null }
        } catch (e: Throwable) {
            Log.w(TAG, "não li os argumentos de ${m.name}: $e")
            null
        } finally {
            p.recycle()
        }
    }

    private fun lerArg(p: Parcel, t: Class<*>, g: java.lang.reflect.Type): RegrasDono.Arg? {
        val outro = RegrasDono.Arg.Outro
        return when {
            t == String::class.java -> RegrasDono.Arg.Texto(p.readString())
            t == Array<String>::class.java -> RegrasDono.Arg.Textos(p.createStringArray()?.toList() ?: emptyList())
            t == List::class.java -> {
                val item = (g as? ParameterizedType)?.actualTypeArguments?.firstOrNull()
                when {
                    item == String::class.java -> RegrasDono.Arg.Textos(p.createStringArrayList() ?: emptyList())
                    item is Class<*> && Parcelable::class.java.isAssignableFrom(item) -> {
                        val criador = criador(item) ?: return null
                        val lista = p.createTypedArrayList(criador) ?: emptyList<Any>()
                        RegrasDono.Arg.Textos(lista.filterIsInstance<ComponentName>().map { it.packageName })
                    }
                    else -> null
                }
            }
            t == ComponentName::class.java ->
                RegrasDono.Arg.Componente(if (p.readInt() != 0) ComponentName.CREATOR.createFromParcel(p).packageName else null)
            t == Int::class.javaPrimitiveType || t == Boolean::class.javaPrimitiveType ||
                t == Byte::class.javaPrimitiveType || t == Char::class.javaPrimitiveType || t == Short::class.javaPrimitiveType -> { p.readInt(); outro }
            t == Long::class.javaPrimitiveType -> { p.readLong(); outro }
            t == Float::class.javaPrimitiveType -> { p.readFloat(); outro }
            t == Double::class.javaPrimitiveType -> { p.readDouble(); outro }
            t == IntArray::class.java -> { p.createIntArray(); outro }
            t == LongArray::class.java -> { p.createLongArray(); outro }
            t == ByteArray::class.java -> { p.createByteArray(); outro }
            t == BooleanArray::class.java -> { p.createBooleanArray(); outro }
            t == IBinder::class.java || IInterface::class.java.isAssignableFrom(t) -> { p.readStrongBinder(); outro }
            Parcelable::class.java.isAssignableFrom(t) -> {
                if (p.readInt() == 0) outro else {
                    val criador = criador(t) ?: return null
                    val obj = criador.createFromParcel(p)
                    if (obj is ComponentName) RegrasDono.Arg.Componente(obj.packageName) else outro
                }
            }
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun criador(t: Class<*>): Parcelable.Creator<Any>? = try {
        t.getField("CREATOR").get(null) as Parcelable.Creator<Any>
    } catch (_: Throwable) { null }
}
