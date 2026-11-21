package br.paulo.tvlauncher.backup

import android.os.Build
import android.os.Environment
import android.os.storage.StorageVolume
import java.io.File

/**
 * A pasta de um volume ([StorageVolume.getDirectory] só existe do Android 11 em diante; o app aceita Android 10 e lá a
 * chamada derrubaria a tela com NoSuchMethodError). No 10: o principal é o armazenamento interno e um pendrive monta em
 * /storage/<uuid>, que é o mesmo caminho que o 11 devolve. Achado do lint na depuração de 21/11/2026.
 */
val StorageVolume.folder: File?
    get() = if (Build.VERSION.SDK_INT >= 30) directory
        else if (isPrimary) Environment.getExternalStorageDirectory()
        else uuid?.let { File("/storage/$it") }?.takeIf { it.exists() }
