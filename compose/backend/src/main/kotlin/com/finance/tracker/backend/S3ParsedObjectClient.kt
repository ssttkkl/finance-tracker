package com.finance.tracker.backend

import java.net.URI
import java.time.Duration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.retries.StandardRetryStrategy
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.S3Exception

internal class S3ParsedObjectClient(private val client: S3Client) : ParsedObjectClient, AutoCloseable {
    private fun <T> call(action: () -> T): T = try { action() }
        catch (error: S3Exception) { throw ParsedObjectProviderError(error.awsErrorDetails()?.errorCode() ?: error.statusCode().toString()) }
        catch (_: Exception) { throw ParsedImportStorageUnavailable() }

    override fun get(bucket: String, key: String): ParsedObjectResponse = call {
        val body = client.getObject { it.bucket(bucket).key(key) }
        ParsedObjectResponse(body, body.response().eTag(), body.response().contentLength() ?: 0)
    }
    override fun put(bucket: String, key: String, content: ByteArray, ifMatch: String?, ifNoneMatch: String?,
        contentType: String, metadata: Map<String, String>) { call {
        client.putObject({ it.bucket(bucket).key(key).ifMatch(ifMatch).ifNoneMatch(ifNoneMatch).contentType(contentType).metadata(metadata) },
            RequestBody.fromBytes(content))
    } }
    override fun delete(bucket: String, key: String) { call { client.deleteObject { it.bucket(bucket).key(key) } } }
    override fun list(bucket: String, prefix: String, limit: Int, cursor: String?): ParsedObjectPage = call {
        val result = client.listObjectsV2 { it.bucket(bucket).prefix(prefix).maxKeys(limit).continuationToken(cursor) }
        ParsedObjectPage(result.contents().map { it.key() ?: throw ParsedImportStorageUnavailable() },
            result.isTruncated ?: false, result.nextContinuationToken())
    }
    override fun head(bucket: String, key: String): ParsedObjectHead = call {
        val result = client.headObject { it.bucket(bucket).key(key) }
        ParsedObjectHead(result.metadata().orEmpty(), result.lastModified())
    }
    override fun close() = client.close()

    companion object {
        internal fun connect(endpoint: URI, accessKey: String, secret: String, region: String = "auto"): S3ParsedObjectClient =
            S3ParsedObjectClient(S3Client.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secret)))
                .forcePathStyle(true)
                .serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(3)).socketTimeout(Duration.ofSeconds(5)))
                .overrideConfiguration { it.retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()) }
                .build())
    }
}
