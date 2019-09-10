/*
 * Copyright (C) 2018. OpenLattice, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * You can contact the owner of the copyright at support@openlattice.com
 */

package com.openlattice.edm;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.Sets;
import com.openlattice.IdConstants;
import com.openlattice.authorization.securable.AbstractSecurableObject;
import com.openlattice.authorization.securable.SecurableObjectType;
import com.openlattice.client.serialization.SerializationConstants;
import com.openlattice.edm.set.EntitySetFlag;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

/**
 * Describes an entity set and associated metadata, including the active audit record entity set.
 * <p>
 * TODO: Ensure that we are being consistent around how internal sets are accessed and modified. i.e is it okay
 * to return modifiable versions of linked entity sets or should we expose add/remove/update methods. The latter seems
 * like the most explicitly safe thing to do.
 */
public class EntitySet extends AbstractSecurableObject {
    private final UUID                   entityTypeId;
    private final Set<UUID>              linkedEntitySets;
    private final EnumSet<EntitySetFlag> flags;
    private final Set<Integer>           partitions        = new LinkedHashSet<>( 8 );
    private       String                 name;
    private       Set<String>            contacts;
    private       UUID                   organizationId;
    private       int                    partitionsVersion = 0;

    /**
     * Creates an entity set with provided parameters and will automatically generate a UUID if not provided.
     *
     * @param id An optional UUID for the entity set.
     * @param name The name of the entity set.
     * @param title The friendly name for the entity set.
     * @param description A description of the entity set.
     */
    @JsonCreator
    public EntitySet(
            @JsonProperty( SerializationConstants.ID_FIELD ) Optional<UUID> id,
            @JsonProperty( SerializationConstants.ENTITY_TYPE_ID ) UUID entityTypeId,
            @JsonProperty( SerializationConstants.NAME_FIELD ) String name,
            @JsonProperty( SerializationConstants.TITLE_FIELD ) String title,
            @JsonProperty( SerializationConstants.DESCRIPTION_FIELD ) Optional<String> description,
            @JsonProperty( SerializationConstants.CONTACTS ) Set<String> contacts,
            @JsonProperty( SerializationConstants.LINKED_ENTITY_SETS ) Optional<Set<UUID>> linkedEntitySets,
            @JsonProperty( SerializationConstants.ORGANIZATION_ID ) Optional<UUID> organizationId,
            @JsonProperty( SerializationConstants.FLAGS_FIELD ) Optional<EnumSet<EntitySetFlag>> flags,
            @JsonProperty( SerializationConstants.PARTITIONS ) Optional<LinkedHashSet<Integer>> partitions ) {
        super( id, title, description );
        this.linkedEntitySets = linkedEntitySets.orElse( new HashSet<>() );
        this.flags = flags.orElse( EnumSet.of( EntitySetFlag.EXTERNAL ) );
        checkArgument( StringUtils.isNotBlank( name ), "Entity set name cannot be blank." );
        checkArgument( this.linkedEntitySets.isEmpty() || isLinking(),
                "You cannot specify linked entity sets unless this is a linking entity set." );

        // Temporary
        //        checkArgument( contacts != null && !contacts.isEmpty(), "Contacts cannot be blank." );
        this.name = name;
        this.entityTypeId = checkNotNull( entityTypeId );
        this.contacts = Sets.newHashSet( contacts );
        this.organizationId = organizationId.orElse( IdConstants.GLOBAL_ORGANIZATION_ID.getId() );
        partitions.ifPresent( this.partitions::addAll );
    }

    //Constructor for serialization
    public EntitySet(
            UUID id,
            UUID entityTypeId,
            String name,
            String title,
            String description,
            Set<String> contacts,
            Set<UUID> linkedEntitySets,
            UUID organizationId,
            EnumSet<EntitySetFlag> flags,
            LinkedHashSet<Integer> partitions,
            int partitionsVersion
    ) {
        super( id, title, description);
        this.linkedEntitySets = linkedEntitySets;
        this.flags = flags;
        checkArgument( StringUtils.isNotBlank( name ), "Entity set name cannot be blank." );
        checkArgument( this.linkedEntitySets.isEmpty() || isLinking(),
                "You cannot specify linked entity sets unless this is a linking entity set." );

        // Temporary
        //        checkArgument( contacts != null && !contacts.isEmpty(), "Contacts cannot be blank." );
        this.name = name;
        this.entityTypeId = checkNotNull( entityTypeId );
        this.contacts = Sets.newHashSet( contacts );
        this.organizationId = organizationId;
        this.partitions.addAll( partitions );
        this.partitionsVersion = partitionsVersion;
    }

    public EntitySet(
            UUID id,
            UUID entityTypeId,
            String name,
            String title,
            Optional<String> description,
            Set<String> contacts ) {
        this( Optional.of( id ),
                entityTypeId,
                name,
                title,
                description,
                contacts,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty() );
    }

    public EntitySet(
            UUID entityTypeId,
            String name,
            String title,
            Optional<String> description,
            Set<String> contacts ) {
        this( Optional.empty(),
                entityTypeId,
                name,
                title,
                description,
                contacts,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty() );
    }

    public EntitySet(
            UUID entityTypeId,
            String name,
            String title,
            Optional<String> description,
            Set<String> contacts,
            Optional<Set<UUID>> linkedEntitySets,
            Optional<UUID> organizationId,
            Optional<EnumSet<EntitySetFlag>> flags,
            Optional<LinkedHashSet<Integer>> partitions ) {
        this( Optional.empty(),
                entityTypeId,
                name,
                title,
                description,
                contacts,
                linkedEntitySets,
                organizationId,
                flags,
                partitions );
    }

    @JsonProperty( SerializationConstants.ORGANIZATION_ID )
    public UUID getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId( UUID organizationId ) {
        this.organizationId = organizationId;
    }

    @JsonProperty( SerializationConstants.ENTITY_TYPE_ID )
    public UUID getEntityTypeId() {
        return entityTypeId;
    }

    @JsonProperty( SerializationConstants.NAME_FIELD )
    public String getName() {
        return name;
    }

    public void setName( String name ) {
        this.name = name;
    }

    @JsonProperty( SerializationConstants.CONTACTS )
    public Set<String> getContacts() {
        return contacts;
    }

    public void setContacts( Set<String> contacts ) {
        this.contacts = contacts;
    }

    @JsonProperty( SerializationConstants.LINKED_ENTITY_SETS )
    public Set<UUID> getLinkedEntitySets() {
        return linkedEntitySets;
    }

    @JsonProperty( SerializationConstants.FLAGS_FIELD )
    public EnumSet<EntitySetFlag> getFlags() {
        return flags;
    }

    @JsonProperty( SerializationConstants.PARTITIONS )
    public Set<Integer> getPartitions() {
        return partitions;
    }

    @JsonIgnore
    public void setPartitions( Collection<Integer> partitions ) {
        this.partitions.clear();
        addPartitions( partitions );
    }

    @JsonIgnore
    public int getPartitionsVersion() {
        return partitionsVersion;
    }

    public void addFlag( EntitySetFlag flag ) {
        this.flags.add( flag );
    }

    public void removeFlag( EntitySetFlag flag ) {
        this.flags.remove( flag );
    }

    void addPartitions( Collection<Integer> partitions ) {
        this.partitions.addAll( partitions );
        partitionsVersion++;
    }

    @JsonIgnore
    public boolean isExternal() {
        return flags.contains( EntitySetFlag.EXTERNAL );
    }

    @JsonIgnore
    public boolean isLinking() {
        return flags.contains( EntitySetFlag.LINKING );
    }

    @Override public boolean equals( Object o ) {
        if ( this == o ) { return true; }
        if ( o == null || getClass() != o.getClass() ) { return false; }
        if ( !super.equals( o ) ) { return false; }
        EntitySet entitySet = (EntitySet) o;
        return Objects.equals( entityTypeId, entitySet.entityTypeId ) &&
                Objects.equals( linkedEntitySets, entitySet.linkedEntitySets ) &&
                Objects.equals( name, entitySet.name ) &&
                Objects.equals( contacts, entitySet.contacts ) &&
                Objects.equals( organizationId, entitySet.organizationId ) &&
                Objects.equals( flags, entitySet.flags );
    }

    @Override public int hashCode() {
        return Objects.hash( super.hashCode(), entityTypeId, linkedEntitySets, name, contacts, organizationId, flags );
    }

    @Override
    @JsonIgnore
    public SecurableObjectType getCategory() {
        return SecurableObjectType.EntitySet;
    }
}
