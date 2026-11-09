package org.purpurmc.purpur.spectate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Append-only observation lifecycle audit; no player position or other payload data is recorded. */
public final class SpectateAudit {
    public enum Event { START, STOP, DISCONNECT, TARGET_DISCONNECT, RECOVERED }
    private static final ConcurrentHashMap<Path, Object> PATH_LOCKS = new ConcurrentHashMap<>();
    private final Path path;

    public SpectateAudit(Path path) { this.path = Objects.requireNonNull(path).toAbsolutePath().normalize(); }

    /** An IOException is a failure: callers must not start observation if audit cannot be written. */
    public void record(Event event, UUID sessionId, UUID observerId, String observerName, UUID targetId, String targetName)
            throws IOException {
        Objects.requireNonNull(event); Objects.requireNonNull(sessionId); Objects.requireNonNull(observerId); Objects.requireNonNull(observerName);
        Objects.requireNonNull(targetId); Objects.requireNonNull(targetName);
        String entry = "{\"time\":\"" + Instant.now() + "\",\"event\":\"" + event.name()
            + "\",\"session\":\"" + sessionId + "\",\"observer_uuid\":\"" + observerId + "\",\"observer\":\"" + escape(observerName)
            + "\",\"target_uuid\":\"" + targetId + "\",\"target\":\"" + escape(targetName) + "\"}\n";
        // A per-path JVM monitor plus an OS file lock coordinates separate instances and processes.
        // A torn final record is never silently joined to the next observation.
        synchronized (PATH_LOCKS.computeIfAbsent(path, ignored -> new Object())) {
            PrivateStorage.directory(path.getParent());
            PrivateStorage.auditFile(path);
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 FileLock lock = channel.lock()) {
                long size = channel.size();
                if (size > 0) {
                    ByteBuffer last = ByteBuffer.allocate(1);
                    channel.position(size - 1);
                    if (channel.read(last) != 1 || last.array()[0] != '\n')
                        throw new IOException("Audit has an incomplete final record");
                }
                channel.position(size);
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(entry);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            } catch (OverlappingFileLockException ex) {
                throw new IOException("Audit lock unavailable", ex);
            }
        }
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
