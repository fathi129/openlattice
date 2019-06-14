/*
 * Copyright (C) 2019. OpenLattice, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * You can contact the owner of the copyright at support@openlattice.com
 *
 *
 */

package com.openlattice.edm.processors

import com.hazelcast.aggregation.Aggregator
import com.openlattice.edm.EntitySet
import com.openlattice.edm.set.EntitySetFlag
import java.util.*
import java.util.Map

data class EntitySetsFlagFilteringAggregator(
        val filteringFlags: Set<EntitySetFlag>
) : Aggregator<Map.Entry<UUID, EntitySet>, Set<UUID>>() {
    private val filteredEntitySetIds = mutableSetOf<UUID>()

    override fun accumulate(input: Map.Entry<UUID, EntitySet>) {
        if (input.value.flags.containsAll(filteringFlags)) {
            filteredEntitySetIds.add(input.key)
        }
    }

    override fun combine(aggregator: Aggregator<*, *>) {
        if (aggregator is EntitySetsFlagFilteringAggregator) {
            filteredEntitySetIds.addAll(aggregator.filteredEntitySetIds)
        }
    }

    override fun aggregate(): Set<UUID> {
        return filteredEntitySetIds
    }
}