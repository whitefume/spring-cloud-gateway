/*
 * Copyright 2013-2018 the original author or authors.
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

package org.springframework.cloud.gateway.filter;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter.Type;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.client.HttpClientRequest;

import java.net.URI;
import java.util.List;

import static org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter.filterRequest;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.*;

/**
 * 根据http https 前缀(scheme) 过滤处理， 根据ntty实现HttpClient请求后端服务
 * NettyWriteResponseFilter ，与 NettyRoutingFilter 成对使用的网关过滤器。
 * 其将 NettyRoutingFilter 请求后端 Http 服务的响应写回客户端。
 * @author Spencer Gibb
 * @author Biju Kunjummen
 */
public class NettyRoutingFilter implements GlobalFilter, Ordered {

	private final HttpClient httpClient;
	private final ObjectProvider<List<HttpHeadersFilter>> headersFilters;

	public NettyRoutingFilter(HttpClient httpClient,
			ObjectProvider<List<HttpHeadersFilter>> headersFilters) {
		this.httpClient = httpClient;
		this.headersFilters = headersFilters;
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		// 获得requestUrl
		URI requestUrl = exchange.getRequiredAttribute(GATEWAY_REQUEST_URL_ATTR);

		// 判断是否能处理: 以 http 或https 开头， 没有其他route 处理过
		String scheme = requestUrl.getScheme();
		if (isAlreadyRouted(exchange) || (!"http".equals(scheme) && !"https".equals(scheme))) {
			return chain.filter(exchange);
		}

		// 设置已经路由
		setAlreadyRouted(exchange);


		ServerHttpRequest request = exchange.getRequest();

		// 创建 Netty Request Method 对象。request#getMethod()
		// 返回的不是 io.netty.handler.codec.http.HttpMethod ，所以需要进行转换。
		final HttpMethod method = HttpMethod.valueOf(request.getMethod().toString());
		final String url = requestUrl.toString();

		// 根据headersFilters 组成httpHeader， 写入httpHeaders
		HttpHeaders filtered = filterRequest(this.headersFilters.getIfAvailable(),
				exchange);
		final DefaultHttpHeaders httpHeaders = new DefaultHttpHeaders();
		filtered.forEach(httpHeaders::set);

		// 根据 Transfer-Encoding 字段决定传输编码方式
		String transferEncoding = request.getHeaders().getFirst(HttpHeaders.TRANSFER_ENCODING);
		boolean chunkedTransfer = "chunked".equalsIgnoreCase(transferEncoding);

		// 是否保留host 信息， 应该是有http 请求绑定域名
		boolean preserveHost = exchange.getAttributeOrDefault(PRESERVE_HOST_HEADER_ATTRIBUTE, false);

		return this.httpClient.request(method, url, req -> {
			final HttpClientRequest proxyRequest = req.options(NettyPipeline.SendOptions::flushOnEach)
					.headers(httpHeaders)
					.chunkedTransfer(chunkedTransfer)
					.failOnServerError(false)
					.failOnClientError(false); // 是否请求失败抛出异常
			// 设置请求失败( 后端服务返回响应状体码 >= 400 )时，不抛出异常。

			if (preserveHost) {
				String host = request.getHeaders().getFirst(HttpHeaders.HOST);
				proxyRequest.header(HttpHeaders.HOST, host);
			}

			return proxyRequest.sendHeaders() //I shouldn't need this
					.send(request.getBody().map(dataBuffer ->
							((NettyDataBuffer)dataBuffer).getNativeBuffer()));
		}).doOnNext(res -> {
			ServerHttpResponse response = exchange.getResponse();
			// put headers and status so filters can modify the response
			HttpHeaders headers = new HttpHeaders();
			
			res.responseHeaders().forEach(entry -> headers.add(entry.getKey(), entry.getValue()));

			HttpHeaders filteredResponseHeaders = HttpHeadersFilter.filter(
					this.headersFilters.getIfAvailable(), headers, exchange, Type.RESPONSE);
			
			response.getHeaders().putAll(filteredResponseHeaders);
			response.setStatusCode(HttpStatus.valueOf(res.status().code()));

			// 设置 Response 到 CLIENT_RESPONSE_ATTR
			// Defer committing the response until all route filters have run
			// Put client response as ServerWebExchange attribute and write response later NettyWriteResponseFilter
			exchange.getAttributes().put(CLIENT_RESPONSE_ATTR, res);
		}).then(chain.filter(exchange));
	}
}
