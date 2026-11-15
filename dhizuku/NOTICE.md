# Dhizuku SERVER_API (vendorizado)

Origem: https://github.com/iamr0s/Dhizuku-API, módulo `server_api`, commit fd1b9ff (SERVER_API 0.2.0). Licença MIT
(`LICENSE` nesta pasta). O lado cliente (`io.github.iamr0s:Dhizuku-API:2.7.0`, MIT) vem do Maven Central.

Por que está aqui: o One TV Launcher, que é o Device Owner da TV, faz o papel de servidor do Dhizuku e empresta o dono a
outros apps (15/11/2026, `DonoEmprestado.kt`). O app Dhizuku em si (GPL) NÃO entra.

Mudanças nossas:
- `DhizukuProcess.java` reescrito: o original estende `AppProcess.None` da biblioteca `io.github.iamr0s:AndroidAppProcess`
  (LGPL-3.0). O nosso usa só APIs públicas no processo do launcher (transact direto, ProcessBuilder, bindService ao
  `ServicoUsuarioDhizuku`), para nada LGPL entrar no APK.
- `UserServiceConnection.java`: pega o binder do serviço do usuário por `DhizukuProcess.isolatedServiceBinder(...)`.
- `build.gradle`: módulo de biblioteca do nosso build, sem a dependência LGPL.
