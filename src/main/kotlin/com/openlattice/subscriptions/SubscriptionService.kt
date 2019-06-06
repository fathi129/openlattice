package com.openlattice.subscriptions

import com.openlattice.authorization.Principal
import com.openlattice.graph.NeighborhoodQuery
import java.util.*

/**
 * This service is for managing Subscriptions on entities
 */
interface SubscriptionService {

    fun addSubscription( subscription: NeighborhoodQuery, user: Principal )

    fun updateSubscription( subscription: NeighborhoodQuery, user: Principal )

    fun deleteSubscription( ekId: UUID, user: Principal )

    fun getAllSubscriptions( user: Principal): Iterable<NeighborhoodQuery>

    fun getSubscriptions( ekIds: List<UUID>, user: Principal ): Iterable<NeighborhoodQuery>
}