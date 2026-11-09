package org.purpurmc.purpur.spectate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Durable pre-observation return data. These private files contain real positions, unlike the audit. */
public final class ReturnStore {
    private static final Set<String> MODES = Set.of("SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR");
    private final Path directory;
    public record Snapshot(UUID session, UUID observer, String observerName, UUID target, String targetName,
                           UUID world, double x, double y, double z, float yaw, float pitch,
                           String gameMode, boolean allowFlight, boolean flying) {
        public Snapshot {
            Objects.requireNonNull(session); Objects.requireNonNull(observer); Objects.requireNonNull(observerName);
            Objects.requireNonNull(target); Objects.requireNonNull(targetName); Objects.requireNonNull(world); Objects.requireNonNull(gameMode);
            if (observer.equals(target) || observerName.isBlank() || targetName.isBlank()
                    || observerName.length() > 64 || targetName.length() > 64 || !MODES.contains(gameMode) || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch) || (flying && !allowFlight))
                throw new IllegalArgumentException("Invalid original state");
        }
    }
    public ReturnStore(Path directory) { this.directory = Objects.requireNonNull(directory).toAbsolutePath(); }
    private Path file(UUID observer) { return directory.resolve(observer.toString() + ".properties"); }

    /** Caller must save before any change in location, game mode, or protocol visibility. */
    public synchronized void save(Snapshot s) throws IOException {
        Objects.requireNonNull(s);
        PrivateStorage.directory(directory);
        Properties p = new Properties();
        p.setProperty("session", s.session().toString()); p.setProperty("observer", s.observer().toString());
        p.setProperty("observerName", s.observerName()); p.setProperty("target", s.target().toString());
        p.setProperty("targetName", s.targetName()); p.setProperty("world", s.world().toString());
        p.setProperty("x", Double.toString(s.x())); p.setProperty("y", Double.toString(s.y())); p.setProperty("z", Double.toString(s.z()));
        p.setProperty("yaw", Float.toString(s.yaw())); p.setProperty("pitch", Float.toString(s.pitch()));
        p.setProperty("mode", s.gameMode()); p.setProperty("allowFlight", Boolean.toString(s.allowFlight())); p.setProperty("flying", Boolean.toString(s.flying()));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); p.store(bytes, "Private original player state");
        Path temporary = Files.createTempFile(directory, ".return-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            PrivateStorage.existingFile(temporary);
            // A no-overwrite atomic link avoids losing a pending return state on a duplicate start.
            // If hard links are unsupported, fail closed rather than doing a partial write.
            try { Files.createLink(file(s.observer()), temporary); }
            catch (UnsupportedOperationException ex) { throw new IOException("Atomic no-overwrite save is unavailable", ex); }
        } finally { Files.deleteIfExists(temporary); }
    }

    public synchronized Optional<Snapshot> load(UUID observer) throws IOException {
        Objects.requireNonNull(observer);
        Path path = file(observer);
        Properties p = new Properties();
        try {
            Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException ex) { return Optional.empty(); }
        PrivateStorage.directory(directory);
        PrivateStorage.existingFile(path);
        try (InputStream in = Files.newInputStream(path)) { p.load(in); }
        catch (NoSuchFileException ex) { throw new IOException("Return state disappeared during recovery", ex); }
        catch (IllegalArgumentException ex) { throw new IOException("Malformed saved return state for " + observer, ex); }
        try {
            UUID stored = UUID.fromString(p.getProperty("observer"));
            if (!observer.equals(stored)) throw new IllegalArgumentException("Observer mismatch");
            return Optional.of(new Snapshot(UUID.fromString(p.getProperty("session")), stored, p.getProperty("observerName"),
                UUID.fromString(p.getProperty("target")), p.getProperty("targetName"), UUID.fromString(p.getProperty("world")),
                Double.parseDouble(p.getProperty("x")), Double.parseDouble(p.getProperty("y")), Double.parseDouble(p.getProperty("z")),
                Float.parseFloat(p.getProperty("yaw")), Float.parseFloat(p.getProperty("pitch")), p.getProperty("mode"),
                parseBoolean(p.getProperty("allowFlight")), parseBoolean(p.getProperty("flying"))));
        } catch (RuntimeException ex) { throw new IOException("Invalid saved return state for " + observer, ex); }
    }
    private static boolean parseBoolean(String text) {
        if ("true".equals(text)) return true;
        if ("false".equals(text)) return false;
        throw new IllegalArgumentException("Invalid boolean");
    }
    /** Invoke only after return and protocol teardown have both succeeded. */
    public synchronized void clear(UUID observer) throws IOException { Files.deleteIfExists(file(Objects.requireNonNull(observer))); }
}
