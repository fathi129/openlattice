package com.openlattice.data.storage.aws

import com.amazonaws.AmazonServiceException
import com.amazonaws.ClientConfiguration
import com.amazonaws.HttpMethod
import com.amazonaws.SdkClientException
import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.retry.PredefinedBackoffStrategies
import com.amazonaws.retry.PredefinedRetryPolicies
import com.amazonaws.retry.RetryPolicy
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.DeleteObjectRequest
import com.amazonaws.services.s3.model.DeleteObjectsRequest
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectRequest
import com.amazonaws.services.s3.model.ResponseHeaderOverrides
import com.amazonaws.services.s3.transfer.TransferManagerBuilder
import com.google.common.util.concurrent.ListeningExecutorService
import com.openlattice.data.storage.BinaryObjectWithMetadata
import com.openlattice.data.storage.ByteBlobDataManager
import com.openlattice.datastore.configuration.DatastoreConfiguration
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URL
import java.util.*
import java.util.concurrent.Callable


private val logger = LoggerFactory.getLogger(AwsBlobDataService::class.java)
const val MAX_ERROR_RETRIES = 5

@Service
class AwsBlobDataService(
        private val datastoreConfiguration: DatastoreConfiguration,
        private val executorService: ListeningExecutorService
) : ByteBlobDataManager {

    private val s3Credentials = BasicAWSCredentials(datastoreConfiguration.accessKeyId, datastoreConfiguration.secretAccessKey)
    private val s3 = newS3Client(datastoreConfiguration)

    private final fun newS3Client(datastoreConfiguration: DatastoreConfiguration): AmazonS3 {
        val builder = AmazonS3ClientBuilder.standard()
        builder.region = datastoreConfiguration.regionName
        builder.credentials = AWSStaticCredentialsProvider(s3Credentials)
        val retryPolicy = RetryPolicy(
                PredefinedRetryPolicies.DEFAULT_RETRY_CONDITION,
                PredefinedBackoffStrategies.SDKDefaultBackoffStrategy(), //TODO try jitter
                MAX_ERROR_RETRIES,
                false)
        builder.clientConfiguration = ClientConfiguration().withRetryPolicy(retryPolicy)
        return builder.build()
    }

    override fun putObject(s3Key: String, binaryObjectWithMetadata: BinaryObjectWithMetadata) {
        val metadata = ObjectMetadata()
        val dataInputStream = binaryObjectWithMetadata.data.inputStream()
        metadata.contentLength = dataInputStream.available().toLong()
        metadata.contentType = binaryObjectWithMetadata.contentType
        binaryObjectWithMetadata.contentDisposition?.let { metadata.contentDisposition = it }

        val putRequest = PutObjectRequest(datastoreConfiguration.bucketName, s3Key, dataInputStream, metadata)
        val transferManager = TransferManagerBuilder.standard().withS3Client(s3).build()
        val upload = transferManager.upload(putRequest)
        upload.waitForCompletion()
        transferManager.shutdownNow(false)
    }

    override fun deleteObjects(s3Keys: List<String>) {
        val keysToDelete = s3Keys.map { DeleteObjectsRequest.KeyVersion(it) }.toList()
        val deleteRequest = DeleteObjectsRequest(datastoreConfiguration.bucketName).withKeys(keysToDelete)
        s3.deleteObjects(deleteRequest)
    }

    override fun deleteObject(s3Key: String) {
        val deleteRequest = DeleteObjectRequest(datastoreConfiguration.bucketName, s3Key)
        s3.deleteObject(deleteRequest)
    }

    override fun getObjects(keys: Collection<Any>): List<Any> {
        return getPresignedUrls(keys)
    }

    override fun getPresignedUrls(keys: Collection<Any>): List<URL> {
        return getPresignedUrlsWithDispositions(keys.associate { it as String to null }).values.toList()
    }

    override fun getPresignedUrlsWithDispositions(keysToDispositions: Map<String, String?>): Map<String, URL> {
        val expirationTime = getDefaultExpirationDateTime()

        return keysToDispositions
                .map { (key, disposition) ->
                    executorService.submit(Callable<Pair<String, URL>> {
                        key to getPresignedUrl(
                                key = key,
                                expiration = expirationTime,
                                httpMethod = HttpMethod.GET,
                                contentDisposition = disposition
                        )
                    })
                }.map { it.get() }.toMap()
    }

    override fun getPresignedUrl(
            key: Any,
            expiration: Date,
            httpMethod: HttpMethod,
            contentType: String?,
            contentDisposition: String?
    ): URL {
        val urlRequest = GeneratePresignedUrlRequest(datastoreConfiguration.bucketName, key.toString()).withMethod(
                httpMethod
        ).withExpiration(expiration)
        contentType?.let { urlRequest.contentType = it }
        contentDisposition?.let { urlRequest.responseHeaders = ResponseHeaderOverrides().withContentDisposition(it) }
        lateinit var url: URL
        try {
            url = s3.generatePresignedUrl(urlRequest)
        } catch (e: AmazonServiceException) {
            logger.warn("Amazon couldn't process call")
        } catch (e: SdkClientException) {
            logger.warn("Amazon S3 couldn't be contacted or the client couldn't parse the response from S3")
        }
        return url
    }

    override fun getDefaultExpirationDateTime(): Date {
        val expirationTime = Date()
        val timeToLive = expirationTime.time + datastoreConfiguration.timeToLive
        expirationTime.time = timeToLive
        return expirationTime
    }


}