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
 *
 */

package com.openlattice.linking.matching

import com.codahale.metrics.annotation.Timed
import com.google.common.base.Stopwatch
import com.openlattice.data.EntityDataKey
import com.openlattice.linking.EntityKeyPair
import com.openlattice.linking.PostgresLinkingFeedbackService
import com.openlattice.linking.Block
import com.openlattice.linking.util.PersonMetric
import com.openlattice.rhizome.hazelcast.DelegatedStringSet
import org.apache.olingo.commons.api.edm.FullQualifiedName
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork
import org.deeplearning4j.parallelism.ParallelInference
import org.deeplearning4j.parallelism.inference.InferenceMode
import org.nd4j.linalg.factory.Nd4j
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.TimeUnit

const val THRESHOLD = 0.9
private val logger = LoggerFactory.getLogger(SocratesMatcher::class.java)

/**
 * Performs matching using the model generated by Socrates.
 */
@Component
class SocratesMatcher(
        model: MultiLayerNetwork,
        private val fqnToIdMap: Map<FullQualifiedName, UUID>,
        private val linkingFeedbackService: PostgresLinkingFeedbackService
) : Matcher {

    private var localModel = ParallelInference.Builder(model)
                        .inferenceMode(InferenceMode.SEQUENTIAL)
                        // .batchLimit(32)
                        .workers(Runtime.getRuntime().availableProcessors())
                        .build()

    override fun updateMatchingModel(model: MultiLayerNetwork) {
        localModel.updateModel(model)
    }

    /**
     * Gets an initial block of entities that closely match the entity.
     * @param block A block of potential matches based on search
     * @return block The resulting block around the entity data key in block.first
     */
    @Timed
    override fun initialize(block: Block): PairwiseMatch {
        val entityDataKey = block.entityDataKey
        // negative feedbacks are already filtered out when blocking
        val entities = block.entities

        // extract properties and features for all entities in block
        val firstProperties = extractProperties(entities.getValue(entityDataKey))
        val extractedFeatures = entities.mapValues {
            val extractedProperties = extractProperties(it.value)
            extractFeatures(firstProperties, extractedProperties)
        }

        // transform features to matrix and compute scores
        val featureMatrix = extractedFeatures.values.toTypedArray()
        val scores = computeScore(localModel, featureMatrix).asIterable()

        val matchedEntities = extractedFeatures.keys.zip(scores).toMap(mutableMapOf())
        val initializedBlock = PairwiseMatch(entityDataKey, mutableMapOf(entityDataKey to matchedEntities))

        // trim low scores
        trimAndMerge(initializedBlock)
        return initializedBlock
    }

    /**
     * Computes the pairwise matching values for a block.
     * @param block The resulting block around for the entity data key in block.first and property values for each
     * entity around as block.second
     * @return All pairs of entities in the block scored by the current model.
     */
    @Timed
    override fun match(block: Block): PairwiseMatch {
        val sw = Stopwatch.createStarted()

        val positiveFeedbacks = mutableSetOf<EntityKeyPair>()
        // filter out positive matches from feedback to avoid computation of scores
        // negative feedbacks are already filter out when blocking
        val entities = block.entities

        // get feedbacks in bulk
//        val entityPairs = entities.flatMapTo(mutableSetOf()) { entity ->
//            entities.keys.mapTo(mutableSetOf()) {
//                EntityKeyPair(entity.key, it)
//            }
//        }
//        val feedbacks = linkingFeedbackService.getLinkingFeedbacks(entityPairs)

        // filter out feedbacks
        val filteredEntities = entities.mapValues { _ ->
//            entities.keys.filter {
//                val entityPair = EntityKeyPair(entity.key, it)
//                val linked = feedbacks[entityPair]
//                if (linked != null && linked) {
//                    positiveFeedbacks.add(entityPair)
//                    return@filter false
//                }
//                return@filter true
//            }
            entities.keys
        }.filter {
            it.value.isNotEmpty()
        }

        val results = computeResults(entities, filteredEntities, positiveFeedbacks)

        // from list of results to expected output
        val matchedEntities = results.groupBy { it.lhs }
                .mapValues { x ->
                    x.value.groupBy { it.rhs }
                            .mapValues { y -> y.value[0].score }
                            .toMutableMap()
                }.toMutableMap()

        logger.info(
                "Matching block {} with {} elements took {} ms",
                block.entityDataKey, block.size,
                sw.elapsed(TimeUnit.MILLISECONDS)
        )

        return PairwiseMatch(block.entityDataKey, matchedEntities)
    }

    /**
     * Looks like currently the speed limiting factor
     */
    private fun computeResults(
            entityValues: Map<EntityDataKey, Map<UUID, Set<Any>>>,
            entities: Map<EntityDataKey, Set<EntityDataKey>>,
            positiveFeedbacks: Set<EntityKeyPair>
    ): List<ResultSet> {
        val positiveMatches = positiveFeedbacks.flatMap {
            listOf(
                    ResultSet(it.first, it.second, 1.0),
                    ResultSet(it.second, it.first, 1.0)
            )
        }

        // all entities have positive feedback
        if (entities.isEmpty()) {
            logger.info("All entities have positive feedback")
            return positiveMatches
        }
        val sw = Stopwatch.createStarted()

        // extract properties
        val extractedProperties = entityValues.mapValues { extractProperties(it.value) }

        val propsExtractionSw = sw.elapsed(TimeUnit.MILLISECONDS)

        // extract features for all entities in block
        val extractedFeatures = entities.mapValues { (edk, neighbors) ->
            val selfProperties = extractedProperties.getValue(edk)
            val neighborExtractedProperties = neighbors.associateWith {
                extractedProperties.getValue(it)
            }
            extractFeaturesBulk(selfProperties, neighborExtractedProperties)
        }

        val blockFeatureExtraction = sw.elapsed(TimeUnit.MILLISECONDS)

        // transform features to matrix and compute scores
        val featureMatrix = extractedFeatures
                .flatMap { (_, features) -> features.map { it.value } }
                .toTypedArray()

        // extract list of keys (instead of map)
        val featureKeys = extractedFeatures.flatMap { (entityDataKey1, features) ->
            features.map {
                entityDataKey1 to it.key
            }
        }

        val featureExtractionSW = sw.elapsed(TimeUnit.MILLISECONDS)

        // get scores from matrix
        val scores = computeScore(localModel.get(), featureMatrix)

        // collect and combine keys and scores
        val results = scores.zip(featureKeys).map {
            ResultSet(it.second.first, it.second.second, it.first)
        }.plus(positiveMatches)

        val bfTime = blockFeatureExtraction - propsExtractionSw
        val fblTime = featureExtractionSW - blockFeatureExtraction

        if (propsExtractionSw > 300){
            logger.error("Property extraction: $propsExtractionSw ms")
        }
        if (bfTime > 300){
            logger.error("Block feature extraction: $bfTime ms")
        }
        if (fblTime > 300){
            logger.error("final transforms: $fblTime ms")
        }
        return results
    }

    private fun computeScore(
            model: MultiLayerNetwork,
            features: Array<DoubleArray>
    ): DoubleArray {
        val sw = Stopwatch.createStarted()
        val scores = model.getModelScore(features)
        logger.info("The model computed scores in {} ms", sw.elapsed(TimeUnit.MILLISECONDS))
        return scores
    }

    fun extractFeaturesBulk(
            lhs: Map<UUID, DelegatedStringSet>,
            rhs: Map<EntityDataKey, Map<UUID, DelegatedStringSet>>
    ): Map<EntityDataKey, DoubleArray> {
        return PersonMetric.pDistanceBulk(lhs, rhs, fqnToIdMap)
    }

    override fun extractFeatures(
            lhs: Map<UUID, DelegatedStringSet>,
            rhs: Map<UUID, DelegatedStringSet>
    ): DoubleArray {
        return PersonMetric.pDistance(lhs, rhs, fqnToIdMap).map { it * 100.0 }.toDoubleArray()
    }

    override fun extractProperties(entity: Map<UUID, Set<Any>>): Map<UUID, DelegatedStringSet> {
        return entity.mapValues { ( _, properties ) ->
            DelegatedStringSet.wrap( properties.mapTo(mutableSetOf()) { it.toString() } )
        }
    }

    @Timed
    override fun trimAndMerge(
            matchedBlock: PairwiseMatch
    ) {
        //Trim non-center matching thigns.
        val filtered = matchedBlock.matches.getOrDefault(
                matchedBlock.candidate, mutableMapOf()
        ).filterTo(mutableMapOf()) {
            it.value > THRESHOLD
        }
        matchedBlock.matches[matchedBlock.candidate] = filtered
    }
}

fun MultiLayerNetwork.getModelScore(features: Array<DoubleArray>): DoubleArray {
    return try {
        output(Nd4j.create(features)).toDoubleVector()
    } catch (ex: Exception) {
        logger.error("Failed to compute model score trying again! Features = {}", features.toList(), ex)
        try {
            output(Nd4j.create(features)).toDoubleVector()
        } catch (ex2: Exception) {
            logger.error("Failed to compute model score a second time! Return 0! Features = {}", features.toList(), ex)
            Nd4j.ones(features.size).toDoubleVector()
        }
    }
}

data class ResultSet(val lhs: EntityDataKey, val rhs: EntityDataKey, val score: Double)
