package com.rosan.dhizuku.server_api;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.rosan.dhizuku.aidl.IDhizukuRemoteProcess;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SuppressWarnings({"RedundantThrows", "CallToPrintStackTrace"})
public class RemoteProcess extends IDhizukuRemoteProcess.Stub {
    private static final String TAG = "DhizukuRemoteProcess";

    protected Process mProcess;

    private ParcelFileDescriptor mOut;

    private ParcelFileDescriptor mIn;

    private ParcelFileDescriptor mErr;

    public RemoteProcess(Process process) {
        mProcess = process;
    }

    @Override
    public synchronized ParcelFileDescriptor getOutputStream() throws RemoteException {
        if (mOut != null) return mOut;
        mOut = parcelable(mProcess.getOutputStream());
        return mOut;
    }

    @Override
    public synchronized ParcelFileDescriptor getInputStream() throws RemoteException {
        if (mIn != null) return mIn;
        mIn = parcelable(mProcess.getInputStream());
        return mIn;
    }

    @Override
    public synchronized ParcelFileDescriptor getErrorStream() throws RemoteException {
        if (mErr != null) return mErr;
        mErr = parcelable(mProcess.getErrorStream());
        return mErr;
    }

    @Override
    public int exitValue() throws RemoteException {
        return mProcess.exitValue();
    }

    @Override
    public void destroy() throws RemoteException {
        mProcess.destroy();
    }

    /**
     * Revogar o app (17/11/2026, launcher): encerra o processo E os filhos/netos dele (o destroy() só manda SIGTERM ao
     * pid direto, e um "sh -c ..." deixava os netos rodando com o uid do launcher). Acha os descendentes pelo ppid em
     * /proc e manda SIGKILL a todos (mesmo uid: o sistema deixa).
     */
    void encerrarTudo() {
        int pid = pid(mProcess);
        if (pid > 0) {
            java.util.List<Integer> alvos = new java.util.ArrayList<>();
            alvos.add(pid);
            java.util.Map<Integer, java.util.List<Integer>> filhos = filhosPorPai();
            for (int i = 0; i < alvos.size(); i++) {
                java.util.List<Integer> f = filhos.get(alvos.get(i));
                if (f != null) for (Integer c : f) if (!alvos.contains(c)) alvos.add(c);
            }
            for (int i = alvos.size() - 1; i >= 0; i--) {
                if (alvos.get(i) == android.os.Process.myPid()) continue;   // nunca o próprio launcher
                try { android.os.Process.sendSignal(alvos.get(i), android.os.Process.SIGNAL_KILL); } catch (Throwable ignored) { }
            }
        }
        try { if (android.os.Build.VERSION.SDK_INT >= 26) mProcess.destroyForcibly(); else mProcess.destroy(); } catch (Throwable ignored) { }
    }

    private static int pid(Process p) {
        for (String campo : new String[]{"pid", "handle"}) {
            try {
                java.lang.reflect.Field f = p.getClass().getDeclaredField(campo);
                f.setAccessible(true);
                return ((Number) f.get(p)).intValue();
            } catch (Throwable ignored) { }
        }
        return -1;
    }

    /** ppid -> pids, lido de /proc/<pid>/stat (campo 4; o nome entre parênteses pode ter espaços). */
    private static java.util.Map<Integer, java.util.List<Integer>> filhosPorPai() {
        java.util.Map<Integer, java.util.List<Integer>> r = new java.util.HashMap<>();
        java.io.File[] dirs = new java.io.File("/proc").listFiles();
        if (dirs == null) return r;
        for (java.io.File d : dirs) {
            int pid;
            try { pid = Integer.parseInt(d.getName()); } catch (NumberFormatException e) { continue; }
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(new java.io.File(d, "stat")))) {
                String s = br.readLine();
                if (s == null) continue;
                String[] campos = s.substring(s.lastIndexOf(')') + 2).split(" ");
                int ppid = Integer.parseInt(campos[1]);
                java.util.List<Integer> l = r.get(ppid);
                if (l == null) { l = new java.util.ArrayList<>(); r.put(ppid, l); }
                l.add(pid);
            } catch (Throwable ignored) { }
        }
        return r;
    }

    @Override
    public boolean alive() throws RemoteException {
        try {
            exitValue();
            return false;
        } catch (IllegalThreadStateException ignored) {
            return true;
        }
    }

    @Override
    public int waitFor() throws RemoteException {
        try {
            return mProcess.waitFor();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean waitForTimeout(long timeout, String unitName) throws RemoteException {
        TimeUnit unit = TimeUnit.valueOf(unitName);
        long startTime = System.nanoTime();
        long rem = unit.toNanos(timeout);

        do {
            try {
                exitValue();
                return true;
            } catch (IllegalThreadStateException ignored) {
                if (rem > 0) {
                    try {
                        Thread.sleep(Math.min(TimeUnit.NANOSECONDS.toMillis(rem) + 1, 100));
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
            rem = unit.toNanos(timeout) - (System.nanoTime() - startTime);
        } while (rem > 0);
        return false;
    }

    public static ParcelFileDescriptor parcelable(InputStream stream) {
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            transfer(stream, new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]));
            return pipe[0];
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static ParcelFileDescriptor parcelable(OutputStream stream) {
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            transfer(new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]), stream);
            return pipe[1];
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final ExecutorService TRANSFER_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "DhizukuRemoteProcess-transfer");
        thread.setDaemon(true);
        return thread;
    });

    public static void transfer(InputStream in, OutputStream out) {
        TRANSFER_EXECUTOR.execute(() -> {
            byte[] buf = new byte[8 * 1024];
            //noinspection UnusedAssignment
            int len = 0;
            try {
                while (true) {
                    len = in.read(buf);
                    if (len <= 0) break;
                    out.write(buf, 0, len);
                    out.flush();
                }
            } catch (IOException e) {
                // runs on a daemon thread, so surface the failure instead of dropping it
                Log.e(TAG, "stream transfer failed", e);
            } finally {
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {
                    out.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }
}
