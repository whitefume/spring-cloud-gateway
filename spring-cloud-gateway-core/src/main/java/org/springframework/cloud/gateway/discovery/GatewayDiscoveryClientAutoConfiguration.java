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
 */

package org.springframework.cloud.gateway.discovery;

import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.gateway.config.GatewayAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.DispatcherHandler;

/**
 * @author Spencer Gibb
 */
@Configuration
@ConditionalOnProperty(name = "spring.cloud.gateway.enabled", matchIfMissing = true)
@AutoConfigureBefore(GatewayAutoConfiguration.class)
@ConditionalOnClass({DispatcherHandler.class, DiscoveryClient.class})
public class GatewayDiscoveryClientAutoConfiguration {

	/**
	 * 通过调用DiscoveryClient获取注册中心的服务列表，生成对应的RouteDefinition数组
	 * @param discoveryClient
	 * @return
	 */
	@Bean
	@ConditionalOnBean(DiscoveryClient.class)
	@ConditionalOnProperty(name = "spring.cloud.gateway.discovery.locator.enabled")
	public DiscoveryClientRouteDefinitionLocator discoveryClientRouteDefinitionLocator(DiscoveryClient discoveryClient) {
		return new DiscoveryClientRouteDefinitionLocator(discoveryClient);
	}

}

