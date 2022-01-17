/*
 * Copyright (C) 2018. OpenLattice, Inc.
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
 */

package com.openlattice.hazelcast.serializers;

import com.geekbeast.rhizome.hazelcast.serializers.UUIDStreamSerializerUtils;
import com.openlattice.hazelcast.StreamSerializerTypeIds;
import com.google.common.collect.Sets;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.openlattice.rhizome.hazelcast.DelegatedUUIDSet;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class DelegatedUUIDSetStreamSerializer extends SetStreamSerializer<DelegatedUUIDSet, UUID> {

    public DelegatedUUIDSetStreamSerializer() {
        super( DelegatedUUIDSet.class );
    }

    @Override
    public int getTypeId() {
        return StreamSerializerTypeIds.UUID_SET.ordinal();
    }

    @Override
    protected DelegatedUUIDSet newInstanceWithExpectedSize( int size ) {
        return DelegatedUUIDSet.wrap( Sets.newHashSetWithExpectedSize( size ) );
    }

    @Override
    protected UUID readSingleElement( ObjectDataInput in ) throws IOException {
        return UUIDStreamSerializerUtils.deserialize( in );
    }

    @Override
    protected void writeSingleElement( ObjectDataOutput out, UUID element ) throws IOException {
        UUIDStreamSerializerUtils.serialize( out, element );
    }

    @Override
    public DelegatedUUIDSet generateTestValue() {
        return new DelegatedUUIDSet( Set.of( UUID.randomUUID(), UUID.randomUUID() ) );
    }
}
