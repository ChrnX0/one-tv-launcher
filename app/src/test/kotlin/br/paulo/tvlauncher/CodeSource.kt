package br.paulo.tvlauncher

import java.io.File

/** Os .kt do app para os testes que leem o código (desde 21/11/2026 em subpacotes por área: home/, player/, panel/...). */
object CodeSource {
    val root: File = listOf(File("src/main/kotlin/br/paulo/tvlauncher"), File("app/src/main/kotlin/br/paulo/tvlauncher")).first { it.isDirectory }
    fun all(): Sequence<File> = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
    fun file(name: String): File = all().firstOrNull { it.name == name } ?: error("$name não achado em ${root.path}")
}
