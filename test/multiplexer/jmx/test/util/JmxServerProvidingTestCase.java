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

package multiplexer.jmx.test.util;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import org.junit.After;
import org.junit.Before;

/**
 * @author Piotr Findeisen
 */
public class JmxServerProvidingTestCase {

	private JmxServerRunner jmxServerRunner;
	
	@Before
	public void startJmxServer() throws Exception {
		jmxServerRunner = new JmxServerRunner();
		jmxServerRunner.start();
	}

	@After
	public void stopJmxServer() throws Exception {
		jmxServerRunner.stop();
		jmxServerRunner = null;
	}

	protected int getLocalServerPort() {
		return jmxServerRunner.getLocalServerPort();
	}

	protected InetSocketAddress getLocalServerAddress()
		throws UnknownHostException {
		return jmxServerRunner.getLocalServerAddress();
	}
}
