package multiplexer.jmx.example.echo;

import static multiplexer.jmx.example.echo.JmxServerRunner.ECHO_CLIENT;
import static multiplexer.jmx.example.echo.JmxServerRunner.ECHO_REQUEST;
import static multiplexer.jmx.example.echo.JmxServerRunner.jmxServerAddress;

import java.util.concurrent.TimeUnit;

import multiplexer.jmx.client.IncomingMessageData;
import multiplexer.jmx.client.JmxClient;
import multiplexer.jmx.exceptions.NoPeerForTypeException;
import multiplexer.jmx.exceptions.OperationFailedException;
import multiplexer.jmx.server.JmxServer;

import com.google.protobuf.ByteString;

/**
 * @author Piotr Findeisen
 */
public class EchoClient {

	/**
	 * Send a message to {@link EchoBackend} via a {@link JmxServer} run by
	 * {@link JmxServerRunner}.
	 * 
	 * @throws NoPeerForTypeException
	 * @throws OperationFailedException
	 * @throws InterruptedException
	 */
	public static void main(String[] args) throws OperationFailedException,
		NoPeerForTypeException, InterruptedException {

		/*
		 * Create a JmxClient and connect it to the server.
		 */
		JmxClient client = new JmxClient(ECHO_CLIENT);
		client.connect(jmxServerAddress());

		/*
		 * Query the EchoBackend.
		 */
		ByteString message = ByteString
			.copyFromUtf8("Hey, Echo server! Please reply to me!");
		IncomingMessageData response = client.query(message, ECHO_REQUEST, 1,
			TimeUnit.SECONDS);

		/*
		 * Validate the response.
		 */
		if (response == null) {
			System.err.println("Query did not return any response.");
		} else if (!response.getMessage().getMessage().equals(message)) {
			System.err
				.println("Query did return something different that we sent.");
		} else {
			System.out.println("Query returned exactly what we have sent:");
			System.out.println(response.getMessage().getMessage()
				.toStringUtf8());
		}

		/*
		 * JmxClient creates worker threads, we have to explicitly shut them
		 * down.
		 */
		client.shutdown();
	}

}