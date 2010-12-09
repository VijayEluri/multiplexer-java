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

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import multiplexer.jmx.exceptions.NoPeerForPeerIdException;
import multiplexer.jmx.exceptions.NoPeerForTypeException;
import multiplexer.protocol.Constants.PeerTypes;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.LinkedListMultimap;

/**
 * A class for managing groups of {@link Channel}s, indexed by connected peers'
 * types and Ids. Provides access to all {@code Channel}s as well as searching
 * by peer type (Peer types are described in {@link PeerTypes} and in any
 * additional generated files). Methods {@code getAny(peerType)} and {@code
 * getAll(peerType)} return one or all peers of the given type respectively.
 * 
 * <p>For each peer (denoted by Id) only one channel can be held in the structure.
 * 
 * <p>This object is thread-safe (with some restrictions, see {@link #getAll}).
 * 
 * @author Kasia Findeisen
 * @author Piotr Findeisen
 */
public class ConnectionsMap {

	/**
	 * A multimap of {@link Channel}s grouped by connected peers' types.
	 */
	private LinkedListMultimap<Integer, Channel> channelsByType = LinkedListMultimap
		.create();

	/**
	 * Provides access to all open channels while cleaning up.
	 */
	private ChannelGroup allChannels = new DefaultChannelGroup();

	/**
	 * A map of {@link Channel}s by peer Id. Id is associated with the peer's
	 * most recent connection.
	 */
	private BiMap<Long, Channel> channelsByPeerId = HashBiMap.create();

	/**
	 * Helper {@link Map}, reverse to {@code channelsByType}, which is a
	 * {@link LinkedListMultimap} and therefore has no reverse access.
	 */
	private Map<Channel, Integer> peerTypeByChannel = new WeakHashMap<Channel, Integer>();

	private final ChannelFutureListener remover = new ChannelRemover(this);

	/**
	 * Adds a new channel to {@code allChannels} which is a {@link ChannelGroup}
	 * . Any closed channel will be removed automatically.
	 * 
	 * @param channel
	 *            a new connection
	 */
	public void addNew(Channel channel) {
		channel.getCloseFuture().addListener(remover);
		allChannels.add(channel);
	}

	/**
	 * Adds a new {@link Channel}, together with the connected peer's Id (
	 * {@code peerId}) and the connected peer's type ({@code peerType}) to
	 * global maps allowing indexing by peer types and Id. If a connection with
	 * the peer (a peer having the same Id as {@code peerId}) is already
	 * established, it is overwritten in the maps. The {@link Channel} of the
	 * previous connection is returned so that it can be closed by the callee.
	 * 
	 * @param channel
	 *            a new connection
	 * @param peerId
	 *            Id of the connected peer
	 * @param peerType
	 *            type of the connected peer
	 * @return a channel of a previous connection to the peer or null if the
	 *         peer wasn't connected
	 */
	public synchronized Channel add(Channel channel, long peerId, int peerType) {
		channel.getCloseFuture().addListener(remover);
		Channel oldChannel = channelsByPeerId.put(peerId, channel);
		if (oldChannel != null) {
			channelsByType
				.remove(peerTypeByChannel.get(oldChannel), oldChannel);
			peerTypeByChannel.remove(oldChannel);
		}
		peerTypeByChannel.put(channel, peerType);
		channelsByType.put(peerType, channel);
		return oldChannel;
	}
	
	public synchronized Integer getChannelPeerType(Channel channel) {
		return peerTypeByChannel.get(channel);
	}

	/**
	 * Removes the {@link Channel} previously added with {@link #addNew} or
	 * {@link #add}. Returns true if the {@code channel} has been removed from
	 * any of internal structures.
	 * 
	 * @param channel
	 *            channel to be removed
	 * @return true, if the channel has been removed
	 */
	public synchronized boolean remove(Channel channel) {
		boolean removed = false;
		if (allChannels.remove(channel)) {
			// The channel was registered with `addNew`.
			removed = true;
		}

		Integer type = peerTypeByChannel.get(channel);
		if (type != null) {
			// The channel was registered with `add`.
			channelsByType.remove(type, channel);
			channelsByPeerId.inverse().remove(channel);
			removed = true;
		}
		return removed;
	}

	/**
	 * Returns a {@link Channel} associated with some peer of the given type (
	 * {@code peerType}). Chooses the channel on a basis of round-robin
	 * algorithm.
	 * 
	 * @param peerType
	 *            requested type of the peer
	 * @throws NoPeerForTypeException
	 *             when there are no Channels for given type
	 */
	public synchronized Channel getAny(int peerType)
		throws NoPeerForTypeException {

		List<Channel> list = channelsByType.get(peerType);
		if (list == null || list.size() == 0)
			throw new NoPeerForTypeException("" + peerType);

		Channel anyChannel;
		while (list.size() > 0) {
			anyChannel = list.remove(0);
			if (anyChannel.isOpen()) {
				list.add(anyChannel);
				return anyChannel;
			}
		}
		throw new NoPeerForTypeException("" + peerType);
	}

	/**
	 * Returns an {@link Iterator} of all {@link Channel}s associated with the
	 * given peer type ({@code peerType}). You should manually synchronize on
	 * this {@link ConnectionsMap} when calling this method and iterating over
	 * the returned value.
	 * 
	 * @param peerType
	 *            requested type of the peer
	 * @return iterator over connections of give type.
	 * @throws NoPeerForTypeException
	 */
	public Iterator<Channel> getAll(int peerType) throws NoPeerForTypeException {
		List<Channel> list = channelsByType.get(peerType);
		if (list == null || list.size() == 0)
			throw new NoPeerForTypeException("" + peerType);
		return list.iterator();
	}

	/**
	 * Returns a {@link Channel} holding a connection with the peer having id
	 * {@code peerId}.
	 * 
	 * @throws NoPeerForPeerIdException
	 *             if there is no connection with a peer of id {@code peerId}
	 */
	public Channel getByPeerId(long peerId) throws NoPeerForPeerIdException {
		Channel channel;
		synchronized (this) {
			channel = channelsByPeerId.get(peerId);
		}
		if (channel == null)
			throw new NoPeerForPeerIdException("" + peerId);

		return channel;
	}

	/**
	 * Get all {@link Channel}s that have been added with {@link #addNew} and
	 * has not yet been closed. You should not modify the returned set.
	 * 
	 * @return all channels
	 */
	public ChannelGroup getAllChannels() {
		return allChannels;
	}

	/**
	 * This class is equivalent to simplistic anonymous implementation such as:
	 * 
	 * <pre>
	 * new ChannelFutureListener() {
	 * 		public void operationComplete(ChannelFuture future) throws Exception {
	 * 			remove(future.getChannel());
	 * 		}
	 * </pre>
	 * 
	 * except that is stores a {@link WeakReference} to the enclosing
	 * {@link ConnectionsMap} instead of strong reference involved in non-static
	 * classes. This may be required to avoid memory leaks, because
	 * {@link Channel}s are referenced by worker threads, and their close
	 * {@link ChannelFuture futures} are referenced by channels.
	 * 
	 * @author Piotr Findeisen
	 */
	private static class ChannelRemover implements ChannelFutureListener {

		private final WeakReference<ConnectionsMap> connectionsMap;

		public ChannelRemover(ConnectionsMap connectionsMap) {
			this.connectionsMap = new WeakReference<ConnectionsMap>(
				connectionsMap);
		}

		public void operationComplete(ChannelFuture future) throws Exception {
			ConnectionsMap connectionsMap = this.connectionsMap.get();
			if (connectionsMap != null)
				connectionsMap.remove(future.getChannel());
		}
	}
}
