package com.openlattice.organization

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.openlattice.authorization.securable.AbstractSecurableObject
import com.openlattice.authorization.securable.SecurableObjectType
import com.openlattice.client.serialization.SerializationConstants
import java.util.*

/**
 * Creates a securable object for an organization's entire database
 *
 * @param id An optional UUID that will be automatically generated if not provided
 * @param name The name of the column
 * @param title A title for the object
 * @param description A description for the object
 * @param tableId The id of the table that contains this column
 * @param externalId A datasource specific unique string identifier for a table.
 * @param organizationId The id of the organization that owns this column
 * @param dataSourceId The id of the data source to which this table belongs.
 * @param dataType The sql data type of this column
 * @param primaryKey A boolean denoting if the column is a primary key of the containing table
 * @param ordinalPosition The index of the column within the containing table
 */

class OrganizationExternalDatabaseColumn(
        @JsonProperty(SerializationConstants.ID_FIELD) id: Optional<UUID>,
        @JsonProperty(SerializationConstants.NAME_FIELD) var name: String,
        @JsonProperty(SerializationConstants.TITLE_FIELD) title: String,
        @JsonProperty(SerializationConstants.DESCRIPTION_FIELD) description: Optional<String>,
        @JsonProperty(SerializationConstants.EXTERNAL_ID) val externalId: String,
        @JsonProperty(SerializationConstants.TABLE_ID) var tableId: UUID,
        @JsonProperty(SerializationConstants.ORGANIZATION_ID) var organizationId: UUID,
        @JsonProperty(SerializationConstants.DATA_SOURCE_ID) val dataSourceId: UUID,
        @JsonProperty(SerializationConstants.DATATYPE_FIELD) var dataType: String,
        @JsonProperty(SerializationConstants.PRIMARY_KEY) var primaryKey: Boolean,
        @JsonProperty(SerializationConstants.ORDINAL_POSITION) var ordinalPosition: Int
) : AbstractSecurableObject(id, title, description) {

    constructor(
            id: UUID,
            name: String,
            title: String,
            description: Optional<String>,
            externalId: String,
            tableId: UUID,
            organizationId: UUID,
            dataSourceId: UUID,
            dataType: String,
            primaryKey: Boolean,
            ordinalPosition: Int
    ) : this(
            Optional.of(id),
            name,
            title,
            description,
            externalId,
            tableId,
            organizationId,
            dataSourceId,
            dataType,
            primaryKey,
            ordinalPosition
    )

    @JsonIgnore
    override fun getCategory(): SecurableObjectType {
        return SecurableObjectType.OrganizationExternalDatabaseColumn
    }
}
