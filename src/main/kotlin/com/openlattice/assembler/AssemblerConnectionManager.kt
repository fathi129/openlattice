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

package com.openlattice.assembler

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.MetricRegistry.name
import com.codahale.metrics.Timer
import com.google.common.collect.Sets
import com.google.common.eventbus.EventBus
import com.google.common.eventbus.Subscribe
import com.openlattice.assembler.PostgresDatabases.Companion.buildOrganizationDatabaseName
import com.openlattice.assembler.PostgresRoles.Companion.buildOrganizationUserId
import com.openlattice.assembler.PostgresRoles.Companion.buildPostgresRoleName
import com.openlattice.assembler.PostgresRoles.Companion.buildPostgresUsername
import com.openlattice.authorization.*
import com.openlattice.data.storage.MetadataOption
import com.openlattice.data.storage.entityKeyIdColumnsList
import com.openlattice.data.storage.linkingEntityKeyIdColumnsList
import com.openlattice.directory.MaterializedViewAccount
import com.openlattice.edm.EntitySet
import com.openlattice.edm.type.PropertyType
import com.openlattice.organization.OrganizationEntitySetFlag
import com.openlattice.organization.roles.Role
import com.openlattice.organizations.HazelcastOrganizationService
import com.openlattice.organizations.roles.SecurePrincipalsManager
import com.openlattice.postgres.DataTables.quote
import com.openlattice.postgres.PostgresColumn.*
import com.openlattice.postgres.PostgresTable.*
import com.openlattice.postgres.ResultSetAdapters
import com.openlattice.postgres.streams.PostgresIterable
import com.openlattice.postgres.streams.StatementHolder
import com.openlattice.principals.RoleCreatedEvent
import com.openlattice.principals.UserCreatedEvent
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.Statement
import java.util.*
import java.util.function.Function
import java.util.function.Supplier

private val logger = LoggerFactory.getLogger(AssemblerConnectionManager::class.java)

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
@Component
class AssemblerConnectionManager(
        private val assemblerConfiguration: AssemblerConfiguration,
        private val hds: HikariDataSource,
        private val securePrincipalsManager: SecurePrincipalsManager,
        private val authorizationManager: AuthorizationManager,
        private val edmAuthorizationHelper: EdmAuthorizationHelper,
        private val organizations: HazelcastOrganizationService,
        private val dbCredentialService: DbCredentialService,
        eventBus: EventBus,
        metricRegistry: MetricRegistry
) {

    private val target: HikariDataSource = connect("postgres")
    private val materializeAllTimer: Timer =
            metricRegistry.timer(name(AssemblerConnectionManager::class.java, "materializeAll"))
    private val materializeEntitySetsTimer: Timer =
            metricRegistry.timer(name(AssemblerConnectionManager::class.java, "materializeEntitySets"))
    private val materializeEdgesTimer: Timer =
            metricRegistry.timer(name(AssemblerConnectionManager::class.java, "materializeEdges"))

    init {
        eventBus.register(this)
    }

    companion object {
        @JvmStatic
        val MATERIALIZED_VIEWS_SCHEMA = "openlattice"
        @JvmStatic
        val PRODUCTION_FOREIGN_SCHEMA = "prod"
        @JvmStatic
        val PRODUCTION_VIEWS_SCHEMA = "olviews"  //This is the scheme that is created on production server to hold entity set views
        @JvmStatic
        val PUBLIC_SCHEMA = "public"

        @JvmStatic
        val PRODUCTION_SERVER = "olprod"
    }

    fun connect(dbname: String): HikariDataSource {
        return connect(dbname, assemblerConfiguration.server.clone() as Properties)
    }

    fun connect(dbname: String, account: MaterializedViewAccount): HikariDataSource {
        val config = assemblerConfiguration.server.clone() as Properties
        config["username"] = account.username
        config["password"] = account.credential

        return connect(dbname, config)
    }

    fun connect(dbname: String, config: Properties): HikariDataSource {
        config.computeIfPresent("jdbcUrl") { _, jdbcUrl ->
            "${(jdbcUrl as String).removeSuffix(
                    "/"
            )}/$dbname" + if (assemblerConfiguration.ssl) {
                "?sslmode=required"
            } else {
                ""
            }
        }
        return HikariDataSource(HikariConfig(config))
    }

    @Subscribe
    fun handleUserCreated(userCreatedEvent: UserCreatedEvent) {
        createUnprivilegedUser(userCreatedEvent.user)
    }

    @Subscribe
    fun handleRoleCreated(roleCreatedEvent: RoleCreatedEvent) {
        createRole(roleCreatedEvent.role)
    }

    /**
     * Creates a private organization database that can be used for uploading data using launchpad.
     * Also sets up foreign data wrapper using assembler in assembler so that materialized views of data can be
     * provided.
     */
    fun createOrganizationDatabase(organizationId: UUID) {
        val organization = organizations.getOrganization(organizationId)
        val dbname = buildOrganizationDatabaseName(organizationId)
        createOrganizationDatabase(organizationId, dbname)

        connect(dbname).use { datasource ->
            configureRolesInDatabase(datasource, securePrincipalsManager)
            createOpenlatticeSchema(datasource)

            datasource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                            "ALTER ROLE ${assemblerConfiguration.server["username"]} SET search_path to $PRODUCTION_FOREIGN_SCHEMA,$MATERIALIZED_VIEWS_SCHEMA,$PUBLIC_SCHEMA"
                    )
                }
            }

            addMembersToOrganization(dbname, datasource, organization.members)

            createForeignServer(datasource)
//                materializePropertyTypes(datasource)
//                materialzieEntityTypes(datasource)
        }
    }

    fun addMembersToOrganization(dbName: String, dataSource: HikariDataSource, members: Set<Principal>) {
        logger.info("Configuring members for organization database {}", dbName)
        val validUserPrincipals = members
                .filter {
                    it.id != SystemRole.OPENLATTICE.principal.id && it.id != SystemRole.ADMIN.principal.id
                }
                .filter {
                    val principalExists = securePrincipalsManager.principalExists(it)
                    if (!principalExists) {
                        logger.warn("Principal {} does not exists", it)
                    }
                    return@filter principalExists
                } //There are some bad principals in the member list some how-- probably from testing.

        val securablePrincipalsToAdd = securePrincipalsManager.getSecurablePrincipals(validUserPrincipals)
        configureNewUsersInDatabase(dbName, dataSource, securablePrincipalsToAdd)
    }

    fun configureNewUsersInDatabase(
            dbName: String,
            dataSource: HikariDataSource,
            principals: Collection<SecurablePrincipal>
    ) {
        if (principals.isNotEmpty()) {
            val userNames = principals.map { quote(buildPostgresUsername(it)) }
            configureUsersInDatabase(dataSource, dbName, userNames)
        }
    }

    fun removeMembersFromOrganization(
            dbName: String,
            dataSource: HikariDataSource,
            principals: Collection<SecurablePrincipal>
    ) {
        if (principals.isNotEmpty()) {
            val userNames = principals.map { quote(buildPostgresUsername(it)) }
            revokeConnectAndSchemaUsage(dataSource, dbName, userNames)
        }
    }

    private fun createOrganizationDatabase(organizationId: UUID, dbname: String) {
        val db = quote(dbname)
        val dbRole = "${dbname}_role"
        val unquotedDbAdminUser = buildOrganizationUserId(organizationId)
        val dbOrgUser = quote(unquotedDbAdminUser)
        val dbAdminUserPassword = dbCredentialService.getOrCreateUserCredentials(unquotedDbAdminUser)
                ?: dbCredentialService.getDbCredential(unquotedDbAdminUser)
        val createOrgDbRole = createRoleIfNotExistsSql(dbRole)
        val createOrgDbUser = createUserIfNotExistsSql(unquotedDbAdminUser, dbAdminUserPassword)

        val grantRole = "GRANT ${quote(dbRole)} TO $dbOrgUser"
        val createDb = " CREATE DATABASE $db"
        val revokeAll = "REVOKE ALL ON DATABASE $db FROM $PUBLIC_SCHEMA"

        //We connect to default db in order to do initial db setup

        target.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(createOrgDbRole)
                statement.execute(createOrgDbUser)
                statement.execute(grantRole)
                if (!exists(dbname)) {
                    statement.execute(createDb)
                    //Allow usage of schema public
                    //statement.execute("REVOKE USAGE ON SCHEMA $PUBLIC_SCHEMA FROM ${quote(dbOrgUser)}")
                    statement.execute("GRANT ${MEMBER_ORG_DATABASE_PERMISSIONS.joinToString(", ")} " +
                            "ON DATABASE $db TO $dbOrgUser")
                }
                statement.execute(revokeAll)
                return@use
            }
        }
    }

    fun dropOrganizationDatabase(organizationId: UUID) {
        dropOrganizationDatabase(organizationId, buildOrganizationDatabaseName(organizationId))
    }

    fun dropOrganizationDatabase(organizationId: UUID, dbname: String) {
        val db = quote(dbname)
        val dbRole = quote("${dbname}_role")
        val unquotedDbAdminUser = buildOrganizationUserId(organizationId)
        val dbAdminUser = quote(unquotedDbAdminUser)

        val dropDb = " DROP DATABASE $db"
        val dropDbUser = "DROP ROLE $dbAdminUser"
        //TODO: If we grant this role to other users, we need to make sure we drop it
        val dropDbRole = "DROP ROLE $dbRole"


        //We connect to default db in order to do initial db setup

        target.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(dropDb)
                statement.execute(dropDbUser)
                statement.execute(dropDbRole)
                return@use
            }
        }
    }

    fun materializeEdges(organizationId: UUID, entitySetIds: Set<UUID>) {
        logger.info("Materializing edges in organization $organizationId database")

        connect(buildOrganizationDatabaseName(organizationId)).use { datasource ->
            // re-import foreign view edges before creating materialized view
            updatePublicTables(datasource, setOf(EDGES.name))
            materializeEdges(datasource, entitySetIds)
        }
    }

    /**
     * The reason we use an "IN" the query for this function is that only entity sets that have been materialized
     * should have their edges materialized.
     * For every edge materialization we use every entity set, that has been materialized within an organization.
     */
    private fun materializeEdges(datasource: HikariDataSource, entitySetIds: Set<UUID>) {
        materializeEdgesTimer.time().use {
            datasource.connection.use { connection ->
                connection.createStatement().use { stmt ->
                    val clause = entitySetIds.joinToString { entitySetId -> "'$entitySetId'" }

                    val tableName = "$MATERIALIZED_VIEWS_SCHEMA.${EDGES.name}"
                    stmt.execute("DROP MATERIALIZED VIEW IF EXISTS $tableName")
                    stmt.execute(
                            "CREATE MATERIALIZED VIEW IF NOT EXISTS $tableName AS " +
                                    "SELECT * FROM $PRODUCTION_FOREIGN_SCHEMA.${EDGES.name} " +
                                    "WHERE ${SRC_ENTITY_SET_ID.name} IN ($clause) " +
                                    "OR ${DST_ENTITY_SET_ID.name} IN ($clause) " +
                                    "OR ${EDGE_ENTITY_SET_ID.name} IN ($clause) "
                    )
                    // TODO: when roles are ready grant select to member role of org
                    val selectGrantedResults = grantSelectForEdges(stmt, tableName, entitySetIds)

                    logger.info("Granted select for ${selectGrantedResults.filter { it >= 0 }.size} users/roles " +
                            "on materialized view $tableName")
                    return@use
                }
            }
        }
    }

    fun materializeEntitySets(
            organizationId: UUID,
            authorizedPropertyTypesByEntitySet: Map<EntitySet, Map<UUID, PropertyType>>
    ): Map<UUID, Set<OrganizationEntitySetFlag>> {
        logger.info("Materializing entity sets ${authorizedPropertyTypesByEntitySet.keys.map { it.id }} in " +
                "organization $organizationId database.")

        materializeAllTimer.time().use {
            connect(buildOrganizationDatabaseName(organizationId)).use { datasource ->
                materializeEntitySets(datasource, authorizedPropertyTypesByEntitySet)
            }
            return authorizedPropertyTypesByEntitySet
                    .map { it.key.id to EnumSet.of(OrganizationEntitySetFlag.MATERIALIZED) }
                    .toMap()
        }
    }


    private fun materializeEntitySets(
            datasource: HikariDataSource,
            authorizedPropertyTypesByEntitySet: Map<EntitySet, Map<UUID, PropertyType>>
    ) {
        authorizedPropertyTypesByEntitySet.forEach { entitySet, authorizedPropertyTypes ->
            // re-import materialized view of entity set
            updateProductionViewTables(datasource, setOf(entitySetIdTableName(entitySet.id)))
            // (re)-import property_types and entity_types in case of property type change
            updatePublicTables(datasource, setOf(ENTITY_TYPES.name, PROPERTY_TYPES.name))

            materialize(datasource, entitySet, authorizedPropertyTypes)
        }
    }

    /**
     * Materializes an entity set on atlas.
     */
    private fun materialize(
            datasource: HikariDataSource,
            entitySet: EntitySet,
            authorizedPropertyTypes: Map<UUID, PropertyType>) {
        materializeEntitySetsTimer.time().use {

            val selectColumns = ((if (entitySet.isLinking) linkingEntityKeyIdColumnsList else entityKeyIdColumnsList) +
                    ResultSetAdapters.mapMetadataOptionToPostgresColumn(MetadataOption.ENTITY_KEY_IDS) +
                    authorizedPropertyTypes.values.map { quote(it.type.fullQualifiedNameAsString) })
                    .joinToString(",")

            val sql = "SELECT $selectColumns FROM $PRODUCTION_FOREIGN_SCHEMA.${entitySetIdTableName(entitySet.id)} "

            val tableName = "$MATERIALIZED_VIEWS_SCHEMA.${quote(entitySet.name)}"

            datasource.connection.use { connection ->
                val dropMaterializedEntitySet = "DROP MATERIALIZED VIEW IF EXISTS $tableName"
                val createMaterializedViewSql = "CREATE MATERIALIZED VIEW IF NOT EXISTS $tableName AS $sql"

                logger.info("Executing create materialize view sql: {}", createMaterializedViewSql)
                connection.createStatement().use { stmt ->
                    stmt.execute(dropMaterializedEntitySet)
                    stmt.execute(createMaterializedViewSql)
                }
                //Next we need to grant select on materialize view to everyone who has permission.
                val selectGrantedResults = grantSelectForEntitySet(
                        connection,
                        tableName,
                        entitySet,
                        authorizedPropertyTypes
                )
                logger.info("Granted select for ${selectGrantedResults.filter { it >= 0 }.size} users/roles " +
                        "on materialized view $tableName")
            }
        }
    }

    private fun grantSelectForEntitySet(
            connection: Connection,
            tableName: String,
            entitySet: EntitySet,
            materializedPropertyTypes: Map<UUID, PropertyType>
    ): IntArray {

        // collect all principals of type user, role, which have read access on entityset
        val authorizedPrincipals = securePrincipalsManager
                .getAuthorizedPrincipalsOnSecurableObject(AclKey(entitySet.id), EdmAuthorizationHelper.READ_PERMISSION)
                .filter { it.type == PrincipalType.USER || it.type == PrincipalType.ROLE }
                .toSet()

        val propertyCheckFunction: (Principal) -> (Map<UUID, PropertyType>) = if (entitySet.isLinking) {
            { principal ->
                // only grant select on authorized columns if principal has read access on every normal entity set
                // within the linking entity set
                if (entitySet.linkedEntitySets.all {
                            authorizationManager.checkIfHasPermissions(
                                    AclKey(it),
                                    setOf(principal),
                                    EdmAuthorizationHelper.READ_PERMISSION)
                        }) {
                    edmAuthorizationHelper.getAuthorizedPropertyTypes(
                            entitySet.id, EdmAuthorizationHelper.READ_PERMISSION, setOf(principal))
                            .filter { materializedPropertyTypes.keys.contains(it.key) }
                } else {
                    mapOf()
                }
            }
        } else {
            { principal ->
                edmAuthorizationHelper.getAuthorizedPropertiesOnNormalEntitySets(
                        setOf(entitySet.id), EdmAuthorizationHelper.READ_PERMISSION, setOf(principal))[entitySet.id]!!
                        .filter { materializedPropertyTypes.keys.contains(it.key) }
            }
        }

        // collect all authorized property types for principals which have read access on entity set
        val authorizedPropertiesOfPrincipal = authorizedPrincipals
                .map { it to propertyCheckFunction(it).values }
                .toMap()

        // prepare batch queries
        return connection.createStatement().use { stmt ->
            authorizedPropertiesOfPrincipal.forEach { principal, propertyTypes ->
                val columns = (if (entitySet.isLinking) linkingEntityKeyIdColumnsList else entityKeyIdColumnsList) +
                        ResultSetAdapters.mapMetadataOptionToPostgresColumn(MetadataOption.ENTITY_KEY_IDS) +
                        propertyTypes.map { it.type.fullQualifiedNameAsString }
                val grantSelectSql = grantSelectSql(tableName, principal, columns)

                stmt.addBatch(grantSelectSql)
            }
            stmt.executeBatch()
        }
    }

    fun grantSelectForEdges(stmt: Statement, tableName: String, entitySetIds: Set<UUID>): IntArray {
        val permissions = EnumSet.of(Permission.READ)
        // collect all principals of type user, role, which have read access on entityset
        val authorizedPrincipals = entitySetIds.fold(mutableSetOf<Principal>()) { acc, entitySetId ->
            Sets.union(acc,
                    securePrincipalsManager.getAuthorizedPrincipalsOnSecurableObject(AclKey(entitySetId), permissions)
                            .filter { it.type == PrincipalType.USER || it.type == PrincipalType.ROLE }
                            .toSet())
        }

        authorizedPrincipals.forEach {
            val grantSelectSql = grantSelectSql(tableName, it, listOf())
            stmt.addBatch(grantSelectSql)
        }

        return stmt.executeBatch()
    }

    /**
     * Build grant select sql statement for a given table and user with column level security.
     * If properties (columns) are left empty, it will grant select on whole table.
     */
    private fun grantSelectSql(
            entitySetTableName: String,
            principal: Principal,
            columns: List<String>
    ): String {
        val postgresUserName = if (principal.type == PrincipalType.USER) {
            buildPostgresUsername(securePrincipalsManager.getPrincipal(principal.id))
        } else {
            buildPostgresRoleName(securePrincipalsManager.lookupRole(principal))
        }

        val onProperties = if (columns.isEmpty()) {
            ""
        } else {
            "(${columns.joinToString(",") { quote(it) }}) "
        }

        return "GRANT SELECT $onProperties " +
                "ON $entitySetTableName " +
                "TO ${quote(postgresUserName)}"
    }

    /**
     * Synchronize data changes in entity set materialized view in organization database
     */
    fun refreshEntitySet(organizationId: UUID, entitySet: EntitySet, authorizedPropertyTypes: Map<UUID, PropertyType>) {
        logger.info("Refreshing entity set ${entitySet.id} in organization $organizationId database")
        connect(buildOrganizationDatabaseName(organizationId)).use { datasource ->
            // re-import materialized view of entity set
            updateProductionViewTables(datasource, setOf(entitySetIdTableName(entitySet.id)))

            // re-materialize entity set
            materialize(datasource, entitySet, authorizedPropertyTypes)
        }
    }

    /**
     * Removes a materialized entity set from atlas.
     */
    fun dematerializeEntitySets(organizationId: UUID, entitySetIds: Set<UUID>) {
        val dbName = buildOrganizationDatabaseName(organizationId)
        connect(dbName).use { datasource ->
            entitySetIds.forEach { dropMaterializedEntitySet(datasource, it) }
        }
    }

    fun dropMaterializedEntitySet(datasource: HikariDataSource, entitySetId: UUID) {
        // we drop materialized view of entity set from organization database, update edges and entity_sets table
        updatePublicTables(datasource, setOf(ENTITY_SETS.name, EDGES.name))

        datasource.connection.createStatement().use { stmt ->
            stmt.execute(dropProductionForeignSchemaSql(entitySetIdTableName(entitySetId)))
        }
    }

    internal fun exists(dbname: String): Boolean {
        target.connection.use { connection ->
            connection.createStatement().use { stmt ->
                stmt.executeQuery("select count(*) from pg_database where datname = '$dbname'").use { rs ->
                    rs.next()
                    return rs.getInt("count") > 0
                }
            }
        }
    }

    fun getAllRoles(spm: SecurePrincipalsManager): PostgresIterable<Role> {
        return PostgresIterable(
                Supplier {
                    val conn = hds.connection
                    val ps = conn.prepareStatement(PRINCIPALS_SQL)
                    ps.setString(1, PrincipalType.ROLE.name)
                    StatementHolder(conn, ps, ps.executeQuery())
                },
                Function { spm.getSecurablePrincipal(ResultSetAdapters.aclKey(it)) as Role }
        )
    }

    fun getAllUsers(spm: SecurePrincipalsManager): PostgresIterable<SecurablePrincipal> {
        return PostgresIterable(
                Supplier {
                    val conn = hds.connection
                    val ps = conn.prepareStatement(PRINCIPALS_SQL)
                    ps.setString(1, PrincipalType.USER.name)
                    StatementHolder(conn, ps, ps.executeQuery())
                },
                Function { spm.getSecurablePrincipal(ResultSetAdapters.aclKey(it)) }
        )
    }


    private fun configureRolesInDatabase(datasource: HikariDataSource, spm: SecurePrincipalsManager) {
        val roles = getAllRoles(spm)
        if (roles.iterator().hasNext()) {
            val roleIds = roles.map { quote(buildPostgresRoleName(it)) }
            val roleIdsSql = roleIds.joinToString(",")

            datasource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    logger.info("Revoking $PUBLIC_SCHEMA schema right from roles: {}", roleIds)
                    //Don't allow users to access public schema which will contain foreign data wrapper tables.
                    statement.execute("REVOKE USAGE ON SCHEMA $PUBLIC_SCHEMA FROM $roleIdsSql")

                    return@use
                }
            }
        }
    }

    fun dropUserIfExists(user: SecurablePrincipal) {
        target.connection.use { connection ->
            connection.createStatement().use { statement ->
                //TODO: Go through every database and for old users clean them out.
//                    logger.info("Attempting to drop owned by old name {}", user.name)
//                    statement.execute(dropOwnedIfExistsSql(user.name))
                logger.info("Attempting to drop user {}", user.name)
                statement.execute(dropUserIfExistsSql(user.name)) //Clean out the old users.
                dbCredentialService.deleteUserCredential(user.name)
                //Don't allow users to access public schema which will contain foreign data wrapper tables.
                logger.info("Revoking $PUBLIC_SCHEMA schema right from user {}", user)
            }
        }
    }

    fun createRole(role: Role) {
        val dbRole = buildPostgresRoleName(role)

        target.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(createRoleIfNotExistsSql(dbRole))
                //Don't allow users to access public schema which will contain foreign data wrapper tables.
                logger.info("Revoking $PUBLIC_SCHEMA schema right from role: {}", role)
                statement.execute("REVOKE USAGE ON SCHEMA $PUBLIC_SCHEMA FROM ${quote(dbRole)}")

                return@use
            }
        }
    }

    fun createUnprivilegedUser(user: SecurablePrincipal) {
        val dbUser = buildPostgresUsername(user)
        //user.name
        val dbUserPassword = dbCredentialService.getOrCreateUserCredentials(dbUser)

        target.connection.use { connection ->
            connection.createStatement().use { statement ->
                //TODO: Go through every database and for old users clean them out.
//                    logger.info("Attempting to drop owned by old name {}", user.name)
//                    statement.execute(dropOwnedIfExistsSql(user.name))
//                    logger.info("Attempting to drop user {}", user.name)
//                    statement.execute(dropUserIfExistsSql(user.name)) //Clean out the old users.
//                    logger.info("Creating new user {}", dbUser)
                logger.info("Creating user if not exists {}", dbUser)
                statement.execute(createUserIfNotExistsSql(dbUser, dbUserPassword))
                //Don't allow users to access public schema which will contain foreign data wrapper tables.
                logger.info("Revoking $PUBLIC_SCHEMA schema right from user {}", user)
                statement.execute("REVOKE USAGE ON SCHEMA $PUBLIC_SCHEMA FROM ${quote(dbUser)}")

                return@use
            }
        }
    }

    private fun configureUsersInDatabase(datasource: HikariDataSource, dbname: String, userIds: List<String>) {
        val userIdsSql = userIds.joinToString(", ")

        logger.info("Configuring users {} in database {}", userIds, dbname)
        //First we will grant all privilege which for database is connect, temporary, and create schema
        target.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("GRANT ${MEMBER_ORG_DATABASE_PERMISSIONS.joinToString(", ")} " +
                        "ON DATABASE ${quote(dbname)} TO $userIdsSql")
            }
        }

        datasource.connection.use { connection ->
            connection.createStatement().use { statement ->

                statement.execute("GRANT USAGE ON SCHEMA $MATERIALIZED_VIEWS_SCHEMA TO $userIdsSql")
                //Don't allow users to access public schema which will contain foreign data wrapper tables.
                logger.info("Revoking $PUBLIC_SCHEMA schema right from user: {}", userIds)
                statement.execute("REVOKE USAGE ON SCHEMA $PUBLIC_SCHEMA FROM $userIdsSql")
                //Set the search path for the user
                userIds.forEach { userId ->
                    statement.addBatch("ALTER USER $userId SET search_path TO $MATERIALIZED_VIEWS_SCHEMA")
                }
                statement.executeBatch()

                return@use
            }
        }
    }

    private fun revokeConnectAndSchemaUsage(datasource: HikariDataSource, dbname: String, userIds: List<String>) {
        val userIdsSql = userIds.joinToString(", ")

        logger.info("Removing users {} from database {} and schema usage of $MATERIALIZED_VIEWS_SCHEMA",
                userIds,
                dbname)

        datasource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("REVOKE ${MEMBER_ORG_DATABASE_PERMISSIONS.joinToString(", ")} " +
                        "ON DATABASE ${quote(dbname)} FROM $userIdsSql")
                stmt.execute("REVOKE ALL PRIVILEGES ON SCHEMA $MATERIALIZED_VIEWS_SCHEMA FROM $userIdsSql")
            }
        }
    }

    private fun createForeignServer(datasource: HikariDataSource) {
        logger.info("Setting up foreign server for datasource: {}", datasource.jdbcUrl)
        datasource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE EXTENSION IF NOT EXISTS postgres_fdw")
                logger.info("Installed postgres_fdw extension.")

                statement.execute(
                        "CREATE SERVER IF NOT EXISTS $PRODUCTION_SERVER FOREIGN DATA WRAPPER postgres_fdw " +
                                "OPTIONS (host '${assemblerConfiguration.foreignHost}', " +
                                "dbname '${assemblerConfiguration.foreignDbName}', " +
                                "port '${assemblerConfiguration.foreignPort}')"
                )
                logger.info("Created foreign server definition.")

                statement.execute(
                        "CREATE USER MAPPING IF NOT EXISTS FOR CURRENT_USER SERVER $PRODUCTION_SERVER " +
                                "OPTIONS ( user '${assemblerConfiguration.foreignUsername}', " +
                                "password '${assemblerConfiguration.foreignPassword}')"
                )
                logger.info("Created user mapping for foreign server.")

                resetForeignSchema(statement)
                logger.info("Imported foreign schema")
            }
        }
    }

    private fun resetForeignSchema(statement: Statement) {
        logger.info("Resetting $PRODUCTION_FOREIGN_SCHEMA")
        statement.execute("DROP SCHEMA IF EXISTS $PRODUCTION_FOREIGN_SCHEMA CASCADE")
        statement.execute("CREATE SCHEMA IF NOT EXISTS $PRODUCTION_FOREIGN_SCHEMA")
        logger.info("Created user mapping. ")
        statement.execute(importProductionViewsSchemaSql(setOf()))
        statement.execute(importPublicSchemaSql(PUBLIC_TABLES))
    }

    private fun updatePublicTables(datasource: HikariDataSource, tables: Set<String>) {
        logger.info("Updating foreign tables $tables in $PUBLIC_SCHEMA schema")
        datasource.connection.use { connection ->
            connection.createStatement().use { stmt ->
                tables.forEach {
                    stmt.execute(dropProductionForeignSchemaSql(it))
                }
                stmt.execute(importPublicSchemaSql(tables))
            }
        }
    }

    private fun updateProductionViewTables(datasource: HikariDataSource, tables: Set<String>) {
        logger.info("Updating foreign tables $tables in $PRODUCTION_VIEWS_SCHEMA schema")
        datasource.connection.use { connection ->
            connection.createStatement().use { stmt ->
                tables.forEach {
                    stmt.execute(dropProductionForeignSchemaSql(it))
                }
                stmt.execute(importProductionViewsSchemaSql(tables))
            }
        }
    }

    private fun dropProductionForeignSchemaSql(table: String): String {
        return "DROP FOREIGN TABLE IF EXISTS $PRODUCTION_FOREIGN_SCHEMA.$table CASCADE"
    }

    private fun importProductionViewsSchemaSql(limitTo: Set<String>): String {
        return importForeignSchema(PRODUCTION_VIEWS_SCHEMA, limitTo)
    }

    private fun importPublicSchemaSql(limitTo: Set<String>): String {
        return importForeignSchema(PUBLIC_SCHEMA, limitTo)
    }

    private fun importForeignSchema(from: String, limitTo: Set<String>): String {
        val limitToSql = if (limitTo.isEmpty()) "" else "LIMIT TO ( ${limitTo.joinToString(", ")} )"
        return "IMPORT FOREIGN SCHEMA $from $limitToSql FROM SERVER $PRODUCTION_SERVER INTO $PRODUCTION_FOREIGN_SCHEMA"
    }

    private fun entitySetIdTableName(entitySetId: UUID): String {
        return quote(entitySetId.toString())
    }
}

val MEMBER_ORG_DATABASE_PERMISSIONS = setOf("CREATE", "CONNECT", "TEMPORARY", "TEMP")
val PUBLIC_TABLES = setOf(EDGES.name, PROPERTY_TYPES.name, ENTITY_TYPES.name, ENTITY_SETS.name)


private val PRINCIPALS_SQL = "SELECT acl_key FROM principals WHERE ${PRINCIPAL_TYPE.name} = ?"

internal fun createSchema(datasource: HikariDataSource, schema: String) {
    datasource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.execute("CREATE SCHEMA IF NOT EXISTS $schema")
        }
    }
}

internal fun createOpenlatticeSchema(datasource: HikariDataSource) {
    createSchema(datasource, SCHEMA)
}


internal fun createRoleIfNotExistsSql(dbRole: String): String {
    return "DO\n" +
            "\$do\$\n" +
            "BEGIN\n" +
            "   IF NOT EXISTS (\n" +
            "      SELECT\n" +
            "      FROM   pg_catalog.pg_roles\n" +
            "      WHERE  rolname = '$dbRole') THEN\n" +
            "\n" +
            "      CREATE ROLE ${quote(
                    dbRole
            )} NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOLOGIN;\n" +
            "   END IF;\n" +
            "END\n" +
            "\$do\$;"
}

internal fun createUserIfNotExistsSql(dbUser: String, dbUserPassword: String): String {
    return "DO\n" +
            "\$do\$\n" +
            "BEGIN\n" +
            "   IF NOT EXISTS (\n" +
            "      SELECT\n" +
            "      FROM   pg_catalog.pg_roles\n" +
            "      WHERE  rolname = '$dbUser') THEN\n" +
            "\n" +
            "      CREATE ROLE ${quote(
                    dbUser
            )} NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT LOGIN ENCRYPTED PASSWORD '$dbUserPassword';\n" +
            "   END IF;\n" +
            "END\n" +
            "\$do\$;"
}


internal fun dropOwnedIfExistsSql(dbUser: String): String {
    return "DO\n" +
            "\$do\$\n" +
            "BEGIN\n" +
            "   IF EXISTS (\n" +
            "      SELECT\n" +
            "      FROM   pg_catalog.pg_roles\n" +
            "      WHERE  rolname = '$dbUser') THEN\n" +
            "\n" +
            "      DROP OWNED BY ${quote(
                    dbUser
            )} ;\n" +
            "   END IF;\n" +
            "END\n" +
            "\$do\$;"
}

internal fun dropUserIfExistsSql(dbUser: String): String {
    return "DO\n" +
            "\$do\$\n" +
            "BEGIN\n" +
            "   IF EXISTS (\n" +
            "      SELECT\n" +
            "      FROM   pg_catalog.pg_roles\n" +
            "      WHERE  rolname = '$dbUser') THEN\n" +
            "\n" +
            "      DROP ROLE ${quote(
                    dbUser
            )} ;\n" +
            "   END IF;\n" +
            "END\n" +
            "\$do\$;"
}


