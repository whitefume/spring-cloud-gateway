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

package org.springframework.cloud.gateway.route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.config.GatewayProperties;
import org.springframework.cloud.gateway.event.FilterArgsEvent;
import org.springframework.cloud.gateway.event.PredicateArgsEvent;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.GatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.handler.predicate.RoutePredicateFactory;
import org.springframework.cloud.gateway.support.ConfigurationUtils;
import org.springframework.cloud.gateway.support.ShortcutConfigurable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.tuple.Tuple;
import org.springframework.tuple.TupleBuilder;
import org.springframework.validation.Validator;
import org.springframework.web.server.ServerWebExchange;

import static org.springframework.cloud.gateway.support.ShortcutConfigurable.getValue;
import static org.springframework.cloud.gateway.support.ShortcutConfigurable.normalizeKey;

import reactor.core.publisher.Flux;

/**
 * 从RouteDefinitionLocator获取RouteDefinition转换成Route
 * {@link RouteLocator} that loads routes from a {@link RouteDefinitionLocator}
 * @author Spencer Gibb
 */
public class RouteDefinitionRouteLocator implements RouteLocator, BeanFactoryAware, ApplicationEventPublisherAware {
	protected final Log logger = LogFactory.getLog(getClass());

	private final RouteDefinitionLocator routeDefinitionLocator;
	private final Map<String, RoutePredicateFactory> predicates = new LinkedHashMap<>();
	private final Map<String, GatewayFilterFactory> gatewayFilterFactories = new HashMap<>();
	private final GatewayProperties gatewayProperties;
	private final SpelExpressionParser parser = new SpelExpressionParser();
	private BeanFactory beanFactory;
	private ApplicationEventPublisher publisher;

	public RouteDefinitionRouteLocator(RouteDefinitionLocator routeDefinitionLocator,
									   List<RoutePredicateFactory> predicates,
									   List<GatewayFilterFactory> gatewayFilterFactories,
									   GatewayProperties gatewayProperties) {
		// 设置RouteDefinitionLocator
		this.routeDefinitionLocator = routeDefinitionLocator;
		// 初始化RoutePredicateFactory, key 为RoutePredicateFactory:name()，通过它将RouteDefintion.predicates转换成Route.predicates
		initFactories(predicates);
		// 初始化RouteFilterFactory, key 为GatewayFilterFactory:name(), 将RouteDefinition.filter转换成Route.filters
		gatewayFilterFactories.forEach(factory -> this.gatewayFilterFactories.put(factory.name(), factory));
		// 设置 GatewayProperties,使用GatewayProperties.defaultFilters默认过滤器定义数组，添加到每个route
		this.gatewayProperties = gatewayProperties;
	}

	@Autowired
	private Validator validator;

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		this.beanFactory = beanFactory;
	}

	@Override
	public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
		this.publisher = publisher;
	}

	private void initFactories(List<RoutePredicateFactory> predicates) {
		predicates.forEach(factory -> {
			String key = factory.name();
			if (this.predicates.containsKey(key)) {
				this.logger.warn("A RoutePredicateFactory named "+ key
						+ " already exists, class: " + this.predicates.get(key)
						+ ". It will be overwritten.");
			}
			this.predicates.put(key, factory);
			if (logger.isInfoEnabled()) {
				logger.info("Loaded RoutePredicateFactory [" + key + "]");
			}
		});
	}

	@Override
	public Flux<Route> getRoutes() {
		return this.routeDefinitionLocator.getRouteDefinitions()
				// RouteDefinition 转 Route
				.map(this::convertToRoute)
				//TODO: error handling
				.map(route -> {
					if (logger.isDebugEnabled()) {
						logger.debug("RouteDefinition matched: " + route.getId());
					}
					return route;
				});


		/* TODO: trace logging
			if (logger.isTraceEnabled()) {
				logger.trace("RouteDefinition did not match: " + routeDefinition.getId());
			}*/
	}

	private Route convertToRoute(RouteDefinition routeDefinition) {
		// 合并 Predicate， 将RouteDefinition.predicates数组合并一个java.util.funcion.Predicate
		Predicate<ServerWebExchange> predicate = combinePredicates(routeDefinition);
		// 获得 GatewayFilter， 将FilterDefinition 转换成GatewayFilter
		List<GatewayFilter> gatewayFilters = getFilters(routeDefinition);
		// 构建Route
		return Route.builder(routeDefinition)
				.predicate(predicate)
				.replaceFilters(gatewayFilters)
				.build();
	}

	@SuppressWarnings("unchecked")
	private List<GatewayFilter> loadGatewayFilters(String id, List<FilterDefinition> filterDefinitions) {
		List<GatewayFilter> filters = filterDefinitions.stream()
				.map(definition -> {
					GatewayFilterFactory factory = this.gatewayFilterFactories.get(definition.getName());
					if (factory == null) {
                        throw new IllegalArgumentException("Unable to find GatewayFilterFactory with name " + definition.getName());
					}
					Map<String, String> args = definition.getArgs();
					if (logger.isDebugEnabled()) {
						logger.debug("RouteDefinition " + id + " applying filter " + args + " to " + definition.getName());
					}

                    if (factory.isConfigurable()) {
                        Map<String, Object> properties = factory.shortcutType().normalize(args, factory, this.parser, this.beanFactory);

                        Object configuration = factory.newConfig();

                        ConfigurationUtils.bind(configuration, properties,
                                factory.shortcutFieldPrefix(), definition.getName(), validator);

						GatewayFilter gatewayFilter = factory.apply(configuration);
                        if (this.publisher != null) {
                            this.publisher.publishEvent(new FilterArgsEvent(this, id, properties));
                        }
						return gatewayFilter;
                    } else {
                        Tuple tuple = getTuple(factory, args, this.parser, this.beanFactory);
                        return factory.apply(tuple);
                    }
				})
				.collect(Collectors.toList());

		ArrayList<GatewayFilter> ordered = new ArrayList<>(filters.size());
		for (int i = 0; i < filters.size(); i++) {
			ordered.add(new OrderedGatewayFilter(filters.get(i), i+1));
		}

		return ordered;
	}

	@SuppressWarnings("Duplicates")
	@Deprecated //TODO: remove after Tuple is removed
	/* for testing */ static Tuple getTuple(ShortcutConfigurable shortcutConf, Map<String, String> args, SpelExpressionParser parser, BeanFactory beanFactory) {
		TupleBuilder builder = TupleBuilder.tuple();

		// 参数为空
		List<String> argNames = shortcutConf.shortcutFieldOrder();
		if (!argNames.isEmpty()) {
			// ensure size is the same for key replacement later
			if (shortcutConf.validateFieldsExist() && args.size() != argNames.size()) {
				throw new IllegalArgumentException("Wrong number of arguments. Expected " + argNames
						+ " " + argNames + ". Found " + args.size() + " " + args + "'");
			}
		}

		// 创建Tuple
		int entryIdx = 0;
		for (Map.Entry<String, String> entry : args.entrySet()) {
			// 获取参数key
			String key = normalizeKey(entry.getKey(), entryIdx, shortcutConf, args);
			// 获取参数value
			Object value = getValue(parser, beanFactory, entry.getValue());
            // 添加key、value
			builder.put(key, value);
			entryIdx++;
		}

		Tuple tuple = builder.build();

		// 校验参数
		if (shortcutConf.validateFieldsExist()) {
			for (String name : argNames) {
				if (!tuple.hasFieldName(name)) {
					throw new IllegalArgumentException("Missing argument '" + name + "'. Given " + tuple);
				}
			}
		}
		return tuple;
	}

	private List<GatewayFilter> getFilters(RouteDefinition routeDefinition) {
		List<GatewayFilter> filters = new ArrayList<>();

		// 添加 默认过滤器
		//TODO: support option to apply defaults after route specific filters?
		if (!this.gatewayProperties.getDefaultFilters().isEmpty()) {
			filters.addAll(loadGatewayFilters("defaultFilters",
					this.gatewayProperties.getDefaultFilters()));
		}

		// 添加 配置过滤器
		if (!routeDefinition.getFilters().isEmpty()) {
			filters.addAll(loadGatewayFilters(routeDefinition.getId(), routeDefinition.getFilters()));
		}

		// 排序
		AnnotationAwareOrderComparator.sort(filters);
		return filters;
	}

	private Predicate<ServerWebExchange> combinePredicates(RouteDefinition routeDefinition) {
		// 寻找 Predicate
		List<PredicateDefinition> predicates = routeDefinition.getPredicates();
		// 拼装 Predicate
		Predicate<ServerWebExchange> predicate = lookup(routeDefinition, predicates.get(0));

		for (PredicateDefinition andPredicate : predicates.subList(1, predicates.size())) {
			Predicate<ServerWebExchange> found = lookup(routeDefinition, andPredicate);
			predicate = predicate.and(found);
		}
		// 返回 Predicate
		return predicate;
	}

	@SuppressWarnings("unchecked")
	private Predicate<ServerWebExchange> lookup(RouteDefinition route, PredicateDefinition predicate) {
		// 获取 RoutePredicateFactory
		RoutePredicateFactory<Object> factory = this.predicates.get(predicate.getName());
		if (factory == null) {
            throw new IllegalArgumentException("Unable to find RoutePredicateFactory with name " + predicate.getName());
		}

		// 获取
		Map<String, String> args = predicate.getArgs();
		if (logger.isDebugEnabled()) {
			logger.debug("RouteDefinition " + route.getId() + " applying "
					+ args + " to " + predicate.getName());
		}

		if (!factory.isConfigurable()) {
			Tuple tuple = getTuple(factory, args, this.parser, this.beanFactory);
			return factory.apply(tuple);
		} else {
			Map<String, Object> properties = factory.shortcutType().normalize(args, factory, this.parser, this.beanFactory);
			Object config = factory.newConfig();
			ConfigurationUtils.bind(config, properties,
					factory.shortcutFieldPrefix(), predicate.getName(), validator);
			if (this.publisher != null) {
				this.publisher.publishEvent(new PredicateArgsEvent(this, route.getId(), properties));
			}
			return factory.apply(config);
		}
	}
}
