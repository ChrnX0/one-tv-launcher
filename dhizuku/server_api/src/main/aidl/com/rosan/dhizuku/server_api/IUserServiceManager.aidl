package com.rosan.dhizuku.server_api;

import android.content.ComponentName;
import android.os.IBinder;

interface IUserServiceManager {
    void quit();

    IBinder startService(in ComponentName component);

    // tv-launcher (18/11/2026): para SÓ o serviço de um app. O processo é dividido entre os apps; quit() derrubava
    // o serviço de todos quando um só saía. Fica no fim para não mudar os códigos de transação acima.
    void stopService(in ComponentName component);
}