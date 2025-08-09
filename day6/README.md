# Day 6: AWS Streaming Services (Kinesis, MSK, KDA) - Complete Learning Guide

## 📚 Learning Overview
Today you'll master AWS's managed streaming services, learning to build cloud-native streaming architectures. You'll understand the trade-offs between different AWS services and learn to design cost-effective, scalable solutions using Kinesis, MSK, and Kinesis Data Analytics.

## 🎯 Learning Objectives
By the end of today, you will be able to:
- Design and implement solutions with Amazon Kinesis Data Streams
- Deploy and manage Amazon MSK clusters for Kafka workloads  
- Build applications with Kinesis Data Analytics and Apache Flink
- Integrate streaming services with the broader AWS ecosystem
- Implement serverless streaming architectures with Lambda
- Optimize costs and performance for production workloads
- Apply security best practices for cloud streaming services

---

## 📖 PART 1: Amazon Kinesis Data Streams Deep Dive

### Understanding Kinesis Architecture

#### Core Components
**Kinesis Data Streams** is AWS's fully managed streaming data service that can continuously capture gigabytes of data per second from hundreds of thousands of sources.

```
Producer → Kinesis Stream (Shards) → Consumer Applications
         ↗                      ↘
    Application                  Lambda
    Mobile App                   KDA
    IoT Device                   Custom App
```

#### Shards: The Scaling Unit
- **Write Capacity**: 1 MB/sec or 1,000 records/sec per shard
- **Read Capacity**: 2 MB/sec per shard (5 transactions/sec max per consumer)
- **Retention**: 1 hour (default) to 365 days
- **Ordering**: Within a shard, records are ordered by sequence number

#### Partition Key Strategy
```python
import hashlib
import boto3

def calculate_shard_for_key(partition_key, shard_count):
    """
    Determine which shard a partition key routes to
    (same algorithm Kinesis uses)
    """
    hash_key = hashlib.md5(partition_key.encode()).hexdigest()
    hash_key_decimal = int(hash_key, 16)
    shard_id = hash_key_decimal % shard_count
    return f"shardId-{shard_id:012d}"

# Example: Even distribution
users = ["user1", "user2", "user3", "user4"]
for user in users:
    shard = calculate_shard_for_key(user, 4)
    print(f"{user} → {shard}")

# Hot partition example (avoid this!)
# All records with same key go to same shard
hot_key = "popular_product_123"
shard = calculate_shard_for_key(hot_key, 4)
print(f"All {hot_key} records → {shard}")  # Bottleneck!
```

### Producer Patterns

#### Basic Producer (Low throughput)
```python
import boto3
import json
from datetime import datetime
import uuid

def basic_kinesis_producer():
    kinesis = boto3.client('kinesis', region_name='us-east-1')
    
    # Simple put_record
    response = kinesis.put_record(
        StreamName='user-events',
        Data=json.dumps({
            'event_id': str(uuid.uuid4()),
            'user_id': 'user_123',
            'event_type': 'page_view',
            'timestamp': datetime.now().isoformat(),
            'page': '/products/laptop'
        }),
        PartitionKey='user_123'  # Routes to specific shard
    )
    
    return response['ShardId'], response['SequenceNumber']

# Batch producer (better throughput)
def batch_kinesis_producer(events):
    kinesis = boto3.client('kinesis', region_name='us-east-1')
    
    # Prepare records for batch
    records = []
    for event in events:
        records.append({
            'Data': json.dumps(event),
            'PartitionKey': event['user_id']
        })
        
        # Kinesis batch limit: 500 records or 5MB
        if len(records) == 500:
            response = kinesis.put_records(
                StreamName='user-events',
                Records=records
            )
            
            # Handle failed records
            failed_records = []
            for i, record in enumerate(response['Records']):
                if 'ErrorCode' in record:
                    failed_records.append(records[i])
            
            if failed_records:
                print(f"Retrying {len(failed_records)} failed records")
                # Implement exponential backoff retry logic
            
            records = []
    
    # Send remaining records
    if records:
        kinesis.put_records(StreamName='user-events', Records=records)
```

#### Kinesis Producer Library (KPL) - High Throughput
```java
import com.amazonaws.services.kinesis.producer.KinesisProducer;
import com.amazonaws.services.kinesis.producer.KinesisProducerConfiguration;
import com.amazonaws.services.kinesis.producer.UserRecordResult;
import com.google.common.util.concurrent.ListenableFuture;

public class HighThroughputProducer {
    
    private final KinesisProducer producer;
    
    public HighThroughputProducer() {
        KinesisProducerConfiguration config = new KinesisProducerConfiguration()
            .setRegion("us-east-1")
            .setAggregationEnabled(true)        // Pack multiple records
            .setCompressionEnabled(true)        // Reduce network usage
            .setRecordMaxBufferedTime(15000)    // Max 15s batching
            .setMaxConnections(24)              // HTTP connection pool
            .setRequestTimeout(60000)           // 60s timeout
            .setRecordTtl(30000);              // 30s TTL
        
        this.producer = new KinesisProducer(config);
    }
    
    public void sendEvent(String streamName, Event event) {
        String data = JsonUtils.toJson(event);
        String partitionKey = event.getUserId();
        
        ListenableFuture<UserRecordResult> future = producer.addUserRecord(
            streamName, 
            partitionKey, 
            ByteBuffer.wrap(data.getBytes())
        );
        
        // Handle completion asynchronously
        Futures.addCallback(future, new FutureCallback<UserRecordResult>() {
            @Override
            public void onSuccess(UserRecordResult result) {
                System.out.println("Record sent successfully: " + 
                    result.getShardId() + ":" + result.getSequenceNumber());
            }
            
            @Override
            public void onFailure(Throwable t) {
                // Implement retry logic with exponential backoff
                System.err.println("Failed to send record: " + t.getMessage());
                retryWithBackoff(streamName, event, 1);
            }
        }, executorService);
    }
    
    private void retryWithBackoff(String streamName, Event event, int attempt) {
        if (attempt > 3) {
            // Send to DLQ or log for manual processing
            deadLetterQueue.add(event);
            return;
        }
        
        // Exponential backoff: 1s, 2s, 4s
        long delay = (long) Math.pow(2, attempt) * 1000;
        scheduler.schedule(() -> {
            sendEvent(streamName, event);
        }, delay, TimeUnit.MILLISECONDS);
    }
}
```

### Consumer Patterns

#### Kinesis Client Library (KCL) - Scalable Consumer
```java
public class ScalableKinesisConsumer implements IRecordProcessor {
    private String shardId;
    private final MetricRegistry metrics = new MetricRegistry();
    private final Counter processedRecords;
    private final Timer processingLatency;
    
    public ScalableKinesisConsumer() {
        this.processedRecords = metrics.counter("records.processed");
        this.processingLatency = metrics.timer("processing.latency");
    }
    
    @Override
    public void initialize(InitializationInput initializationInput) {
        this.shardId = initializationInput.getShardId();
        System.out.println("Initialized consumer for shard: " + shardId);
    }
    
    @Override
    public void processRecords(ProcessRecordsInput processRecordsInput) {
        Timer.Context context = processingLatency.time();
        
        try {
            List<Record> records = processRecordsInput.getRecords();
            
            // Process records in batches for efficiency
            List<Event> eventBatch = new ArrayList<>();
            
            for (Record record : records) {
                try {
                    String data = StandardCharsets.UTF_8.decode(record.getData()).toString();
                    Event event = JsonUtils.fromJson(data, Event.class);
                    
                    eventBatch.add(event);
                    
                    // Process in batches of 100
                    if (eventBatch.size() >= 100) {
                        processBatch(eventBatch);
                        eventBatch.clear();
                    }
                    
                    processedRecords.inc();
                    
                } catch (Exception e) {
                    // Send problematic record to DLQ
                    sendToDeadLetterQueue(record, e);
                }
            }
            
            // Process remaining records
            if (!eventBatch.isEmpty()) {
                processBatch(eventBatch);
            }
            
            // Checkpoint progress
            processRecordsInput.getCheckpointer().checkpoint();
            
        } catch (Exception e) {
            System.err.println("Error processing records: " + e.getMessage());
            // Don't checkpoint on error - retry the batch
        } finally {
            context.stop();
        }
    }
    
    private void processBatch(List<Event> events) {
        // Batch operations for efficiency
        // Example: Batch write to DynamoDB
        DynamoDB dynamoDB = new DynamoDB(AmazonDynamoDBClientBuilder.defaultClient());
        Table table = dynamoDB.getTable("user-events");
        
        TableWriteItems tableWriteItems = new TableWriteItems("user-events");
        
        for (Event event : events) {
            Item item = new Item()
                .withPrimaryKey("user_id", event.getUserId())
                .withString("event_type", event.getEventType())
                .withLong("timestamp", event.getTimestamp())
                .withMap("properties", event.getProperties());
            
            tableWriteItems.addItemToPut(item);
        }
        
        try {
            BatchWriteItemOutcome outcome = dynamoDB.batchWriteItem(tableWriteItems);
            
            // Handle unprocessed items
            if (outcome.getUnprocessedItems().size() > 0) {
                // Retry unprocessed items with exponential backoff
                retryUnprocessedItems(outcome.getUnprocessedItems());
            }
            
        } catch (Exception e) {
            System.err.println("Batch write failed: " + e.getMessage());
            throw e; // Don't checkpoint if batch processing fails
        }
    }
}

// KCL Application Setup
public class KinesisConsumerApplication {
    public static void main(String[] args) {
        KinesisClientLibConfiguration config = new KinesisClientLibConfiguration(
            "my-consumer-app",                    // Application name
            "user-events",                        // Stream name
            DefaultAWSCredentialsProviderChain.getInstance(),
            "consumer-worker-1"                   // Worker ID
        )
        .withInitialPositionInStream(InitialPositionInStream.LATEST)
        .withMaxRecords(10000)                    // Records per batch
        .withIdleTimeBetweenReadsInMillis(1000)   // Polling frequency
        .withFailoverTimeMillis(10000)            // Failover time
        .withShardSyncIntervalMillis(60000)       // Shard discovery interval
        .withCleanupLeasesUponShardCompletion(true);
        
        IRecordProcessorFactory factory = () -> new ScalableKinesisConsumer();
        
        Worker worker = new Worker.Builder()
            .recordProcessorFactory(factory)
            .config(config)
            .build();
        
        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down consumer...");
            worker.shutdown();
        }));
        
        worker.run();
    }
}
```

### Advanced Kinesis Patterns

#### Enhanced Fan-Out (EFO) Consumer
```java
public class EnhancedFanOutConsumer {
    private final KinesisAsyncClient kinesisClient;
    private final String streamName;
    private final String consumerName;
    
    public EnhancedFanOutConsumer(String streamName, String consumerName) {
        this.streamName = streamName;
        this.consumerName = consumerName;
        this.kinesisClient = KinesisAsyncClient.builder()
            .region(Region.US_EAST_1)
            .build();
    }
    
    public void startConsumingWithEFO() {
        // Register enhanced fan-out consumer
        RegisterStreamConsumerRequest registerRequest = RegisterStreamConsumerRequest.builder()
            .streamARN(getStreamARN())
            .consumerName(consumerName)
            .build();
        
        kinesisClient.registerStreamConsumer(registerRequest)
            .thenCompose(response -> {
                String consumerARN = response.consumer().consumerARN();
                return subscribeToShard(consumerARN);
            })
            .exceptionally(throwable -> {
                System.err.println("Failed to register consumer: " + throwable.getMessage());
                return null;
            });
    }
    
    private CompletableFuture<Void> subscribeToShard(String consumerARN) {
        // Subscribe to all shards
        return listShards()
            .thenCompose(shards -> {
                List<CompletableFuture<Void>> subscriptions = shards.stream()
                    .map(shard -> subscribeToSingleShard(consumerARN, shard.shardId()))
                    .collect(Collectors.toList());
                
                return CompletableFuture.allOf(subscriptions.toArray(new CompletableFuture[0]));
            });
    }
    
    private CompletableFuture<Void> subscribeToSingleShard(String consumerARN, String shardId) {
        SubscribeToShardRequest subscribeRequest = SubscribeToShardRequest.builder()
            .consumerARN(consumerARN)
            .shardId(shardId)
            .startingPosition(StartingPosition.builder()
                .type(ShardIteratorType.LATEST)
                .build())
            .build();
        
        // Real-time subscription with reactive streams
        return kinesisClient.subscribeToShard(subscribeRequest, new SubscribeToShardResponseHandler() {
            @Override
            public void responseReceived(SubscribeToShardResponse response) {
                // Handle initial response
            }
            
            @Override
            public void onEventStream(SdkPublisher<SubscribeToShardEventStream> publisher) {
                publisher.subscribe(event -> {
                    if (event instanceof SubscribeToShardEvent) {
                        SubscribeToShardEvent shardEvent = (SubscribeToShardEvent) event;
                        processRecords(shardEvent.records(), shardId);
                    }
                });
            }
            
            @Override
            public void exceptionOccurred(Throwable throwable) {
                System.err.println("Error in shard subscription: " + throwable.getMessage());
                // Implement retry logic
                retrySubscription(consumerARN, shardId);
            }
        });
    }
    
    private void processRecords(List<Record> records, String shardId) {
        System.out.println(String.format("Processing %d records from shard %s", 
            records.size(), shardId));
        
        records.parallelStream().forEach(record -> {
            try {
                String data = record.data().asUtf8String();
                Event event = JsonUtils.fromJson(data, Event.class);
                
                // Process individual record
                processEvent(event);
                
                // EFO provides automatic checkpointing
                
            } catch (Exception e) {
                System.err.println("Failed to process record: " + e.getMessage());
                // Send to DLQ for manual processing
                sendToDeadLetterQueue(record, e);
            }
        });
    }
}
```

---

## 📖 PART 2: Amazon MSK (Managed Streaming for Apache Kafka)

### MSK Architecture and Setup

#### Understanding MSK Benefits
Amazon MSK removes the operational overhead of running Apache Kafka while providing:
- **Fully Managed**: Automatic provisioning, configuration, and maintenance
- **High Availability**: Multi-AZ deployment with automatic failover
- **Security**: VPC isolation, encryption, IAM integration
- **Monitoring**: CloudWatch metrics and logging
- **Compatibility**: 100% compatible with Apache Kafka APIs

#### MSK Cluster Configuration
```yaml
# CloudFormation template for production MSK cluster
AWSTemplateFormatVersion: '2010-09-09'
Description: 'Production MSK Cluster'

Parameters:
  VpcId:
    Type: AWS::EC2::VPC::Id
    Description: VPC for MSK cluster
    
  PrivateSubnetIds:
    Type: List<AWS::EC2::Subnet::Id>
    Description: Private subnets for broker placement

Resources:
  MSKCluster:
    Type: AWS::MSK::Cluster
    Properties:
      ClusterName: !Sub '${AWS::StackName}-msk-cluster'
      KafkaVersion: '2.8.1'
      NumberOfBrokerNodes: 6  # 2 brokers per AZ for HA
      
      BrokerNodeGroupInfo:
        InstanceType: kafka.m5.xlarge    # Choose based on throughput needs
        ClientSubnets: !Ref PrivateSubnetIds
        SecurityGroups:
          - !Ref MSKSecurityGroup
        StorageInfo:
          EBSStorageInfo:
            VolumeSize: 1000              # GB per broker
            ProvisionedThroughput:
              Enabled: true
              VolumeThroughput: 250       # MiB/s
        ConnectivityInfo:
          PublicAccess:
            Type: DISABLED              # Private cluster
            
      # Encryption configuration
      EncryptionInfo:
        EncryptionInTransit:
          ClientBroker: TLS             # TLS for client-broker
          InCluster: true               # TLS for inter-broker
        EncryptionAtRest:
          DataVolumeKMSKeyId: !Ref MSKKMSKey
          
      # Authentication
      ClientAuthentication:
        Unauthenticated:
          Enabled: false
        Sasl:
          Iam:
            Enabled: true               # Use IAM for authentication
            
      # Configuration
      ConfigurationInfo:
        Arn: !Ref MSKConfiguration
        Revision: 1
        
      # Monitoring
      EnhancedMonitoring: PER_TOPIC_PER_BROKER
      LoggingInfo:
        BrokerLogs:
          CloudWatchLogs:
            Enabled: true
            LogGroup: !Ref MSKLogGroup
          Firehose:
            Enabled: false
          S3:
            Enabled: false

  # Custom Kafka configuration
  MSKConfiguration:
    Type: AWS::MSK::Configuration
    Properties:
      Name: !Sub '${AWS::StackName}-msk-config'
      KafkaVersionsList: 
        - '2.8.1'
      ServerProperties: |
        # Broker configuration for high throughput
        num.network.threads=8
        num.io.threads=16
        socket.send.buffer.bytes=102400
        socket.receive.buffer.bytes=102400
        socket.request.max.bytes=104857600
        
        # Log configuration
        num.partitions=3
        default.replication.factor=3
        min.insync.replicas=2
        unclean.leader.election.enable=false
        
        # Retention settings
        log.retention.hours=168
        log.retention.bytes=1073741824
        log.segment.bytes=1073741824
        log.cleanup.policy=delete
        
        # Performance tuning
        replica.fetch.max.bytes=1048576
        message.max.bytes=1000012
        replica.fetch.response.max.bytes=10485760
        group.initial.rebalance.delay.ms=3000

  # Security group for MSK
  MSKSecurityGroup:
    Type: AWS::EC2::SecurityGroup
    Properties:
      GroupDescription: Security group for MSK cluster
      VpcId: !Ref VpcId
      SecurityGroupIngress:
        # Kafka brokers (PLAINTEXT)
        - IpProtocol: tcp
          FromPort: 9092
          ToPort: 9092
          SourceSecurityGroupId: !Ref ClientSecurityGroup
        # Kafka brokers (TLS)
        - IpProtocol: tcp
          FromPort: 9094
          ToPort: 9094
          SourceSecurityGroupId: !Ref ClientSecurityGroup
        # Kafka brokers (SASL)
        - IpProtocol: tcp
          FromPort: 9096
          ToPort: 9096
          SourceSecurityGroupId: !Ref ClientSecurityGroup
        # Zookeeper
        - IpProtocol: tcp
          FromPort: 2181
          ToPort: 2181
          SourceSecurityGroupId: !Ref ClientSecurityGroup

  # KMS key for encryption
  MSKKMSKey:
    Type: AWS::KMS::Key
    Properties:
      Description: KMS key for MSK cluster encryption
      KeyPolicy:
        Statement:
          - Sid: Enable IAM User Permissions
            Effect: Allow
            Principal:
              AWS: !Sub 'arn:aws:iam::${AWS::AccountId}:root'
            Action: 'kms:*'
            Resource: '*'
          - Sid: Allow MSK service
            Effect: Allow
            Principal:
              Service: kafka.amazonaws.com
            Action:
              - kms:Encrypt
              - kms:Decrypt
              - kms:ReEncrypt*
              - kms:GenerateDataKey*
              - kms:DescribeKey
            Resource: '*'
            
Outputs:
  MSKClusterArn:
    Description: ARN of the MSK cluster
    Value: !Ref MSKCluster
    Export:
      Name: !Sub '${AWS::StackName}-MSKClusterArn'
      
  BootstrapServers:
    Description: Bootstrap servers for the MSK cluster
    Value: !GetAtt MSKCluster.BootstrapBrokerString
```

### MSK with IAM Authentication

#### IAM Policies for MSK Access
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "MSKClusterAccess",
      "Effect": "Allow",
      "Action": [
        "kafka-cluster:Connect",
        "kafka-cluster:AlterCluster",
        "kafka-cluster:DescribeCluster"
      ],
      "Resource": "arn:aws:kafka:us-east-1:123456789012:cluster/production-cluster/*"
    },
    {
      "Sid": "MSKTopicAccess",
      "Effect": "Allow",
      "Action": [
        "kafka-cluster:CreateTopic",
        "kafka-cluster:DeleteTopic",
        "kafka-cluster:AlterTopic",
        "kafka-cluster:DescribeTopic"
      ],
      "Resource": "arn:aws:kafka:us-east-1:123456789012:topic/production-cluster/*"
    },
    {
      "Sid": "MSKProducerAccess",
      "Effect": "Allow",
      "Action": [
        "kafka-cluster:WriteData"
      ],
      "Resource": [
        "arn:aws:kafka:us-east-1:123456789012:topic/production-cluster/*/user-events",
        "arn:aws:kafka:us-east-1:123456789012:topic/production-cluster/*/order-events"
      ]
    },
    {
      "Sid": "MSKConsumerAccess", 
      "Effect": "Allow",
      "Action": [
        "kafka-cluster:ReadData"
      ],
      "Resource": [
        "arn:aws:kafka:us-east-1:123456789012:topic/production-cluster/*/user-events",
        "arn:aws:kafka:us-east-1:123456789012:group/production-cluster/*/analytics-consumer-*"
      ]
    }
  ]
}
```

#### Java Client with IAM Authentication
```java
public class MSKProducerWithIAM {
    
    public static void main(String[] args) {
        Properties props = new Properties();
        
        // MSK bootstrap servers
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, 
            "b-1.prod-msk.abc123.c2.kafka.us-east-1.amazonaws.com:9096,b-2.prod-msk.abc123.c2.kafka.us-east-1.amazonaws.com:9096");
        
        // Serializers
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        
        // IAM authentication configuration
        props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
        props.put(SaslConfigs.SASL_MECHANISM, "AWS_MSK_IAM");
        props.put(SaslConfigs.SASL_JAAS_CONFIG, "software.amazon.msk.auth.iam.IAMLoginModule required;");
        props.put(SaslConfigs.SASL_CLIENT_CALLBACK_HANDLER_CLASS, "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
        
        // Performance tuning
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 1);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        
        try (Producer<String, String> producer = new KafkaProducer<>(props)) {
            
            // Send records with proper error handling
            for (int i = 0; i < 1000; i++) {
                UserEvent event = createUserEvent(i);
                
                ProducerRecord<String, String> record = new ProducerRecord<>(
                    "user-events",
                    event.getUserId(),
                    JsonUtils.toJson(event)
                );
                
                producer.send(record, (metadata, exception) -> {
                    if (exception != null) {
                        System.err.println("Failed to send record: " + exception.getMessage());
                        // Implement retry logic or send to DLQ
                    } else {
                        System.out.printf("Sent record to partition %d offset %d%n", 
                            metadata.partition(), metadata.offset());
                    }
                });
            }
            
            // Ensure all records are sent
            producer.flush();
            
        } catch (Exception e) {
            System.err.println("Producer error: " + e.getMessage());
        }
    }
}
```

### MSK Connect (Managed Kafka Connect)

#### Setting Up MSK Connect
```yaml
MSKConnectWorkerConfiguration:
  Type: AWS::KafkaConnect::WorkerConfiguration
  Properties:
    Name: !Sub '${AWS::StackName}-connect-config'
    PropertiesFileContent: |
      # Kafka Connect worker configuration
      key.converter=org.apache.kafka.connect.json.JsonConverter
      value.converter=org.apache.kafka.connect.json.JsonConverter
      key.converter.schemas.enable=false
      value.converter.schemas.enable=false
      
      # Offset storage
      offset.storage.topic=__amazon_msk_connect_offsets
      offset.storage.replication.factor=3
      offset.storage.partitions=25
      
      # Configuration storage
      config.storage.topic=__amazon_msk_connect_configs
      config.storage.replication.factor=3
      
      # Status storage
      status.storage.topic=__amazon_msk_connect_status
      status.storage.replication.factor=3
      status.storage.partitions=5

MSKConnectConnector:
  Type: AWS::KafkaConnect::Connector
  Properties:
    ConnectorName: !Sub '${AWS::StackName}-s3-sink'
    KafkaCluster:
      ApacheKafkaCluster:
        BootstrapServers: !GetAtt MSKCluster.BootstrapBrokerStringTls
        Vpc:
          SecurityGroups:
            - !Ref MSKConnectSecurityGroup
          Subnets: !Ref PrivateSubnetIds
    
    KafkaConnectVersion: '2.7.1'
    Capacity:
      AutoScaling:
        MaxWorkerCount: 10
        MinWorkerCount: 2
        ScaleInPolicy:
          CpuUtilizationPercentage: 20
        ScaleOutPolicy:
          CpuUtilizationPercentage: 80
        McuCount: 1
    
    ConnectorConfiguration:
      'connector.class': 'io.confluent.connect.s3.S3SinkConnector'
      'tasks.max': '4'
      'topics': 'user-events,order-events'
      'topics.dir': 'kafka-data'
      'flush.size': '1000'
      'rotate.interval.ms': '60000'
      'timezone': 'UTC'
      'partitioner.class': 'io.confluent.connect.storage.partitioner.TimeBasedPartitioner'
      'partition.duration.ms': '3600000'  # Hourly partitions
      'timestamp.extractor': 'Record'
      'path.format': "'year'=YYYY/'month'=MM/'day'=dd/'hour'=HH"
      's3.region': 'us-east-1'
      's3.bucket.name': !Ref DataLakeBucket
      'storage.class': 'io.confluent.connect.s3.storage.S3Storage'
      'format.class': 'io.confluent.connect.s3.format.json.JsonFormat'
      'schema.compatibility': 'NONE'
      
    Plugins:
      - CustomPlugin:
          CustomPluginArn: !Ref S3SinkPlugin
          Revision: 1
    
    ServiceExecutionRoleArn: !GetAtt MSKConnectServiceRole.Arn
    WorkerConfiguration:
      WorkerConfigurationArn: !Ref MSKConnectWorkerConfiguration
      Revision: 1
```

---

## 📖 PART 3: Kinesis Data Analytics (KDA) with Apache Flink

### Understanding KDA Architecture

#### KDA vs Self-Managed Flink
**Kinesis Data Analytics** provides a fully managed Apache Flink service that handles:
- **Infrastructure Management**: No servers to provision or manage
- **Auto-scaling**: Automatic scaling based on parallelism units (KPUs)
- **Fault Tolerance**: Automatic checkpointing and recovery
- **Monitoring**: Built-in CloudWatch metrics and logging
- **Security**: VPC integration and IAM access control

#### KDA Application Structure
```
Application JAR
├── Main Class (implements StreamingJob)
├── Dependencies (Flink libraries)
├── Configuration (application.properties)
└── Resources (static files, schemas)
```

### Building KDA Applications

#### Comprehensive KDA Streaming Application
```java
public class RealTimeAnalyticsKDA {
    
    private static final Logger LOG = LoggerFactory.getLogger(RealTimeAnalyticsKDA.class);
    private static final String APPLICATION_CONFIG_GROUP = "kinesis.analytics.flink.run.options";
    
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        
        // Configure for KDA
        configureForKDA(env);
        
        // Get application properties
        Map<String, Properties> applicationProperties = KinesisAnalyticsRuntime.getApplicationProperties();
        Properties flinkProperties = applicationProperties.get(APPLICATION_CONFIG_GROUP);
        
        // Build streaming pipeline
        DataStream<UserEvent> userEvents = createKinesisSource(env, flinkProperties);
        
        // Complex multi-window analytics
        buildAnalyticsPipeline(userEvents, flinkProperties);
        
        env.execute("Real-time Analytics KDA Application");
    }
    
    private static void configureForKDA(StreamExecutionEnvironment env) {
        // Enable checkpointing for exactly-once processing
        env.enableCheckpointing(Duration.ofMinutes(1), CheckpointingMode.EXACTLY_ONCE);
        
        CheckpointConfig checkpointConfig = env.getCheckpointConfig();
        checkpointConfig.setMinPauseBetweenCheckpoints(Duration.ofSeconds(30));
        checkpointConfig.setCheckpointTimeout(Duration.ofMinutes(10));
        checkpointConfig.setMaxConcurrentCheckpoints(1);
        
        // Restart strategy
        env.setRestartStrategy(RestartStrategies.exponentialDelayRestart(
            3,                              // max failures
            Duration.ofSeconds(10),         // initial delay
            Duration.ofMinutes(2),          // max delay
            2.0                            // backoff multiplier
        ));
        
        // State backend (KDA handles this, but good to be explicit)
        env.setStateBackend(new HashMapStateBackend());
        env.getCheckpointConfig().setCheckpointStorage("file:///tmp/checkpoints");
    }
    
    private static DataStream<UserEvent> createKinesisSource(
            StreamExecutionEnvironment env, 
            Properties properties) {
        
        String inputStreamName = properties.getProperty("input.stream.name", "user-events");
        String awsRegion = properties.getProperty("aws.region", "us-east-1");
        
        // Configure Kinesis source
        Properties sourceConfig = new Properties();
        sourceConfig.setProperty(ConsumerConfigConstants.AWS_REGION, awsRegion);
        sourceConfig.setProperty(ConsumerConfigConstants.STREAM_INITIAL_POSITION, "LATEST");
        sourceConfig.setProperty(ConsumerConfigConstants.SHARD_GETRECORDS_MAX, "10000");
        sourceConfig.setProperty(ConsumerConfigConstants.SHARD_GETRECORDS_INTERVAL_MILLIS, "1000");
        
        // Watermark strategy for event time processing
        WatermarkStrategy<UserEvent> watermarkStrategy = WatermarkStrategy
            .<UserEvent>forBoundedOutOfOrderness(Duration.ofMinutes(5))
            .withTimestampAssigner((event, timestamp) -> event.getEventTime())
            .withIdleness(Duration.ofMinutes(1));
        
        return env
            .addSource(new FlinkKinesisConsumer<>(
                inputStreamName,
                new UserEventDeserializationSchema(),
                sourceConfig
            ))
            .assignTimestampsAndWatermarks(watermarkStrategy)
            .name("Kinesis Source");
    }
    
    private static void buildAnalyticsPipeline(DataStream<UserEvent> events, Properties properties) {
        
        // Real-time user session analysis
        DataStream<UserSession> sessions = events
            .keyBy(UserEvent::getUserId)
            .window(EventTimeSessionWindows.withGap(Time.minutes(30)))
            .aggregate(new SessionAggregator())
            .name("Session Analytics");
        
        // Real-time product popularity (sliding window)
        DataStream<ProductPopularity> productMetrics = events
            .filter(event -> "product_view".equals(event.getEventType()))
            .keyBy(event -> event.getProperties().get("product_id"))
            .window(SlidingEventTimeWindows.of(Time.hours(1), Time.minutes(5)))
            .aggregate(new ProductPopularityAggregator())
            .name("Product Popularity");
        
        // Anomaly detection with CEP
        Pattern<UserEvent, ?> suspiciousPattern = Pattern.<UserEvent>begin("rapid_clicks")
            .where(event -> "click".equals(event.getEventType()))
            .timesOrMore(10)
            .within(Time.minutes(1));
        
        DataStream<Alert> anomalies = CEP.pattern(
                events.keyBy(UserEvent::getUserId), 
                suspiciousPattern
            )
            .select(new AnomalySelector())
            .name("Anomaly Detection");
        
        // Output to different destinations
        outputToDestinations(sessions, productMetrics, anomalies, properties);
    }
    
    private static void outputToDestinations(
            DataStream<UserSession> sessions,
            DataStream<ProductPopularity> productMetrics,
            DataStream<Alert> anomalies,
            Properties properties) {
        
        String outputStreamName = properties.getProperty("output.stream.name", "analytics-output");
        String alertsStreamName = properties.getProperty("alerts.stream.name", "alerts-output");
        String awsRegion = properties.getProperty("aws.region", "us-east-1");
        
        // Configure Kinesis sink
        Properties sinkConfig = new Properties();
        sinkConfig.setProperty(AWSConfigConstants.AWS_REGION, awsRegion);
        sinkConfig.setProperty(ProducerConfigConstants.AGGREGATION_ENABLED, "true");
        sinkConfig.setProperty(ProducerConfigConstants.AGGREGATION_MAX_COUNT, "4294967295");
        sinkConfig.setProperty(ProducerConfigConstants.AGGREGATION_MAX_SIZE, "51200");
        
        // Sessions to Kinesis
        sessions
            .map(new SessionToJsonMapper())
            .addSink(new FlinkKinesisProducer<>(
                new SimpleStringSchema(),
                sinkConfig
            ).withDestinationStream(outputStreamName))
            .name("Sessions Sink");
        
        // Product metrics to Kinesis
        productMetrics
            .map(new ProductMetricsToJsonMapper()) 
            .addSink(new FlinkKinesisProducer<>(
                new SimpleStringSchema(),
                sinkConfig
            ).withDestinationStream(outputStreamName))
            .name("Product Metrics Sink");
        
        // Anomalies to alerts stream
        anomalies
            .map(new AlertToJsonMapper())
            .addSink(new FlinkKinesisProducer<>(
                new SimpleStringSchema(),
                sinkConfig
            ).withDestinationStream(alertsStreamName))
            .name("Alerts Sink");
        
        // Also send high-priority alerts to CloudWatch
        anomalies
            .filter(alert -> alert.getSeverity().equals("HIGH"))
            .addSink(new CloudWatchMetricSink())
            .name("CloudWatch Alerts");
    }
}

// Custom aggregators and functions
public class SessionAggregator implements AggregateFunction<UserEvent, SessionAccumulator, UserSession> {
    
    @Override
    public SessionAccumulator createAccumulator() {
        return new SessionAccumulator();
    }
    
    @Override
    public SessionAccumulator add(UserEvent event, SessionAccumulator accumulator) {
        accumulator.addEvent(event);
        return accumulator;
    }
    
    @Override
    public UserSession getResult(SessionAccumulator accumulator) {
        return accumulator.toUserSession();
    }
    
    @Override
    public SessionAccumulator merge(SessionAccumulator a, SessionAccumulator b) {
        return a.merge(b);
    }
}

public class CloudWatchMetricSink extends RichSinkFunction<Alert> {
    private transient CloudWatchAsyncClient cloudWatch;
    
    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        this.cloudWatch = CloudWatchAsyncClient.builder()
            .region(Region.of(getRuntimeContext().getExecutionConfig()
                .getGlobalJobParameters().toMap().get("aws.region")))
            .build();
    }
    
    @Override
    public void invoke(Alert alert, Context context) throws Exception {
        PutMetricDataRequest request = PutMetricDataRequest.builder()
            .namespace("RealTimeAnalytics")
            .metricData(MetricDatum.builder()
                .metricName("HighSeverityAlerts")
                .value(1.0)
                .unit(StandardUnit.COUNT)
                .timestamp(Instant.now())
                .dimensions(Dimension.builder()
                    .name("AlertType")
                    .value(alert.getType())
                    .build())
                .build())
            .build();
        
        cloudWatch.putMetricData(request).get();
    }
}
```

#### KDA Application Configuration
```yaml
# CloudFormation for KDA application
KDAApplication:
  Type: AWS::KinesisAnalyticsV2::Application
  Properties:
    ApplicationName: !Sub '${AWS::StackName}-real-time-analytics'
    ApplicationDescription: 'Real-time user behavior analytics'
    RuntimeEnvironment: FLINK-1_15
    ServiceExecutionRole: !GetAtt KDAServiceRole.Arn
    
    ApplicationConfiguration:
      ApplicationCodeConfiguration:
        CodeContent:
          S3ContentLocation:
            BucketARN: !GetAtt CodeBucket.Arn
            FileKey: 'real-time-analytics-1.0.jar'
        CodeContentType: ZIPFILE
        
      ApplicationSnapshotConfiguration:
        SnapshotsEnabled: true
        
      VpcConfiguration:
        SecurityGroupIds:
          - !Ref KDASecurityGroup
        SubnetIds: !Ref PrivateSubnetIds
        
      FlinkApplicationConfiguration:
        CheckpointConfiguration:
          ConfigurationType: CUSTOM
          CheckpointingEnabled: true
          CheckpointInterval: 60000
          MinPauseBetweenCheckpoints: 30000
          
        MonitoringConfiguration:
          ConfigurationType: CUSTOM
          LogLevel: INFO
          MetricsLevel: APPLICATION
          
        ParallelismConfiguration:
          ConfigurationType: CUSTOM
          Parallelism: 4
          ParallelismPerKPU: 1
          AutoScalingEnabled: true
          
      ApplicationPropertyConfiguration:
        PropertyGroups:
          - PropertyGroupId: kinesis.analytics.flink.run.options
            PropertyMap:
              'input.stream.name': 'user-events'
              'output.stream.name': 'analytics-output'
              'alerts.stream.name': 'alerts-output'
              'aws.region': !Ref AWS::Region

# Auto-scaling configuration
ApplicationAutoScaling:
  Type: AWS::ApplicationAutoScaling::ScalableTarget
  Properties:
    MaxCapacity: 32
    MinCapacity: 2
    ResourceId: !Sub 'application/${KDAApplication}'
    RoleARN: !GetAtt ApplicationAutoScalingRole.Arn
    ScalableDimension: kinesisanalyticsv2:application:parallelism
    ServiceNamespace: kinesisanalyticsv2

AutoScalingPolicy:
  Type: AWS::ApplicationAutoScaling::ScalingPolicy
  Properties:
    PolicyName: KDAAutoScalingPolicy
    PolicyType: TargetTrackingScaling
    ScalingTargetId: !Ref ApplicationAutoScaling
    TargetTrackingScalingPolicyConfiguration:
      TargetValue: 70.0
      PredefinedMetricSpecification:
        PredefinedMetricType: KinesisAnalyticsApplicationCpuUtilization
      ScaleInCooldown: 300
      ScaleOutCooldown: 300
```

---

## 📖 PART 4: Serverless Streaming with AWS Lambda

### Lambda for Stream Processing

#### Advanced Lambda Event Processing
```python
import json
import base64
import boto3
from typing import List, Dict, Any
from datetime import datetime, timedelta
import logging
from dataclasses import dataclass
from botocore.exceptions import ClientError
import asyncio
import aiohttp

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

@dataclass
class ProcessingResult:
    success_count: int
    failure_count: int
    errors: List[str]

class StreamProcessor:
    def __init__(self):
        self.dynamodb = boto3.resource('dynamodb')
        self.cloudwatch = boto3.client('cloudwatch')
        self.sns = boto3.client('sns')
        
        # Configuration from environment variables
        self.user_sessions_table = os.environ['USER_SESSIONS_TABLE']
        self.metrics_table = os.environ['METRICS_TABLE']
        self.alert_topic_arn = os.environ['ALERT_TOPIC_ARN']
        
        # Initialize tables
        self.sessions_table = self.dynamodb.Table(self.user_sessions_table)
        self.metrics_table = self.dynamodb.Table(self.metrics_table)
        
    async def process_kinesis_records(self, event: Dict[str, Any]) -> ProcessingResult:
        """Process Kinesis records with error handling and metrics"""
        
        records = event.get('Records', [])
        logger.info(f"Processing {len(records)} Kinesis records")
        
        success_count = 0
        failure_count = 0
        errors = []
        
        # Process records in batches for efficiency
        batch_size = 25  # DynamoDB batch write limit
        
        for i in range(0, len(records), batch_size):
            batch = records[i:i + batch_size]
            
            try:
                result = await self._process_record_batch(batch)
                success_count += result.success_count
                failure_count += result.failure_count
                errors.extend(result.errors)
                
            except Exception as e:
                logger.error(f"Batch processing error: {str(e)}")
                failure_count += len(batch)
                errors.append(f"Batch {i//batch_size + 1} failed: {str(e)}")
        
        # Publish metrics
        await self._publish_processing_metrics(success_count, failure_count)
        
        return ProcessingResult(success_count, failure_count, errors)
    
    async def _process_record_batch(self, records: List[Dict]) -> ProcessingResult:
        """Process a batch of records"""
        
        user_session_updates = []
        metrics_updates = []
        alerts = []
        
        success_count = 0
        failure_count = 0
        errors = []
        
        # Parse and validate records
        for record in records:
            try:
                # Decode Kinesis record
                payload = json.loads(
                    base64.b64decode(record['kinesis']['data']).decode('utf-8')
                )
                
                event_data = self._validate_event(payload)
                
                # Process different event types
                if event_data['event_type'] == 'user_activity':
                    session_update = self._process_user_activity(event_data)
                    if session_update:
                        user_session_updates.append(session_update)
                        
                elif event_data['event_type'] == 'transaction':
                    metric_update = self._process_transaction(event_data)
                    if metric_update:
                        metrics_updates.append(metric_update)
                        
                    # Check for anomalies
                    if self._detect_anomaly(event_data):
                        alert = self._create_alert(event_data)
                        alerts.append(alert)
                
                success_count += 1
                
            except Exception as e:
                logger.error(f"Record processing error: {str(e)}")
                failure_count += 1
                errors.append(str(e))
        
        # Batch write to DynamoDB
        await self._batch_write_to_dynamo(user_session_updates, metrics_updates)
        
        # Send alerts
        await self._send_alerts(alerts)
        
        return ProcessingResult(success_count, failure_count, errors)
    
    def _validate_event(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        """Validate event structure"""
        required_fields = ['event_type', 'user_id', 'timestamp']
        
        for field in required_fields:
            if field not in payload:
                raise ValueError(f"Missing required field: {field}")
        
        # Validate timestamp
        try:
            datetime.fromisoformat(payload['timestamp'].replace('Z', '+00:00'))
        except ValueError:
            raise ValueError("Invalid timestamp format")
        
        return payload
    
    def _process_user_activity(self, event: Dict[str, Any]) -> Dict[str, Any]:
        """Process user activity event"""
        
        user_id = event['user_id']
        timestamp = event['timestamp']
        activity_type = event.get('activity_type', 'unknown')
        
        # Calculate session window (30 minutes)
        event_time = datetime.fromisoformat(timestamp.replace('Z', '+00:00'))
        session_start = event_time.replace(minute=(event_time.minute // 30) * 30, second=0, microsecond=0)
        session_id = f"{user_id}:{int(session_start.timestamp())}"
        
        return {
            'PutRequest': {
                'Item': {
                    'session_id': session_id,
                    'user_id': user_id,
                    'session_start': session_start.isoformat(),
                    'last_activity': timestamp,
                    'activity_count': 1,
                    'activities': [activity_type],
                    'ttl': int((session_start + timedelta(hours=24)).timestamp())
                }
            }
        }
    
    def _process_transaction(self, event: Dict[str, Any]) -> Dict[str, Any]:
        """Process transaction event"""
        
        user_id = event['user_id']
        amount = float(event.get('amount', 0))
        currency = event.get('currency', 'USD')
        timestamp = event['timestamp']
        
        # Create hourly metrics entry
        event_time = datetime.fromisoformat(timestamp.replace('Z', '+00:00'))
        hour_key = event_time.strftime('%Y-%m-%d-%H')
        metric_id = f"{user_id}:{hour_key}"
        
        return {
            'PutRequest': {
                'Item': {
                    'metric_id': metric_id,
                    'user_id': user_id,
                    'hour': hour_key,
                    'transaction_count': 1,
                    'total_amount': amount,
                    'currency': currency,
                    'timestamp': timestamp,
                    'ttl': int((event_time + timedelta(days=30)).timestamp())
                }
            }
        }
    
    def _detect_anomaly(self, event: Dict[str, Any]) -> bool:
        """Simple anomaly detection logic"""
        
        if event['event_type'] == 'transaction':
            amount = float(event.get('amount', 0))
            # Flag transactions over $10,000
            if amount > 10000:
                return True
        
        return False
    
    def _create_alert(self, event: Dict[str, Any]) -> Dict[str, Any]:
        """Create alert for anomalous event"""
        
        return {
            'alert_id': f"alert_{int(datetime.now().timestamp())}",
            'user_id': event['user_id'],
            'event_type': event['event_type'],
            'severity': 'HIGH',
            'message': f"High-value transaction detected: ${event.get('amount', 0)}",
            'timestamp': datetime.now().isoformat(),
            'event_data': event
        }
    
    async def _batch_write_to_dynamo(self, session_updates: List, metric_updates: List):
        """Batch write to DynamoDB with error handling"""
        
        try:
            # Write session updates
            if session_updates:
                with self.sessions_table.batch_writer(overwrite_by_pkeys=['session_id']) as batch:
                    for update in session_updates:
                        batch.put_item(Item=update['PutRequest']['Item'])
            
            # Write metric updates  
            if metric_updates:
                with self.metrics_table.batch_writer(overwrite_by_pkeys=['metric_id']) as batch:
                    for update in metric_updates:
                        batch.put_item(Item=update['PutRequest']['Item'])
                        
        except ClientError as e:
            logger.error(f"DynamoDB batch write error: {e.response['Error']['Message']}")
            raise
    
    async def _send_alerts(self, alerts: List[Dict]):
        """Send alerts via SNS"""
        
        for alert in alerts:
            try:
                self.sns.publish(
                    TopicArn=self.alert_topic_arn,
                    Message=json.dumps(alert),
                    Subject=f"Alert: {alert['severity']} - {alert['message']}"
                )
                logger.info(f"Alert sent: {alert['alert_id']}")
                
            except ClientError as e:
                logger.error(f"Failed to send alert {alert['alert_id']}: {str(e)}")
    
    async def _publish_processing_metrics(self, success_count: int, failure_count: int):
        """Publish custom metrics to CloudWatch"""
        
        try:
            self.cloudwatch.put_metric_data(
                Namespace='StreamProcessing',
                MetricData=[
                    {
                        'MetricName': 'RecordsProcessedSuccess',
                        'Value': success_count,
                        'Unit': 'Count',
                        'Timestamp': datetime.now()
                    },
                    {
                        'MetricName': 'RecordsProcessedFailure', 
                        'Value': failure_count,
                        'Unit': 'Count',
                        'Timestamp': datetime.now()
                    }
                ]
            )
        except ClientError as e:
            logger.error(f"Failed to publish metrics: {str(e)}")

# Lambda handler
def lambda_handler(event: Dict[str, Any], context) -> Dict[str, Any]:
    """Main Lambda handler for Kinesis stream processing"""
    
    processor = StreamProcessor()
    
    try:
        # Process records
        result = asyncio.run(processor.process_kinesis_records(event))
        
        logger.info(f"Processing complete: {result.success_count} successful, "
                   f"{result.failure_count} failed")
        
        # Return success response
        return {
            'statusCode': 200,
            'body': json.dumps({
                'message': 'Processing completed successfully',
                'processed': result.success_count,
                'failed': result.failure_count,
                'errors': result.errors[:10]  # Limit error details
            })
        }
        
    except Exception as e:
        logger.error(f"Lambda execution error: {str(e)}")
        
        return {
            'statusCode': 500,
            'body': json.dumps({
                'message': 'Processing failed',
                'error': str(e)
            })
        }
```

### Lambda with Dead Letter Queues and Retry Logic

#### Enhanced Error Handling
```python
class EnhancedStreamProcessor(StreamProcessor):
    
    def __init__(self):
        super().__init__()
        self.sqs = boto3.client('sqs')
        self.dlq_url = os.environ.get('DLQ_URL')
        self.retry_queue_url = os.environ.get('RETRY_QUEUE_URL')
    
    async def process_with_retry_logic(self, event: Dict[str, Any]) -> ProcessingResult:
        """Process with comprehensive retry and error handling"""
        
        max_retries = 3
        retry_count = 0
        
        while retry_count <= max_retries:
            try:
                result = await self.process_kinesis_records(event)
                
                # If we have failures, decide on retry strategy
                if result.failure_count > 0:
                    if retry_count < max_retries:
                        # Exponential backoff
                        wait_time = 2 ** retry_count
                        logger.info(f"Retrying in {wait_time} seconds (attempt {retry_count + 1})")
                        await asyncio.sleep(wait_time)
                        retry_count += 1
                        continue
                    else:
                        # Send failed records to DLQ
                        await self._send_to_dlq(event, result.errors)
                
                return result
                
            except Exception as e:
                logger.error(f"Processing attempt {retry_count + 1} failed: {str(e)}")
                retry_count += 1
                
                if retry_count > max_retries:
                    # Send entire batch to DLQ
                    await self._send_to_dlq(event, [str(e)])
                    raise
        
        return ProcessingResult(0, len(event.get('Records', [])), ['Max retries exceeded'])
    
    async def _send_to_dlq(self, original_event: Dict, errors: List[str]):
        """Send failed records to Dead Letter Queue"""
        
        if not self.dlq_url:
            logger.warning("DLQ not configured, failed records will be lost")
            return
        
        dlq_message = {
            'original_event': original_event,
            'errors': errors,
            'failed_at': datetime.now().isoformat(),
            'retry_count': 0
        }
        
        try:
            self.sqs.send_message(
                QueueUrl=self.dlq_url,
                MessageBody=json.dumps(dlq_message),
                MessageAttributes={
                    'ErrorType': {
                        'StringValue': 'ProcessingFailure',
                        'DataType': 'String'
                    }
                }
            )
            logger.info("Failed records sent to DLQ")
            
        except ClientError as e:
            logger.error(f"Failed to send to DLQ: {str(e)}")

# CloudFormation for Lambda with DLQ
LambdaFunction:
  Type: AWS::Lambda::Function
  Properties:
    FunctionName: !Sub '${AWS::StackName}-stream-processor'
    Runtime: python3.9
    Handler: stream_processor.lambda_handler
    Code:
      S3Bucket: !Ref CodeBucket
      S3Key: stream-processor.zip
    Role: !GetAtt LambdaExecutionRole.Arn
    
    Environment:
      Variables:
        USER_SESSIONS_TABLE: !Ref UserSessionsTable
        METRICS_TABLE: !Ref MetricsTable
        ALERT_TOPIC_ARN: !Ref AlertTopic
        DLQ_URL: !Ref DeadLetterQueue
        RETRY_QUEUE_URL: !Ref RetryQueue
    
    DeadLetterConfig:
      TargetArn: !GetAtt DeadLetterQueue.Arn
    
    ReservedConcurrencyLimit: 100
    Timeout: 300
    MemorySize: 1024

EventSourceMapping:
  Type: AWS::Lambda::EventSourceMapping
  Properties:
    EventSourceArn: !GetAtt UserEventsStream.Arn
    FunctionName: !Ref LambdaFunction
    StartingPosition: LATEST
    BatchSize: 100
    MaximumBatchingWindowInSeconds: 5
    ParallelizationFactor: 2
    MaximumRetryAttempts: 3
    MaximumRecordAgeInSeconds: 3600  # 1 hour
    BisectBatchOnFunctionError: true
    TumblingWindowInSeconds: 60
```

---

## 🛠️ PRACTICAL HANDS-ON EXERCISES

### Exercise 1: End-to-End Kinesis Pipeline

**Goal:** Build a complete real-time analytics pipeline using Kinesis services.

**Step 1: Infrastructure Setup**
```bash
# Navigate to day6 directory
cd day6

# Start LocalStack for AWS services simulation
docker-compose up -d localstack

# Wait for LocalStack to be ready
sleep 30

# Create Kinesis streams
aws kinesis create-stream \
  --stream-name user-events \
  --shard-count 2 \
  --endpoint-url http://localhost:4566 \
  --region us-east-1

aws kinesis create-stream \
  --stream-name analytics-output \
  --shard-count 1 \
  --endpoint-url http://localhost:4566 \
  --region us-east-1

# Create DynamoDB tables
aws dynamodb create-table \
  --table-name user-sessions \
  --attribute-definitions AttributeName=session_id,AttributeType=S \
  --key-schema AttributeName=session_id,KeyType=HASH \
  --provisioned-throughput ReadCapacityUnits=5,WriteCapacityUnits=5 \
  --endpoint-url http://localhost:4566 \
  --region us-east-1
```

**Step 2: Deploy Data Generators**
```bash
# Start event generators
docker-compose up -d user-event-generator transaction-generator

# Monitor stream status
aws kinesis describe-stream \
  --stream-name user-events \
  --endpoint-url http://localhost:4566 \
  --region us-east-1
```

**Step 3: Deploy Processing Applications**
```bash
# Start Lambda simulator for stream processing
docker-compose up stream-processor-lambda

# Start KDA simulator (Flink application)
docker-compose up kinesis-analytics-simulator

# Monitor processing metrics
curl http://localhost:8080/metrics
```

### Exercise 2: MSK with IAM Authentication

**Goal:** Set up secure Kafka cluster with IAM-based access control.

**Step 1: Create MSK Cluster Simulation**
```bash
# Start MSK simulator with security
docker-compose up -d msk-cluster-simulator

# Configure IAM authentication simulation
docker-compose up msk-iam-auth-setup

# Verify cluster connectivity
docker exec -it msk-cluster-simulator \
  kafka-topics --bootstrap-server localhost:9092 --list
```

**Step 2: Test IAM-Authenticated Clients**
```bash
# Run producer with IAM auth
docker-compose up msk-iam-producer

# Run consumer with IAM auth  
docker-compose up msk-iam-consumer

# Monitor IAM authentication logs
docker logs msk-iam-auth-setup
```

### Exercise 3: Real-time ML Feature Pipeline

**Goal:** Build a feature engineering pipeline for ML using AWS services.

**Step 1: Deploy Feature Pipeline**
```bash
# Start feature extraction service
docker-compose up feature-extractor

# Start ML model service
docker-compose up ml-model-service

# Start feature store simulator (DynamoDB)
aws dynamodb create-table \
  --table-name ml-features \
  --attribute-definitions \
    AttributeName=user_id,AttributeType=S \
    AttributeName=timestamp,AttributeType=N \
  --key-schema \
    AttributeName=user_id,KeyType=HASH \
    AttributeName=timestamp,KeyType=RANGE \
  --provisioned-throughput ReadCapacityUnits=10,WriteCapacityUnits=10 \
  --endpoint-url http://localhost:4566
```

**Step 2: Test End-to-End ML Pipeline**
```bash
# Generate user behavior data
docker-compose up ml-data-generator

# Monitor feature extraction
curl http://localhost:8081/features/user_123

# Test model predictions
curl -X POST http://localhost:8082/predict \
  -H "Content-Type: application/json" \
  -d '{"user_id": "user_123", "features": {...}}'
```

---

## 📈 PERFORMANCE OPTIMIZATION AND COST MANAGEMENT

### Kinesis Optimization Strategies

#### Shard Scaling Calculator
```python
class KinesisOptimizer:
    
    def __init__(self):
        self.shard_write_capacity = 1000  # records/sec per shard
        self.shard_throughput = 1  # MB/sec per shard
        self.shard_cost_per_hour = 0.015  # USD
        
    def calculate_required_shards(self, 
                                 records_per_second: int,
                                 avg_record_size_kb: float,
                                 peak_multiplier: float = 2.0) -> Dict[str, Any]:
        """Calculate optimal shard count"""
        
        # Account for peak traffic
        peak_records_per_sec = records_per_second * peak_multiplier
        peak_throughput_mb_per_sec = (peak_records_per_sec * avg_record_size_kb) / 1024
        
        # Calculate shards needed for each constraint
        shards_for_records = math.ceil(peak_records_per_sec / self.shard_write_capacity)
        shards_for_throughput = math.ceil(peak_throughput_mb_per_sec / self.shard_throughput)
        
        # Take the maximum
        required_shards = max(shards_for_records, shards_for_throughput)
        
        # Cost calculation
        monthly_cost = required_shards * self.shard_cost_per_hour * 24 * 30
        
        return {
            'required_shards': required_shards,
            'constraint': 'records' if shards_for_records > shards_for_throughput else 'throughput',
            'peak_records_per_sec': peak_records_per_sec,
            'peak_throughput_mb_per_sec': peak_throughput_mb_per_sec,
            'monthly_cost_usd': monthly_cost,
            'recommendations': self._generate_recommendations(
                records_per_second, avg_record_size_kb, required_shards
            )
        }
    
    def _generate_recommendations(self, 
                                records_per_sec: int,
                                avg_record_size_kb: float, 
                                required_shards: int) -> List[str]:
        """Generate optimization recommendations"""
        
        recommendations = []
        
        # Record size optimization
        if avg_record_size_kb > 10:  # 10KB threshold
            recommendations.append(
                "Consider compressing records or using more efficient serialization "
                f"(current avg: {avg_record_size_kb:.1f}KB)"
            )
        
        # Batching recommendations
        if records_per_sec > 100:
            recommendations.append(
                "Use KPL (Kinesis Producer Library) for automatic batching and compression"
            )
        
        # Partition key distribution
        recommendations.append(
            "Ensure even partition key distribution to avoid hot shards"
        )
        
        # Alternative services
        if required_shards > 20:
            recommendations.append(
                "Consider Amazon MSK for very high throughput requirements"
            )
        
        return recommendations

# Usage example
optimizer = KinesisOptimizer()
result = optimizer.calculate_required_shards(
    records_per_second=5000,
    avg_record_size_kb=2.5,
    peak_multiplier=3.0
)

print(f"Required shards: {result['required_shards']}")
print(f"Monthly cost: ${result['monthly_cost_usd']:.2f}")
for rec in result['recommendations']:
    print(f"- {rec}")
```

### Cost Optimization Framework

#### Multi-Service Cost Analyzer
```python
class AWSStreamingCostAnalyzer:
    
    def __init__(self):
        # Pricing (as of 2024, US East 1)
        self.pricing = {
            'kinesis': {
                'shard_hour': 0.015,
                'put_payload_unit': 0.000014,  # per 25KB
                'extended_retention': 0.023    # per shard hour > 24h
            },
            'msk': {
                'm5_large': 0.252,    # per hour
                'm5_xlarge': 0.504,   # per hour
                'm5_2xlarge': 1.008,  # per hour
                'storage_gb': 0.10    # per GB per month
            },
            'kda': {
                'kpu_hour': 0.11      # Kinesis Processing Unit
            },
            'lambda': {
                'request': 0.0000002,  # per request
                'gb_second': 0.0000166667  # per GB-second
            }
        }
    
    def analyze_kinesis_costs(self, 
                            shard_count: int,
                            records_per_month: int,
                            avg_record_size_kb: float,
                            retention_hours: int = 24) -> Dict[str, float]:
        """Analyze Kinesis Data Streams costs"""
        
        # Shard hours cost
        shard_hours_per_month = shard_count * 24 * 30
        shard_cost = shard_hours_per_month * self.pricing['kinesis']['shard_hour']
        
        # PUT payload units cost
        payload_units = math.ceil((records_per_month * avg_record_size_kb) / 25)
        put_cost = payload_units * self.pricing['kinesis']['put_payload_unit']
        
        # Extended retention cost
        extended_cost = 0
        if retention_hours > 24:
            extended_hours = retention_hours - 24
            extended_shard_hours = shard_count * extended_hours * 30
            extended_cost = extended_shard_hours * self.pricing['kinesis']['extended_retention']
        
        total_cost = shard_cost + put_cost + extended_cost
        
        return {
            'shard_cost': shard_cost,
            'put_cost': put_cost,
            'extended_retention_cost': extended_cost,
            'total_monthly_cost': total_cost
        }
    
    def analyze_msk_costs(self,
                         broker_count: int,
                         instance_type: str,
                         storage_gb_per_broker: int) -> Dict[str, float]:
        """Analyze MSK costs"""
        
        if instance_type not in self.pricing['msk']:
            raise ValueError(f"Unknown instance type: {instance_type}")
        
        # Compute costs
        instance_cost = (broker_count * 
                        self.pricing['msk'][instance_type] * 
                        24 * 30)  # Monthly
        
        storage_cost = (broker_count * 
                       storage_gb_per_broker * 
                       self.pricing['msk']['storage_gb'])
        
        total_cost = instance_cost + storage_cost
        
        return {
            'instance_cost': instance_cost,
            'storage_cost': storage_cost,
            'total_monthly_cost': total_cost
        }
    
    def compare_kinesis_vs_msk(self,
                              throughput_records_per_sec: int,
                              avg_record_size_kb: float) -> Dict[str, Any]:
        """Compare Kinesis vs MSK costs for given throughput"""
        
        # Calculate Kinesis requirements
        records_per_month = throughput_records_per_sec * 60 * 60 * 24 * 30
        required_shards = max(1, math.ceil(throughput_records_per_sec / 1000))
        
        kinesis_costs = self.analyze_kinesis_costs(
            shard_count=required_shards,
            records_per_month=records_per_month,
            avg_record_size_kb=avg_record_size_kb
        )
        
        # Calculate MSK requirements (conservative estimate)
        # Assume kafka.m5.large can handle ~10K records/sec
        required_brokers = max(3, math.ceil(throughput_records_per_sec / 10000) * 3)  # 3-broker minimum
        
        msk_costs = self.analyze_msk_costs(
            broker_count=required_brokers,
            instance_type='m5_large',
            storage_gb_per_broker=100
        )
        
        # Calculate break-even point
        cost_difference = kinesis_costs['total_monthly_cost'] - msk_costs['total_monthly_cost']
        
        return {
            'throughput_records_per_sec': throughput_records_per_sec,
            'kinesis': {
                'required_shards': required_shards,
                'monthly_cost': kinesis_costs['total_monthly_cost']
            },
            'msk': {
                'required_brokers': required_brokers,
                'monthly_cost': msk_costs['total_monthly_cost']
            },
            'cost_difference': cost_difference,
            'recommendation': 'Kinesis' if cost_difference < 0 else 'MSK',
            'reasoning': self._generate_cost_reasoning(kinesis_costs, msk_costs, throughput_records_per_sec)
        }
    
    def _generate_cost_reasoning(self, 
                               kinesis_costs: Dict,
                               msk_costs: Dict,
                               throughput: int) -> List[str]:
        """Generate cost-based recommendations"""
        
        reasoning = []
        
        if kinesis_costs['total_monthly_cost'] < msk_costs['total_monthly_cost']:
            reasoning.append("Kinesis is more cost-effective for this throughput")
            reasoning.append("Lower operational overhead with managed service")
        else:
            reasoning.append("MSK is more cost-effective for this throughput")
            reasoning.append("Better cost efficiency at high throughput")
        
        if throughput < 1000:
            reasoning.append("Consider Lambda + SQS for very low throughput")
        elif throughput > 100000:
            reasoning.append("Consider self-managed Kafka for maximum cost optimization")
        
        return reasoning

# Usage example
analyzer = AWSStreamingCostAnalyzer()
comparison = analyzer.compare_kinesis_vs_msk(
    throughput_records_per_sec=50000,
    avg_record_size_kb=3.0
)

print(f"Recommendation: {comparison['recommendation']}")
print(f"Kinesis cost: ${comparison['kinesis']['monthly_cost']:.2f}/month")
print(f"MSK cost: ${comparison['msk']['monthly_cost']:.2f}/month")
print(f"Reasoning:")
for reason in comparison['reasoning']:
    print(f"  - {reason}")
```

---

## 📊 MONITORING AND TROUBLESHOOTING

### Comprehensive CloudWatch Dashboards

#### Custom Metrics Dashboard
```python
import boto3
from typing import List, Dict

class StreamingMonitoringSetup:
    
    def __init__(self, region='us-east-1'):
        self.cloudwatch = boto3.client('cloudwatch', region_name=region)
        self.region = region
    
    def create_kinesis_dashboard(self, stream_name: str) -> str:
        """Create comprehensive Kinesis monitoring dashboard"""
        
        dashboard_body = {
            "widgets": [
                {
                    "type": "metric",
                    "x": 0, "y": 0,
                    "width": 12, "height": 6,
                    "properties": {
                        "metrics": [
                            ["AWS/Kinesis", "IncomingRecords", "StreamName", stream_name],
                            [".", "IncomingBytes", ".", "."],
                            [".", "OutgoingRecords", ".", "."],
                            [".", "OutgoingBytes", ".", "."]
                        ],
                        "period": 300,
                        "stat": "Sum",
                        "region": self.region,
                        "title": "Kinesis Throughput"
                    }
                },
                {
                    "type": "metric", 
                    "x": 12, "y": 0,
                    "width": 12, "height": 6,
                    "properties": {
                        "metrics": [
                            ["AWS/Kinesis", "WriteProvisionedThroughputExceeded", "StreamName", stream_name],
                            [".", "ReadProvisionedThroughputExceeded", ".", "."],
                            [".", "UserRecordsPending", ".", "."]
                        ],
                        "period": 300,
                        "stat": "Sum",
                        "region": self.region,
                        "title": "Kinesis Errors and Throttling"
                    }
                },
                {
                    "type": "metric",
                    "x": 0, "y": 6, 
                    "width": 24, "height": 6,
                    "properties": {
                        "metrics": [
                            ["AWS/Kinesis", "IteratorAgeMilliseconds", "StreamName", stream_name]
                        ],
                        "period": 300,
                        "stat": "Maximum",
                        "region": self.region,
                        "title": "Consumer Lag (Iterator Age)",
                        "yAxis": {"left": {"min": 0}}
                    }
                }
            ]
        }
        
        dashboard_name = f"Kinesis-{stream_name}-Monitor"
        
        response = self.cloudwatch.put_dashboard(
            DashboardName=dashboard_name,
            DashboardBody=json.dumps(dashboard_body)
        )
        
        return dashboard_name
    
    def create_msk_dashboard(self, cluster_name: str) -> str:
        """Create MSK monitoring dashboard"""
        
        dashboard_body = {
            "widgets": [
                {
                    "type": "metric",
                    "x": 0, "y": 0,
                    "width": 12, "height": 6,
                    "properties": {
                        "metrics": [
                            ["AWS/Kafka", "BytesInPerSec", "Cluster Name", cluster_name],
                            [".", "BytesOutPerSec", ".", "."],
                            [".", "MessagesInPerSec", ".", "."]
                        ],
                        "period": 300,
                        "stat": "Average",
                        "region": self.region,
                        "title": "MSK Throughput"
                    }
                },
                {
                    "type": "metric",
                    "x": 12, "y": 0,
                    "width": 12, "height": 6,
                    "properties": {
                        "metrics": [
                            ["AWS/Kafka", "CpuSystem", "Cluster Name", cluster_name],
                            [".", "CpuUser", ".", "."],
                            [".", "MemoryUsed", ".", "."]
                        ],
                        "period": 300,
                        "stat": "Average", 
                        "region": self.region,
                        "title": "MSK Resource Usage"
                    }
                },
                {
                    "type": "metric",
                    "x": 0, "y": 6,
                    "width": 24, "height": 6,
                    "properties": {
                        "metrics": [
                            ["AWS/Kafka", "EstimatedMaxTimeLag", "Cluster Name", cluster_name, "Consumer Group", "analytics-consumer"],
                            [".", "SumOffsetLag", ".", ".", ".", "."]
                        ],
                        "period": 300,
                        "stat": "Maximum",
                        "region": self.region,
                        "title": "Consumer Lag"
                    }
                }
            ]
        }
        
        dashboard_name = f"MSK-{cluster_name}-Monitor"
        
        self.cloudwatch.put_dashboard(
            DashboardName=dashboard_name,
            DashboardBody=json.dumps(dashboard_body)
        )
        
        return dashboard_name
    
    def create_alerts(self, 
                     stream_name: str,
                     sns_topic_arn: str) -> List[str]:
        """Create CloudWatch alarms for streaming services"""
        
        alarms = []
        
        # High iterator age alarm
        alarm_name = f"{stream_name}-high-iterator-age"
        self.cloudwatch.put_metric_alarm(
            AlarmName=alarm_name,
            ComparisonOperator='GreaterThanThreshold',
            EvaluationPeriods=2,
            MetricName='IteratorAgeMilliseconds',
            Namespace='AWS/Kinesis',
            Period=300,
            Statistic='Maximum',
            Threshold=300000.0,  # 5 minutes
            ActionsEnabled=True,
            AlarmActions=[sns_topic_arn],
            AlarmDescription='Iterator age too high - consumers falling behind',
            Dimensions=[
                {
                    'Name': 'StreamName',
                    'Value': stream_name
                }
            ]
        )
        alarms.append(alarm_name)
        
        # Throttling alarm
        alarm_name = f"{stream_name}-throttling"
        self.cloudwatch.put_metric_alarm(
            AlarmName=alarm_name,
            ComparisonOperator='GreaterThanThreshold',
            EvaluationPeriods=1,
            MetricName='WriteProvisionedThroughputExceeded',
            Namespace='AWS/Kinesis',
            Period=300,
            Statistic='Sum',
            Threshold=0.0,
            ActionsEnabled=True,
            AlarmActions=[sns_topic_arn],
            AlarmDescription='Write throttling detected - need more shards',
            Dimensions=[
                {
                    'Name': 'StreamName',
                    'Value': stream_name
                }
            ]
        )
        alarms.append(alarm_name)
        
        return alarms

# Usage
monitor_setup = StreamingMonitoringSetup()
dashboard = monitor_setup.create_kinesis_dashboard('user-events')
alarms = monitor_setup.create_alerts('user-events', 'arn:aws:sns:us-east-1:123456789012:alerts')

print(f"Created dashboard: {dashboard}")
print(f"Created alarms: {alarms}")
```

---

## 📝 PRACTICAL INTERVIEW QUESTIONS

### Intermediate Level (2-5 years)

**Q1: What are the key differences between Kinesis Data Streams and Amazon MSK?**
**A:** 
- **Kinesis**: Fully serverless, auto-scaling shards, integrated with AWS services, pay-per-shard
- **MSK**: Managed Kafka, more control over configuration, better for Kafka expertise, pay-per-instance
- **Use Kinesis** for: AWS-native applications, variable workloads, simpler operations
- **Use MSK** for: Kafka expertise, complex configurations, predictable workloads

**Q2: How does KDA handle fault tolerance and exactly-once processing?**
**A:** KDA provides built-in fault tolerance through:
- **Automatic checkpointing** to durable storage
- **State snapshots** for recovery
- **Task restart** on failure
- **Exactly-once semantics** with proper source/sink configuration
- **Auto-scaling** based on parallelism units

**Q3: Explain the cost optimization strategies for AWS streaming services.**
**A:**
- **Right-size resources**: Choose appropriate shard counts, instance types
- **Use reserved capacity**: For predictable workloads
- **Optimize data lifecycle**: Set appropriate retention periods
- **Monitor utilization**: Scale based on actual usage patterns
- **Consider alternatives**: Lambda for low volume, MSK for high volume

**Q4: How would you design a multi-region disaster recovery strategy?**
**A:**
- **Cross-region replication**: Use Kinesis Data Firehose or custom applications
- **Regional failover**: Route traffic to backup region
- **Data consistency**: Implement eventual consistency patterns
- **State synchronization**: Use S3 for shared state/checkpoints
- **Automated failover**: CloudFormation/Terraform for infrastructure

### Advanced Level (5+ years)

**Q5: Design a cost-optimized streaming architecture for a startup scaling to enterprise.**
**A:**
1. **Start simple**: Lambda + SQS/Kinesis for initial scale
2. **Scale incrementally**: Add MSK when throughput demands increase  
3. **Optimize continuously**: Monitor costs and right-size resources
4. **Use managed services**: Reduce operational overhead
5. **Plan for growth**: Design for 10x scale from day one

**Q6: How would you implement exactly-once processing across Kinesis and downstream services?**
**A:**
- **Idempotent operations**: Design downstream systems to handle duplicates
- **Transactional sinks**: Use transactional databases/message queues
- **Deduplication strategies**: Use unique identifiers and state stores
- **Checkpointing**: Coordinate checkpoints with external systems
- **Error handling**: Implement proper retry and dead letter patterns

**Q7: Implement a real-time ML inference pipeline using AWS streaming services.**
**A:**
```
Data Sources → Kinesis Data Streams → KDA/Lambda (feature extraction)
     ↓
Feature Store (DynamoDB) → SageMaker/Lambda (inference) → Results (Kinesis/DDB)
```
Key considerations: Model versioning, A/B testing, feedback loops, monitoring

**Q8: Design an exactly-once ETL pipeline processing millions of events per hour.**
**A:**
- **MSK or Kinesis** for ingestion (choose based on volume/cost)
- **KDA with Flink** for complex transformations
- **Idempotent sinks** with proper deduplication
- **Monitoring and alerting** for data quality
- **Backup and recovery** strategies

---

## ✅ DAY 6 COMPLETION CHECKLIST

Before moving to Day 7, ensure you can:

**Amazon Kinesis:**
- [ ] Create and configure Kinesis Data Streams
- [ ] Implement producers with KPL for high throughput
- [ ] Build scalable consumers with KCL
- [ ] Handle shard scaling and partition key strategies
- [ ] Configure retention and encryption

**Amazon MSK:**
- [ ] Deploy MSK clusters with proper configuration
- [ ] Implement IAM authentication and authorization
- [ ] Set up MSK Connect for data integration
- [ ] Monitor cluster health and performance
- [ ] Handle security and network configuration

**Kinesis Data Analytics:**
- [ ] Build and deploy Flink applications on KDA
- [ ] Configure auto-scaling and resource management
- [ ] Implement exactly-once processing patterns
- [ ] Handle state management and checkpointing
- [ ] Monitor application performance

**Integration and Architecture:**
- [ ] Design serverless streaming architectures
- [ ] Integrate with Lambda, DynamoDB, S3
- [ ] Implement cost optimization strategies
- [ ] Set up comprehensive monitoring
- [ ] Handle error scenarios and dead letter queues

**Production Skills:**
- [ ] Deploy production-ready streaming applications
- [ ] Implement proper security and access control
- [ ] Monitor and troubleshoot streaming workloads
- [ ] Optimize costs for different workload patterns

---

## 🚀 WHAT'S NEXT?

Tomorrow (Day 7), you'll complete the program with production patterns:
- Comprehensive monitoring and observability strategies
- Production deployment and DevOps patterns
- Disaster recovery and business continuity
- Performance optimization techniques  
- Cost management and operational excellence
- Building streaming centers of excellence

**Preparation:**
- Ensure all Day 6 exercises work correctly
- Review cost optimization strategies
- Think about operational challenges at scale
- Consider how to build organizational streaming capabilities

## 📚 Additional Resources

**AWS Kinesis:**
- [Kinesis Developer Guide](https://docs.aws.amazon.com/kinesis/latest/dev/)
- [Kinesis Best Practices](https://docs.aws.amazon.com/streams/latest/dev/kinesis-record-processor-implementation-app-py.html)

**Amazon MSK:**
- [MSK Developer Guide](https://docs.aws.amazon.com/msk/latest/developerguide/)
- [MSK Best Practices](https://docs.aws.amazon.com/msk/latest/developerguide/bestpractices.html)

**Kinesis Data Analytics:**
- [KDA Developer Guide](https://docs.aws.amazon.com/kinesisanalytics/latest/java/)
- [KDA Examples](https://github.com/aws-samples/amazon-kinesis-data-analytics-java-examples)

**Cost Optimization:**
- [AWS Cost Optimization](https://aws.amazon.com/aws-cost-management/)
- [Streaming Workloads Cost Guide](https://docs.aws.amazon.com/whitepapers/latest/cost-optimization-right-sizing/right-sizing.html)

---

Excellent work completing Day 6! You now understand AWS's managed streaming services and can design cloud-native streaming architectures. Tomorrow we'll wrap up with production patterns and operational excellence.

## Training Modules

### Module 1: Kinesis Data Streams (60 minutes)

Basic Kinesis Operations:
```python
import boto3

# Create Kinesis client
kinesis = boto3.client('kinesis', 
    endpoint_url='http://localhost:4566',  # LocalStack
    region_name='us-east-1'
)

# Create stream
kinesis.create_stream(
    StreamName='demo-stream',
    ShardCount=2
)

# Put record
response = kinesis.put_record(
    StreamName='demo-stream',
    Data=json.dumps({
        'timestamp': datetime.now().isoformat(),
        'user_id': 'user123',
        'event_type': 'click'
    }),
    PartitionKey='user123'
)

# Get records
shard_iterator = kinesis.get_shard_iterator(
    StreamName='demo-stream',
    ShardId='shardId-000000000000',
    ShardIteratorType='TRIM_HORIZON'
)['ShardIterator']

records = kinesis.get_records(ShardIterator=shard_iterator)
```

Kinesis Producer Library (KPL):
```java
KinesisProducer producer = new KinesisProducer(new KinesisProducerConfiguration()
    .setRegion("us-east-1")
    .setAggregationEnabled(true)
    .setCompressionEnabled(true)
    .setRecordMaxBufferedTime(15000));

ListenableFuture<UserRecordResult> future = producer.addUserRecord(
    "demo-stream",
    "partitionKey",
    ByteBuffer.wrap("data".getBytes())
);
```

### Module 2: Amazon MSK Configuration (45 minutes)

MSK Cluster Configuration:
```yaml
# CloudFormation template
MSKCluster:
  Type: AWS::MSK::Cluster
  Properties:
    ClusterName: streaming-cluster
    KafkaVersion: 2.8.1
    NumberOfBrokerNodes: 6
    BrokerNodeGroupInfo:
      InstanceType: kafka.m5.large
      ClientSubnets:
        - !Ref PrivateSubnet1
        - !Ref PrivateSubnet2
        - !Ref PrivateSubnet3
      SecurityGroups:
        - !Ref MSKSecurityGroup
      StorageInfo:
        EBSStorageInfo:
          VolumeSize: 100
    EncryptionInfo:
      EncryptionInTransit:
        ClientBroker: TLS
        InCluster: true
      EncryptionAtRest:
        DataVolumeKMSKeyId: !Ref KMSKey
    ClientAuthentication:
      Unauthenticated:
        Enabled: false
      Sasl:
        Iam:
          Enabled: true
```

IAM Policies for MSK:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "kafka-cluster:Connect",
        "kafka-cluster:AlterCluster",
        "kafka-cluster:DescribeCluster"
      ],
      "Resource": "arn:aws:kafka:*:*:cluster/*/*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "kafka-cluster:*Topic*",
        "kafka-cluster:WriteData",
        "kafka-cluster:ReadData"
      ],
      "Resource": "arn:aws:kafka:*:*:topic/*/*"
    }
  ]
}
```

### Module 3: Kinesis Data Analytics with Flink (60 minutes)

KDA Application Configuration:
```yaml
KinesisAnalyticsApplication:
  Type: AWS::KinesisAnalyticsV2::Application
  Properties:
    ApplicationName: flink-streaming-app
    RuntimeEnvironment: FLINK-1_15
    ServiceExecutionRole: !GetAtt KDAServiceRole.Arn
    ApplicationConfiguration:
      ApplicationCodeConfiguration:
        CodeContent:
          S3ContentLocation:
            BucketARN: !GetAtt S3Bucket.Arn
            FileKey: flink-app.jar
        CodeContentType: ZIPFILE
      FlinkApplicationConfiguration:
        CheckpointConfiguration:
          ConfigurationType: CUSTOM
          CheckpointingEnabled: true
          CheckpointInterval: 60000
          MinPauseBetweenCheckpoints: 5000
        MonitoringConfiguration:
          ConfigurationType: CUSTOM
          LogLevel: INFO
          MetricsLevel: APPLICATION
        ParallelismConfiguration:
          ConfigurationType: CUSTOM
          Parallelism: 4
          ParallelismPerKPU: 1
```

Flink Job for KDA:
```java
public class StreamingJob {
    public static void main(String[] args) throws Exception {
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        
        // Configure checkpointing
        env.enableCheckpointing(60000, CheckpointingMode.EXACTLY_ONCE);
        
        // Kinesis source
        Properties sourceProperties = new Properties();
        sourceProperties.setProperty("aws.region", "us-east-1");
        sourceProperties.setProperty("scan.stream.initpos", "LATEST");
        
        DataStream<String> kinesisStream = env.addSource(
            new FlinkKinesisConsumer<>("input-stream", new SimpleStringSchema(), sourceProperties)
        );
        
        // Processing logic
        DataStream<ProcessedEvent> processedStream = kinesisStream
            .map(new EventParser())
            .keyBy(ProcessedEvent::getUserId)
            .window(TumblingProcessingTimeWindows.of(Time.minutes(5)))
            .aggregate(new EventAggregator());
        
        // Kinesis sink
        Properties sinkProperties = new Properties();
        sinkProperties.setProperty("aws.region", "us-east-1");
        
        processedStream.addSink(
            new FlinkKinesisProducer<>(new EventSerializer(), sinkProperties)
        );
        
        env.execute("Streaming Analytics Job");
    }
}
```

### Module 4: Serverless Architecture Patterns (45 minutes)

Lambda-based Processing:
```python
import json
import boto3
from datetime import datetime

def lambda_handler(event, context):
    """Process Kinesis records in Lambda"""
    
    dynamodb = boto3.resource('dynamodb')
    table = dynamodb.Table('user-sessions')
    
    for record in event['Records']:
        # Decode Kinesis record
        payload = json.loads(
            base64.b64decode(record['kinesis']['data']).decode('utf-8')
        )
        
        # Process event
        result = process_event(payload)
        
        # Store in DynamoDB
        table.put_item(Item={
            'user_id': result['user_id'],
            'timestamp': result['timestamp'],
            'metrics': result['metrics'],
            'ttl': int(time.time()) + 86400  # 24 hours TTL
        })
    
    return {'statusCode': 200, 'body': 'Processed successfully'}

def process_event(event):
    """Business logic for event processing"""
    return {
        'user_id': event['user_id'],
        'timestamp': datetime.now().isoformat(),
        'metrics': calculate_metrics(event)
    }
```

## Hands-on Exercises

### Exercise 1: Kinesis Data Streams Pipeline
```bash
# Start LocalStack
docker-compose up -d localstack

# Create Kinesis stream
aws kinesis create-stream --stream-name demo-stream --shard-count 2 --endpoint-url http://localhost:4566

# Start data generator
docker-compose up kinesis-generator

# Monitor stream metrics
aws kinesis describe-stream --stream-name demo-stream --endpoint-url http://localhost:4566
```

### Exercise 2: MSK Integration
```bash
# Start MSK simulator (Kafka)
docker-compose up -d kafka-msk

# Create topics
docker exec -it kafka-msk-simulator kafka-topics --create --topic user-events --bootstrap-server localhost:9092

# Test MSK connectivity
docker-compose up stream-processor
```

### Exercise 3: KDA with Flink
```bash
# Deploy Flink job to simulate KDA
docker exec -it flink-kda-simulator flink run /opt/flink/jobs/KDASimulatorJob.jar

# Monitor job performance
curl http://localhost:8081/jobs
```

## Best Practices

### Kinesis Best Practices
1. **Choose appropriate shard count** based on throughput requirements
2. **Use effective partition keys** for even distribution
3. **Configure retention period** based on downstream processing needs
4. **Monitor shard utilization** and scale appropriately
5. **Use KPL/KCL** for production applications

### MSK Best Practices
1. **Right-size broker instances** for workload requirements
2. **Use multi-AZ deployment** for high availability
3. **Enable encryption** in transit and at rest
4. **Monitor cluster metrics** via CloudWatch
5. **Configure appropriate retention policies**

### KDA Best Practices
1. **Use appropriate parallelism** based on data volume
2. **Configure checkpointing** for fault tolerance
3. **Monitor application metrics** via CloudWatch
4. **Use VPC deployment** for security
5. **Implement proper error handling**

## Real-World Examples

### Real-time E-commerce Analytics
```
Architecture:
1. Web/Mobile → Kinesis Data Streams (click events)
2. KDA Flink App → Process events, calculate metrics
3. DynamoDB → Store user sessions and metrics
4. CloudWatch → Real-time dashboards
5. S3 → Archive raw data for batch analytics
6. Lambda → Trigger actions based on events
```

### IoT Data Processing Platform
```
Architecture:
1. IoT Devices → Kinesis Data Streams (sensor data)
2. Kinesis Data Firehose → S3 (raw data archival)
3. KDA → Real-time anomaly detection
4. SNS → Alert notifications
5. DynamoDB → Device state management
6. ElasticSearch → Log analytics and search
```

### Financial Transaction Processing
```
Architecture:
1. Payment Systems → MSK (transaction events)
2. KDA Flink → Fraud detection and risk scoring
3. RDS → Transaction storage
4. Lambda → Real-time notifications
5. Kinesis Analytics → Compliance reporting
6. CloudWatch → Monitoring and alerting
```

## Advanced Patterns

### 1. Multi-Region Streaming
```yaml
# Cross-region replication for Kinesis
KinesisStreamReplication:
  Type: AWS::KinesisFirehose::DeliveryStream
  Properties:
    DeliveryStreamName: cross-region-replication
    DeliveryStreamType: KinesisStreamAsSource
    KinesisStreamSourceConfiguration:
      KinesisStreamARN: !GetAtt SourceStream.Arn
      RoleARN: !GetAtt ReplicationRole.Arn
    S3DestinationConfiguration:
      BucketARN: !Sub "${DestinationBucket}/*"
      Prefix: "year=!{timestamp:yyyy}/month=!{timestamp:MM}/day=!{timestamp:dd}/hour=!{timestamp:HH}/"
```

### 2. Event-Driven Architecture
```python
# Lambda function for event routing
def event_router(event, context):
    for record in event['Records']:
        event_data = json.loads(record['body'])
        event_type = event_data.get('event_type')
        
        if event_type == 'user_signup':
            # Send to user processing pipeline
            kinesis.put_record(
                StreamName='user-events',
                Data=json.dumps(event_data),
                PartitionKey=event_data['user_id']
            )
        elif event_type == 'order_placed':
            # Send to order processing pipeline
            kinesis.put_record(
                StreamName='order-events',
                Data=json.dumps(event_data),
                PartitionKey=event_data['order_id']
            )
```

### 3. Auto-scaling Pattern
```python
# CloudWatch custom metric for auto-scaling
def publish_custom_metrics(stream_name, shard_utilization):
    cloudwatch = boto3.client('cloudwatch')
    
    cloudwatch.put_metric_data(
        Namespace='Kinesis/Streams',
        MetricData=[
            {
                'MetricName': 'ShardUtilization',
                'Dimensions': [
                    {'Name': 'StreamName', 'Value': stream_name}
                ],
                'Value': shard_utilization,
                'Unit': 'Percent'
            }
        ]
    )

# Auto-scaling function
def auto_scale_kinesis_stream(stream_name, target_utilization=70):
    current_utilization = get_stream_utilization(stream_name)
    
    if current_utilization > target_utilization:
        # Scale up
        current_shards = get_shard_count(stream_name)
        new_shard_count = int(current_shards * 1.5)
        update_shard_count(stream_name, new_shard_count)
```

## Interview Questions

### Intermediate Level
1. **What are the key differences between Kinesis Data Streams and MSK?**
   - Managed vs serverless, scaling models, pricing, ecosystem integration

2. **How does KDA handle fault tolerance and state management?**
   - Automatic snapshots, checkpoint restoration, exactly-once processing

3. **Explain the cost optimization strategies for AWS streaming services.**
   - Right-sizing, reserved capacity, data lifecycle management

4. **How would you monitor and troubleshoot streaming applications in AWS?**
   - CloudWatch metrics, X-Ray tracing, application logs, custom metrics

### Advanced Level
1. **Design a multi-region disaster recovery strategy for streaming workloads.**
   - Cross-region replication, failover procedures, data consistency

2. **Implement exactly-once processing across Kinesis and downstream services.**
   - Idempotent operations, deduplication strategies, transactional patterns

3. **Optimize costs for a high-volume streaming workload in AWS.**
   - Service selection, capacity planning, data lifecycle, monitoring costs

4. **Design a real-time ML inference pipeline using AWS streaming services.**
   - Model serving, feature stores, A/B testing, feedback loops

## Cost Optimization

### Kinesis Pricing Optimization
```python
# Calculate optimal shard count
def calculate_optimal_shards(throughput_mb_per_sec, records_per_sec):
    # Kinesis limits: 1MB/s or 1000 records/s per shard
    shards_for_throughput = math.ceil(throughput_mb_per_sec)
    shards_for_records = math.ceil(records_per_sec / 1000)
    return max(shards_for_throughput, shards_for_records)

# Cost calculation
def calculate_kinesis_cost(shard_count, retention_hours):
    shard_hour_cost = 0.015  # USD per shard hour
    extended_retention_cost = 0.023  # USD per shard hour for > 24h retention
    
    base_cost = shard_count * 24 * 30 * shard_hour_cost  # Monthly
    
    if retention_hours > 24:
        extra_hours = retention_hours - 24
        extended_cost = shard_count * extra_hours * 30 * extended_retention_cost
        return base_cost + extended_cost
    
    return base_cost
```

### MSK vs Self-managed Kafka TCO
```yaml
# MSK cost factors
MSK_Monthly_Cost:
  Broker_Instances: kafka.m5.large × 3 × $0.252/hour × 720 hours = $544.32
  Storage: 100GB × 3 × $0.10/GB = $30
  Data_Transfer: Varies by usage
  
Self_Managed_Kafka:
  EC2_Instances: m5.large × 3 × $0.096/hour × 720 hours = $207.36
  EBS_Storage: 100GB × 3 × $0.10/GB = $30
  Operational_Overhead: Personnel + Management tools
  High_Availability_Setup: Load balancers, monitoring, backups
```

## Security Best Practices

### IAM Policies
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "KinesisReadWrite",
      "Effect": "Allow",
      "Action": [
        "kinesis:PutRecord",
        "kinesis:PutRecords",
        "kinesis:GetRecords",
        "kinesis:GetShardIterator",
        "kinesis:DescribeStream",
        "kinesis:ListStreams"
      ],
      "Resource": "arn:aws:kinesis:*:*:stream/allowed-stream-*",
      "Condition": {
        "StringEquals": {
          "aws:RequestedRegion": ["us-east-1", "us-west-2"]
        }
      }
    }
  ]
}
```

### VPC and Network Security
```yaml
# VPC Configuration for streaming services
VPC:
  CIDR: 10.0.0.0/16
  
PrivateSubnets:
  - CIDR: 10.0.1.0/24  # AZ-1
  - CIDR: 10.0.2.0/24  # AZ-2
  - CIDR: 10.0.3.0/24  # AZ-3
  
SecurityGroups:
  MSKSecurityGroup:
    Ingress:
      - Protocol: TCP
        Port: 9092-9094
        Source: ApplicationSecurityGroup
  
  KDASecurityGroup:
    Egress:
      - Protocol: TCP
        Port: 443
        Destination: 0.0.0.0/0  # HTTPS to AWS services
```

## Next Steps
Tomorrow we'll complete the program with production patterns:
- Monitoring and observability
- Deployment strategies
- Disaster recovery
- Performance optimization
- Cost management