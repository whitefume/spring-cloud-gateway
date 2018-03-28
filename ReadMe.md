- RouteLocator 可以直接自定义路由( org.springframework.cloud.gateway.route.Route ) ，也可以通过 RouteDefinitionRouteLocator 获取 RouteDefinition ，并转换成 Route 。
- 重要，对于上层调用者 RoutePredicateHandlerMapping ，使用的是 RouteLocator 和 Route 。而 RouteDefinitionLocator 和 RouteDefinition 用于通过配置定义路由。那么自定义 RouteLocator 呢？通过代码定义路由


RouteDefinition => Route
PredicateDefinition => Predication
FilterDefinition => GatewayFilter