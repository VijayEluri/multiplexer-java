package multiplexer.jmx.test;

import java.util.*;

public class TestConstants {

	public static class PeerTypes {

		public final static int WEBSITE = 102;
		public final static int TEST_SERVER = 106;
		public final static int TEST_CLIENT = 107;
		public final static int LOG_STREAMER = 108;
		public final static int LOG_COLLECTOR = 109;
		public final static int EVENTS_COLLECTOR = 110;
		public final static int LOG_RECEIVER_EXAMPLE = 111;
		public final static int ECHO_SERVER = 112;

		private static class MapHolder {
			public final static Map<String, Integer> constants;
			static {
				Map<String, Integer> tmp = new HashMap<String, Integer>();
				tmp.put("WEBSITE", WEBSITE);
				tmp.put("TEST_SERVER", TEST_SERVER);
				tmp.put("TEST_CLIENT", TEST_CLIENT);
				tmp.put("LOG_STREAMER", LOG_STREAMER);
				tmp.put("LOG_COLLECTOR", LOG_COLLECTOR);
				tmp.put("EVENTS_COLLECTOR", EVENTS_COLLECTOR);
				tmp.put("LOG_RECEIVER_EXAMPLE", LOG_RECEIVER_EXAMPLE);
				tmp.put("ECHO_SERVER", ECHO_SERVER);
				constants = Collections.unmodifiableMap(tmp);
			}
		}

		public static Map<String, Integer> getMap() {
			return MapHolder.constants;
		};
	}

	public static class MessageTypes {

		public final static int TEST_REQUEST = 110;
		public final static int TEST_RESPONSE = 111;
		public final static int PICKLE_RESPONSE = 112;
		public final static int LOGS_STREAM = 115;
		public final static int LOGS_STREAM_RESPONSE = 116;
		public final static int SEARCH_COLLECTED_LOGS_REQUEST = 117;
		public final static int SEARCH_COLLECTED_LOGS_RESPONSE = 118;
		public final static int REPLAY_EVENTS_REQUEST = 126;

		private static class MapHolder {
			public final static Map<String, Integer> constants;
			static {
				Map<String, Integer> tmp = new HashMap<String, Integer>();
				tmp.put("TEST_REQUEST", TEST_REQUEST);
				tmp.put("TEST_RESPONSE", TEST_RESPONSE);
				tmp.put("PICKLE_RESPONSE", PICKLE_RESPONSE);
				tmp.put("LOGS_STREAM", LOGS_STREAM);
				tmp.put("LOGS_STREAM_RESPONSE", LOGS_STREAM_RESPONSE);
				tmp.put("SEARCH_COLLECTED_LOGS_REQUEST", SEARCH_COLLECTED_LOGS_REQUEST);
				tmp.put("SEARCH_COLLECTED_LOGS_RESPONSE", SEARCH_COLLECTED_LOGS_RESPONSE);
				tmp.put("REPLAY_EVENTS_REQUEST", REPLAY_EVENTS_REQUEST);
				constants = Collections.unmodifiableMap(tmp);
			}
		}

		public static Map<String, Integer> getMap() {
			return MapHolder.constants;
		};
	}
}