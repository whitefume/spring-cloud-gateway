package com.roth.test;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;

/**
 * author:  Wang Yunlong
 * times:    2018-03-27
 * purpose:
 **/
public class CustomRouteLocator {

	@Bean
	public RouteLocator customeRouteLocator(RouteLocatorBuilder builder) {
		return builder.routes()
				.route(r -> r.host("**.abc.org").and().path("/image/png")
						.filters(f -> f.addResponseHeader("X-TestHeader", "foobar"))
						.uri("http://httpbin.org"))
				.route(r -> r.path("/image/webp")
						.filters(f -> f.addResponseHeader("X-AnotherHeader", "baz"))
						.uri("http://httpbin.org"))
				.build();
	}
}
