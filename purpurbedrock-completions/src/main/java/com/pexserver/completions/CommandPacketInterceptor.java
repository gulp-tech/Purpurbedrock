package com.pexserver.completions;

import io.netty.channel.*;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.AvailableCommandsPacket;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.*;

/** Runs on the channel's event loop; no Bukkit API or completion callback is called here. */
public final class CommandPacketInterceptor extends ChannelOutboundHandlerAdapter {
    private static final Method SENDER_SUB_CLIENT_ID = subClientIdAccessor("getSenderSubClientId");
    private static final Method TARGET_SUB_CLIENT_ID = subClientIdAccessor("getTargetSubClientId");

    private final Supplier<Map<String, CompletionModel.Command>> models;
    private final Consumer<AvailableCommandsPacket> baselineReceived;
    private final Consumer<Throwable> failure;
    private final Set<AvailableCommandsPacket> generated = Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>()));

    public CommandPacketInterceptor(Supplier<Map<String, CompletionModel.Command>> models,
                                    Consumer<AvailableCommandsPacket> baselineReceived, Consumer<Throwable> failure) {
        this.models = models; this.baselineReceived = baselineReceived; this.failure = failure;
    }
    public void markGenerated(AvailableCommandsPacket packet) { generated.add(packet); }
    public void unmarkGenerated(AvailableCommandsPacket packet) { generated.remove(packet); }
    @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof BedrockPacketWrapper wrapper && wrapper.getPacketBuffer() == null &&
                wrapper.getPacket() instanceof AvailableCommandsPacket available && isPrimarySubClient(wrapper)) {
            boolean owned = generated.remove(available);
            try {
                AvailableCommandsPacket split = PacketComposer.splitGenericCommands(available);
                if (!owned) baselineReceived.accept(PacketComposer.copy(split));
                // Generated packets have already been composed (or deliberately
                // restore the baseline on shutdown); never append their hints twice.
                AvailableCommandsPacket patched = owned ? split : PacketComposer.patch(split, models.get());
                wrapper.setPacket(PacketComposer.uniqueHardEnums(patched));
            } catch (RuntimeException | LinkageError e) {
                failure.accept(e); // Leave the original packet intact and continue transmission.
            }
        }
        ctx.write(msg, promise);
    }

    private static boolean isPrimarySubClient(BedrockPacketWrapper wrapper) {
        // Geyser may load a Bedrock library older than the one used to compile this plugin.
        return subClientId(wrapper, SENDER_SUB_CLIENT_ID) == 0 && subClientId(wrapper, TARGET_SUB_CLIENT_ID) == 0;
    }

    private static Method subClientIdAccessor(String getter) {
        try {
            return BedrockPacketWrapper.class.getMethod(getter);
        } catch (NoSuchMethodException | SecurityException ignored) {
            return null;
        }
    }

    private static int subClientId(BedrockPacketWrapper wrapper, Method accessor) {
        // Older versions have no subclient API; they only support the primary client.
        if (accessor == null) return 0;
        try {
            Object value = accessor.invoke(wrapper);
            return value instanceof Byte id ? Byte.toUnsignedInt(id) : ((Number) value).intValue();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return -1;
        }
    }
}
