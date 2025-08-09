# Day 1: Apache Kafka Fundamentals - Complete Learning Guide

## 📚 Learning Overview
Today you'll master the fundamentals of Apache Kafka, the most popular distributed streaming platform. You'll learn core concepts, set up a Kafka cluster, and build your first producer-consumer applications.

## 🎯 Learning Objectives
By the end of today, you will be able to:
- Explain Kafka's architecture and core components
- Set up and configure a Kafka cluster
- Create topics and understand partitioning strategies  
- Build producers and consumers with best practices
- Understand message delivery semantics
- Troubleshoot common Kafka issues

---

## 📖 PART 1: Understanding Apache Kafka

### What is Apache Kafka?
Apache Kafka is a **distributed event streaming platform** designed to handle high-throughput, real-time data feeds. Think of it as a "nervous system" for your organization that allows different systems to communicate through events.

**Key Characteristics:**
- **Distributed**: Runs across multiple servers for fault tolerance
- **Scalable**: Can handle millions of events per second
- **Durable**: Data is persisted to disk and replicated
- **Real-time**: Low-latency message delivery (sub-millisecond)

### Why Use Kafka?
**Traditional Approach Problems:**
```
App A → Database → App B (polling every minute)
Problems: High latency, database load, tight coupling
```

**Kafka Approach:**
```
App A → Kafka Topic → App B (real-time events)
Benefits: Low latency, loose coupling, scalable
```

### Real-World Use Cases
1. **Activity Tracking**: Netflix tracks user interactions to personalize recommendations
2. **Log Aggregation**: LinkedIn collects logs from thousands of services
3. **Event Sourcing**: Financial systems track all account changes as events
4. **Stream Processing**: Uber processes location data for real-time matching

---

## 📖 PART 2: Kafka Architecture Deep Dive

### Core Components

#### 1. **Producer**
Applications that send data to Kafka topics.

```java
// Simple producer example
Properties props = new Properties();
props.put("bootstrap.servers", "localhost:9092");
props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");

Producer<String, String> producer = new KafkaProducer<>(props);
producer.send(new ProducerRecord<>("my-topic", "key", "Hello Kafka!"));
```

**Key Concepts:**
- **Serialization**: Converting objects to bytes for transmission
- **Partitioning**: How messages are distributed across partitions
- **Acknowledgment**: Confirmation that message was received

#### 2. **Consumer**
Applications that read data from Kafka topics.

```java
// Simple consumer example
Properties props = new Properties();
props.put("bootstrap.servers", "localhost:9092");
props.put("group.id", "my-consumer-group");
props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");

Consumer<String, String> consumer = new KafkaConsumer<>(props);
consumer.subscribe(Arrays.asList("my-topic"));

while (true) {
    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
    for (ConsumerRecord<String, String> record : records) {
        System.out.println("Received: " + record.value());
    }
}
```

#### 3. **Broker**
Kafka servers that store and serve data.

**Responsibilities:**
- Store messages on disk
- Serve read/write requests
- Replicate data for fault tolerance
- Handle leader election for partitions

#### 4. **Topic**
Logical categories where messages are published.

**Topic Naming Best Practices:**
```
✅ Good: user-events, order-payments, inventory-updates
❌ Bad: data, events, topic1
```

#### 5. **Partition**
Topics are split into partitions for parallelism and scalability.

**Partition Example:**
```
Topic: user-events (3 partitions)
Partition 0: [msg1, msg4, msg7, ...]
Partition 1: [msg2, msg5, msg8, ...]  
Partition 2: [msg3, msg6, msg9, ...]
```

**Key Points:**
- Messages within a partition are ordered
- No ordering guarantee across partitions
- Partition count affects maximum parallelism

#### 6. **Offset**
Unique identifier for each message within a partition.

```
Partition 0: [0] [1] [2] [3] [4] [5] ...
             msg msg msg msg msg msg
```

**Offset Management:**
- Consumers track their position (offset) in each partition
- Offsets are stored in special `__consumer_offsets` topic
- Enables replay and fault tolerance

---

## 📖 PART 3: Message Delivery Semantics

### 1. At-Most-Once Delivery
Messages may be lost but never duplicated.
```
Producer sends → Network failure → Message lost ❌
Use case: Metrics where occasional loss is acceptable
```

### 2. At-Least-Once Delivery
Messages are never lost but may be duplicated.
```
Producer sends → Ack timeout → Retry → Duplicate ✓✓
Use case: Financial transactions (deduplication downstream)
```

### 3. Exactly-Once Delivery
Each message is delivered exactly once (most complex).
```
Producer sends → Idempotent producer → Exactly once ✓
Use case: Critical business events
```

**Configuration Example:**
```java
// Exactly-once producer
props.put("enable.idempotence", true);
props.put("acks", "all");
props.put("retries", Integer.MAX_VALUE);
props.put("max.in.flight.requests.per.connection", 5);
```

---

## 📖 PART 4: Partitioning Strategies

### Default Partitioner (Round-Robin)
When no key is provided, messages are distributed evenly.

```java
// Round-robin partitioning
producer.send(new ProducerRecord<>("topic", null, "message1")); // → Partition 0
producer.send(new ProducerRecord<>("topic", null, "message2")); // → Partition 1
producer.send(new ProducerRecord<>("topic", null, "message3")); // → Partition 2
```

### Key-Based Partitioning
Messages with the same key go to the same partition.

```java
// Key-based partitioning
producer.send(new ProducerRecord<>("topic", "user123", "login")); // → Always same partition
producer.send(new ProducerRecord<>("topic", "user123", "logout")); // → Same partition
```

**Benefits:**
- Maintains ordering per key
- Enables stateful processing
- Predictable message routing

### Custom Partitioner
Define your own partitioning logic.

```java
public class CustomPartitioner implements Partitioner {
    @Override
    public int partition(String topic, Object key, byte[] keyBytes, Object value, 
                        byte[] valueBytes, Cluster cluster) {
        String keyStr = (String) key;
        if (keyStr.startsWith("premium_")) {
            return 0; // Premium users to partition 0
        }
        return 1; // Regular users to partition 1
    }
}
```

---

## 🛠️ PRACTICAL HANDS-ON EXERCISES

### Exercise 1: Set Up Your First Kafka Cluster

**Step 1: Start the Infrastructure**
```bash
# Navigate to day1 directory
cd day1

# Start Kafka cluster with Zookeeper
docker-compose up -d

# Check if services are running
docker-compose ps
```

**Expected Output:**
```
NAME                COMMAND                  SERVICE             STATUS
kafka               "bash -c 'if [[ -z \"…"   kafka               running
kafka-ui            "/bin/sh -c 'java --a…"   kafka-ui            running
zookeeper           "/etc/confluent/docker…"   zookeeper           running
```

**Step 2: Explore Kafka UI**
- Open http://localhost:8080 in your browser
- Familiarize yourself with the interface
- Note: No topics exist yet!

### Exercise 2: Create Your First Topic

**Using Command Line:**
```bash
# Create a topic with 3 partitions
docker exec -it kafka kafka-topics --create \
  --topic my-first-topic \
  --bootstrap-server localhost:9092 \
  --partitions 3 \
  --replication-factor 1

# List all topics
docker exec -it kafka kafka-topics --list \
  --bootstrap-server localhost:9092

# Describe the topic
docker exec -it kafka kafka-topics --describe \
  --topic my-first-topic \
  --bootstrap-server localhost:9092
```

**Expected Output:**
```
Topic: my-first-topic   TopicId: abc123   PartitionCount: 3       ReplicationFactor: 1
        Topic: my-first-topic   Partition: 0    Leader: 1       Replicas: 1     Isr: 1
        Topic: my-first-topic   Partition: 1    Leader: 1       Replicas: 1     Isr: 1
        Topic: my-first-topic   Partition: 2    Leader: 1       Replicas: 1     Isr: 1
```

### Exercise 3: Send Your First Messages

**Using Console Producer:**
```bash
# Start console producer
docker exec -it kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic my-first-topic

# Type these messages (press Enter after each):
Hello Kafka!
This is message 2
Learning streaming is fun!
^C (Ctrl+C to exit)
```

### Exercise 4: Consume Messages

**Using Console Consumer:**
```bash
# Start console consumer from beginning
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic my-first-topic \
  --from-beginning

# You should see all your messages!
```

**With Key-Value Pairs:**
```bash
# Producer with keys
docker exec -it kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic my-first-topic \
  --property "parse.key=true" \
  --property "key.separator=:"

# Type: user1:logged in
# Type: user2:placed order
# Type: user1:logged out

# Consumer showing keys
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic my-first-topic \
  --property "print.key=true" \
  --property "key.separator=:" \
  --from-beginning
```

### Exercise 5: Run the Java Applications

**Start Producer Application:**
```bash
# Build and run producer
docker-compose up producer-app
```

**Watch the logs** - you'll see messages being sent with metadata like partition and offset.

**Start Consumer Application (new terminal):**
```bash
# Build and run consumer  
docker-compose up consumer-app
```

**Observe:**
- Consumer receives all messages from producer
- Messages are processed with partition and offset info
- Consumer maintains offset position

### Exercise 6: Experiment with Consumer Groups

**Terminal 1 - Consumer Group Member 1:**
```bash
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic user-events \
  --group demo-group \
  --property print.key=true
```

**Terminal 2 - Consumer Group Member 2:**
```bash
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic user-events \
  --group demo-group \
  --property print.key=true
```

**Terminal 3 - Send Messages:**
```bash
docker-compose up producer-app
```

**Observation:**
- Each message goes to only ONE consumer in the group
- Load is balanced between consumers
- If you stop one consumer, the other gets all messages (rebalancing)

---

## 📊 UNDERSTANDING KAFKA INTERNALS

### Log Segments
Kafka stores messages in log segments on disk.

```
/var/lib/kafka/data/my-topic-0/
├── 00000000000000000000.log    # Active segment
├── 00000000000000000000.index  # Offset index
├── 00000000000000000000.timeindex # Time index
└── leader-epoch-checkpoint
```

### Retention Policies

**Time-Based Retention:**
```bash
# Keep messages for 7 days
docker exec -it kafka kafka-configs --alter \
  --bootstrap-server localhost:9092 \
  --entity-type topics \
  --entity-name my-topic \
  --add-config retention.ms=604800000
```

**Size-Based Retention:**
```bash
# Keep maximum 1GB per partition
docker exec -it kafka kafka-configs --alter \
  --bootstrap-server localhost:9092 \
  --entity-type topics \
  --entity-name my-topic \
  --add-config retention.bytes=1073741824
```

### Compaction
For topics where you only need the latest value per key.

```bash
# Enable log compaction
docker exec -it kafka kafka-configs --alter \
  --bootstrap-server localhost:9092 \
  --entity-type topics \
  --entity-name user-profiles \
  --add-config cleanup.policy=compact
```

---

## 🚨 COMMON PITFALLS AND SOLUTIONS

### Problem 1: Consumer Lag
**Symptoms:** Consumers falling behind producers
**Solutions:**
- Increase consumer parallelism
- Optimize consumer processing logic
- Add more consumers to the group

**Monitor Lag:**
```bash
docker exec -it kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --group demo-consumer-group \
  --describe
```

### Problem 2: Uneven Partition Distribution
**Symptoms:** Some partitions get more messages than others
**Solutions:**
- Choose better partition keys
- Use custom partitioner
- Increase partition count

### Problem 3: Message Loss
**Symptoms:** Messages disappear
**Solutions:**
- Set `acks=all` for producers
- Increase `min.insync.replicas`
- Enable producer retries

### Problem 4: Duplicate Messages
**Symptoms:** Same message processed multiple times
**Solutions:**
- Enable idempotent producers
- Implement consumer-side deduplication
- Use exactly-once semantics

---

## 📈 PERFORMANCE TUNING BASICS

### Producer Optimization
```java
Properties props = new Properties();
// Batch multiple messages for efficiency
props.put("batch.size", 16384);
// Wait up to 5ms to batch messages
props.put("linger.ms", 5);
// Compress messages to save bandwidth
props.put("compression.type", "snappy");
// Enable retries for reliability
props.put("retries", Integer.MAX_VALUE);
// Control memory usage
props.put("buffer.memory", 33554432); // 32MB
```

### Consumer Optimization
```java
Properties props = new Properties();
// Process up to 500 records per poll
props.put("max.poll.records", 500);
// Commit offsets every 5 seconds
props.put("auto.commit.interval.ms", 5000);
// Increase fetch size for throughput
props.put("fetch.min.bytes", 1024);
props.put("fetch.max.wait.ms", 500);
```

### Broker Optimization
```properties
# Increase log segment size for better performance
log.segment.bytes=1073741824
# More threads for network and I/O
num.network.threads=8
num.io.threads=16
# Larger socket buffer sizes
socket.send.buffer.bytes=102400
socket.receive.buffer.bytes=102400
```

---

## 🧪 ADVANCED EXERCISES

### Exercise 7: Message Ordering Test
**Goal:** Understand partition ordering guarantees

```bash
# Create topic with 1 partition
docker exec -it kafka kafka-topics --create \
  --topic ordered-topic \
  --bootstrap-server localhost:9092 \
  --partitions 1 \
  --replication-factor 1

# Send ordered messages
for i in {1..10}; do
  echo "Message $i" | docker exec -i kafka kafka-console-producer \
    --bootstrap-server localhost:9092 \
    --topic ordered-topic
done

# Consume and verify order
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic ordered-topic \
  --from-beginning
```

### Exercise 8: Key-Based Partitioning Test
**Goal:** Verify that same keys go to same partitions

```bash
# Send messages with keys
docker exec -it kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic user-events \
  --property "parse.key=true" \
  --property "key.separator=:"

# Type these messages:
user1:login
user2:login  
user1:purchase
user3:login
user2:logout
user1:logout
```

**Check partition assignment:**
```bash
# Consumer showing partition info
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic user-events \
  --property print.key=true \
  --property print.partition=true \
  --from-beginning
```

### Exercise 9: Consumer Group Rebalancing
**Goal:** Observe consumer group rebalancing in action

1. Start 2 consumers in same group
2. Start producer sending messages
3. Stop one consumer and observe rebalancing
4. Start a third consumer and observe partition reassignment

---

## 📝 PRACTICAL INTERVIEW QUESTIONS

### Beginner Level (0-2 years)
**Q1: What is Apache Kafka?**
**A:** Apache Kafka is a distributed event streaming platform that allows you to publish and subscribe to streams of records, similar to a message queue or enterprise messaging system. It's designed for high-throughput, low-latency handling of real-time data feeds.

**Q2: Explain the difference between a topic and a partition.**
**A:** A topic is a logical category or feed name to which messages are published. A partition is a subdivision of a topic that allows for parallel processing and ordering guarantees within that partition. Topics can have multiple partitions for scalability.

**Q3: What happens when a consumer in a consumer group fails?**
**A:** When a consumer fails, Kafka triggers a rebalancing process where the failed consumer's partitions are redistributed among the remaining active consumers in the group. This ensures continued processing without message loss.

### Intermediate Level (2-5 years)
**Q4: How does Kafka ensure message ordering?**
**A:** Kafka guarantees message ordering within a partition, not across partitions. Messages with the same key are always sent to the same partition, ensuring order for that key. If global ordering is needed, use a single partition (limits parallelism).

**Q5: Explain the different acknowledgment levels in Kafka producers.**
**A:** 
- `acks=0`: Fire-and-forget, no acknowledgment waited
- `acks=1`: Wait for leader replica acknowledgment only
- `acks=all/-1`: Wait for all in-sync replicas to acknowledge

**Q6: What is consumer lag and how would you handle it?**
**A:** Consumer lag is the difference between the latest message offset and the consumer's current offset. Handle by: adding more consumers, optimizing processing logic, scaling consumer instances, or increasing partition count.

### Advanced Level (5+ years)
**Q7: How would you implement exactly-once processing in Kafka?**
**A:** Use idempotent producers (`enable.idempotence=true`), transactional semantics for multi-partition writes, and ensure consumers handle duplicates. Also configure `acks=all`, appropriate `retries`, and `max.in.flight.requests.per.connection=5`.

**Q8: Design a Kafka topic strategy for a high-throughput e-commerce system.**
**A:** Consider: partition count based on expected throughput and consumer count, replication factor for fault tolerance, retention policies for storage optimization, key selection for even distribution, and topic naming conventions for organization.

---

## ✅ DAY 1 COMPLETION CHECKLIST

Before moving to Day 2, ensure you can:

**Conceptual Understanding:**
- [ ] Explain Kafka's architecture (brokers, topics, partitions, offsets)
- [ ] Describe producer and consumer roles
- [ ] Understand message delivery semantics
- [ ] Explain partitioning strategies

**Practical Skills:**
- [ ] Start a Kafka cluster using Docker
- [ ] Create topics with appropriate configurations
- [ ] Send messages using producers
- [ ] Consume messages using consumers
- [ ] Monitor topics and consumer groups

**Hands-On Verification:**
- [ ] Successfully run all exercises (1-6)
- [ ] Observe consumer group rebalancing
- [ ] Test message ordering within partitions
- [ ] Experiment with key-based partitioning

**Troubleshooting:**
- [ ] Check service logs when issues occur
- [ ] Use Kafka UI to visualize cluster state
- [ ] Understand common error messages

---

## 🚀 WHAT'S NEXT?

Tomorrow (Day 2), you'll dive into:
- Advanced Kafka patterns and operations
- Kafka Streams for real-time processing
- Schema Registry for data governance
- Multi-broker clusters and replication
- Kafka Connect for integration

**Preparation:**
- Review today's concepts if needed
- Ensure all exercises work correctly
- Read through any error messages you encountered
- Think about how Kafka could solve problems in your current work

## 📚 Additional Resources

**Official Documentation:**
- [Kafka Documentation](https://kafka.apache.org/documentation/)
- [Kafka Quickstart](https://kafka.apache.org/quickstart)

**Books:**
- "Kafka: The Definitive Guide" by Neha Narkhede
- "Designing Data-Intensive Applications" by Martin Kleppmann

**Community:**
- [Confluent Community](https://www.confluent.io/community/)
- [Apache Kafka Users Mailing List](https://kafka.apache.org/contact)

---

Great job completing Day 1! You now have a solid foundation in Kafka fundamentals. Tomorrow we'll build on this knowledge with advanced patterns and real-world applications.