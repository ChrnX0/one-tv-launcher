package br.paulo.tvlauncher.panel

import java.security.MessageDigest
import java.util.Base64

/**
 * O "conector" do Windows para a unidade de rede (14/11/2026, pedido do dono: tem que ser plug and play).
 *
 * Mapear o WebDAV no Windows à mão pede três coisas que ninguém adivinha (medido num Windows 11, build 26300):
 * o certificado da TV em Máquina Local, o serviço WebClient ligado (vem parado e não liga sozinho) e o limite de
 * 50 MB por arquivo. Este arquivo .cmd, que a própria TV entrega em /unidade/conectar.cmd, faz as três e mapeia a
 * unidade: duplo clique, um "Sim" do Windows, usuário e PIN.
 * A letra é S (de Sofaly, pedido do dono em 17/11/2026); ocupada, Y; ocupadas as duas, a que o Windows der.
 *
 * Só texto, sem Android, para os testes JVM (ConectorWindowsTest).
 */
object ConectorWindows {
    const val CAMINHO = "/unidade/conectar.cmd"
    const val ARQUIVO = "Conectar One TV.cmd"

    /** O miolo base64 do PEM, em linhas (sem as linhas BEGIN/END). */
    fun linhasDoPem(pem: String): List<String> =
        pem.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("-----") }

    /** A "impressão digital" SHA-1 do certificado (é por ela que o certutil procura no repositório do Windows). */
    fun digital(pem: String): String {
        val der = Base64.getDecoder().decode(linhasDoPem(pem).joinToString(""))
        return MessageDigest.getInstance("SHA-1").digest(der).joinToString("") { "%02X".format(it) }
    }

    /** [host] só pode ter letras, números, ponto e hífen: ele entra em linhas de comando. */
    fun hostValido(host: String): Boolean = host.isNotEmpty() && host.length <= 253 && host.all { it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '-' }

    /**
     * O texto do .cmd (linhas com CRLF, que o cmd.exe exige para os rótulos). null se o host ou o PEM não servem.
     * Sem acentos nos textos: o cmd.exe usa a página de código antiga do Windows.
     */
    /**
     * A linha que dá nome à unidade no Explorer ("Sofaly · Sala de estar" no lugar de "dav (\\192...)"), pedido do
     * dono em 17/11/2026. Usa o mesmo caminho de quem renomeia com F2. O nome vai como lista de números (um por
     * letra): o .cmd só aceita ASCII e assim nenhum caractere do nome da TV vira comando.
     */
    fun linhaDoRotulo(rotulo: String): String {
        val codigos = rotulo.take(60).map { it.code }.joinToString(",")
        val d = '$'
        return "powershell -NoProfile -Command \"${d}n=New-Object -ComObject WScript.Network;${d}d=${d}n.EnumNetworkDrives();" +
            "for(${d}i=0;${d}i -lt ${d}d.Count();${d}i+=2){if(${d}d.Item(${d}i) -and ${d}d.Item(${d}i+1) -eq '%ALVO%'){" +
            "(New-Object -ComObject Shell.Application).NameSpace(${d}d.Item(${d}i)+'\\').Self.Name=-join[char[]]($codigos)}}\" >nul 2>&1"
    }

    fun script(host: String, porta: Int, pemCa: String, rotulo: String = ""): String? {
        if (!hostValido(host) || porta !in 1..65535) return null
        val linhas = linhasDoPem(pemCa)
        // base64 puro: nada que o cmd.exe interprete (%, &, |, parênteses) pode entrar no arquivo.
        if (linhas.isEmpty() || linhas.any { l -> !l.all { it.isLetterOrDigit() && it.code < 128 || it in "+/=" } }) return null
        val digital = try { digital(pemCa) } catch (_: Exception) { return null }
        val linhaRotulo = if (rotulo.isBlank()) "rem (sem nome para a unidade)" else linhaDoRotulo(rotulo)
        val t = """
@echo off
rem One TV - liga a TV como unidade de rede neste PC (duplo clique).
rem Faz 3 coisas: confia no certificado da TV, liga o servico WebClient do Windows e mapeia a unidade.
setlocal
title One TV - unidade de rede
set "TV=$host"
set "PORTA=$porta"
set "DIGITAL=$digital"
set "ALVO=\\%TV%@SSL@%PORTA%\dav"
if "%~1"=="admin" goto admin

rem So pede administrador se faltar o certificado ou se o servico estiver parado.
set "PRECISA=0"
certutil -verifystore Root %DIGITAL% >nul 2>&1 || set "PRECISA=1"
sc query WebClient | find "RUNNING" >nul || set "PRECISA=1"
if "%PRECISA%"=="1" (
  echo.
  echo O Windows vai perguntar se pode fazer alteracoes. Responda SIM.
  rem O caminho vai por variavel: um apostrofo no nome da pasta quebrava as aspas simples do PowerShell.
  set "EU=%~f0"
  powershell -NoProfile -Command "Start-Process -FilePath ${'$'}env:EU -ArgumentList 'admin' -Verb RunAs -Wait"
)
sc query WebClient | find "RUNNING" >nul || (
  echo.
  echo Nao consegui ligar o servico WebClient do Windows. Nada foi mapeado.
  pause
  exit /b 1
)

net use | find /i "%ALVO%" >nul && goto abrir
echo.
echo Usuario: digite  tv
echo Senha:   o PIN do painel da TV ^(nao aparece enquanto digita^)
echo.
rem A letra: S, de Sofaly; se estiver ocupada, Y; se nao, a que o Windows escolher.
rem "if exist" ve disco e pendrive; o "net use" ve unidade de rede desconectada; sem administrador.
rem O espaco antes da letra e de proposito: sem ele, "Unidades:" e "Drives:" casam com "S:".
set "LETRA=*"
if not exist Y:\ (net use 2>nul | find /i " Y:" >nul || set "LETRA=Y:")
if not exist S:\ (net use 2>nul | find /i " S:" >nul || set "LETRA=S:")
net use %LETRA% "%ALVO%" /savecred /persistent:yes
if errorlevel 1 (
  echo.
  echo Nao mapeou. Confira o PIN e se a chave "Unidade de rede" esta ligada na TV.
  pause
  exit /b 1
)
:abrir
$linhaRotulo
start "" explorer.exe "%ALVO%"
exit /b 0

:admin
rem Parte de administrador: certificado (so vale para a TV), limite de 4 GB por arquivo e servico ligado.
set "CRT=%TEMP%\onetv-ca-%RANDOM%.crt"
(
  echo -----BEGIN CERTIFICATE-----
  for /f "tokens=2 delims=|" %%L in ('findstr /b "::|" "%~f0"') do echo %%L
  echo -----END CERTIFICATE-----
) > "%CRT%"
certutil -addstore Root "%CRT%" >nul
del "%CRT%" >nul 2>&1
reg add HKLM\SYSTEM\CurrentControlSet\Services\WebClient\Parameters /v FileSizeLimitInBytes /t REG_DWORD /d 4294967295 /f >nul
sc config WebClient start= auto >nul
net stop WebClient >nul 2>&1
net start WebClient >nul
exit /b 0

""".trimStart('\n')
        return (t.lines() + linhas.map { "::|$it" } + "").joinToString("\r\n")
    }
}
