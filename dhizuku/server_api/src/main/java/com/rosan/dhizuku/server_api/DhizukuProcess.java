package com.rosan.dhizuku.server_api;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * tv-launcher: substitui o DhizukuProcess da origem (que estendia AppProcess.None, da biblioteca LGPL AndroidAppProcess).
 * Mesmos métodos que o resto do server_api usa, feitos com APIs públicas, tudo no processo/uid do launcher:
 *  - remoteTransact: o binder.transact acontece aqui, então o sistema vê o uid do launcher (o Device Owner);
 *  - remoteProcess: ProcessBuilder comum (uid do launcher);
 *  - isolatedServiceBinder: liga ao serviço do próprio launcher que hospeda o UserService (processo separado,
 *    mesmo uid) e espera o binder até 10 s.
 * O launcher chama {@link #init(Context, String)} antes de servir o primeiro cliente.
 */
public class DhizukuProcess {
    @SuppressLint("StaticFieldLeak")
    private static final DhizukuProcess mInstance = new DhizukuProcess();

    private volatile Context mContext;

    private volatile ComponentName mUserService;

    private DhizukuProcess() {
    }

    public static DhizukuProcess get() {
        return mInstance;
    }

    /** [userServiceClass]: o Service do launcher que devolve um {@link UserService} no onBind. */
    public static void init(Context context, String userServiceClass) {
        mInstance.mContext = context.getApplicationContext();
        mInstance.mUserService = new ComponentName(context.getPackageName(), userServiceClass);
    }

    public ComponentName userServiceComponent() {
        return mUserService;
    }

    public Context getContext() {
        return mContext;
    }

    public boolean remoteTransact(IBinder binder, int code, Parcel data, Parcel reply, int flags) {
        try {
            return binder.transact(code, data, reply, flags);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public Process remoteProcess(List<String> cmd, Map<String, String> env, String dir) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (env != null) pb.environment().putAll(env);
        if (dir != null) pb.directory(new File(dir));
        try {
            return pb.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Liga ao serviço [component] do launcher e devolve o binder dele (null se não ligar em 10 s). Fora da main thread. */
    public IBinder isolatedServiceBinder(ComponentName component) {
        Context ctx = mContext;
        if (ctx == null || component == null) return null;
        CountDownLatch pronto = new CountDownLatch(1);
        AtomicReference<IBinder> ref = new AtomicReference<>();
        ServiceConnection conexao = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                ref.set(service);
                pronto.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };
        if (!ctx.bindService(new Intent().setComponent(component), conexao, Context.BIND_AUTO_CREATE)) return null;
        try {
            pronto.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return ref.get();
    }
}
