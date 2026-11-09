package org.purpurmc.purpur.spectate;

/**
 * Arithmetic primitive only. Applying this to a player position alone is NOT privacy protection:
 * every position-bearing packet, world cache and inbound position must be handled consistently.
 */
public record CoordinateOffset(int chunksX, int chunksZ) {
    public static final int CHUNK_SIZE = 16;

    public CoordinateOffset {
        // Reject values that cannot be represented as a block offset.
        Math.multiplyExact(chunksX, CHUNK_SIZE);
        Math.multiplyExact(chunksZ, CHUNK_SIZE);
    }

    public int blockX(int real) { return Math.addExact(real, Math.multiplyExact(chunksX, CHUNK_SIZE)); }
    public int blockZ(int real) { return Math.addExact(real, Math.multiplyExact(chunksZ, CHUNK_SIZE)); }
    public int realBlockX(int displayed) { return Math.subtractExact(displayed, Math.multiplyExact(chunksX, CHUNK_SIZE)); }
    public int realBlockZ(int displayed) { return Math.subtractExact(displayed, Math.multiplyExact(chunksZ, CHUNK_SIZE)); }
    public int chunkX(int real) { return Math.addExact(real, chunksX); }
    public int chunkZ(int real) { return Math.addExact(real, chunksZ); }
    public int realChunkX(int displayed) { return Math.subtractExact(displayed, chunksX); }
    public int realChunkZ(int displayed) { return Math.subtractExact(displayed, chunksZ); }
}
