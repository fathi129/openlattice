package com.openlattice.ids.mapstores

import com.openlattice.hazelcast.HazelcastMap
import com.openlattice.postgres.PostgresColumn.BASE_FIELD
import com.openlattice.postgres.PostgresColumn.SCOPE_FIELD
import com.openlattice.postgres.PostgresTable.BASE_LONG_IDS
import com.openlattice.postgres.mapstores.AbstractBasePostgresMapstore
import com.zaxxer.hikari.HikariDataSource
import org.apache.commons.lang3.RandomStringUtils
import org.apache.commons.lang3.RandomUtils
import java.sql.PreparedStatement
import java.sql.ResultSet


class LongIdsMapstore(hds: HikariDataSource) : AbstractBasePostgresMapstore<String, Long>(
        HazelcastMap.LONG_IDS.name,
        BASE_LONG_IDS,
        hds
) {
    override fun generateTestKey(): String {
        return RandomStringUtils.random(10)
    }

    override fun generateTestValue(): Long {
        return RandomUtils.nextLong()
    }

    override fun bind(ps: PreparedStatement, key: String, value: Long) {
        val index = bind(ps, key)
        ps.setLong(index, value)
    }

    override fun mapToKey(rs: ResultSet): String {
        return rs.getString(SCOPE_FIELD)
    }

    override fun bind(ps: PreparedStatement, key: String, offset: Int): Int {
        ps.setString(1, key)
        return 2
    }

    override fun mapToValue(rs: ResultSet): Long {
        return rs.getLong(BASE_FIELD)
    }
}