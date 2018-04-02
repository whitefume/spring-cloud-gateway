/*
 * Copyright 2013-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.springframework.cloud.gateway.filter.factory;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;

/**
 * RequestRateLimiterGatewayFilter 使用 Redis + Lua 实现分布式限流。而限流的粒度，例如 URL / 用户 / IP  KeyResolver 实现类决定
 * spring:
 *   cloud:
 *     gateway:
 *       routes:
 *     - id: default_path_to_httpbin
 *       uri: http://127.0.0.1:8081
 *       order: 10000
 *       predicates:
 *       - Path=/**
 *       filters:
 *       - RequestRateLimiter=10, 20, #{@principalNameKeyResolver}
 *
 *
 * User Request Rate Limiter filter. See https://stripe.com/blog/rate-limiters and
 */
public class RequestRateLimiterGatewayFilterFactory extends AbstractGatewayFilterFactory<RequestRateLimiterGatewayFilterFactory.Config> {

	public static final String KEY_RESOLVER_KEY = "keyResolver";

	private final RateLimiter defaultRateLimiter;
	// 限流键解析器 Bean 对象名字，根据 #{@beanName} ，使用 SpEL 表达式，从 Spring 容器中获取 Bean 对象，
	private final KeyResolver defaultKeyResolver;

	public RequestRateLimiterGatewayFilterFactory(RateLimiter defaultRateLimiter,
												  KeyResolver defaultKeyResolver) {
		super(Config.class);
		// 默认 RedisRateLimiter
		this.defaultRateLimiter = defaultRateLimiter;
		// 默认 PrincipalNameKeyResolver
		this.defaultKeyResolver = defaultKeyResolver;
	}

	public KeyResolver getDefaultKeyResolver() {
		return defaultKeyResolver;
	}

	public RateLimiter getDefaultRateLimiter() {
		return defaultRateLimiter;
	}

	@SuppressWarnings("unchecked")
	@Override
	public GatewayFilter apply(Config config) {
		KeyResolver resolver = (config.keyResolver == null) ? defaultKeyResolver : config.keyResolver;
		RateLimiter<Object> limiter = (config.rateLimiter == null) ? defaultRateLimiter : config.rateLimiter;

		return (exchange, chain) -> {
			Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
			// 调用 KeyResolver#resolve(ServerWebExchange) 方法，获得请求的限流键。
			return resolver.resolve(exchange).flatMap(key ->
					// 这里未处理限流键为空的情况. 所以，当限流键为空时，过滤器链不会继续向下执行，
					// 也就是说，不会请求后端 Http / Websocket 服务，并且最终返回客户端 200 状态码，内容为空。
					limiter.isAllowed(route.getId(), key).flatMap(response -> {
						// TODO: set some headers for rate, tokens left

						// 允许访问
						if (response.isAllowed()) {
							return chain.filter(exchange);
						}

						// 被限流，不允许访问
						exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
						return exchange.getResponse().setComplete();
					}));
		};
	}

	public static class Config {
		// 令牌桶上限 。
		private KeyResolver keyResolver;
		// 令牌桶填充平均速率，单位：秒。
		private RateLimiter rateLimiter;


		public KeyResolver getKeyResolver() {
			return keyResolver;
		}

		public Config setKeyResolver(KeyResolver keyResolver) {
			this.keyResolver = keyResolver;
			return this;
		}
		public RateLimiter getRateLimiter() {
			return rateLimiter;
		}

		public Config setRateLimiter(RateLimiter rateLimiter) {
			this.rateLimiter = rateLimiter;
			return this;
		}
	}

}
