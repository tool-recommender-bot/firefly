package com.firefly.client.http2;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.firefly.codec.http2.stream.HTTP2Configuration;
import com.firefly.codec.http2.stream.HTTPConnection;
import com.firefly.codec.http2.stream.Session.Listener;
import com.firefly.core.AbstractLifeCycle;
import com.firefly.net.Client;
import com.firefly.net.DecoderChain;
import com.firefly.net.EncoderChain;
import com.firefly.net.tcp.aio.AsynchronousTcpClient;
import com.firefly.utils.concurrent.Promise;
import com.firefly.utils.log.LogFactory;

public class HTTP2Client extends AbstractLifeCycle {

	private final Client client;
	private final Map<Integer, HTTP2ClientContext> http2ClientContext = new ConcurrentHashMap<>();
	private final AtomicInteger sessionId = new AtomicInteger(0);

	public HTTP2Client(HTTP2Configuration http2Configuration) {
		if (http2Configuration == null)
			throw new IllegalArgumentException("the http2 configuration is null");

		DecoderChain decoder;
		EncoderChain encoder;

		if (http2Configuration.isSecure()) {
			decoder = new ClientSecureDecoder(new HTTP1ClientDecoder(new HTTP2ClientDecoder()));
			encoder = new HTTP1ClientEncoder(new HTTP2ClientEncoder(new ClientSecureEncoder()));
		} else {
			decoder = new HTTP1ClientDecoder(new HTTP2ClientDecoder());
			encoder = new HTTP1ClientEncoder(new HTTP2ClientEncoder());
		}

		this.client = new AsynchronousTcpClient(decoder, encoder,
				new HTTP2ClientHandler(http2Configuration, http2ClientContext), http2Configuration.getTcpIdleTimeout());
	}
	
	public void connect(String host, int port, Promise<HTTPConnection> promise) {
		connect(host, port, promise, new Listener.Adapter());
	}

	public void connect(String host, int port, Promise<HTTPConnection> promise, Listener listener) {
		start();
		HTTP2ClientContext context = new HTTP2ClientContext();
		context.promise = promise;
		context.listener = listener;
		int id = sessionId.getAndIncrement();
		http2ClientContext.put(id, context);
		client.connect(host, port, id);
	}

	@Override
	protected void init() {
	}

	@Override
	protected void destroy() {
		if (client != null)
			client.shutdown();
		LogFactory.getInstance().shutdown();
	}

}