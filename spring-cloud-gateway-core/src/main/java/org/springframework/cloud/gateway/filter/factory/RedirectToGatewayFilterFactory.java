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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.util.Assert;
import reactor.core.publisher.Mono;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.List;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.parse;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.setResponseStatus;

/**
 * 用途 ：将响应重定向到指定 URL ，并设置响应状态码为指定 Status 。注意，Status 必须为 3XX 重定向状态码。
 * id: prefixpath_route
 * uri: http://example.org
 * filters:
 * - RedirectTo=302, http://www.iocoder.cn
 * @author Spencer Gibb
 */
public class RedirectToGatewayFilterFactory extends AbstractGatewayFilterFactory<RedirectToGatewayFilterFactory.Config> {

	public static final String STATUS_KEY = "status";
	public static final String URL_KEY = "url";

	public RedirectToGatewayFilterFactory() {
		super(Config.class);
	}

	@Override
	public List<String> shortcutFieldOrder() {
		return Arrays.asList(STATUS_KEY, URL_KEY);
	}

	@Override
	public GatewayFilter apply(Config config) {
		return apply(config.status, config.url);
	}

	public GatewayFilter apply(String statusString, String urlString) {
		final HttpStatus httpStatus = parse(statusString);
		// 解析 status ，并判断是否是 3XX 重定向状态
		Assert.isTrue(httpStatus.is3xxRedirection(), "status must be a 3xx code, but was " + statusString);
		final URL url;
		try {
			url = URI.create(urlString).toURL();
		} catch (MalformedURLException e) {
			throw new IllegalArgumentException("Invalid url " + urlString, e);
		}
		return apply(httpStatus, url);
	}

	public GatewayFilter apply(HttpStatus httpStatus, URL url) {

		return (exchange, chain) ->
			chain.filter(exchange).then(Mono.defer(() -> {
				// 若响应未提交，设置响应的状态码、响应的 Header ( Location ) 。
				if (!exchange.getResponse().isCommitted()) {
					// 设置响应 Status
					setResponseStatus(exchange, httpStatus);

					// 设置响应 Header
					final ServerHttpResponse response = exchange.getResponse();
					response.getHeaders().set(HttpHeaders.LOCATION, url.toString());
					// 设置响应已提交。
					return response.setComplete();
				}
				return Mono.empty();
			}));
	}

	public static class Config {
		String status;
		String url;

		public String getStatus() {
			return status;
		}

		public void setStatus(String status) {
			this.status = status;
		}

		public String getUrl() {
			return url;
		}

		public void setUrl(String url) {
			this.url = url;
		}
	}

}
