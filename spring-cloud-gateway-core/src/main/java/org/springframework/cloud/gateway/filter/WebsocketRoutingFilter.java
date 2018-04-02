package org.springframework.cloud.gateway.filter;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.reactive.socket.server.WebSocketService;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter.filterRequest;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.*;
import static org.springframework.util.StringUtils.commaDelimitedListToStringArray;

/**
 * Websocket 路由网关过滤器。其根据 ws:// / wss:// 前缀( Scheme )过滤处理，代理后端 Websocket 服务，提供给客户端连接
 * cloud:
 *   gateway:
 *      routes:
 *        - id: websocket_test
 *          uri: ws://localhost:9000
 *          order: 8000
 *          predicates:
 *            - Path=/echo
 *
 *
 *
 *
 * @author Spencer Gibb
 */
public class WebsocketRoutingFilter implements GlobalFilter, Ordered {
	public static final String SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";

	private final WebSocketClient webSocketClient;
	private final WebSocketService webSocketService;
	private final ObjectProvider<List<HttpHeadersFilter>> headersFilters;

	public WebsocketRoutingFilter(WebSocketClient webSocketClient,
								  WebSocketService webSocketService,
								  ObjectProvider<List<HttpHeadersFilter>> headersFilters) {
		// 使用 ReactorNettyWebSocketClient  连接后端【被代理】的 WebSocket 服务。
		this.webSocketClient = webSocketClient;
		// 使用 HandshakeWebSocketService 实现类。通过该属性，处理客户端发起的连接请求( Handshake Request ) 。
		this.webSocketService = webSocketService;
		this.headersFilters = headersFilters;
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		URI requestUrl = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);

		String scheme = requestUrl.getScheme();
		if (isAlreadyRouted(exchange) || (!"ws".equals(scheme) && !"wss".equals(scheme))) {
			return chain.filter(exchange);
		}
		setAlreadyRouted(exchange);


		HttpHeaders headers = exchange.getRequest().getHeaders();
		HttpHeaders filtered = filterRequest(getHeadersFilters(),
				exchange);

		List<String> protocols = headers.get(SEC_WEBSOCKET_PROTOCOL);
		if (protocols != null) {
			// 对header 中 Sec-WebSocket-Protocol 所有协议列表， 其中可能存在 ，分隔字符串
			protocols = headers.get(SEC_WEBSOCKET_PROTOCOL).stream()
					.flatMap(header -> Arrays.stream(commaDelimitedListToStringArray(header)))
					.map(String::trim)
					.collect(Collectors.toList());
		}
        // 处理客户端发起的连接请求， @link ReactorNettyRequestUpgradeStrategy
		return this.webSocketService.handleRequest(exchange,
				new ProxyWebSocketHandler(requestUrl, this.webSocketClient,
						filtered, protocols));
	}

	private List<HttpHeadersFilter> getHeadersFilters() {
		List<HttpHeadersFilter> filters = this.headersFilters.getIfAvailable();
		if (filters == null) {
			filters = new ArrayList<>();
		}

		filters.add((headers, exchange) -> {
			HttpHeaders filtered = new HttpHeaders();
			headers.entrySet().stream()
					.filter(entry -> !entry.getKey().toLowerCase().startsWith("sec-websocket"))
					.forEach(header -> filtered.addAll(header.getKey(), header.getValue()));
			return filtered;
		});

		return filters;
	}

	/**
	 * 代理后端 WebSocket 服务处理器。
	 */
	private static class ProxyWebSocketHandler implements WebSocketHandler {

		private final WebSocketClient client;
		private final URI url;
		private final HttpHeaders headers;
		private final List<String> subProtocols;

		public ProxyWebSocketHandler(URI url, WebSocketClient client, HttpHeaders headers, List<String> protocols) {
			this.client = client;
			this.url = url;
			this.headers = headers;
			if (protocols != null) {
				this.subProtocols = protocols;
			} else {
				this.subProtocols = Collections.emptyList();
			}
		}

		@Override
		public List<String> getSubProtocols() {
			return this.subProtocols;
		}

		@Override
		public Mono<Void> handle(WebSocketSession session) {
			// pass headers along so custom headers can be sent through
			// 通过execute 连接后端代理成功后，回调WebSocketHandler#handle 方法
			return client.execute(url, this.headers, new WebSocketHandler() {
				@Override
				public Mono<Void> handle(WebSocketSession proxySession) {
					// Use retain() for Reactor Netty
					// 转发消息 客户端 -> 后端服务
					Mono<Void> proxySessionSend = proxySession
							.send(session.receive().doOnNext(WebSocketMessage::retain));
							// .log("proxySessionSend", Level.FINE);
					// 转发消息 后端服务 -> 客户端
					Mono<Void> serverSessionSend = session
							.send(proxySession.receive().doOnNext(WebSocketMessage::retain));
							// .log("sessionSend", Level.FINE);
					return Mono.when(proxySessionSend, serverSessionSend);
				}

				/**
				 * Copy subProtocols so they are available downstream.
				 * @return
				 */
				@Override
				public List<String> getSubProtocols() {
					return ProxyWebSocketHandler.this.subProtocols;
				}
			});
		}
	}
}
