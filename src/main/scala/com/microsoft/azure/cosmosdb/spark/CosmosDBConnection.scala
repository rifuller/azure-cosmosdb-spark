/**
  * The MIT License (MIT)
  * Copyright (c) 2016 Microsoft Corporation
  *
  * Permission is hereby granted, free of charge, to any person obtaining a copy
  * of this software and associated documentation files (the "Software"), to deal
  * in the Software without restriction, including without limitation the rights
  * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
  * copies of the Software, and to permit persons to whom the Software is
  * furnished to do so, subject to the following conditions:
  *
  * The above copyright notice and this permission notice shall be included in all
  * copies or substantial portions of the Software.
  *
  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
  * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
  * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
  * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
  * SOFTWARE.
  */
package com.microsoft.azure.cosmosdb.spark

import java.lang.management.ManagementFactory

import com.microsoft.azure.cosmosdb.spark.config._
import com.microsoft.azure.documentdb._
import com.microsoft.azure.documentdb.bulkexecutor.DocumentBulkExecutor
import com.microsoft.azure.documentdb.internal._
import com.microsoft.azure.documentdb.internal.routing.PartitionKeyRangeCache

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.language.implicitConversions
import scala.util.control.Breaks._

case class ClientConfiguration(host: String,
                               database: String,
                               collection: String,
                               key: String,
                               connectionPolicy: ConnectionPolicy,
                               consistencyLevel: ConsistencyLevel,
                               resourceLink: Option[String],
                               bulkConfig: BulkExecutorSettings)

case class BulkExecutorSettings(partitionKeyOption: Option[PartitionKeyDefinition],
                                maxMiniBatchUpdateCount: Int,
                                maxMiniBatchImportSizeKB: Int)

object ClientConfiguration extends CosmosDBLoggingTrait {
  def apply(config: Config): ClientConfiguration = {
    // TODO: validate that either master key or resource token is set but not both
    val connectionPolicy = new ConnectionPolicy()

    val connectionMode = ConnectionMode.valueOf(config.getOrElse(CosmosDBConfig.ConnectionMode, CosmosDBConfig.DefaultConnectionMode))
    connectionPolicy.setConnectionMode(connectionMode)

    val applicationName: Option[String] = config.get[String](CosmosDBConfig.ApplicationName)
    val userAgentString: String = Constants.userAgentSuffix + " " + ManagementFactory.getRuntimeMXBean.getName ++ if (applicationName.isDefined) {
      " " ++ applicationName.get
    }
    connectionPolicy.setUserAgentSuffix(userAgentString)

    config.get[String](CosmosDBConfig.ConnectionRequestTimeout) match {
      case Some(connectionRequestTimeoutStr) => connectionPolicy.setRequestTimeout(connectionRequestTimeoutStr.toInt)
      case None => // skip
    }

    config.get[String](CosmosDBConfig.ConnectionIdleTimeout) match {
      case Some(connectionIdleTimeoutStr) => connectionPolicy.setIdleConnectionTimeout(connectionIdleTimeoutStr.toInt)
      case None => // skip
    }

    val maxConnectionPoolSize = config.getOrElse[String](CosmosDBConfig.ConnectionMaxPoolSize, CosmosDBConfig.DefaultMaxConnectionPoolSize.toString)
    connectionPolicy.setMaxPoolSize(maxConnectionPoolSize.toInt)

    val maxRetryAttemptsOnThrottled = config.getOrElse[String](CosmosDBConfig.QueryMaxRetryOnThrottled, CosmosDBConfig.DefaultQueryMaxRetryOnThrottled.toString)
    connectionPolicy.getRetryOptions.setMaxRetryAttemptsOnThrottledRequests(maxRetryAttemptsOnThrottled.toInt)

    val maxRetryWaitTimeSecs = config.getOrElse[String](CosmosDBConfig.QueryMaxRetryWaitTimeSecs, CosmosDBConfig.DefaultQueryMaxRetryWaitTimeSecs.toString)
    connectionPolicy.getRetryOptions.setMaxRetryWaitTimeInSeconds(maxRetryWaitTimeSecs.toInt)

    val preferredRegionsList = config.get[String](CosmosDBConfig.PreferredRegionsList)
    if (preferredRegionsList.isDefined) {
      logTrace(s"CosmosDBConnection::Input preferred region list: ${preferredRegionsList.get}")
      val preferredLocations = preferredRegionsList.get.split(";").map(_.trim).toList
      connectionPolicy.setPreferredLocations(preferredLocations)
    }

    // Get consistency level
    val consistencyLevel = ConsistencyLevel.valueOf(config.getOrElse(CosmosDBConfig.ConsistencyLevel, CosmosDBConfig.DefaultConsistencyLevel))

    val database = config.get(CosmosDBConfig.Database).get
    val collection = config.get(CosmosDBConfig.Collection).get

    // Check if resource token exists
    val resourceToken = config.get[String](CosmosDBConfig.ResourceToken)
    val resourceLink: Option[String] = if(resourceToken.isDefined) {
      Some(getCollectionLink(database, collection))
    } else {
      None
    }

    val pkDef: Option[PartitionKeyDefinition] = config.get[String](CosmosDBConfig.PartitionKeyDefinition).map(pk => {
      val pkDefinition = new PartitionKeyDefinition()
      val paths: ListBuffer[String] = new ListBuffer[String]()
      paths.add(pk)
      pkDefinition.setPaths(paths)
      pkDefinition
    })

    val maxMiniBatchImportSizeKB: Int = config
      .getOrElse(CosmosDBConfig.MaxMiniBatchImportSizeKB, CosmosDBConfig.DefaultMaxMiniBatchImportSizeKB)
    val maxMiniBatchUpdateCount: Int = config
      .getOrElse(CosmosDBConfig.MaxMiniBatchUpdateCount, CosmosDBConfig.DefaultMaxMiniBatchUpdateCount)

    val bulkExecutorSettings = BulkExecutorSettings(
      pkDef,
      maxMiniBatchUpdateCount,
      maxMiniBatchImportSizeKB)

    ClientConfiguration(
      config.get(CosmosDBConfig.Endpoint).get,
      config.get(CosmosDBConfig.Database).get,
      config.get(CosmosDBConfig.Collection).get,
      config.getOrElse(CosmosDBConfig.Masterkey, resourceToken.get),
      connectionPolicy,
      consistencyLevel,
      resourceLink,
      bulkExecutorSettings)
  }

  def getCollectionLink(database: String, collection: String): String = {
    s"${Paths.DATABASES_PATH_SEGMENT}/$database/${Paths.COLLECTIONS_PATH_SEGMENT}/$collection"
  }
}

case class ClientCacheEntry(docClient: DocumentClient,
                            bulkExecutor: Option[DocumentBulkExecutor])

object CosmosDBConnectionCache {
  lazy val clientCache: mutable.Map[ClientConfiguration, ClientCacheEntry] = mutable.Map[ClientConfiguration,ClientCacheEntry]()

  def invalidate(key: ClientConfiguration): Unit = {
    clientCache.remove(key)
  }

  def getOrCreateBulkExecutor(config: ClientConfiguration): DocumentBulkExecutor = {
    if (!clientCache.contains(config) || clientCache(config).bulkExecutor.isEmpty) {
      val client: DocumentClient = getOrCreateClient(config)

      // Initialize bulk executor per guidance in:
      // https://docs.microsoft.com/en-us/azure/cosmos-db/bulk-executor-java#bulk-import-data-to-azure-cosmos-db
      // Set client's retry options high for initialization
      client.getConnectionPolicy.getRetryOptions.setMaxRetryWaitTimeInSeconds(30) // was:1000
      client.getConnectionPolicy.getRetryOptions.setMaxRetryAttemptsOnThrottledRequests(9) // was: 1000

      val pkDef = config.bulkConfig.partitionKeyOption.getOrElse({
        val collectionLink = ClientConfiguration.getCollectionLink(config.database, config.collection)
        client.readCollection(collectionLink, null).getResource.getPartitionKey
      })

      val builder = DocumentBulkExecutor.builder.from(
        client,
        config.database,
        config.collection,
        pkDef,
        1 )  // TODO: get the throughput from config or from the DB
        .withMaxUpdateMiniBatchCount(config.bulkConfig.maxMiniBatchUpdateCount)
        .withMaxMiniBatchSize(config.bulkConfig.maxMiniBatchImportSizeKB * 1024)

      // Instantiate DocumentBulkExecutor
      val bulkExecutor = builder.build()

      // Set retries to 0 to pass complete control to bulk executor
      client.getConnectionPolicy.getRetryOptions.setMaxRetryWaitTimeInSeconds(0)
      client.getConnectionPolicy.getRetryOptions.setMaxRetryAttemptsOnThrottledRequests(0)

      clientCache(config) = ClientCacheEntry(client, Some(bulkExecutor))
    }

    clientCache(config).bulkExecutor.get
  }

  def getOrCreateClient(config: ClientConfiguration): DocumentClient = {
    if (!clientCache.contains(config)) {
      val client = if(config.resourceLink.isDefined) {
        createClientWithResourceToken(config)
      } else {
        createClientWithMasterKey(config)
      }

      clientCache(config) = ClientCacheEntry(client, null)
    }

    clientCache(config).docClient
  }

  private def createClientWithMasterKey(config: ClientConfiguration): DocumentClient =
    new DocumentClient(
      config.host,
      config.key,
      config.connectionPolicy,
      config.consistencyLevel)

  private def createClientWithResourceToken(config: ClientConfiguration): DocumentClient = {
    val permissionWithNamedResourceLink = new Permission()
    permissionWithNamedResourceLink.set("_token", config.key)
    permissionWithNamedResourceLink.setResourceLink(config.resourceLink.get)

    val namedResourceLinkPermission = List[Permission](permissionWithNamedResourceLink)

    val client = new DocumentClient(
      config.host,
      namedResourceLinkPermission,
      config.connectionPolicy,
      config.consistencyLevel)

    // Grab the other permission thing
    val collection = client.readCollection(config.resourceLink.get, null);
    client.close()

    val collectionSelfLink = collection.getResource.getSelfLink;

    val permissionWithSelfLink = new Permission()
    permissionWithSelfLink.set("_token", config.key)
    permissionWithSelfLink.setResourceLink(collectionSelfLink)

    val permissions = List[Permission](permissionWithSelfLink, permissionWithNamedResourceLink)

    new DocumentClient(
      config.host,
      permissions,
      config.connectionPolicy,
      config.consistencyLevel)
  }
}

object CosmosDBConnection extends CosmosDBLoggingTrait {
   def reinitializeClient(collection: DocumentCollection, connectionMode: ConnectionMode, clientConfiguration: ClientConfiguration): DocumentClient = synchronized {
     logInfo("re-initializing client")
     val cacheKey = clientConfiguration.host + "-" + clientConfiguration.key
     if (clients.get(cacheKey).nonEmpty) {
       logInfo(s"Reinitializing client for host ${clientConfiguration.host}")
       val client = clients.get(cacheKey).get
       val field = client.getClass.getDeclaredField("partitionKeyRangeCache")
       field.setAccessible(true)
       val partitionKeyRangeCache = field.get(client).asInstanceOf[PartitionKeyRangeCache]
       val range = new com.microsoft.azure.documentdb.internal.routing.Range[String]("00", "FF", true, true)
       partitionKeyRangeCache.getOverlappingRanges(collection.getSelfLink, range, true)
     }

     getClient(connectionMode, clientConfiguration)
   }
 }

private[spark] case class CosmosDBConnection(config: Config) extends CosmosDBLoggingTrait with Serializable {

  private val databaseName = config.get[String](CosmosDBConfig.Database).get
  private val maxPagesPerBatch =
    config.getOrElse[String](CosmosDBConfig.ChangeFeedMaxPagesPerBatch, CosmosDBConfig.DefaultChangeFeedMaxPagesPerBatch.toString).toInt
  private val databaseLink = s"${Paths.DATABASES_PATH_SEGMENT}/$databaseName"
  private val collectionName = config.get[String](CosmosDBConfig.Collection).get
  //val collectionLink = s"${Paths.DATABASES_PATH_SEGMENT}/$databaseName/${Paths.COLLECTIONS_PATH_SEGMENT}/$collectionName"

  private var collection: DocumentCollection = _
  private var database: Database = _
  //private var collectionThroughput: Option[Int] = None

  private var documentClient: DocumentClient = CosmosDBConnection.getClient(connectionMode, getClientConfiguration(config))

  def getCollection: DocumentCollection = {
    if (collection == null) {
      collection = documentClient.readCollection(collectionLink, null).getResource
    }
    collection
  }

  def getDatabase: Database = {
    if (database == null) {
      database = documentClient.readDatabase(databaseLink, null).getResource
    }
    database
  }

  def getAllPartitions: Array[PartitionKeyRange] = {
    var ranges = documentClient.readPartitionKeyRanges(collectionLink, null.asInstanceOf[FeedOptions])
    ranges.getQueryIterator.toArray
  }

  def getAllPartitions(query: String): Array[PartitionKeyRange] = {
    getAllPartitions
  }

  def getCollectionThroughput: Int = 360000

  def reinitializeClient () = Unit

  def queryDocuments (queryString : String,
        feedOpts : FeedOptions) : Iterator [Document] = {
    val feedResponse = documentClient.queryDocuments(collectionLink, new SqlQuerySpec(queryString), feedOpts)
    feedResponse.getQueryIterable.iterator()
  }

  def queryDocuments (collectionLink: String, queryString : String,
                      feedOpts : FeedOptions) : Iterator [Document] = {
    val feedResponse = documentClient.queryDocuments(collectionLink, new SqlQuerySpec(queryString), feedOpts)
    feedResponse.getQueryIterable.iterator()
  }

  def readDocuments(feedOptions: FeedOptions): Iterator[Document] = {
    documentClient.readDocuments(collectionLink, feedOptions).getQueryIterable.iterator()
  }

  def readChangeFeed(
    changeFeedOptions: ChangeFeedOptions,
    isStreaming: Boolean,
    shouldInferStreamSchema: Boolean,
    updateTokenFunc: Function3[String, String, String, Unit]
    ): Iterator[Document] = {

    val partitionId = changeFeedOptions.getPartitionKeyRangeId()

    logDebug(s"--> readChangeFeed, PageSize: ${changeFeedOptions.getPageSize().toString()}, ContinuationToken: ${changeFeedOptions.getRequestContinuation()}, PartitionId: ${partitionId}, ShouldInferSchema: ${shouldInferStreamSchema.toString()}")
    
    // The ChangeFeed API in the SDK allows accessing the continuation token
    // from the latest HTTP Response
    // This is not sufficient to build a correct continuation token when
    // the "ChangeFeedMaxPagesPerBatch" limit is reached, because "blocks" that
    // can be retrieved from the SDK can span two or more underlying pages. So the first records in 
    // the block can only be retrieved with the previous continuation token - the last
    // records would have the continuation token of the latest HTTP response that is retrievable
    // The variables below are used to store context necessary to form a continuation token
    // that allows bookmarking an individual record within the changefeed
    // The continuation token that would need to be used to safely allow retrieving changerecords
    // after a bookmark in the form of <blockStartContinuation>|<lastProcessedIdBookmark>
    // Meaning the <blockStartContinuation> needs to be at a previous or the same page as the change record
    // document with Id <lastProcessedIdBookmark>

    // Indicator whether we found the first not yet processed change record
    var foundBookmark = true

    // The id of the last document that has been processed and returned to the caller
    var lastProcessedIdBookmark = ""

    // The original continuation that has been passed to this method by the caller
    val originalContinuation = changeFeedOptions.getRequestContinuation()
    var currentContinuation = originalContinuation

    // The next continuation token that is returned to the caller to continue
    // processing the change feed
    var nextContinuation = changeFeedOptions.getRequestContinuation()
    if (currentContinuation != null && 
        currentContinuation.contains("|"))
    {
      val continuationFragments = currentContinuation.split('|')
      currentContinuation = continuationFragments(0)
      changeFeedOptions.setRequestContinuation(currentContinuation)
      lastProcessedIdBookmark = continuationFragments(1)
      foundBookmark = false
    }

    // The continuation token that would need to be used to safely allow retrieving changerecords
    // after a bookmark in the form of <blockStartContinuation>|<lastProcessedIdBookmark>
    // Meaning the <blockStartContinuation> needs to be at a previous or the same page as the change record
    // document with Id <lastProcessedIdBookmark>
    var previousBlockStartContinuation = currentContinuation

    // blockStartContinuation is used as a place holder to store the feedResponse.getResponseContinuation()
    // of the previous HTTP response to be able to apply it to previousBlockStartContinuation
    // accordingly
    var blockStartContinuation = currentContinuation

    // This method can result in reading the next page of the changefeed and changing the continuation token header
    val feedResponse = documentClient.queryDocumentChangeFeed(collectionLink, changeFeedOptions)
    logDebug(s"    readChangeFeed.InitialResponseContinuation: ${feedResponse.getResponseContinuation()}")

    // If processing from the beginning (no continuation token passed into this method)
    // it is safe to increase previousBlockStartContinuation here because we always at least return
    // one page
    if (Option(currentContinuation).getOrElse("").isEmpty)
    {
      blockStartContinuation = feedResponse.getResponseContinuation()
      previousBlockStartContinuation = blockStartContinuation
    }

    if (isStreaming) {
      var pageCount = 0;

      var isFirstBlock = true;
      // In streaming scenario, the change feed need to be materialized in order to get the information of the continuation token
      val cfDocuments: ListBuffer[Document] = new ListBuffer[Document]
      breakable { 
        // hasNext can result in reading the next page of the changefeed and changing the continuation token header
        while (feedResponse.getQueryIterator.hasNext)
        {
          logDebug(s"    readChangeFeed.InWhile ContinuationToken: ${blockStartContinuation}")
          // fetchNextBlock can result in reading the next page of the changefeed and changing the continuation token header
          val feedItems = feedResponse.getQueryIterable.fetchNextBlock()

          for (feedItem <- feedItems)
          {
            if (!foundBookmark)
            {
              if (feedItem.get("id") == lastProcessedIdBookmark)
              {
                logDebug("    readChangeFeed.FoundBookmarkDueToIdMatch")
                foundBookmark = true
              }
            }
            else
            {
              if (shouldInferStreamSchema)
              {
                cfDocuments.add(feedItem)
              }
              else
              {
                val streamDocument: Document = new Document()
                streamDocument.set("body", feedItem.toJson)
                streamDocument.set("id", feedItem.get("id"))
                streamDocument.set("_rid", feedItem.get("_rid"))
                streamDocument.set("_self", feedItem.get("_self"))
                streamDocument.set("_etag", feedItem.get("_etag"))
                streamDocument.set("_attachments", feedItem.get("_attachments"))
                streamDocument.set("_ts", feedItem.get("_ts"))

                cfDocuments.add(streamDocument)
              }
            }
          }
          logDebug(s"Receving ${cfDocuments.length.toString()} change feed items ${if (cfDocuments.nonEmpty) cfDocuments(0)}")
          
          if (cfDocuments.length > 0)
          {
            pageCount += 1;
          }

          if (pageCount >= maxPagesPerBatch)
          {
            nextContinuation = previousBlockStartContinuation + "|" + feedItems.last.get("id")

            logDebug(s"    readChangeFeed.MaxPageCountExceeded NextContinuation: ${nextContinuation}")
            break;
          }
          else
          {
            // next Continuation Token is plain and simple the same as the latest HTTP response
            // Expected when all records of the current page have been processed
            // Will only get returned to the caller when the changefeed has been processed completely
            // as a continuation token that the caller can use afterwards to see whether the changefeed 
            // contains new change record documents
            nextContinuation = feedResponse.getResponseContinuation()

            previousBlockStartContinuation = blockStartContinuation
            blockStartContinuation = nextContinuation

            logDebug(s"    readChangeFeed.EndInWhile NextContinuation: ${nextContinuation}, blockStartContinuation: ${blockStartContinuation}, previousBlockStartContinuation: ${previousBlockStartContinuation}")
          }
        }
      }
      
      logDebug(s"<-- readChangeFeed, Count: ${cfDocuments.length.toString()}, NextContinuation: ${nextContinuation}")
      
      updateTokenFunc(originalContinuation, nextContinuation, partitionId)
      logDebug(s"changeFeedOptions.partitionKeyRangeId = ${partitionId}, continuation = $originalContinuation, new token = ${nextContinuation}")
      cfDocuments.iterator()
    } else 
    {
      // next Continuation Token is plain and simple when not using Streaming because
      // all records will be processed. The parameter 'maxPagesPerBatch' is irrelevant
      // in this case - so there doesn't need to be any suffix in the continutaion token returned
      nextContinuation = feedResponse.getResponseContinuation()
      logDebug(s"<-- readChangeFeed, Non-Streaming, NextContinuation: ${nextContinuation}")
      new ContinuationTokenTrackingIterator[Document](
            feedResponse,
            updateTokenFunc,
            (msg:String) => logDebug(msg),
            partitionId
          )
    }
  }

  def upsertDocument(collectionLink: String,
                      document: Document,
                      requestOptions: RequestOptions): Unit = {
    logTrace(s"Upserting document $document")
    documentClient.upsertDocument(collectionLink, document, requestOptions, false)
  }

  def isDocumentCollectionEmpty: Boolean = {
    logDebug(s"Reading collection $collectionLink")
    var requestOptions = new RequestOptions
    requestOptions.setPopulateQuotaInfo(true)
    val response = documentClient.readCollection(collectionLink, requestOptions)
    if (collection == null) {
      collection = response.getResource
    }
    response.getDocumentCountUsage == 0
  }
}

