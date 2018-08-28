package com.openlattice.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.openlattice.client.serialization.SerializationConstants;

import java.util.UUID;

public class PropertySummary {
    private final UUID id;
    private final UUID entityTypeID;
    private final UUID entitySetID;
    private final int count;

    @JsonCreator
    public PropertySummary(
            @JsonProperty (SerializationConstants.ID_FIELD ) UUID id,
            @JsonProperty( SerializationConstants.ENTITY_TYPE_ID ) UUID entityTypeID,
            @JsonProperty( SerializationConstants.ENTITY_SET_ID ) UUID entitySetID,
            @JsonProperty( SerializationConstants.COUNT) int count ) {
        this.id = id;
        this.entityTypeID = entityTypeID;
        this.entitySetID = entitySetID;
        this.count = count;
    }

    @JsonProperty( SerializationConstants.ID_FIELD )
    public UUID getPropertyTypeID() {
        return id;
    }

    @JsonProperty( SerializationConstants.ENTITY_TYPE_ID )
    public UUID getEntityTypeID() {
        return entityTypeID;
    }

    @JsonProperty( SerializationConstants.ENTITY_SET_ID )
    public UUID getEntitySetID() {
        return entitySetID;
    }

    @JsonProperty( SerializationConstants.COUNT )
    public int getCount() {
        return count;
    }
}
