# Day 2: Advanced Kafka Patterns and Operations - Complete Learning Guide

## 📚 Learning Overview
Today you'll dive deep into advanced Kafka patterns including Kafka Streams for real-time processing, Schema Registry for data governance, and Kafka Connect for seamless integrations. You'll work with multi-broker clusters and learn production-ready patterns.

## 🎯 Learning Objectives
By the end of today, you will be able to:
- Set up and manage multi-broker Kafka clusters
- Build stream processing applications with Kafka Streams
- Implement schema evolution using Schema Registry
- Create data pipelines with Kafka Connect
- Understand advanced Kafka operations and monitoring
- Apply exactly-once processing patterns

---

## 📖 PART 1: Multi-Broker Kafka Clusters

### Why Multi-Broker Clusters?
Single-broker setups are fine for development, but production requires multiple brokers for:
- **Fault Tolerance**: If one broker fails, others continue serving
- **Scalability**: Distribute load across multiple servers
- **Replication**: Data is copied across brokers for durability

### Understanding Replication

**Replication Factor = 3 Example:**
```
Topic: orders (replication-factor=3)
Partition 0: [Broker 1 (Leader), Broker 2 (Follower), Broker 3 (Follower)]
Partition 1: [Broker 2 (Leader), Broker 1 (Follower), Broker 3 (Follower)]
Partition 2: [Broker 3 (Leader), Broker 1 (Follower), Broker 2 (Follower)]
```

**Key Concepts:**
- **Leader**: Handles all reads and writes for a partition
- **Follower**: Replicates data from the leader
- **ISR (In-Sync Replicas)**: Replicas that are caught up with the leader
- **Min In-Sync Replicas**: Minimum replicas that must acknowledge writes

### Cluster Management Best Practices

**Topic Creation for Production:**
```bash
# Create production topic with proper replication
kafka-topics --create \
  --topic user-events \
  --bootstrap-server localhost:9092 \
  --partitions 12 \
  --replication-factor 3 \
  --config min.insync.replicas=2 \
  --config cleanup.policy=delete \
  --config retention.ms=604800000
```

**Partition Count Guidelines:**
- Start with: `max(expected_throughput_MB/s, expected_consumer_count)`
- E.g., 100 MB/s throughput, 20 consumers → 100 partitions
- Can increase partitions later, but never decrease

---

## 📖 PART 2: Kafka Streams Deep Dive

### What is Kafka Streams?
Kafka Streams is a client library for building real-time applications that process data stored in Kafka. Unlike other stream processing frameworks, it doesn't require a separate cluster.

**Key Benefits:**
- **Simple Deployment**: Just a Java library, no separate cluster
- **Fault Tolerant**: Built-in state management and recovery
- **Scalable**: Automatically distributes processing across instances
- **Exactly-Once**: Supports exactly-once processing semantics

### Core Abstractions

#### 1. KStream (Record Stream)
Represents a stream of records where each record is an independent event.

```java
KStream<String, String> textStream = builder.stream("text-input");

// Transform each record
KStream<String, String> uppercaseStream = textStream
    .mapValues(value -> value.toUpperCase());

// Filter records
KStream<String, String> longMessages = textStream
    .filter((key, value) -> value.length() > 100);

// FlatMap - one input can produce multiple outputs
KStream<String, String> words = textStream
    .flatMapValues(value -> Arrays.asList(value.split("\\s+")));
```

#### 2. KTable (Changelog Stream)
Represents a table where each record represents an update to a key.

```java
KTable<String, String> userProfiles = builder.table("user-profiles");

// Updates are automatically handled
// Key "user123" -> "John Doe" (initial)
// Key "user123" -> "John Smith" (update - overwrites previous)
```

#### 3. GlobalKTable
A special type of KTable that is fully replicated on each application instance.

```java
GlobalKTable<String, String> exchangeRates = builder.globalTable("exchange-rates");

// Available for lookups on all instances without repartitioning
KStream<String, String> enrichedStream = orders
    .join(exchangeRates,
          (orderKey, orderValue) -> orderValue.getCurrency(), // Key selector
          (order, rate) -> enrichOrder(order, rate));          // Value joiner
```

### Stream Processing Patterns

#### Pattern 1: Stateless Transformations
```java
KStream<String, Order> orders = builder.stream("orders");

// Map: Transform each order
KStream<String, OrderTotal> orderTotals = orders
    .mapValues(order -> new OrderTotal(order.getId(), calculateTotal(order)));

// Filter: Only high-value orders
KStream<String, Order> highValueOrders = orders
    .filter((key, order) -> order.getTotal() > 1000);

// Branch: Split stream into multiple streams
KStream<String, Order>[] branches = orders
    .branch(
        (key, order) -> order.getRegion().equals("US"),    // US orders
        (key, order) -> order.getRegion().equals("EU"),    // EU orders
        (key, order) -> true                               // All other orders
    );
```

#### Pattern 2: Stateful Transformations
```java
// Aggregation: Count orders per customer
KTable<String, Long> orderCounts = orders
    .groupByKey()
    .count();

// Windowed aggregation: Count orders per hour
KTable<Windowed<String>, Long> hourlyOrderCounts = orders
    .groupByKey()
    .windowedBy(TimeWindows.of(Duration.ofHours(1)))
    .count();

// Reduce: Calculate total order value per customer
KTable<String, Double> customerSpending = orders
    .groupByKey()
    .aggregate(
        () -> 0.0,                                    // Initializer
        (key, order, total) -> total + order.getTotal(), // Aggregator
        Materialized.with(Serdes.String(), Serdes.Double())
    );
```

#### Pattern 3: Joins
```java
// KStream-KStream Join (within time window)
KStream<String, String> orderShipmentJoin = orders
    .join(
        shipments,
        (order, shipment) -> "Order " + order.getId() + " shipped via " + shipment.getCarrier(),
        JoinWindows.of(Duration.ofHours(24))
    );

// KStream-KTable Join (stream enrichment)
KStream<String, String> enrichedOrders = orders
    .join(
        customers, // KTable
        (order, customer) -> enrichWithCustomerInfo(order, customer)
    );
```

### Windowing in Detail

#### Tumbling Windows
Fixed-size, non-overlapping time intervals.

```java
// 5-minute tumbling windows
KTable<Windowed<String>, Long> tumblingCounts = stream
    .groupByKey()
    .windowedBy(TimeWindows.of(Duration.ofMinutes(5)))
    .count();

// Windows: [00:00-00:05), [00:05-00:10), [00:10-00:15), ...
```

#### Hopping Windows
Fixed-size, overlapping time intervals.

```java
// 10-minute windows, advancing every 5 minutes
KTable<Windowed<String>, Long> hoppingCounts = stream
    .groupByKey()
    .windowedBy(TimeWindows.of(Duration.ofMinutes(10))
                          .advanceBy(Duration.ofMinutes(5)))
    .count();

// Windows: [00:00-00:10), [00:05-00:15), [00:10-00:20), ...
```

#### Session Windows
Dynamic windows based on activity gaps.

```java
// Session windows with 30-minute inactivity gap
KTable<Windowed<String>, String> sessions = stream
    .groupByKey()
    .windowedBy(SessionWindows.with(Duration.ofMinutes(30)))
    .aggregate(
        () -> "",
        (key, value, aggregate) -> aggregate + value + ";",
        (key, leftAggregate, rightAggregate) -> leftAggregate + rightAggregate
    );
```

---

## 📖 PART 3: Schema Registry and Data Governance

### Why Schema Registry?
As systems grow, data formats evolve. Schema Registry provides:
- **Schema Versioning**: Track schema changes over time
- **Compatibility Checking**: Ensure schema changes don't break consumers
- **Centralized Management**: Single source of truth for schemas
- **Serialization Efficiency**: Binary formats with schema references

### Schema Evolution Types

#### 1. Backward Compatibility
New schema can read data written with old schema.
```json
// Version 1
{
  "type": "record",
  "name": "User",
  "fields": [
    {"name": "id", "type": "int"},
    {"name": "name", "type": "string"}
  ]
}

// Version 2 (Backward Compatible)
{
  "type": "record", 
  "name": "User",
  "fields": [
    {"name": "id", "type": "int"},
    {"name": "name", "type": "string"},
    {"name": "email", "type": ["null", "string"], "default": null}
  ]
}
```

#### 2. Forward Compatibility
Old schema can read data written with new schema.
```json
// Old consumers can ignore the new "email" field
```

#### 3. Full Compatibility
Both backward and forward compatible - safest but most restrictive.

### Working with Avro Schemas

**Producer with Schema Registry:**
```java
Properties props = new Properties();
props.put("bootstrap.servers", "localhost:9092");
props.put("key.serializer", StringSerializer.class.getName());
props.put("value.serializer", KafkaAvroSerializer.class.getName());
props.put("schema.registry.url", "http://localhost:8081");

Producer<String, User> producer = new KafkaProducer<>(props);

User user = User.newBuilder()
    .setId(123)
    .setName("John Doe")
    .setEmail("john@example.com")
    .build();

producer.send(new ProducerRecord<>("users", "user-123", user));
```

**Consumer with Schema Registry:**
```java
Properties props = new Properties();
props.put("bootstrap.servers", "localhost:9092");
props.put("key.deserializer", StringDeserializer.class.getName());
props.put("value.deserializer", KafkaAvroDeserializer.class.getName());
props.put("schema.registry.url", "http://localhost:8081");
props.put("specific.avro.reader", "true");

Consumer<String, User> consumer = new KafkaConsumer<>(props);
consumer.subscribe(Collections.singletonList("users"));

while (true) {
    ConsumerRecords<String, User> records = consumer.poll(Duration.ofMillis(1000));
    for (ConsumerRecord<String, User> record : records) {
        User user = record.value();
        System.out.println("Received user: " + user.getName());
    }
}
```

---

## 📖 PART 4: Kafka Connect Deep Dive

### What is Kafka Connect?
Kafka Connect is a framework for scalably and reliably streaming data between Kafka and other data systems.

**Key Benefits:**
- **Fault Tolerant**: Automatic failover and recovery
- **Scalable**: Distributed workers for high throughput
- **Easy Configuration**: JSON-based connector configuration
- **Exactly-Once**: Supports exactly-once delivery guarantees

### Connector Types

#### Source Connectors
Import data from external systems to Kafka.

**Database Source Connector:**
```json
{
  "name": "mysql-source-connector",
  "config": {
    "connector.class": "io.debezium.connector.mysql.MySqlConnector",
    "tasks.max": "1",
    "database.hostname": "mysql",
    "database.port": "3306",
    "database.user": "debezium",
    "database.password": "dbz",
    "database.server.id": "184054",
    "database.server.name": "mysql",
    "database.include.list": "inventory",
    "database.history.kafka.bootstrap.servers": "kafka:9092",
    "database.history.kafka.topic": "schema-changes.inventory"
  }
}
```

#### Sink Connectors
Export data from Kafka to external systems.

**Elasticsearch Sink Connector:**
```json
{
  "name": "elasticsearch-sink-connector",
  "config": {
    "connector.class": "io.confluent.connect.elasticsearch.ElasticsearchSinkConnector",
    "tasks.max": "1",
    "topics": "user-events",
    "connection.url": "http://elasticsearch:9200",
    "type.name": "_doc",
    "key.ignore": "true",
    "schema.ignore": "true"
  }
}
```

### Single Message Transforms (SMTs)
Lightweight transformations applied to each message.

```json
{
  "name": "file-source-with-transforms",
  "config": {
    "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
    "tasks.max": "1",
    "file": "/tmp/input.txt",
    "topic": "file-topic",
    "transforms": "route,TimestampConverter",
    "transforms.route.type": "org.apache.kafka.connect.transforms.RegexRouter",
    "transforms.route.regex": ".*",
    "transforms.route.replacement": "transformed-topic",
    "transforms.TimestampConverter.type": "org.apache.kafka.connect.transforms.TimestampConverter$Value",
    "transforms.TimestampConverter.target.type": "Timestamp",
    "transforms.TimestampConverter.field": "timestamp"
  }
}
```

---

## 🛠️ PRACTICAL HANDS-ON EXERCISES

### Exercise 1: Set Up Multi-Broker Cluster

**Step 1: Start the Infrastructure**
```bash
# Navigate to day2 directory
cd day2

# Start multi-broker Kafka cluster
docker-compose up -d

# Check all services are running
docker-compose ps
```

**You should see:**
- 3 Kafka brokers
- Schema Registry
- Kafka Connect
- Kafka Streams application

**Step 2: Verify Cluster Health**
```bash
# List brokers
docker exec -it kafka-1 kafka-broker-api-versions --bootstrap-server localhost:9092

# Create replicated topic
docker exec -it kafka-1 kafka-topics --create \
  --topic replicated-events \
  --bootstrap-server localhost:9092 \
  --partitions 6 \
  --replication-factor 3

# Describe topic to see replication
docker exec -it kafka-1 kafka-topics --describe \
  --topic replicated-events \
  --bootstrap-server localhost:9092
```

### Exercise 2: Test Fault Tolerance

**Step 1: Create Topic and Send Data**
```bash
# Create topic
docker exec -it kafka-1 kafka-topics --create \
  --topic fault-test \
  --bootstrap-server localhost:9092 \
  --partitions 3 \
  --replication-factor 3

# Start producer
docker exec -it kafka-1 kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic fault-test
```

**Step 2: Simulate Broker Failure**
```bash
# In another terminal, stop a broker
docker stop kafka-2

# Continue sending messages - they should still work!
# Check topic status
docker exec -it kafka-1 kafka-topics --describe \
  --topic fault-test \
  --bootstrap-server localhost:9092
```

**Step 3: Observe Recovery**
```bash
# Restart the broker
docker start kafka-2

# Watch it rejoin the cluster and catch up
```

### Exercise 3: Build Your First Streams Application

**Understanding the Word Count Application:**
The provided `WordCountStreamsApp` does the following:
1. Reads text from `text-input` topic
2. Splits text into individual words
3. Counts occurrences of each word
4. Writes counts to `word-counts` topic

**Step 1: Prepare Topics**
```bash
# Create input topic
docker exec -it kafka-1 kafka-topics --create \
  --topic text-input \
  --bootstrap-server localhost:9092 \
  --partitions 3 \
  --replication-factor 2

# Create output topic
docker exec -it kafka-1 kafka-topics --create \
  --topic word-counts \
  --bootstrap-server localhost:9092 \
  --partitions 3 \
  --replication-factor 2
```

**Step 2: Start the Streams Application**
```bash
# Start the Kafka Streams word count application
docker-compose up kafka-streams-app
```

**Step 3: Send Test Data**
```bash
# Send some text data
docker exec -it kafka-1 kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic text-input

# Type these sentences:
hello world
hello kafka streams
world of streaming
kafka is awesome
```

**Step 4: View Results**
```bash
# View word counts
docker exec -it kafka-1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic word-counts \
  --from-beginning \
  --property print.key=true \
  --property key.separator=" => "
```

**Expected Output:**
```
hello => 2
world => 2
kafka => 2
streams => 1
of => 1
streaming => 1
is => 1
awesome => 1
```

### Exercise 4: Schema Registry Operations

**Step 1: Register a Schema**
```bash
# Register user schema
curl -X POST -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  --data '{
    "schema": "{
      \"type\": \"record\",
      \"name\": \"User\",
      \"fields\": [
        {\"name\": \"id\", \"type\": \"int\"},
        {\"name\": \"name\", \"type\": \"string\"},
        {\"name\": \"email\", \"type\": [\"null\", \"string\"], \"default\": null}
      ]
    }"
  }' \
  http://localhost:8081/subjects/users-value/versions
```

**Step 2: List Subjects**
```bash
curl -X GET http://localhost:8081/subjects
```

**Step 3: Get Schema Details**
```bash
curl -X GET http://localhost:8081/subjects/users-value/versions/1
```

**Step 4: Test Compatibility**
```bash
# Try to register a breaking change
curl -X POST -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  --data '{
    "schema": "{
      \"type\": \"record\",
      \"name\": \"User\",
      \"fields\": [
        {\"name\": \"id\", \"type\": \"int\"},
        {\"name\": \"full_name\", \"type\": \"string\"}
      ]
    }"
  }' \
  http://localhost:8081/compatibility/subjects/users-value/versions/latest
```

### Exercise 5: Kafka Connect Pipeline

**Step 1: Create Source Connector**
```bash
# Create file source connector
curl -X POST -H "Content-Type: application/json" \
  --data '{
    "name": "file-source-connector",
    "config": {
      "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
      "tasks.max": "1",
      "file": "/tmp/input.txt",
      "topic": "file-events"
    }
  }' \
  http://localhost:8083/connectors
```

**Step 2: Check Connector Status**
```bash
curl -X GET http://localhost:8083/connectors/file-source-connector/status
```

**Step 3: Add Data to Source File**
```bash
# Add data to the source file
docker exec -it kafka-connect-day2 bash -c "echo 'Hello from Connect!' >> /tmp/input.txt"
docker exec -it kafka-connect-day2 bash -c "echo 'Another line of data' >> /tmp/input.txt"
```

**Step 4: Verify Data in Kafka**
```bash
# Check if data appears in topic
docker exec -it kafka-1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic file-events \
  --from-beginning
```

---

## 📊 ADVANCED STREAMING PATTERNS

### Pattern 1: Event Sourcing
Store all changes as a sequence of events.

```java
// Event sourcing with Kafka Streams
KStream<String, AccountEvent> events = builder.stream("account-events");

// Build current account state from events
KTable<String, Account> currentState = events
    .groupByKey()
    .aggregate(
        Account::new,                           // Initial state
        (accountId, event, account) -> {        // Apply event
            return account.apply(event);
        },
        Materialized.as("account-state-store")
    );
```

### Pattern 2: CQRS (Command Query Responsibility Segregation)
Separate read and write models.

```java
// Command side - process commands into events
KStream<String, Command> commands = builder.stream("commands");
KStream<String, Event> events = commands
    .mapValues(command -> processCommand(command));

events.to("events");

// Query side - build read models from events
KTable<String, ReadModel> readModel = builder
    .stream("events")
    .groupByKey()
    .aggregate(
        ReadModel::new,
        (key, event, model) -> model.update(event),
        Materialized.as("read-model-store")
    );
```

### Pattern 3: Saga Pattern
Manage distributed transactions across services.

```java
// Saga orchestrator
KStream<String, SagaEvent> sagaEvents = builder.stream("saga-events");

sagaEvents
    .filter((key, event) -> event.getType().equals("ORDER_CREATED"))
    .mapValues(event -> new PaymentCommand(event.getOrderId()))
    .to("payment-commands");

sagaEvents
    .filter((key, event) -> event.getType().equals("PAYMENT_COMPLETED"))
    .mapValues(event -> new ShippingCommand(event.getOrderId()))
    .to("shipping-commands");
```

---

## 📈 PERFORMANCE OPTIMIZATION

### Streams Application Tuning

**Key Configuration Parameters:**
```java
Properties props = new Properties();

// Processing guarantee
props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE);

// Parallelism
props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 4);

// State store configuration
props.put(StreamsConfig.STATE_DIR_CONFIG, "/tmp/kafka-streams");
props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000);

// Cache size for state stores
props.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 10 * 1024 * 1024);

// RocksDB configuration for large state
props.put(StreamsConfig.ROCKSDB_CONFIG_SETTER_CLASS_CONFIG, CustomRocksDBConfigSetter.class);
```

### Monitoring Streams Applications

**Key Metrics to Track:**
```java
// Custom metrics in your application
public class MetricsCollector implements StreamsMetricsReporter {
    private final Counter processedRecords = Counter.build()
        .name("processed_records_total")
        .help("Total processed records")
        .register();

    @Override
    public void recordLatency(String threadId, String taskId, String metricName, double latency) {
        // Record processing latency
    }

    @Override
    public void recordThroughput(String threadId, String taskId, String metricName, double throughput) {
        // Record throughput metrics
    }
}
```

---

## 🧪 ADVANCED EXERCISES

### Exercise 6: Real-Time Analytics Dashboard

**Goal:** Build a real-time analytics pipeline

**Step 1: Set Up Data Generation**
```bash
# Create user activity topic
docker exec -it kafka-1 kafka-topics --create \
  --topic user-activity \
  --bootstrap-server localhost:9092 \
  --partitions 6 \
  --replication-factor 2
```

**Step 2: Generate Sample Data**
```bash
# Start generating user activity data
docker exec -it kafka-1 kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic user-activity

# Send JSON events like:
{"user_id":"user1","action":"login","timestamp":"2024-01-15T10:00:00Z"}
{"user_id":"user1","action":"page_view","page":"/products","timestamp":"2024-01-15T10:01:00Z"}
{"user_id":"user2","action":"login","timestamp":"2024-01-15T10:02:00Z"}
{"user_id":"user1","action":"purchase","amount":99.99,"timestamp":"2024-01-15T10:05:00Z"}
```

**Step 3: Build Streams Analytics**
Create a custom streams application to:
- Count active users per minute
- Calculate total revenue per hour
- Track popular pages

### Exercise 7: Schema Evolution Testing

**Goal:** Test schema compatibility

**Step 1: Create Initial Schema**
```bash
# Register v1 schema (only id and name)
curl -X POST -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  --data '{
    "schema": "{\"type\":\"record\",\"name\":\"Customer\",\"fields\":[{\"name\":\"id\",\"type\":\"int\"},{\"name\":\"name\",\"type\":\"string\"}]}"
  }' \
  http://localhost:8081/subjects/customers-value/versions
```

**Step 2: Evolve Schema (Add Optional Field)**
```bash
# Register v2 schema (add optional email field)
curl -X POST -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  --data '{
    "schema": "{\"type\":\"record\",\"name\":\"Customer\",\"fields\":[{\"name\":\"id\",\"type\":\"int\"},{\"name\":\"name\",\"type\":\"string\"},{\"name\":\"email\",\"type\":[\"null\",\"string\"],\"default\":null}]}"
  }' \
  http://localhost:8081/subjects/customers-value/versions
```

**Step 3: Test Compatibility**
- Send data with v1 schema
- Send data with v2 schema  
- Verify consumers can read both formats

### Exercise 8: Multi-Connect Worker Setup

**Goal:** Scale Kafka Connect for high throughput

**Step 1: Add More Connect Workers**
```bash
# Scale connect workers
docker-compose up -d --scale kafka-connect=3
```

**Step 2: Create High-Throughput Connector**
```bash
# Create connector with multiple tasks
curl -X POST -H "Content-Type: application/json" \
  --data '{
    "name": "multi-task-connector",
    "config": {
      "connector.class": "org.apache.kafka.connect.file.FileStreamSourceConnector",
      "tasks.max": "3",
      "topic": "high-throughput-topic"
    }
  }' \
  http://localhost:8083/connectors
```

---

## 📝 PRACTICAL INTERVIEW QUESTIONS

### Intermediate Level (2-5 years)

**Q1: What's the difference between KStream and KTable?**
**A:** KStream represents a stream of facts/events where each record is independent. KTable represents a changelog stream where each record is an update to a keyed state, maintaining the latest value per key.

**Q2: How does Kafka Streams handle fault tolerance?**
**A:** Kafka Streams uses local state stores backed by changelog topics in Kafka. When a stream thread fails, another instance can read the changelog topic to restore the state and continue processing.

**Q3: Explain schema compatibility types in Schema Registry.**
**A:** 
- **Backward**: New schema can read old data (e.g., adding optional fields)
- **Forward**: Old schema can read new data (e.g., removing fields)
- **Full**: Both backward and forward compatible
- **None**: No compatibility checking

**Q4: When would you use Kafka Connect vs custom producers/consumers?**
**A:** Use Kafka Connect for:
- Standard data sources/sinks (databases, files, cloud services)
- When you need fault tolerance and scalability out-of-the-box
- When minimal custom logic is required

Use custom clients for:
- Complex business logic
- Custom data formats or protocols
- When you need fine-grained control

### Advanced Level (5+ years)

**Q5: Design a exactly-once processing pipeline with Kafka Streams.**
**A:** Enable exactly-once semantics with:
```java
props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE_V2);
props.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 3);
```
Ensure:
- Idempotent producers
- Transactional state stores
- Proper error handling
- Adequate replication factor

**Q6: How would you handle late-arriving data in stream processing?**
**A:** 
- Configure grace period for windows
- Use allowed lateness settings
- Implement custom timestamp extractors
- Handle out-of-order data with watermarks
- Consider side outputs for very late data

**Q7: Design a multi-tenant Kafka Streams application.**
**A:** Strategies include:
- Topic prefixing per tenant
- Separate application instances per tenant
- Shared processing with tenant-aware routing
- Resource quotas and isolation
- Monitoring and alerting per tenant

---

## ✅ DAY 2 COMPLETION CHECKLIST

Before moving to Day 3, ensure you can:

**Multi-Broker Operations:**
- [ ] Set up and manage multi-broker clusters
- [ ] Understand replication and fault tolerance
- [ ] Create topics with proper replication settings
- [ ] Handle broker failures gracefully

**Kafka Streams:**
- [ ] Build basic stream processing applications
- [ ] Understand KStream vs KTable differences
- [ ] Implement windowed operations
- [ ] Join streams and tables

**Schema Registry:**
- [ ] Register and manage schemas
- [ ] Test schema compatibility
- [ ] Handle schema evolution
- [ ] Use Avro with Kafka producers/consumers

**Kafka Connect:**
- [ ] Create and manage connectors
- [ ] Configure source and sink connectors
- [ ] Apply single message transforms
- [ ] Monitor connector health

**Advanced Concepts:**
- [ ] Implement exactly-once processing
- [ ] Handle stateful stream processing
- [ ] Design event-driven architectures
- [ ] Optimize stream processing performance

---

## 🚀 WHAT'S NEXT?

Tomorrow (Day 3), you'll dive into Apache Flink:
- Flink architecture and execution model
- DataStream API fundamentals  
- Event time processing and watermarks
- Checkpointing and fault tolerance
- Integration with external systems

**Preparation:**
- Ensure all Day 2 exercises work correctly
- Review stream processing concepts
- Think about real-world streaming use cases
- Consider how Kafka Streams compares to other stream processing frameworks

## 📚 Additional Resources

**Kafka Streams:**
- [Kafka Streams Documentation](https://kafka.apache.org/documentation/streams/)
- [Kafka Streams Examples](https://github.com/confluentinc/kafka-streams-examples)

**Schema Registry:**
- [Schema Registry Documentation](https://docs.confluent.io/platform/current/schema-registry/)
- [Avro Documentation](https://avro.apache.org/docs/current/)

**Kafka Connect:**
- [Kafka Connect Documentation](https://kafka.apache.org/documentation/#connect)
- [Confluent Hub](https://www.confluent.io/hub/) - Connector repository

---

Excellent work completing Day 2! You now understand advanced Kafka patterns and can build sophisticated streaming applications. Tomorrow we'll explore Apache Flink for even more powerful stream processing capabilities.