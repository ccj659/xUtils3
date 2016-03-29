package org.xutils.common.util;


import android.content.Context;
import android.text.TextUtils;

import org.xutils.x;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.text.DecimalFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程间锁, 仅在同一个应用中有效.
 */
public final class ProcessLock implements Closeable {

    private final String mLockName;
    private final FileLock mFileLock;
    private final File mFile;
    private final Closeable mStream;
    private final boolean mWriteMode;

    private final static String LOCK_FILE_DIR = "process_lock";
    private final static int PID = android.os.Process.myPid();
    /**
     * key1: lockName
     * key2: mFileLock.hashCode()
     */
    private final static DoubleKeyValueMap<String, Integer, ProcessLock> LOCK_MAP = new DoubleKeyValueMap<String, Integer, ProcessLock>();

    static {
        File dir = x.app().getDir(LOCK_FILE_DIR, Context.MODE_PRIVATE);
        if (dir != null) {
            dir.deleteOnExit();
        }
    }

    private ProcessLock(String lockName, File file, FileLock fileLock, Closeable stream, boolean writeMode) {
        mLockName = lockName;
        mFileLock = fileLock;
        mFile = file;
        mStream = stream;
        mWriteMode = writeMode;
    }

    /**
     * 获取进程锁
     *
     * @param lockName
     * @param writeMode 是否写入模式(支持读并发).
     * @return null 或 进程锁, 如果锁已经被占用, 返回null.
     */
    public static ProcessLock tryLock(final String lockName, final boolean writeMode) {
        return tryLockInternal(lockName, customHash(lockName), writeMode);
    }

    /**
     * 获取进程锁
     *
     * @param lockName
     * @param writeMode         是否写入模式(支持读并发).
     * @param maxWaitTimeMillis 最大值 1000 * 60
     * @return null 或 进程锁, 如果锁已经被占用, 则在超时时间内继续尝试获取该锁.
     */
    public static ProcessLock tryLock(final String lockName, final boolean writeMode, final long maxWaitTimeMillis) {
        ProcessLock lock = null;
        long expiryTime = System.currentTimeMillis() + maxWaitTimeMillis;
        String hash = customHash(lockName);
        while (System.currentTimeMillis() < expiryTime) {
            lock = tryLockInternal(lockName, hash, writeMode);
            if (lock != null) {
                break;
            } else {
                try {
                    Thread.sleep(1); // milliseconds
                } catch (Throwable ignored) {
                }
            }
        }

        return lock;
    }

    /**
     * 锁是否有效
     *
     * @return
     */
    public boolean isValid() {
        return isValid(mFileLock);
    }

    /**
     * 释放锁
     */
    public void release() {
        release(mLockName, mFileLock, mStream);
    }

    /**
     * 释放锁
     */
    @Override
    public void close() throws IOException {
        release();
    }

    private static boolean isValid(FileLock fileLock) {
        return fileLock != null && fileLock.isValid();
    }

    private static void release(String lockName, FileLock fileLock, Closeable stream) {
        synchronized (LOCK_MAP) {
            if (fileLock != null) {
                try {
                    LOCK_MAP.remove(lockName, fileLock.hashCode());
                    fileLock.release();
                    LogUtil.d("released: " + lockName + ":" + PID);
                } catch (Throwable ignored) {
                } finally {
                    IOUtil.closeQuietly(fileLock.channel());
                }
            }

            IOUtil.closeQuietly(stream);
        }
    }

    private final static DecimalFormat FORMAT = new DecimalFormat("0.##################");

    // 取得字符串的自定义hash值, 尽量保证255字节内的hash不重复.
    private static String customHash(String str) {
        if (TextUtils.isEmpty(str)) return "0";
        double hash = 0.0;
        byte[] bytes = str.getBytes();
        for (int i = 0; i < str.length(); i++) {
            hash = (255.0 * hash + bytes[i]) * 0.005;
        }
        return FORMAT.format(hash);
    }

    private static ProcessLock tryLockInternal(final String lockName, final String hash, final boolean writeMode) {
        synchronized (LOCK_MAP) {

            ConcurrentHashMap<Integer, ProcessLock> locks = LOCK_MAP.get(lockName);
            if (locks != null && !locks.isEmpty()) {
                Iterator<Map.Entry<Integer, ProcessLock>> itr = locks.entrySet().iterator();
                while (itr.hasNext()) {
                    Map.Entry<Integer, ProcessLock> entry = itr.next();
                    ProcessLock value = entry.getValue();
                    if (value != null) {
                        if (!value.isValid()) {
                            itr.remove();
                        } else if (writeMode) {
                            return null;
                        } else if (value.mWriteMode) {
                            return null;
                        }
                    } else {
                        itr.remove();
                    }
                }
            }

            FileInputStream in = null;
            FileOutputStream out = null;
            FileChannel channel = null;
            try {
                File file = new File(
                        x.app().getDir(LOCK_FILE_DIR, Context.MODE_PRIVATE),
                        hash);
                if (file.exists() || file.createNewFile()) {
                    Closeable stream = null;
                    if (writeMode) {
                        out = new FileOutputStream(file, false);
                        channel = out.getChannel();
                        stream = out;
                    } else {
                        in = new FileInputStream(file);
                        channel = in.getChannel();
                        stream = in;
                    }
                    if (channel != null) {
                        FileLock fileLock = channel.tryLock(0L, Long.MAX_VALUE, !writeMode);
                        if (isValid(fileLock)) {
                            LogUtil.d("lock: " + lockName + ":" + PID);
                            ProcessLock result = new ProcessLock(lockName, file, fileLock, stream, writeMode);
                            LOCK_MAP.put(lockName, fileLock.hashCode(), result);
                            return result;
                        } else {
                            release(lockName, fileLock, out);
                        }
                    } else {
                        throw new IOException("can not get file channel:" + file.getAbsolutePath());
                    }
                }
            } catch (Throwable ignored) {
                LogUtil.d("tryLock: " + lockName + ", " + ignored.getMessage());
                IOUtil.closeQuietly(in);
                IOUtil.closeQuietly(out);
                IOUtil.closeQuietly(channel);
            }
        }

        return null;
    }

    @Override
    public String toString() {
        return mLockName + ": " + mFile.getName();
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        this.release();
    }
}
