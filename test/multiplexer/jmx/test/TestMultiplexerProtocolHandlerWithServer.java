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

package multiplexer.jmx.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import multiplexer.jmx.internal.ChannelBufferFactorySettingHandler;
import multiplexer.jmx.internal.MultiplexerProtocolHandler;
import multiplexer.jmx.internal.MultiplexerProtocolListener;
import multiplexer.jmx.internal.RawMessageFrameDecoder;
import multiplexer.jmx.internal.RawMessageFrameEncoder;
import multiplexer.jmx.test.util.JmxServerProvidingTestCase;
import multiplexer.protocol.Constants;
import multiplexer.protocol.Protocol;
import multiplexer.protocol.Protocol.MultiplexerMessage;
import multiplexer.protocol.Protocol.WelcomeMessage;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.socket.SocketChannel;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.protobuf.ProtobufDecoder;
import org.jboss.netty.handler.codec.protobuf.ProtobufEncoder;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;
import com.google.protobuf.ByteString.Output;

/**
 * @author Kasia Findeisen
 * @author Piotr Findeisen
 */
public class TestMultiplexerProtocolHandlerWithServer extends JmxServerProvidingTestCase {

	private static final Logger logger = LoggerFactory.getLogger(TestMultiplexerProtocolHandlerWithServer.class);

	@Test
	public void testSimpleNettyConnectionWithLEBuffers() throws Exception {
		testSimpleNettyConnection(true);
	}

	@Test
	public void testSimpleNettyConnectionWithoutLEBuffers() throws Exception {
		testSimpleNettyConnection(false);
	}

	private void testSimpleNettyConnection(boolean useLittleEndianBuffers) throws Exception {

		final int PYTHON_TEST_SERVER = TestConstants.PeerTypes.TEST_SERVER;
		final int CONNECTION_WELCOME = Constants.MessageTypes.CONNECTION_WELCOME;
		final int MULTIPLEXER = Constants.PeerTypes.MULTIPLEXER;
		final int PYTHON_TEST_REQUEST = TestConstants.MessageTypes.TEST_REQUEST;

		ChannelFactory factory = new NioClientSocketChannelFactory(Executors.newCachedThreadPool(), Executors.newCachedThreadPool());
		SimpleNettyConnection c = new SimpleNettyConnection(factory, getLocalServerAddress(), useLittleEndianBuffers);

		// send out invitation
		logger.info("sending welcome message");
		ByteString message = WelcomeMessage.newBuilder().setType(PYTHON_TEST_SERVER).setId(c.getInstanceId()).build().toByteString();
		c.sendMessage(message, CONNECTION_WELCOME).future.await();

		// receive the invitation
		logger.info("waiting for welcome message");
		MultiplexerMessage mxmsg = c.receiveMessage();
		logger.info("validating welcome message");
		assertEquals(mxmsg.getType(), CONNECTION_WELCOME);
		WelcomeMessage peer = WelcomeMessage.parseFrom(mxmsg.getMessage());
		assertEquals(peer.getType(), MULTIPLEXER);
		peer.getId();

		// send a stupid search_query
		ArrayList<Byte> sq = new ArrayList<Byte>();
		for (byte d : "this is a search query with null (\\x00) bytes and other ".getBytes())
			sq.add(d);
		for (int i = Byte.MIN_VALUE; i <= Byte.MAX_VALUE; i++)
			sq.add((byte) i);

		Output sqo = ByteString.newOutput();
		for (byte d : sq)
			sqo.write(d);

		logger.info("sending sample search query");
		long id = c.sendMessage(sqo.toByteString(), PYTHON_TEST_REQUEST).messageId;
		logger.info("waiting for sample search query");
		mxmsg = c.receiveMessage();
		logger.info("validating sample search query");
		assertEquals(mxmsg.getId(), id);
		assertEquals(mxmsg.getType(), PYTHON_TEST_REQUEST);
		assertEquals(mxmsg.getMessage(), sqo.toByteString());

		// send a large search_query
		Random rand = new Random();
		final int size = 1024 * 1024;
		Output lqo = ByteString.newOutput(1024);
		for (int i = 0; i < size; i++)
			lqo.write(rand.nextInt());
		ByteString query = lqo.toByteString();
		assertEquals(query.size(), size);
		logger.info("sending large search query");
		id = c.sendMessage(query, PYTHON_TEST_REQUEST).messageId;
		logger.info("waiting for large search query");
		mxmsg = c.receiveMessage();
		logger.info("validating large search query");
		assertEquals(mxmsg.getId(), id);
		assertEquals(mxmsg.getType(), PYTHON_TEST_REQUEST);
		assertEquals(mxmsg.getMessage(), query);

		c.close();
		factory.releaseExternalResources();
	}

	public static class SimpleNettyConnection implements MultiplexerProtocolListener {

		private final long instanceId;
		private boolean connected = false;
		private boolean connecting = false;
		BlockingQueue<MultiplexerMessage> queue = new LinkedBlockingQueue<MultiplexerMessage>();

		private volatile SocketChannel channel;

		public SimpleNettyConnection(ChannelFactory factory, SocketAddress address, boolean useLittleEndianBuffers)
			throws InterruptedException {

			instanceId = new Random().nextLong();

			ChannelFuture connectFuture = asyncConnect(factory, address, useLittleEndianBuffers);
			connectFuture.addListener(new ChannelFutureListener() {

				public void operationComplete(ChannelFuture future) throws Exception {
					assertTrue(future.isSuccess());
				}
			});
			connectFuture.await();
		}

		private ChannelFuture asyncConnect(ChannelFactory factory, SocketAddress address, boolean useLittleEndianBuffers) {
			assert !connected;
			assert !connecting;
			connecting = true;

			ClientBootstrap bootstrap = new ClientBootstrap(factory);

			bootstrap.setOption("tcpNoDelay", true);
			bootstrap.setOption("keepAlive", true);

			ChannelPipeline pipeline = bootstrap.getPipeline();

			// Configuration
			if (useLittleEndianBuffers) {
				pipeline.addFirst("littleEndianEndiannessSetter", ChannelBufferFactorySettingHandler.LITTLE_ENDIAN_BUFFER_FACTORY_SETTER);
			}

			// Encoders
			pipeline.addLast("rawMessageEncoder", new RawMessageFrameEncoder());
			pipeline.addLast("multiplexerMessageEncoder", new ProtobufEncoder());

			// Decoders
			pipeline.addLast("rawMessageDecoder", new RawMessageFrameDecoder());
			pipeline.addLast("multiplexerMessageDecoder", new ProtobufDecoder(Protocol.MultiplexerMessage.getDefaultInstance()));

			// Protocol handler
			pipeline.addLast("multiplexerProtocolHandler", new MultiplexerProtocolHandler(this));

			ChannelFuture connectOperation = bootstrap.connect(address);
			channel = (SocketChannel) connectOperation.getChannel();
			connectOperation.addListener(new ChannelFutureListener() {

				public void operationComplete(ChannelFuture future) throws Exception {

					logger.info("connected");
					assert future.isDone();
					assert !connected;
					assert connecting;
					connected = true;
					connecting = false;
				}
			});
			return connectOperation;
		}

		long getInstanceId() {
			return instanceId;
		}

		static class SendingResult {
			ChannelFuture future;
			long messageId;

			public SendingResult(ChannelFuture future, long messageId) {
				this.future = future;
				this.messageId = messageId;
			}
		}

		public SendingResult sendMessage(ByteString message, int type) {
			MultiplexerMessage mxmsg = MultiplexerMessage.newBuilder().setId(new Random().nextLong()).setFrom(getInstanceId())
				.setType(type).setMessage(message).build();

			return new SendingResult(channel.write(mxmsg), mxmsg.getId());
		}

		public MultiplexerMessage receiveMessage() throws InterruptedException {
			// Give much time as the test runs very poorly under Eclipse's
			// console when the large query is printed.
			MultiplexerMessage message = queue.poll(30, TimeUnit.SECONDS);
			assertNotNull("receiveMessage timed out", message);
			return message;
		}

		private void close() {
			channel.close().awaitUninterruptibly();
		}

		public void channelOpen(Channel channel) {
			if (this.channel != null)
				assertSame(channel, this.channel);
		}

		public void channelDisconnected(Channel channel) {
			assertSame(channel, this.channel);
		}

		public void messageReceived(MultiplexerMessage message, Channel channel) {
			boolean offered = queue.offer(message);
			assert offered : "sorry not offered, offiaro";
		}

		@Override
		public String toString() {
			return SimpleNettyConnection.class.getSimpleName() + "(id=" + instanceId + ")";
		}
	}
}
