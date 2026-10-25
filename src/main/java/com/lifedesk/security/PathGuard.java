package com.lifedesk.security;

import java.nio.file.Path;
import java.util.List;

/**
 * Path confinement: every file operation (file manager, agent file tools)
 * must target a path inside a user-granted root. Prevents path traversal
 * via .. segments and symlink tricks by normalizing before comparison.
 */
public final class PathGuard {
    private PathGuard() {
    }

    /** True when {@code candidate} is inside at least one of the allowed roots. */
    public static boolean isInside(List<Path> roots, Path candidate) {
        if (candidate == null) {
            return false;
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        for (Path root : roots) {
            Path rn = root.toAbsolutePath().normalize();
            if (normalized.equals(rn) || normalized.startsWith(rn)) {
                return true;
            }
        }
        return false;
    }

    /** Returns the candidate or throws IllegalArgumentException when outside allowed roots. */
    public static Path requireInside(List<Path> roots, Path candidate) {
        if (!isInside(roots, candidate)) {
            throw new IllegalArgumentException("Đường dẫn nằm ngoài các thư mục được cấp quyền: " + candidate);
        }
        return candidate.toAbsolutePath().normalize();
    }
}
