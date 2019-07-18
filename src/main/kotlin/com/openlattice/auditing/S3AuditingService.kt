package com.openlattice.auditing

import com.amazonaws.services.s3.model.ListObjectsV2Request
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectRequest
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.common.collect.Queues
import com.google.common.util.concurrent.MoreExecutors
import com.openlattice.aws.newS3Client
import com.openlattice.ids.HazelcastLongIdService
import com.openlattice.ids.IdScopes
import org.springframework.http.MediaType
import java.util.concurrent.Executors

private const val LONG_IDS_BATCH_SIZE = 8192

/**
 *
 * @author Matthew Tamayo-Rios &lt;matthew@openlattice.com&gt;
 */
class S3AuditingService(
        auditingConfiguration: AuditingConfiguration,
        private val longIdService: HazelcastLongIdService,
        private val mapper: ObjectMapper

) {
    private val partitions = auditingConfiguration.partitions
    private val bucket = auditingConfiguration.awsS3ClientConfiguration.bucket
    private val longIdsQueue = Queues.newArrayBlockingQueue<Long>(LONG_IDS_BATCH_SIZE)
    private val executorService = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    private val s3 = newS3Client(auditingConfiguration.awsS3ClientConfiguration)

    private val refreshQueue = executorService.execute {
        while(true) {
            longIdService
                    .getIds(IdScopes.AUDITING.name, LONG_IDS_BATCH_SIZE.toLong())
                    .forEach(longIdsQueue::put)
        }
    }

    fun recordEvents(events: List<AuditableEvent>): Int {
        val eventsBytes = mapper.writeValueAsBytes(events)
        val eventsInputStream = eventsBytes.inputStream()

        val metadata = ObjectMetadata()
        metadata.contentLength = eventsInputStream.available().toLong()
        metadata.contentType = MediaType.APPLICATION_JSON_VALUE

        val partition = getId() % partitions.toLong()
        val key = "$partition/${System.currentTimeMillis()}/event-${getId()}.json"
        s3.putObject(PutObjectRequest(bucket, key,eventsInputStream, metadata))
        return events.size
    }

    fun getRecordedEvents(): List<AuditableEvent> {
        //TODO: Need to make sure that auditable event is only queued up once from s3
        

        val objectListing = s3.listObjectsV2(ListObjectsV2Request().withBucketName(bucket) )

        val objects : List<AuditableEvent> = objectListing.objectSummaries.flatMap { objSummary ->
            val someObjects: List<AuditableEvent>  = mapper.readValue(s3.getObject( bucket, objSummary.key).objectContent)
            return@flatMap someObjects
        }

        return objects
    }

    fun deleteIntegratedEvents( keys: List<String>) {
        TODO("Implement deleting events after they are stored.")
    }

    private fun getId(): Long {
        return longIdsQueue.take()

    }

}