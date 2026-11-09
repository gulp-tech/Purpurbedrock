package org.purpurmc.purpur.spectate;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Threat demonstration; this test intentionally proves a leak, not its mitigation. */
final class ChunkLeakTest {
    @Test void rewritingOnlyChunkHeaderLeavesAbsoluteCoordinatesInOpaqueNbtPayload() throws Exception {
        int realX = 19321, realZ = -29074;
        int chunkShiftX = 37, chunkShiftZ = -53;
        ByteBuf payload = Unpooled.buffer();
        ByteBuf wire = Unpooled.buffer();
        LevelChunkPacket original = new LevelChunkPacket();
        LevelChunkPacket decoded = new LevelChunkPacket();
        original.setData(payload); // ownership transferred to packet
        try {
            // A synthetic payload deliberately consists of a single network NBT tag; it is not
            // intended as a renderable full chunk. Actual chunks also include block-entity NBT.
            try (var writer = NbtUtils.createNetworkWriter(new ByteBufOutputStream(payload))) {
                writer.writeTag(NbtMap.builder().putString("id", "Chest")
                        .putInt("x", realX).putInt("y", 72).putInt("z", realZ).build());
            }
            original.setChunkX(Math.floorDiv(realX, 16) + chunkShiftX);
            original.setChunkZ(Math.floorDiv(realZ, 16) + chunkShiftZ);
            original.setDimension(0);
            original.setSubChunksLength(0);
            original.setCachingEnabled(false);
            var codec = Bedrock_v2225.CODEC;
            var serializer = codec.getPacketDefinition(LevelChunkPacket.class).getSerializer();
            serializer.serialize(wire, codec.createHelper(), original);
            serializer.deserialize(wire, codec.createHelper(), decoded);
            assertEquals(original.getChunkX(), decoded.getChunkX());
            assertEquals(original.getChunkZ(), decoded.getChunkZ());
            assertEquals(0, wire.readableBytes());
            try (var reader = NbtUtils.createNetworkReader(new ByteBufInputStream(decoded.getData()))) {
                NbtMap blockEntity = (NbtMap) reader.readTag();
                assertEquals(realX, blockEntity.getInt("x"));
                assertEquals(realZ, blockEntity.getInt("z"));
                assertNotEquals(realX + chunkShiftX * 16, blockEntity.getInt("x"));
                assertNotEquals(realZ + chunkShiftZ * 16, blockEntity.getInt("z"));
            }
            assertThrows(UnsupportedOperationException.class, original::clone);
        } finally {
            if (decoded.getData() != null) decoded.release();
            original.release();
            wire.release();
        }
    }
}
