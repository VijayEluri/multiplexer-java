// Copyright 2009 Warsaw University, Faculty of Physics
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

package multiplexer.jmx.internal;

import static com.google.common.base.Objects.firstNonNull;
import static com.google.common.base.Preconditions.checkNotNull;
import static multiplexer.jmx.util.Channels.awaitSemiInterruptibly;

import java.lang.ref.WeakReference;
import java.math.BigInteger;
import java.net.SocketAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import multiplexer.jmx.client.ChannelFutureGroup;
import multiplexer.jmx.client.ChannelFutureSet;
import multiplexer.jmx.client.Connection;
import multiplexer.jmx.client.SendingMethod;
import multiplexer.jmx.exceptions.NoPeerForPeerIdException;
import multiplexer.jmx.exceptions.NoPeerForTypeException;
import multiplexer.jmx.util.RecentLongPool;
import multiplexer.protocol.Constants.MessageTypes;
import multiplexer.protocol.Protocol.MultiplexerMessage;
import multiplexer.protocol.Protocol.WelcomeMessage;

import org.jboss.netty.bootstrap.Bootstrap;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.timeout.IdleStateHandler;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.Timer;
import org.jboss.netty.util.TimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.TextFormat;

/**
 * A class for connections management, instantiated by any Multiplexer server's
 * client.
 * 
 * It is responsible for establishing, keeping and closing connections. It
 * handles all system messages accordingly to the Multiplexer's protocol.
 * 
 * Any incoming message which is not a system message is forwarded to the client
 * (client must provide a {@link MessageReceivedListener}). Also, the
 * ConnectionsManager allows the client to send messages asynchronously. The
 * sending method's ({@code sendMessage()}) return type is {@link ChannelFuture}
 * which allows asynchronous and synchronous handling of the initialized sending
 * operation. Apart from the asynchronous sending operation, a method {@code
 * flushAll()} is available for synchronous completion of all initialized
 * sending operations.
 * 
 * @author Kasia Findeisen
 * @author Piotr Findeisen
 */
public class ConnectionsManager implements MultiplexerProtocolListener {

	private static final Logger logger = LoggerFactory
		.getLogger(ConnectionsManager.class);

	private final Object lock = new Object();

	private volatile boolean shuttingDown = false;

	private final long instanceId = new Random().nextLong();
	private final int instanceType;
	private final Bootstrap bootstrap;
	private final ConnectionsMap connectionsMap = new ConnectionsMap();
	private MessageReceivedListener messageReceivedListener;
	private final ChannelFutureSet allPendingChannelFutures = new ChannelFutureSet();
	private final Timer timer = new HashedWheelTimer();
	private final Config config = new Config();
	private final RecentLongPool recentMsgIds = new RecentLongPool();

	private final Map<Channel, WeakReference<ChannelFuture>> pendingRegistrations = new WeakHashMap<Channel, WeakReference<ChannelFuture>>();

	private final Map<Channel, SocketAddress> endpointByChannel = new WeakHashMap<Channel, SocketAddress>();

	private volatile MultiplexerMessage cachedWelcomeMessage;

	// TODO document property use
	private volatile ByteString multiplexerPassword = ByteString
		.copyFromUtf8(System.getProperty("multiplexer.jmx.multiplexerPassword",
			""));

	/**
	 * Constructs new ConnectionsManager with given type.
	 * 
	 * @param instanceType
	 *            the type of the peer that this {@link ConnectionsManager} will
	 *            represent
	 */
	public ConnectionsManager(final int instanceType) {
		this(instanceType, Executors.newCachedThreadPool());
	}

	/**
	 * Constructs new ConnectionsManager with given type.
	 * 
	 * @param instanceType
	 *            the type of the peer that this {@link ConnectionsManager} will
	 *            represent
	 * @param threadPool
	 *            ExecutorService that will be used to create boss and worker
	 *            threads for handling {@code java.nio}
	 */
	public ConnectionsManager(final int instanceType, ExecutorService threadPool) {
		this(instanceType, threadPool, threadPool);
	}

	/**
	 * Constructs new client-side ConnectionsManager with given type.
	 * 
	 * @param instanceType
	 *            the type of the peer that this {@link ConnectionsManager} will
	 *            represent
	 * @param bossExecutor
	 *            {@link ExecutorService} that will be used to create boss
	 *            thread
	 * @param workerExecutor
	 *            {@link ExecutorService} that will be used to create I/O worker
	 *            threads; it must be able to create at least a thread per CPU
	 *            core
	 */
	public ConnectionsManager(final int instanceType, Executor bossExecutor,
		Executor workerExecutor) {

		this(instanceType, new ClientBootstrap(
			new NioClientSocketChannelFactory(bossExecutor, workerExecutor)));
	}

	public ConnectionsManager(final int instanceType, Bootstrap bootstrap) {

		this.instanceType = instanceType;
		logger.info("creating {}", this);

		this.bootstrap = bootstrap;
		bootstrap.setOption("tcpNoDelay", true);
		bootstrap.setOption("keepAlive", true);
		bootstrap
			.setPipelineFactory(new ConnectionsManagerChannelPipelineFactory(
				timer, this));
	}

	public MultiplexerMessage.Builder createMessageBuilder() {
		return initializeMessageBuilder(MultiplexerMessage.newBuilder());
	}

	private MultiplexerMessage.Builder initializeMessageBuilder(
		MultiplexerMessage.Builder message) {
		return message.setId(new Random().nextLong()).setFrom(instanceId)
			.setTimestamp(System.currentTimeMillis() / 1000);
	}

	public MultiplexerMessage createMessage(ByteString message, int type) {
		return createMessageBuilder().setMessage(message).setType(type).build();
	}

	public MultiplexerMessage createMessage(MultiplexerMessage.Builder message) {
		return initializeMessageBuilder(message).build();
	}

	public ChannelFuture asyncConnect(SocketAddress address) {
		return asyncConnect(address, 3, TimeUnit.SECONDS);
	}

	public synchronized ChannelFuture asyncConnect(final SocketAddress address,
		final long reconnectTime, final TimeUnit reconnectTimeUnit) {

		logger.debug("{} connecting to {}", this, address);
		// TODO send THROUGH_ALL/THROUGH_ALL in case of no connections should
		// also try to reconnect immediately
		// TODO send via(Connection) should try to reconnect to the same
		// address, if connection is lost
		// In both those cases this is normally done after a delay; prevent
		// flood of connection attempts in case of `send' performed in a loop.
		assert bootstrap instanceof ClientBootstrap;
		ChannelFuture connectOperation = ((ClientBootstrap) bootstrap)
			.connect(address);
		final Channel channel = checkNotNull(connectOperation.getChannel());
		endpointByChannel.put(channel, address);

		final ChannelFuture registrationFuture = Channels.future(channel, true);

		synchronized (pendingRegistrations) {
			if (shuttingDown) {
				logger.debug("{}: connect to {} cancelled by shutdown", this,
					address);
				Channels.close(channel);
				registrationFuture.setFailure(new RuntimeException(
					"connect cancelled by shutdown"));
				return registrationFuture;
			}
			pendingRegistrations.put(channel, new WeakReference<ChannelFuture>(
				registrationFuture));
		}
		channel.getCloseFuture().addListener(
			new WeakFailureSettingListener(registrationFuture));

		connectOperation.addListener(new ChannelFutureListener() {

			public void operationComplete(ChannelFuture future)
				throws Exception {
				assert future.isDone();
				if (future.isCancelled())
					return;
				if (!future.isSuccess()) {
					registrationFuture.setFailure(future.getCause());
					synchronized (pendingRegistrations) {
						pendingRegistrations.remove(channel);
					}
					scheduleReconnect(address, reconnectTime, reconnectTimeUnit);
					return;
				}

				// Send out welcome message.
				sendMessage(createWelcomeMessage(), future.getChannel());
			}
		});

		return registrationFuture;
	}

	private void scheduleReconnect(final SocketAddress address,
		final long delay, final TimeUnit unit) {

		timer.newTimeout(new TimerTask() {
			public void run(Timeout timeout) throws Exception {
				asyncConnect(address, delay, unit);
			}
		}, delay, unit);
	}

	public void channelOpen(Channel channel) {
		logger.info("{} now has open channel {}", this, channel);
		connectionsMap.addNew(channel);
	}

	public void channelDisconnected(Channel channel) {
		assert !channel.isConnected();
		if (shuttingDown) {
			if (logger.isDebugEnabled())
				logger.debug("channel {} is disconnected now ({} shutdown)",
					toString(channel), this);
			return;
		}
		SocketAddress address = endpointByChannel.get(channel);
		if (address != null) {
			if (logger.isWarnEnabled())
				logger.warn(
					"channel {} is disconnected now in {}, reconnecting to {}",
					new Object[] { toString(channel), this, address });

			// Warning: unconditionally calling asyncConnect here leads to busy
			// waiting (connecting) when the server accepts TCP connections but
			// drops them immediately (e.g. invalid handshake). On the other
			// hand it enables quick reconnects in normal scenarios, when the
			// connection is just dropped accidentally.
			scheduleReconnect(address, 1, TimeUnit.SECONDS);
			// TODO enable fast reconnect while preventing busy wait (see above)
			// asyncConnect(address);
		} else {
			if (logger.isWarnEnabled())
				logger.warn("channel {} is disconnected now in {}",
					toString(channel), this);
		}
	}

	private MultiplexerMessage createWelcomeMessage() {
		if (cachedWelcomeMessage != null)
			return cachedWelcomeMessage;
		WelcomeMessage welcomeMessage = WelcomeMessage.newBuilder().setType(
			instanceType).setId(instanceId).setMultiplexerPassword(
			multiplexerPassword).build();
		logger.debug("created welcome message\n{}", welcomeMessage);
		ByteString message = welcomeMessage.toByteString();
		cachedWelcomeMessage = createMessage(message,
			MessageTypes.CONNECTION_WELCOME);
		return cachedWelcomeMessage;
	}

	public void messageReceived(MultiplexerMessage message, Channel channel) {

		if (message.getType() != MessageTypes.CONNECTION_WELCOME
			&& !recentMsgIds.add(message.getId())) {
			logger.debug("Duplicate message received and dropped\n{}", message);
			return;
		}

		if (message.getType() == MessageTypes.CONNECTION_WELCOME) {
			WelcomeMessage welcome;
			try {
				welcome = WelcomeMessage.parseFrom(message.getMessage());
			} catch (InvalidProtocolBufferException e) {
				logger.warn("Malformed CONNECTION_WELCOME received.", e);
				Channels.close(channel);
				return;
			}

			// Check multiplexerPassword
			if (multiplexerPassword.size() > 0
				&& !multiplexerPassword
					.equals(welcome.getMultiplexerPassword())) {
				logger
					.warn(
						"WelcomeMessage with invalid multiplexerPassword received over {}",
						channel);
				Channels.close(channel);
				return;
			}

			int peerType = welcome.getType();
			Channel oldChannel = connectionsMap.add(channel, message.getFrom(),
				peerType);
			WeakReference<ChannelFuture> registartionFutureRef;
			synchronized (pendingRegistrations) {
				registartionFutureRef = pendingRegistrations.remove(channel);
			}
			if (registartionFutureRef != null) {
				assert bootstrap instanceof ClientBootstrap : channel;
				ChannelFuture registartionFuture = registartionFutureRef.get();
				if (registartionFuture != null)
					registartionFuture.setSuccess();
			} else {
				assert bootstrap instanceof ServerBootstrap : channel;
				sendMessage(createWelcomeMessage(), channel);
			}

			channel.getPipeline().replace(
				"idleHandler",
				"idleHandler",
				new IdleStateHandler(timer, config.getReadIdleTime(peerType),
					config.getWriteIdleTime(peerType), Long.MAX_VALUE,
					TimeUnit.SECONDS));

			if (oldChannel != null && oldChannel != channel) {
				logger
					.warn(
						"CONNECTION_WELCOME received from already connected peer over {}; closing previous connection {}:\n{}",
						new Object[] { channel, oldChannel, welcome });
				Channels.close(oldChannel);
				return;
			}

		} else if (message.getType() == MessageTypes.HEARTBIT) {
			// Ignored, functionality of HEARTBITs handled by the pipeline.

		} else {
			if (!fireOnMessageReceived(message, channel)) {
				logger.debug(
					"A message received while no listener is registered:\n{}",
					message);
			}
		}
	}

	public MessageReceivedListener getMessageReceivedListener() {
		return messageReceivedListener;
	}

	public void setMessageReceivedListener(
		MessageReceivedListener messageReceivedListener) {
		if (messageReceivedListener == null) {
			throw new NullPointerException("messageReceivedListener");
		}
		this.messageReceivedListener = messageReceivedListener;
	}

	private boolean fireOnMessageReceived(MultiplexerMessage message,
		Channel channel) {
		if (messageReceivedListener != null) {
			messageReceivedListener.onMessageReceived(message, new Connection(
				channel));
			return true;
		} else {
			return false;
		}
	}

	private ChannelFuture sendMessage(MultiplexerMessage message,
		Channel channel) {
		ChannelFuture cf = channel.write(message);
		allPendingChannelFutures.add(cf);
		return cf;
	}

	public ChannelFutureGroup sendMessage(MultiplexerMessage message,
		SendingMethod.ViaConnectionsOfType method)
		throws NoPeerForTypeException {

		if (method.getQuantity() == SendingMethod.ANY) {
			Channel channel;
			channel = connectionsMap.getAny(method.getPeerType());
			return new ChannelFutureGroup(sendMessage(message, channel));

		} else if (method.getQuantity() == SendingMethod.ALL) {
			Iterator<Channel> channels = connectionsMap.getAll(method
				.getPeerType());
			Channel channel;
			ChannelFutureGroup channelFutureGroup = new ChannelFutureGroup();
			while (channels.hasNext()) {
				channel = channels.next();
				channelFutureGroup.add(sendMessage(message, channel));
			}
			return channelFutureGroup;
		}
		throw new RuntimeException("Unsupported SendingMethod");
	}

	public ChannelFutureGroup sendMessage(MultiplexerMessage message,
		SendingMethod.ViaConnection method) {
		return new ChannelFutureGroup(sendMessage(message, method
			.getConnection().getChannel()));
	}

	public ChannelFutureGroup sendMessage(MultiplexerMessage message,
		SendingMethod.ViaPeer method) throws NoPeerForPeerIdException {

		return new ChannelFutureGroup(sendMessage(message, connectionsMap
			.getByPeerId(method.getPeerId())));
	}

	public void flushAll() throws InterruptedException {
		copyActiveChannelFutures().await();
	}

	public boolean flushAll(long timeout, TimeUnit unit)
		throws InterruptedException {
		return copyActiveChannelFutures().await(timeout, unit);

	}

	public boolean flushAll(long timeoutMillis) throws InterruptedException {
		return copyActiveChannelFutures().await(timeoutMillis);
	}

	private ChannelFutureGroup copyActiveChannelFutures() {
		synchronized (allPendingChannelFutures) {
			return new ChannelFutureGroup(allPendingChannelFutures);
		}
	}

	public Timer getTimer() {
		return timer;
	}

	public long getInstanceId() {
		return instanceId;
	}

	public ByteString getMultiplexerPassword() {
		return multiplexerPassword;
	}

	public void setMultiplexerPassword(ByteString multiplexerPassword) {
		if (multiplexerPassword == null)
			throw new NullPointerException("multiplexerPassword");
		this.multiplexerPassword = multiplexerPassword;
	}

	/**
	 * Shut downs this {@link ConnectionsManager} and also frees the resources
	 * held by {@link ChannelFactory} that was used (explicitly or via
	 * {@link Bootstrap}) to construct this ConnectionsManager.
	 * 
	 * @throws InterruptedException
	 */
	public void shutdown() throws InterruptedException {
		synchronized (lock) {
			if (shuttingDown)
				return;
			shuttingDown = true;
		}
		// Timer stop is also invoked from
		// IdleStateHandler.releaseExternalResources() if any Channels are
		// created.
		timer.stop();
		ChannelGroup allChannels = new DefaultChannelGroup();
		synchronized (pendingRegistrations) {
			allChannels.addAll(pendingRegistrations.keySet());
		}
		allChannels.addAll(connectionsMap.getAllChannels());
		awaitSemiInterruptibly(allChannels.close(), 3);
		bootstrap.releaseExternalResources();
	}

	@Override
	public String toString() {
		StringBuilder str = new StringBuilder(128);
		str.append(ConnectionsManager.class.getSimpleName()).append("(type=")
			.append(instanceType).append(", id=").append(
				unsignedToString(instanceId));
		if (shuttingDown)
			str.append(", shut");
		str.append(")");
		return str.toString();
	}

	private String toString(Channel channel) {
		if (channel == null)
			return "null";
		return channel + "[type="
			+ firstNonNull(connectionsMap.getChannelPeerType(channel), "?")
			+ "]";
	}

	/**
	 * Convert an unsigned 64-bit integer to a string. Copied shamelessly from
	 * {@link TextFormat}.
	 */
	private static String unsignedToString(long value) {
		if (value >= 0) {
			return Long.toString(value);
		} else {
			/*
			 * Pull off the most-significant bit so that BigInteger doesn't
			 * think the number is negative, then set it again using setBit().
			 */
			return BigInteger.valueOf(value & 0x7FFFFFFFFFFFFFFFL).setBit(63)
				.toString();
		}
	}

	@Override
	protected void finalize() throws Throwable {
		shutdown();
		super.finalize();
	}
}
