package org.springframework.cloud.gateway.filter;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.*;

/**
 * 将请求转发到当前网关实例本地接口。当请求 http://127.0.0.1:8080/globalfilters 接口
 * 配置的 PrefixPathGatewayFilterFactory 将请求改写成 http://127.0.0.1:8080/application/gateway/globalfilters
 * 加入配置
 * spring:
 *     application:
 *         name: api-gateway
 *     cloud:
 *         gateway:
 *              routes:
 *                  id: forward_sample
 *                  uri: forward:///globalfilters
 *                  order: 10000
 *                  predicates:
 *                     Path=/globalfilters
 *                  filters:
 *                     PrefixPath=/application/gateway
 * 为什么需要配置 PrefixPathGatewayFilterFactory ？需要通过 PrefixPathGatewayFilterFactory 将请求重写路径，以匹配本地 API
 * ，否则 DispatcherHandler 转发会失败。
 */
public class ForwardRoutingFilter implements GlobalFilter, Ordered {

	private static final Log log = LogFactory.getLog(ForwardRoutingFilter.class);

	private final DispatcherHandler dispatcherHandler;

	public ForwardRoutingFilter(DispatcherHandler dispatcherHandler) {
		this.dispatcherHandler = dispatcherHandler;
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		// 获得RouteToRequestUrlFilter产生的URL
		URI requestUrl = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);

		// 判断是否可以处理，标记已经被路由，
		String scheme = requestUrl.getScheme();
		if (isAlreadyRouted(exchange) || !"forward".equals(scheme)) {
			return chain.filter(exchange);
		}
		setAlreadyRouted(exchange);

		//TODO: translate url?

		if (log.isTraceEnabled()) {
			log.trace("Forwarding to URI: "+requestUrl);
		}

		return this.dispatcherHandler.handle(exchange);
	}
}
