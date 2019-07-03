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

package com.openlattice.mapstores;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.openlattice.authorization.Ace;
import com.openlattice.authorization.AceValue;
import com.openlattice.authorization.Acl;
import com.openlattice.authorization.AclData;
import com.openlattice.authorization.AclKey;
import com.openlattice.authorization.Action;
import com.openlattice.authorization.Permission;
import com.openlattice.authorization.Principal;
import com.openlattice.authorization.PrincipalType;
import com.openlattice.authorization.securable.AbstractSecurableObject;
import com.openlattice.authorization.securable.AbstractSecurableType;
import com.openlattice.authorization.securable.SecurableObjectType;
import com.openlattice.data.EntityDataKey;
import com.openlattice.data.EntityKey;
import com.openlattice.edm.EdmDetails;
import com.openlattice.edm.EntitySet;
import com.openlattice.edm.requests.MetadataUpdate;
import com.openlattice.edm.type.Analyzer;
import com.openlattice.edm.type.AssociationType;
import com.openlattice.edm.type.EntityType;
import com.openlattice.edm.type.PropertyType;
import com.openlattice.organization.Organization;
import com.openlattice.organization.roles.Role;
import com.openlattice.postgres.IndexType;
import com.openlattice.requests.PermissionsRequestDetails;
import com.openlattice.requests.Request;
import com.openlattice.requests.RequestStatus;
import com.openlattice.requests.Status;
import com.openlattice.search.PersistentSearchNotificationType;
import com.openlattice.search.requests.PersistentSearch;
import com.openlattice.search.requests.SearchConstraints;
import com.openlattice.search.requests.SearchDetails;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.FullQualifiedName;

@SuppressFBWarnings( value = "SECPR", justification = "Only used for testing." )
public final class TestDataFactory {
    private static final SecurableObjectType[] securableObjectTypes = SecurableObjectType.values();
    private static final Permission[]          permissions          = Permission.values();
    private static final Action[]              actions              = Action.values();
    private static final RequestStatus[]       requestStatuses      = RequestStatus.values();
    private static final Analyzer[]            analyzers            = Analyzer.values();
    private static final IndexType[]           INDEX_TYPES          = IndexType.values();
    private static final Random                r                    = new Random();

    private TestDataFactory() {
    }

    public static EntityDataKey entityDataKey() {
        return new EntityDataKey( UUID.randomUUID(), UUID.randomUUID() );
    }

    public static Long longValue() {
        return r.nextLong();
    }

    public static Integer integer() {
        return r.nextInt();
    }

    public static Principal userPrincipal() {
        return new Principal( PrincipalType.USER, RandomStringUtils.randomAlphanumeric( 10 ) );
    }

    public static Principal rolePrincipal() {
        return new Principal( PrincipalType.ROLE, RandomStringUtils.randomAlphanumeric( 5 ) );
    }

    public static EntityType entityType( PropertyType... keys ) {
        return childEntityType( null, null, keys );
    }

    public static EntityType entityType( SecurableObjectType category, PropertyType... keys ) {
        return childEntityType( null, category, keys );
    }

    public static EntityType entityType(
            Optional<FullQualifiedName> fqn,
            SecurableObjectType category,
            PropertyType... keys ) {
        return childEntityTypeWithPropertyType(
                null,
                fqn,
                ImmutableSet.of( UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID() ),
                category,
                keys );
    }

    public static EntityType childEntityType( UUID parentId, SecurableObjectType category, PropertyType... keys ) {
        return childEntityTypeWithPropertyType( parentId,
                Optional.empty(),
                ImmutableSet.of( UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID() ),
                category,
                keys );
    }

    public static EntityType childEntityTypeWithPropertyType(
            UUID parentId,
            Optional<FullQualifiedName> fqn,
            Set<UUID> propertyTypes,
            SecurableObjectType category,
            PropertyType... keys ) {
        LinkedHashSet<UUID> k = keys.length > 0
                ? Arrays.asList( keys ).stream().map( PropertyType::getId )
                .collect( Collectors.toCollection( Sets::newLinkedHashSet ) )
                : Sets.newLinkedHashSet( Arrays.asList( UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID() ) );
        var propertyTags = LinkedHashMultimap.<UUID, String>create();

        for ( UUID id : k ) {
            propertyTags.put( id, "PRIMARY KEY TAG" );
        }

        SecurableObjectType entityTypeCategory = ( category == null ) ? SecurableObjectType.EntityType : category;

        return new EntityType(
                UUID.randomUUID(),
                fqn.orElseGet( TestDataFactory::fqn ),
                RandomStringUtils.randomAlphanumeric( 5 ),
                Optional.of( RandomStringUtils.randomAlphanumeric( 5 ) ),
                ImmutableSet.of( fqn(), fqn(), fqn() ),
                k,
                Sets.newLinkedHashSet( Sets
                        .union( k, propertyTypes ) ),
                propertyTags,
                Optional.ofNullable( parentId ),
                Optional.of( entityTypeCategory ),
                Optional.of( RandomUtils.nextInt( 1, 5 ) ) );
    }

    public static AssociationType associationType( PropertyType... keys ) {
        EntityType et = entityType( SecurableObjectType.AssociationType, keys );
        return new AssociationType(
                Optional.of( et ),
                Sets.newLinkedHashSet( Arrays.asList( UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID() ) ),
                Sets.newLinkedHashSet( Arrays.asList( UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID() ) ),
                false );
    }

    public static AssociationType associationTypeWithProperties( Set<UUID> propertyTypes, PropertyType... keys ) {
        if ( propertyTypes.size() == 0 ) { return associationType( keys ); }
        EntityType et = childEntityTypeWithPropertyType(
                null,
                Optional.empty(),
                propertyTypes,
                SecurableObjectType.AssociationType,
                keys );
        UUID ptId = propertyTypes.iterator().next();
        return new AssociationType(
                Optional.of( et ),
                Sets.newLinkedHashSet( Arrays.asList( ptId ) ),
                Sets.newLinkedHashSet( Arrays.asList( ptId ) ),
                false );
    }

    public static FullQualifiedName fqn() {
        return new FullQualifiedName(
                RandomStringUtils.randomAlphanumeric( 5 ),
                RandomStringUtils.randomAlphanumeric( 5 ) );
    }

    public static String email() {
        return RandomStringUtils.randomAlphanumeric( 5 ) + "@" + RandomStringUtils.randomAlphanumeric( 5 ) + ".com";
    }

    public static String name() {
        return RandomStringUtils.randomAlphanumeric( 5 );
    }

    public static EntitySet entitySet() {
        return entitySetWithType( UUID.randomUUID() );
    }

    public static EntitySet entitySetWithType( UUID entityTypeId ) {
        return new EntitySet(
                UUID.randomUUID(),
                entityTypeId,
                RandomStringUtils.randomAlphanumeric( 5 ),
                RandomStringUtils.randomAlphanumeric( 5 ),
                Optional.of( RandomStringUtils.randomAlphanumeric( 5 ) ),
                ImmutableSet.of( email(), email() ) );
    }

    public static PropertyType datePropertyType() {
        return new PropertyType(
                UUID.randomUUID(),
                fqn(),
                RandomStringUtils.randomAlphanumeric( 5 ),
                Optional.of( RandomStringUtils.randomAlphanumeric( 5 ) ),
                ImmutableSet.of(),
                EdmPrimitiveTypeKind.Date,
                Optional.of( r.nextBoolean() ),
                Optional.of( Analyzer.STANDARD ),
                Optional.of( INDEX_TYPES[ r.nextInt( INDEX_TYPES.length ) ] ) );
    }

    public static PropertyType dateTimePropertyType() {
        return new PropertyType(
                UUID.randomUUID(),
                fqn(),
                RandomStringUtils.randomAlphanumeric( 5 ),
                Optional.of( RandomStringUtils.randomAlphanumeric( 5 ) ),
                ImmutableSet.of(),
                EdmPrimitiveTypeKind.DateTimeOffset,
                Optional.of( r.nextBoolean() ),
                Optional.of( Analyzer.STANDARD ),
                Optional.of( INDEX_TYPES[ r.nextInt( INDEX_TYPES.length ) ] ) );
    }

    public static PropertyType propertyType() {
        return propertyType( INDEX_TYPES[ r.nextInt( INDEX_TYPES.length ) ] );
    }

    public static PropertyType propertyType( IndexType postgresIndexType ) {
        return new PropertyType(
                UUID.randomUUID(),
                fqn(),
                RandomStringUtils.randomAlphanumeric( 5 ),
                Optional.of( RandomStringUtils.randomAlphanumeric( 5 ) ),
                ImmutableSet.of(),
                EdmPrimitiveTypeKind.String,
                Optional.of( r.nextBoolean() ),
                Optional.of( analyzers[ r.nextInt( analyzers.length ) ] ),
                Optional.of( postgresIndexType ) );
    }

    public static PropertyType binaryPropertyType() {
        return new PropertyType(
                UUID.randomUUID(),
                fqn(),
                RandomStringUtils.randomAlphanumeric( 5 ),
                Optional.of( RandomStringUtils.randomAlphanumeric( 5 ) ),
                ImmutableSet.of(),
                EdmPrimitiveTypeKind.Binary,
                Optional.of( r.nextBoolean() ),
                Optional.of( analyzers[ r.nextInt( analyzers.length ) ] ),
                Optional.of( IndexType.NONE );
    }

    public static Organization organization() {
        return new Organization(
                Optional.of( UUID.randomUUID() ),
                organizationPrincipal(),
                RandomStringUtils.randomAlphanumeric( 5 ),
                Optional.of( RandomStringUtils.randomAlphanumeric( 5 ) ),
                ImmutableSet.of( RandomStringUtils.randomAlphanumeric( 5 ), RandomStringUtils.randomAlphanumeric( 5 ) ),
                ImmutableSet.of( userPrincipal() ),
                ImmutableSet.of( role() ),
                ImmutableSet.of( UUID.randomUUID() ),
                Optional.empty(),
                Optional.of( Lists.newArrayList( 1, 2, 3 ) ) );
    }

    public static Principal organizationPrincipal() {
        return new Principal( PrincipalType.ORGANIZATION, RandomStringUtils.randomAlphanumeric( 10 ) );
    }

    public static Role role() {
        return new Role(
                Optional.of( UUID.randomUUID() ),
                UUID.randomUUID(),
                rolePrincipal(),
                RandomStringUtils.randomAlphanumeric( 5 ),
                Optional.of( RandomStringUtils.randomAlphanumeric( 5 ) ) );
    }

    public static Role role( UUID organizationId ) {
        return new Role(
                Optional.of( UUID.randomUUID() ),
                organizationId,
                rolePrincipal(),
                RandomStringUtils.randomAlphanumeric( 5 ),
                Optional.of( RandomStringUtils.randomAlphanumeric( 5 ) ) );
    }

    public static SecurableObjectType securableObjectType() {
        return securableObjectTypes[ r.nextInt( securableObjectTypes.length ) ];
    }

    public static EnumSet<Permission> permissions() {
        return Arrays.asList( permissions )
                .stream()
                .filter( elem -> r.nextBoolean() )
                .collect( Collectors.toCollection( () -> EnumSet.noneOf( Permission.class ) ) );
    }

    public static EnumSet<Permission> nonEmptyPermissions() {
        EnumSet<Permission> ps = permissions();
        while ( ps.isEmpty() ) {
            ps = permissions();
        }
        return ps;
    }

    public static Ace ace() {
        return new Ace( userPrincipal(), permissions() );
    }

    public static AceValue aceValue() {
        return new AceValue(
                permissions(),
                securableObjectType()
        );
    }

    public static Acl acl() {
        return new Acl(
                ImmutableList.of( UUID.randomUUID(), UUID.randomUUID() ),
                ImmutableList.of( ace(), ace(), ace(), ace() ) );
    }

    public static AclData aclData() {
        return new AclData(
                acl(),
                actions[ r.nextInt( actions.length ) ] );
    }

    public static AclKey aclKey() {
        return new AclKey( UUID.randomUUID(), UUID.randomUUID() );
    }

    public static EdmDetails edmDetails() {
        Set<PropertyType> pts = ImmutableSet.of( propertyType(), propertyType(), propertyType() );
        Set<EntityType> ets = ImmutableSet.of( entityType(), entityType(), entityType() );
        Set<EntitySet> ess = ImmutableSet.of( entitySet() );
        return new EdmDetails(
                pts.stream().collect( Collectors.toMap( AbstractSecurableType::getId, v -> v ) ),
                ets.stream().collect( Collectors.toMap( AbstractSecurableType::getId, v -> v ) ),
                ess.stream().collect( Collectors.toMap( AbstractSecurableObject::getId, v -> v ) ) );
    }

    public static Map<UUID, EnumSet<Permission>> aclChildPermissions() {
        Map<UUID, EnumSet<Permission>> permissions = new HashMap<>();
        permissions.put( UUID.randomUUID(), EnumSet.of( Permission.READ ) );
        permissions.put( UUID.randomUUID(), EnumSet.of( Permission.WRITE ) );
        permissions.put( UUID.randomUUID(), EnumSet.of( Permission.READ, Permission.WRITE ) );
        return permissions;
    }

    public static PermissionsRequestDetails unresolvedPRDetails() {
        return new PermissionsRequestDetails( aclChildPermissions(), RequestStatus.SUBMITTED );
    }

    public static PermissionsRequestDetails resolvedPRDetails() {
        return new PermissionsRequestDetails( aclChildPermissions(), RequestStatus.APPROVED );
    }

    public static RequestStatus requestStatus() {
        return requestStatuses[ r.nextInt( requestStatuses.length ) ];
    }

    public static Request request() {
        return new Request(
                TestDataFactory.aclKey(),
                TestDataFactory.permissions(),
                Optional.of( "Requesting for this object because RandomStringUtils.randomAlphanumeric( 5 )" ) );
    }

    public static Status status() {
        return new Status(
                request(),
                TestDataFactory.userPrincipal(),
                TestDataFactory.requestStatus() );
    }

    public static Map<UUID, Map<UUID, Set<Object>>> randomStringEntityData(
            int numberOfEntries,
            Set<UUID> propertyIds ) {
        Map<UUID, Map<UUID, Set<Object>>> data = new HashMap<>();
        for ( int i = 0; i < numberOfEntries; i++ ) {
            UUID entityId = UUID.randomUUID();
            SetMultimap<UUID, Object> entity = HashMultimap.create();
            for ( UUID propertyId : propertyIds ) {
                entity.put( propertyId, RandomStringUtils.randomAlphanumeric( 5 ) );
            }

            data.put( entityId, Multimaps.asMap( entity ) );
        }
        return data;
    }

    public static EntityKey entityKey() {
        return entityKey( UUID.randomUUID() );
    }

    public static EntityKey entityKey( UUID entitySetId ) {
        return new EntityKey( entitySetId, RandomStringUtils.random( 10 ).replace( Character.MIN_VALUE, '0' ) );
    }

    public static PropertyType propertyType( EdmPrimitiveTypeKind type ) {
        switch ( type ) {
            case String:
                return new PropertyType(
                        UUID.randomUUID(),
                        fqn(),
                        RandomStringUtils.randomAlphanumeric( 5 ),
                        Optional.of( RandomStringUtils.randomAlphanumeric( 5 ) ),
                        ImmutableSet.of(),
                        type,
                        Optional.of( r.nextBoolean() ),
                        Optional.of( analyzers[ r.nextInt( analyzers.length ) ] ),
                        Optional.of( INDEX_TYPES[ r.nextInt( INDEX_TYPES.length ) ] ) );
            default:
                return new PropertyType(
                        UUID.randomUUID(),
                        fqn(),
                        RandomStringUtils.randomAlphanumeric( 5 ),
                        Optional.of( RandomStringUtils.randomAlphanumeric( 5 ) ),
                        ImmutableSet.of(),
                        type,
                        Optional.of( r.nextBoolean() ),
                        Optional.empty(),
                        Optional.of( INDEX_TYPES[ r.nextInt( INDEX_TYPES.length ) ] ) );
        }
    }

    public static EntityType entityTypesFromKeyAndTypes( PropertyType key, PropertyType... propertyTypes ) {
        final var propertyTags = LinkedHashMultimap.<UUID, String>create();
        propertyTags.put( key.getId(), "PRIMARY KEY TAG" );
        return new EntityType( UUID.randomUUID(),
                fqn(),
                RandomStringUtils.randomAlphanumeric( 5 ),
                Optional.empty(),
                ImmutableSet.of(),
                Stream.of( key ).map( PropertyType::getId )
                        .collect( Collectors.toCollection( LinkedHashSet::new ) ),
                Stream.concat( Stream.of( key ), Stream.of( propertyTypes ) ).map( PropertyType::getId )
                        .collect( Collectors.toCollection( LinkedHashSet::new ) ),
                propertyTags,
                Optional.empty(),
                Optional.empty(),
                Optional.empty() );
    }

    public static Map<UUID, Map<UUID, Set<Object>>> randomBinaryData(
            int numberOfEntries,
            UUID keyType,
            UUID binaryType ) {
        Map<UUID, Map<UUID, Set<Object>>> data = new HashMap<>();
        for ( int i = 0; i < numberOfEntries; i++ ) {
            data.put( UUID.randomUUID(), randomElement( keyType, binaryType ) );
        }

        return data;
    }

    public static Map<UUID, Set<Object>> randomElement( UUID keyType, UUID binaryType ) {
        SetMultimap<UUID, Object> element = HashMultimap.create();
        element.put( keyType, RandomStringUtils.random( 5 ) );
        element.put( binaryType,
                ImmutableMap.of( "content-type", "application/octet-stream", "data", RandomUtils.nextBytes( 128 ) ) );
        element.put( binaryType,
                ImmutableMap.of( "content-type", "application/octet-stream", "data", RandomUtils.nextBytes( 128 ) ) );
        element.put( binaryType,
                ImmutableMap.of( "content-type", "application/octet-stream", "data", RandomUtils.nextBytes( 128 ) ) );
        return Multimaps.asMap( element );
    }

    public static MetadataUpdate metadataUpdate() {
        final var propertyTags = LinkedHashMultimap.<UUID, String>create();
        propertyTags.put( UUID.randomUUID(), "SOME PROPERTY TAG" );
        return new MetadataUpdate( Optional.of( RandomStringUtils.randomAlphanumeric( 5 ) ),
                Optional.of( RandomStringUtils.randomAlphanumeric( 5 ) ),
                Optional.empty(),
                Optional.of( new HashSet<>( Arrays.asList( RandomStringUtils.randomAlphanumeric( 3 ),
                        RandomStringUtils.randomAlphanumeric( 5 ) ) ) ),
                Optional.of( fqn() ),
                Optional.of( r.nextBoolean() ),
                Optional.empty(),
                Optional.of( RandomStringUtils.randomAlphanumeric( 4 ) ),
                Optional.of( propertyTags ),
                Optional.of( UUID.randomUUID() ),
                Optional.of(new LinkedHashSet<>( Arrays.asList(1,2,3,4) )) );
    }

    public static SearchDetails searchDetails() {
        return new SearchDetails( RandomStringUtils.randomAlphanumeric( 10 ), UUID.randomUUID(), r.nextBoolean() );
    }

    public static SearchConstraints simpleSearchConstraints() {
        return SearchConstraints.simpleSearchConstraints( new UUID[] { UUID.randomUUID() },
                r.nextInt( 1000 ),
                r.nextInt( 1000 ),
                RandomStringUtils.randomAlphanumeric( 10 ) );
    }

    public static PersistentSearch persistentSearch() {
        return new PersistentSearch( Optional.empty(),
                Optional.empty(),
                OffsetDateTime.now(),
                PersistentSearchNotificationType.ALPR_ALERT,
                simpleSearchConstraints(),
                ImmutableMap.of() );
    }

}