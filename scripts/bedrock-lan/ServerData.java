/*
 * Copyright 2026 CloudburstMC
 *
 * Licensed under the Apache License, Version 2.0.
 */
package org.cloudburstmc.netty.channel.nethernet.signaling;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;

/**
 * Encodes the dedicated-server LAN status layouts read by modern Bedrock clients.
 * Discovery requests do not identify a layout, so the server answers in both versions.
 */
public final class ServerData {
    /** Runtime marker verified by Purpurbedrock self-tests. */
    public static final boolean PURPURBEDROCK_LAN_STATUS_V6_V7 = true;
    /** 26.40-26.45 read v6; 26.50-26.60 read v7. */
    static final int[] VERSIONS = {6, 7};

    private ServerData() {
    }

    static byte[] encode(PongData data, int version) {
        ByteBuf buf = Unpooled.buffer();
        try {
            buf.writeByte(version);
            switch (version) {
                case 6 -> {
                    writeString(buf, data.serverName());
                    writeString(buf, data.levelName());
                    writeVarInt(buf, data.gameType());
                    buf.writeIntLE(data.playerCount());
                    buf.writeIntLE(data.maxPlayerCount());
                    buf.writeBoolean(data.isEditorWorld());
                    buf.writeBoolean(data.isHardcore());
                    buf.writeBoolean(data.onlineAuth());
                    buf.writeBoolean(data.selfSignedAuth());
                    writeString(buf, data.nonce());
                    writeVarInt(buf, data.transportLayer());
                    writeVarInt(buf, data.connectionType());
                }
                case 7 -> {
                    writeString(buf, data.serverName());
                    writeVarInt(buf, data.protocol());
                    writeString(buf, data.version());
                    writeString(buf, data.levelName());
                    writeVarInt(buf, data.playerCount());
                    writeVarInt(buf, data.maxPlayerCount());
                    writeVarInt(buf, data.gameType());
                    buf.writeBoolean(data.isEditorWorld());
                    buf.writeBoolean(data.isHardcore());
                    buf.writeBoolean(data.onlineAuth());
                    buf.writeBoolean(data.selfSignedAuth());
                    writeString(buf, data.nonce());
                    writeVarInt(buf, data.connectionType());
                }
                default -> throw new IllegalArgumentException("No server data version " + version);
            }
            byte[] hex = ByteBufUtil.hexDump(buf).getBytes(StandardCharsets.US_ASCII);
            byte[] encoded = new byte[Integer.BYTES + hex.length];
            Unpooled.wrappedBuffer(encoded).setIntLE(0, hex.length).setBytes(Integer.BYTES, hex);
            return encoded;
        } finally {
            buf.release();
        }
    }

    private static void writeString(ByteBuf buf, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeUnsignedVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        writeUnsignedVarInt(buf, (value << 1) ^ (value >> 31));
    }

    private static void writeUnsignedVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}
