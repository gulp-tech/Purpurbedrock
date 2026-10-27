/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.channel.nethernet.signaling;

import org.cloudburstmc.netty.channel.nethernet.NetherNetConstants;
import org.cloudburstmc.netty.channel.nethernet.signaling.PongData;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetSignaling.SignalHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public class NetherNetDiscovery extends SimpleChannelInboundHandler<DatagramPacket> {
    private static final InternalLogger log = InternalLoggerFactory.getInstance(NetherNetDiscovery.class);
    /** Runtime marker verified by Purpurbedrock self-tests. */
    public static final boolean PURPURBEDROCK_LAN_V7 = true;

    private final long networkId;
    private final Map<String, SignalHandler> signalHandlers = new ConcurrentHashMap<>();
    private final Map<Long, InetSocketAddress> peerAddresses = new ConcurrentHashMap<>();
    private Channel channel;
    private byte[][] pongData;
    private NetherNetServerSignaling.NewConnectionHandler newConnectionHandler;
    private BiConsumer<Long, ByteBuf> discoveryCallback;

    /**
     * Creates a NetherNetDiscovery instance with the specified Network ID.
     *
     * @param networkId The Network ID to use for discovery.
     */
    public NetherNetDiscovery(long networkId) {
        this.networkId = networkId;
    }

    public void bind() {
        bind(NetherNetConstants.DISCOVERY_PORT);
    }

    public void bind(int port) {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_BROADCAST, true)
                    .handler(this);

            this.channel = bootstrap.bind(port).sync().channel();
            log.info("NetherNet Discovery listening on port {}", port);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void bind(InetSocketAddress address) {
        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioDatagramChannel.class)
                    .option(ChannelOption.SO_BROADCAST, true)
                    .handler(this);

            this.channel = bootstrap.bind(address).sync().channel();
            log.info("NetherNet Discovery listening on {}", address);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void sendDiscoveryRequest(InetSocketAddress target, BiConsumer<Long, ByteBuf> onServerFound) {
        this.discoveryCallback = onServerFound;

        ByteBuf buf = Unpooled.buffer();
        buf.writeShortLE(NetherNetConstants.ID_DISCOVERY_REQUEST);
        buf.writeLongLE(this.networkId);
        buf.writeZero(8); // Padding

        sendPacket(buf, target);
    }

    public void setPongData(PongData data) {
        PongData visible = new PongData.Builder()
                .setServerName(data.serverName())
                .setProtocol(data.protocol())
                .setVersion(data.version())
                .setLevelName(data.levelName())
                .setGameType(data.gameType())
                // Bedrock 26.60 hides dedicated LAN worlds advertised with no players.
                .setPlayerCount(Math.max(1, data.playerCount()))
                .setMaxPlayerCount(Math.max(1, data.maxPlayerCount()))
                .setIsEditorWorld(data.isEditorWorld())
                .setIsHardcore(data.isHardcore())
                .setTransportLayer(data.transportLayer())
                .setConnectionType(data.connectionType())
                .setDataVersion(data.dataVersion())
                .setOnlineAuth(data.onlineAuth())
                .setSelfSignedAuth(data.selfSignedAuth())
                .setNonce(data.nonce())
                .build();
        this.pongData = new byte[ServerData.VERSIONS.length][];
        for (int i = 0; i < ServerData.VERSIONS.length; i++) {
            this.pongData[i] = ServerData.encode(visible, ServerData.VERSIONS[i]);
        }
    }

    public void registerSignalHandler(String connectionId, SignalHandler handler) {
        this.signalHandlers.put(connectionId, handler);
    }

    public void unregisterSignalHandler(String connectionId) {
        this.signalHandlers.remove(connectionId);
    }

    public void setNewConnectionHandler(NetherNetServerSignaling.NewConnectionHandler handler) {
        this.newConnectionHandler = handler;
    }

    /**
     * Sends a signal immediately and schedules it to be resent periodically
     * until the returned ScheduledFuture is cancelled.
     */
    public ScheduledFuture<?> sendSignalRetrying(InetSocketAddress recipient, long targetNetworkId, String data,
                                                 long delayMs) {
        return channel.eventLoop().scheduleAtFixedRate(() -> {
            log.debug("Resending signal to {}: {}", recipient, data);
            sendSignal(recipient, targetNetworkId, data);
        }, 0, delayMs, TimeUnit.MILLISECONDS);
    }

    public void sendSignal(InetSocketAddress recipient, long targetNetworkId, String data) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShortLE(NetherNetConstants.ID_DISCOVERY_MESSAGE);
        buf.writeLongLE(this.networkId); // Sender ID
        buf.writeZero(8); // Padding

        buf.writeLongLE(targetNetworkId); // Recipient ID
        byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
        buf.writeIntLE(dataBytes.length);
        buf.writeBytes(dataBytes);

        sendPacket(buf, recipient);
    }

    public void sendSignal(long targetNetworkId, String data) {
        InetSocketAddress recipient = peerAddresses.get(targetNetworkId);
        if (recipient != null) {
            sendSignal(recipient, targetNetworkId, data);
        } else {
            throw new IllegalArgumentException("Attempted to send signal to unknown peer: " + targetNetworkId);
        }
    }

    private void sendPacket(ByteBuf packetData, InetSocketAddress target) {
        try {
            byte[] encrypted = NetherNetConstants.encryptDiscoveryPacket(packetData);
            channel.writeAndFlush(new DatagramPacket(Unpooled.wrappedBuffer(encrypted), target));
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt discovery packet", e);
        } finally {
            packetData.release();
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        ByteBuf content = packet.content();
        ByteBuf decrypted = null;
        try {
            decrypted = NetherNetConstants.decryptDiscoveryPacket(content);
        } catch (Exception e) {
            log.debug("Failed to decrypt discovery packet from {}", packet.sender(), e);
            return;
        }

        if (decrypted == null) {
            log.debug("Received invalid discovery packet from {}", packet.sender());
            return;
        }

        try {
            int packetId = decrypted.readUnsignedShortLE();
            long senderId = decrypted.readLongLE();

            decrypted.skipBytes(8); // Padding

            if (senderId == this.networkId) {
                log.debug("Ignoring own discovery packet");
                return;
            }

            peerAddresses.put(senderId, packet.sender());

            switch (packetId) {
                case NetherNetConstants.ID_DISCOVERY_REQUEST -> {
                    log.trace("Handled discovery request from {}", packet.sender());
                    handleRequest(senderId, packet.sender());
                }
                case NetherNetConstants.ID_DISCOVERY_MESSAGE -> {
                    log.trace("Handled discovery message from {}", packet.sender());
                    log.trace("Message Data: {}", decrypted.toString(StandardCharsets.UTF_8));
                    handleMessage(decrypted, senderId, packet.sender());
                }
                case NetherNetConstants.ID_DISCOVERY_RESPONSE -> {
                    log.trace("Handled discovery response from {}", packet.sender());
                    if (discoveryCallback != null) {
                        log.trace("Response Data: {}", decrypted.toString(StandardCharsets.UTF_8));
                        // We retain it because we are passing it out of the pipeline handler
                        discoveryCallback.accept(senderId, decrypted.retain());
                    }
                }
                default -> {
                    log.debug("Received unknown discovery packet ID {} from {}", packetId, packet.sender());
                }
            }
        } catch (Exception e) {
            log.debug("Error processing discovery packet from {}", packet.sender(), e);
        } finally {
            decrypted.release();
        }
    }

    private void handleRequest(long senderId, InetSocketAddress sender) {
        if (this.pongData == null) {
            return;
        }

        // Discovery requests do not identify which status layout the client reads.
        // Answer in both BDS layouts: v6 for 26.40-26.45 and v7 for 26.50-26.60.
        for (byte[] status : this.pongData) {
            ByteBuf buf = Unpooled.buffer();
            buf.writeShortLE(NetherNetConstants.ID_DISCOVERY_RESPONSE);
            buf.writeLongLE(this.networkId);
            buf.writeZero(8);
            buf.writeBytes(status);
            sendPacket(buf, sender);
        }
    }

    private void handleMessage(ByteBuf data, long senderId, InetSocketAddress sender) {
        long recipientId = data.readLongLE();

        if (recipientId != this.networkId && recipientId != 0) {
            log.trace("Ignoring message intended for {}, but I am {}", recipientId, this.networkId);
            return;
        }

        int len = data.readIntLE();
        if (data.readableBytes() < len) {
            log.trace("Malformed message: claimed length {} but only has {}", len, data.readableBytes());
            return;
        }

        String messageData = data.readCharSequence(len, StandardCharsets.UTF_8).toString();
        if ("Ping".equals(messageData)) {
            return;
        }

        NetherNetConstants.Signal signal = NetherNetConstants.parseSignal(messageData);
        if (signal == null) {
            log.debug("Malformed signal in message: {}", messageData);
            return;
        }
        String connectionId = signal.connectionId();

        SignalHandler handler = signalHandlers.get(connectionId);

        if (handler != null) {
            handler.onSignal(messageData);
        } else if (NetherNetConstants.RTC_NEGOTIATION_CONNECT_REQUEST.equals(signal.type())) {
            if (newConnectionHandler != null) {
                log.trace("Dispatching New Connection: ID={} Sender={}", connectionId,
                        Long.toUnsignedString(senderId));
                newConnectionHandler.onConnect(connectionId, Long.toUnsignedString(senderId), signal.payload(),
                        sender, null);
            } else {
                log.debug("Received CONNECT_REQUEST but no NewConnectionHandler is set!");
            }
        } else {
            log.debug("Unhandled signal type: {}", signal.type());
        }
    }

    public void close() {
        if (channel != null) {
            channel.close();
        }
    }

    public boolean isActive() {
        return channel != null && channel.isActive();
    }

}
