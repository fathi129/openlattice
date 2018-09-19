package com.openlattice.graph.processing.processors

import com.openlattice.data.storage.PostgresEntityDataQueryService
import com.openlattice.datastore.services.EdmManager
import com.openlattice.graph.processing.util.NONE
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.temporal.ChronoUnit
import java.util.*

@Component
class SupportiveHousingStayProcessor(edmManager: EdmManager, entityDataService: PostgresEntityDataQueryService):
        BaseDurationProcessor(edmManager, entityDataService) {

    override fun processAssociations(newEntities: Map<UUID, Any?>) {
    }

    private val handledEntityType = "housing.stay"

    companion object {
        private val logger = LoggerFactory.getLogger(SupportiveHousingStayProcessor.javaClass)
    }

    override fun getLogger(): Logger {
        return logger
    }

    override fun getPropertyTypeForStart(): String {
        return "date.admission"
    }

    override fun getPropertyTypeForEnd(): String {
        return "ol.datetime_release"
    }

    override fun getPropertyTypeForDuration(): String {
        return "housing.lengthofstay"
    }

    override fun getTimeUnit(): ChronoUnit {
        return ChronoUnit.DAYS
    }

    override fun getTransformationType(): String {
        return NONE
    }

    override fun handledEntityTypes(): Set<UUID> {
        return setOf(getEntityTypeId(handledEntityType))
    }
}