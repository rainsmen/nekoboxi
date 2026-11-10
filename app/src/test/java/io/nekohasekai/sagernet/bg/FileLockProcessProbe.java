package io.nekohasekai.sagernet.bg;

import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

/** Separate JVM so the test exercises OS locking, not only Java's overlap registry. */
public final class FileLockProcessProbe {
    public static void main(String[] args) throws Exception {
        try (FileChannel channel = new RandomAccessFile(args[0], "rw").getChannel()) {
            FileLock lock = channel.tryLock(0, Long.MAX_VALUE, Boolean.parseBoolean(args[1]));
            System.out.println(lock == null ? "blocked" : "acquired");
            if (lock != null) lock.release();
        }
    }
}
