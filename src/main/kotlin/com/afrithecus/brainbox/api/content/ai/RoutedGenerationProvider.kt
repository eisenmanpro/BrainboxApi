package com.afrithecus.brainbox.api.content.ai

/**
 * A provider that can be ranked in the routing pool: the generation seam plus the
 * price the CHEAPEST policy sorts by. Keeping this separate from
 * [ContentGenerationProvider] lets the routing pool be unit-tested with fakes
 * while the router still consumes the plain generation seam.
 */
interface RoutedGenerationProvider : ContentGenerationProvider {

    /** The price the routing policy ranks this provider by. */
    val costProfile: ProviderCostProfile
}
