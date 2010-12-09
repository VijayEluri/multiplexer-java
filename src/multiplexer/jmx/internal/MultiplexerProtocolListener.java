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

import multiplexer.protocol.Protocol.MultiplexerMessage;

import org.jboss.netty.channel.Channel;

/**
 * Listens to the messages and events returned by
 * {@link MultiplexerProtocolHandler}.
 * 
 * @author Piotr Findeisen
 */
public interface MultiplexerProtocolListener {

	/**
	 * Invoked when the {@link MultiplexerProtocolHandler} associated with this
	 * listener receives a message from the network.
	 * 
	 * @param message
	 *            message read from the network
	 * @param channel
	 *            from which the message was read
	 */
	public void messageReceived(MultiplexerMessage message, Channel channel);
	
	/**
	 * Invoked when the {@link MultiplexerProtocolHandler} receives the
	 * information about a {@link Channel} being connected.
	 * 
	 * @param channel
	 *            the connected channel
	 */
	public void channelConnected(Channel channel);

	/**
	 * Invoked when the {@link MultiplexerProtocolHandler} receives the
	 * information about a {@link Channel} being disconnected.
	 * 
	 * @param channel
	 *            the closed channel
	 */
	public void channelDisconnected(Channel channel);

	/**
	 * Invoked when the {@link MultiplexerProtocolHandler} receives the
	 * information about a {@link Channel} being open.
	 * 
	 * @param channel
	 *            the opened channel
	 */
	public void channelOpen(Channel channel);
}
