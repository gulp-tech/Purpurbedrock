package org.purpurmc.purpur.spectate;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Restricts private spectator data to the operating-system owner; other plugins in the same JVM remain trusted. */
final class PrivateStorage {
    private static final Set<PosixFilePermission> DIR = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");
    private PrivateStorage() { }

    static void directory(Path path) throws IOException {
        try {
            Files.createDirectories(path, PosixFilePermissions.asFileAttribute(DIR));
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Private directory is not a real directory: " + path);
            requireOwnerOnly(path, DIR);
        } catch (UnsupportedOperationException ex) {
            throw new IOException("Cannot verify private storage permissions on this filesystem", ex);
        }
    }

    static void auditFile(Path path) throws IOException {
        try {
            try { Files.createFile(path, PosixFilePermissions.asFileAttribute(FILE)); }
            catch (FileAlreadyExistsException ignored) { }
            existingFile(path);
        } catch (UnsupportedOperationException ex) {
            throw new IOException("Cannot verify audit file permissions on this filesystem", ex);
        }
    }

    static void existingFile(Path path) throws IOException {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Private path is not a regular file: " + path);
            requireOwnerOnly(path, FILE);
        } catch (UnsupportedOperationException ex) {
            throw new IOException("Cannot verify private file permissions on this filesystem", ex);
        }
    }

    private static void requireOwnerOnly(Path path, Set<PosixFilePermission> allowed) throws IOException {
        Set<PosixFilePermission> actual = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
        if (!allowed.containsAll(actual) || !actual.containsAll(allowed))
            throw new IOException("Private storage must have owner-only permissions: " + path);
    }
}
