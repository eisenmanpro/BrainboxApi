package com.afrithecus.brainbox.api.content.ai

/**
 * How the routing provider orders healthy candidates. Every policy keeps a
 * provider whose recent calls are failing at the back of the queue, and every
 * policy still tries the last candidate rather than failing without a call.
 */
enum class RoutingPolicy {
    /** Lowest configured price first, then lowest observed latency (the default). */
    CHEAPEST,

    /** Lowest observed latency first, then lowest configured price. */
    FASTEST,

    /** The declared order of the provider beans, with no dynamic ranking. */
    CONFIGURED,
}
