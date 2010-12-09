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

import multiplexer.protocol.Constants.MessageTypes;
import multiplexer.protocol.Protocol.MultiplexerMessage;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.ChannelHandler.Sharable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;

/**
 * @author Kasia Findeisen
 * @author Piotr Findeisen
 */
@Sharable
public class MultiplexerProtocolHandler extends SimpleChannelHandler {

	private static final int DEBUG_MESSAGE_MAX_LENGTH = 256;

	private static final Logger logger = LoggerFactory.getLogger(MultiplexerProtocolHandler.class);

	private final MultiplexerProtocolListener protocolListener;

	public MultiplexerProtocolHandler(MultiplexerProtocolListener protocolListener) {
		this.protocolListener = protocolListener;
	}

	@Override
	public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {

		protocolListener.channelDisconnected(e.getChannel());
		ctx.sendUpstream(e);
	}

	@Override
	public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
		protocolListener.channelConnected(e.getChannel());
		ctx.sendUpstream(e);
	}

	@Override
	public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {

		protocolListener.channelOpen(e.getChannel());
		ctx.sendUpstream(e);
	}

	@Override
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {

		if (!(e.getMessage() instanceof MultiplexerMessage)) {
			ctx.sendUpstream(e);
			return;
		}

		MultiplexerMessage message = (MultiplexerMessage) e.getMessage();
		if (message.getType() != MessageTypes.HEARTBIT) {
			if (logger.isDebugEnabled()) {
				logger.debug("Received over {}\n{}", e.getChannel(), makeShortDebugMessage(message));
			}
			protocolListener.messageReceived(message, e.getChannel());
		}
	}

	@Override
	public void writeRequested(ChannelHandlerContext ctx, MessageEvent e) throws Exception {

		if (logger.isDebugEnabled() && e.getMessage() instanceof MultiplexerMessage) {
			MultiplexerMessage message = (MultiplexerMessage) e.getMessage();

			if (message.getType() != MessageTypes.HEARTBIT) {
				logger.debug("Writing over {}\n{}", e.getChannel(), makeShortDebugMessage(message));
			}
		}

		ctx.sendDownstream(e);
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
		Channel channel = e.getChannel();
		if (logger.isWarnEnabled()) {
			logger.warn("Unhandled exception on " + channel + ", open=" + (channel != null ? channel.isOpen() : "null")
				+ ", manager=" + protocolListener, e.getCause());
		}
		Channels.close(channel);
	}

	private static MultiplexerMessage makeShortDebugMessage(MultiplexerMessage message) {
		if (message.getMessage().size() < DEBUG_MESSAGE_MAX_LENGTH) {
			return message;
		} else {
			return message.toBuilder().setMessage(
				ByteString.copyFromUtf8("<message length=" + message.getMessage().size() + ">")).build();
		}
	}
}
