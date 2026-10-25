package com.lifedesk.app;

import com.lifedesk.common.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Prevents two LifeDesk instances from writing the same data directory
 * concurrently (both would corrupt the file stores). Uses a lock file
 * holding an OS-level {@link FileLock} for the lifetime of the app.
 */
public class SingleInstanceLock implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SingleInstanceLock.class);

    private final Path lockFile;
    private RandomAccessFile raf;
    private FileLock lock;

    private SingleInstanceLock(Path lockFile) {
        this.lockFile = lockFile;
    }

    /** Acquires the lock or throws {@link AppException} when another instance holds it. */
    public static SingleInstanceLock acquire(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
            Path lockFile = dataDir.resolve(".lifedesk.lock");
            RandomAccessFile raf = new RandomAccessFile(lockFile.toFile(), "rw");
            FileLock lock;
            try {
                lock = raf.getChannel().tryLock();
            } catch (Exception e) {
                // OverlappingFileLockException etc. — release our handle before failing.
                try {
                    raf.close();
                } catch (IOException ignore) {
                    // best effort
                }
                throw new AppException("Thư mục dữ liệu đang được mở bởi một tiến trình LifeDesk khác.\n"
                        + "Hãy đóng tiến trình đó hoặc chọn thư mục dữ liệu khác.", e);
            }
            if (lock == null) {
                raf.close();
                throw new AppException("Thư mục dữ liệu đang được mở bởi một tiến trình LifeDesk khác.\n"
                        + "Hãy đóng tiến trình đó hoặc chọn thư mục dữ liệu khác.");
            }
            LOG.info("Data directory lock acquired: {}", lockFile);
            return new SingleInstanceLock(lockFile, raf, lock);
        } catch (IOException e) {
            throw new AppException("Không thể khóa thư mục dữ liệu: " + e.getMessage(), e);
        }
    }

    private SingleInstanceLock(Path lockFile, RandomAccessFile raf, FileLock lock) {
        this.lockFile = lockFile;
        this.raf = raf;
        this.lock = lock;
    }

    @Override
    public void close() {
        try {
            if (lock != null) {
                lock.release();
            }
            if (raf != null) {
                raf.close();
            }
            Files.deleteIfExists(lockFile);
        } catch (IOException e) {
            LOG.warn("Failed to release data lock: {}", e.toString());
        }
    }
}
