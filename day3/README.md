# Day 3: Apache Flink Basics and Stream Processing - Complete Learning Guide

## 📚 Learning Overview
Today you'll master Apache Flink, the leading stream processing framework. You'll learn Flink's architecture, DataStream API, event time processing, and fault tolerance mechanisms. You'll build real-time applications that handle complex event streams.

## 🎯 Learning Objectives
By the end of today, you will be able to:
- Understand Flink's distributed architecture and execution model
- Use the DataStream API for stream transformations
- Implement event time processing with watermarks
- Configure checkpointing for fault tolerance
- Build end-to-end stream processing applications
- Handle different data sources and sinks

---

## 📖 PART 1: Understanding Apache Flink

### What is Apache Flink?
Apache Flink is a **distributed stream processing framework** for stateful computations over unbounded and bounded data streams. Unlike batch processing systems, Flink treats batch as a special case of streaming.

**Key Characteristics:**
- **Stream-first**: Designed for continuous data processing
- **Low Latency**: Sub-second processing latencies
- **High Throughput**: Millions of events per second
- **Exactly-Once**: Strong consistency guarantees
- **Stateful**: Rich state management capabilities

### Flink vs Other Systems

**Flink vs Kafka Streams:**
```
Kafka Streams:
✅ Simple deployment (library)
✅ Kafka-native
❌ Limited to Kafka sources
❌ JVM-only

Apache Flink:
✅ Multiple data sources (Kafka, files, databases)
✅ Multiple languages (Java, Scala, Python)
✅ More complex operations (CEP, ML)
✅ Better resource management
❌ More complex deployment
```

**Flink vs Spark Streaming:**
```
Spark Streaming:
- Micro-batching approach
- Higher latency (seconds)
- Mature ecosystem

Apache Flink:
- True streaming
- Lower latency (milliseconds)
- Better exactly-once guarantees
```

### Real-World Use Cases

**1. Fraud Detection (Banking)**
```
Credit card transactions → Flink → Real-time scoring → Block suspicious transactions
Benefits: Sub-second detection, complex pattern matching, stateful rules
```

**2. Real-time Recommendations (E-commerce)**
```
User interactions → Flink → Feature computation → ML model → Recommendations
Benefits: Low latency, personalization, session management
```

**3. IoT Analytics (Manufacturing)**
```
Sensor data → Flink → Anomaly detection → Alerts → Predictive maintenance
Benefits: Time window aggregations, event pattern detection
```

---

## 📖 PART 2: Flink Architecture Deep Dive

### Core Components

#### 1. **JobManager**
The master process that coordinates distributed execution.

**Responsibilities:**
- **Job Scheduling**: Decides where to execute tasks
- **Checkpointing**: Coordinates distributed snapshots
- **Recovery**: Handles failure recovery
- **Resource Management**: Allocates slots to tasks

**Components:**
- **Dispatcher**: Accepts job submissions
- **ResourceManager**: Manages TaskManager slots
- **JobMaster**: Manages specific job execution

#### 2. **TaskManager**
Worker processes that execute tasks and manage data exchange.

**Responsibilities:**
- **Task Execution**: Runs actual stream processing code
- **Memory Management**: Handles memory allocation for tasks
- **Network**: Manages data exchange between tasks
- **State Storage**: Maintains local state (with state backend)

**Slots:**
```
TaskManager (4 slots):
[Slot 1: Source Task] → [Slot 2: Map Task] → [Slot 3: KeyBy Task] → [Slot 4: Sink Task]
```

#### 3. **Execution Graph**
Physical execution plan derived from your Flink program.

```
Your Code:           stream.map(...).keyBy(...).sum(...)
     ↓
Job Graph:           Source → Map → KeyBy → Sum → Sink
     ↓
Execution Graph:     Task1 → Task2 → Task3 (with parallelism)
```

### Distributed Execution

**Example with Parallelism 4:**
```
Source (4) → Map (4) → KeyBy (4) → Sum (4) → Sink (4)

TaskManager 1: [Source-1, Map-1, KeyBy-1, Sum-1]
TaskManager 2: [Source-2, Map-2, KeyBy-2, Sum-2]  
TaskManager 3: [Source-3, Map-3, KeyBy-3, Sum-3]
TaskManager 4: [Source-4, Map-4, KeyBy-4, Sum-4]
```

---

## 📖 PART 3: DataStream API Fundamentals

### Creating DataStreams

#### From Collections (Testing)
```java
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

// From collection
DataStream<Integer> numbers = env.fromCollection(Arrays.asList(1, 2, 3, 4, 5));

// From elements
DataStream<String> words = env.fromElements("hello", "world", "flink");
```

#### From Sources (Production)
```java
// Kafka source
Properties props = new Properties();
props.setProperty("bootstrap.servers", "localhost:9092");

DataStream<String> kafkaStream = env
    .addSource(new FlinkKafkaConsumer<>("my-topic", new SimpleStringSchema(), props));

// File source
DataStream<String> fileStream = env.readTextFile("hdfs://path/to/file");

// Socket source (for testing)
DataStream<String> socketStream = env.socketTextStream("localhost", 9999);
```

### Basic Transformations

#### Map - One-to-One Transformation
```java
DataStream<String> words = env.fromElements("hello", "world", "flink");

// Transform to uppercase
DataStream<String> upperWords = words.map(word -> word.toUpperCase());

// Transform to length
DataStream<Integer> lengths = words.map(String::length);
```

#### FlatMap - One-to-Many Transformation
```java
DataStream<String> sentences = env.fromElements("hello world", "flink streaming");

// Split sentences into words
DataStream<String> words = sentences.flatMap(
    (String sentence, Collector<String> out) -> {
        for (String word : sentence.split(" ")) {
            out.collect(word);
        }
    }
).returns(Types.STRING);
```

#### Filter - Conditional Selection
```java
DataStream<Integer> numbers = env.fromElements(1, 2, 3, 4, 5, 6);

// Only even numbers
DataStream<Integer> evenNumbers = numbers.filter(n -> n % 2 == 0);

// Only numbers greater than 3
DataStream<Integer> largeNumbers = numbers.filter(n -> n > 3);
```

### KeyedStreams and Aggregations

#### KeyBy - Partitioning by Key
```java
public class WordCount {
    public String word;
    public int count;
    
    // constructors, getters, setters
}

DataStream<WordCount> wordCounts = // ... some source

// Key by word field
KeyedStream<WordCount, String> keyedWords = wordCounts.keyBy(wc -> wc.word);

// Key by multiple fields
KeyedStream<WordCount, Tuple2<String, String>> keyedByMultiple = 
    wordCounts.keyBy(wc -> Tuple2.of(wc.word, wc.category));
```

#### Aggregations on Keyed Streams
```java
// Sum aggregation
DataStream<WordCount> sums = keyedWords.sum("count");

// Reduce aggregation
DataStream<WordCount> reduced = keyedWords.reduce(
    (wc1, wc2) -> new WordCount(wc1.word, wc1.count + wc2.count)
);

// Custom aggregation
DataStream<WordCount> aggregated = keyedWords.aggregate(new AggregateFunction<WordCount, WordCount, WordCount>() {
    @Override
    public WordCount createAccumulator() {
        return new WordCount("", 0);
    }
    
    @Override
    public WordCount add(WordCount value, WordCount accumulator) {
        accumulator.count += value.count;
        return accumulator;
    }
    
    @Override
    public WordCount getResult(WordCount accumulator) {
        return accumulator;
    }
    
    @Override
    public WordCount merge(WordCount a, WordCount b) {
        return new WordCount(a.word, a.count + b.count);
    }
});
```

---

## 📖 PART 4: Event Time Processing and Watermarks

### Time Semantics

#### Processing Time
Time when events are processed by Flink operators.
```java
// Set processing time
env.setStreamTimeCharacteristic(TimeCharacteristic.ProcessingTime);

// Simple but can lead to non-deterministic results
```

#### Event Time
Time when events actually occurred (embedded in the data).
```java
// Set event time
env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);

// Extract timestamp from data
env.assignTimestampsAndWatermarks(
    WatermarkStrategy.<Event>forBoundedOutOfOrderness(Duration.ofSeconds(10))
        .withTimestampAssigner((event, timestamp) -> event.getTimestamp())
);
```

#### Ingestion Time
Time when events enter Flink system.
```java
env.setStreamTimeCharacteristic(TimeCharacteristic.IngestionTime);
```

### Watermarks Explained

**What are Watermarks?**
Watermarks are special markers that flow with the data stream, indicating progress in event time.

```
Events:     [t=1] [t=3] [t=2] [t=5] [t=4] [t=7]
Watermark:             W(t=0)      W(t=2)      W(t=4)

Meaning: "No more events with timestamp <= t will arrive"
```

**Watermark Generation Strategies:**

#### Periodic Watermarks
```java
// Generate watermarks every 200ms
WatermarkStrategy<Event> strategy = WatermarkStrategy
    .<Event>forBoundedOutOfOrderness(Duration.ofSeconds(5))
    .withWatermarkInterval(Duration.ofMillis(200))
    .withTimestampAssigner((event, timestamp) -> event.getEventTime());
```

#### Punctuated Watermarks
```java
// Generate watermark on specific events
WatermarkStrategy<Event> strategy = WatermarkStrategy
    .<Event>forGenerator((context) -> new PunctuatedWatermarkGenerator<Event>() {
        @Override
        public void onEvent(Event event, long eventTimestamp, WatermarkOutput output) {
            if (event.hasWatermarkMarker()) {
                output.emitWatermark(new Watermark(eventTimestamp));
            }
        }
    })
    .withTimestampAssigner((event, timestamp) -> event.getEventTime());
```

### Handling Late Events

#### Allowed Lateness
```java
DataStream<Event> lateStream = keyedStream
    .window(TumblingEventTimeWindows.of(Time.minutes(5)))
    .allowedLateness(Time.minutes(1)) // Allow 1 minute late events
    .sum("value");
```

#### Side Outputs for Very Late Events
```java
OutputTag<Event> lateEventsTag = new OutputTag<Event>("late-events") {};

SingleOutputStreamOperator<Result> result = keyedStream
    .window(TumblingEventTimeWindows.of(Time.minutes(5)))
    .allowedLateness(Time.minutes(1))
    .sideOutputLateData(lateEventsTag)
    .sum("value");

// Get the late events
DataStream<Event> lateEvents = result.getSideOutput(lateEventsTag);
```

---

## 📖 PART 5: Fault Tolerance and Checkpointing

### Checkpointing Mechanism

**How Checkpointing Works:**
1. JobManager initiates checkpoint
2. Source operators inject checkpoint barriers
3. Barriers flow downstream with regular data
4. When operator receives barrier from all inputs, it checkpoints its state
5. Checkpoint completes when all operators have checkpointed

```
Stream: [data] [data] [barrier-1] [data] [data] [barrier-2] [data]
State:   S0     S1     CHECKPOINT   S2     S3     CHECKPOINT   S4
```

### Checkpoint Configuration

#### Basic Setup
```java
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

// Enable checkpointing every 5 seconds
env.enableCheckpointing(5000);

// Set checkpointing mode
env.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);

// Timeout for checkpoints
env.getCheckpointConfig().setCheckpointTimeout(60000);

// Minimum pause between checkpoints
env.getCheckpointConfig().setMinPauseBetweenCheckpoints(1000);

// Maximum concurrent checkpoints
env.getCheckpointConfig().setMaxConcurrentCheckpoints(1);
```

#### Advanced Configuration
```java
CheckpointConfig checkpointConfig = env.getCheckpointConfig();

// Keep checkpoints when job is cancelled
checkpointConfig.enableExternalizedCheckpoints(
    CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);

// State backend configuration
env.setStateBackend(new RocksDBStateBackend("hdfs://checkpoints/"));

// Restart strategy
env.setRestartStrategy(RestartStrategies.fixedDelayRestart(3, Time.of(10, TimeUnit.SECONDS)));
```

### State Backends

#### Memory State Backend
```java
// Good for: Development, small state
env.setStateBackend(new MemoryStateBackend(10 * 1024 * 1024)); // 10 MB
```

#### FileSystem State Backend
```java
// Good for: Large state, checkpoints to distributed filesystem
env.setStateBackend(new FsStateBackend("hdfs://namenode:9000/flink/checkpoints"));
```

#### RocksDB State Backend
```java
// Good for: Very large state, incremental checkpoints
env.setStateBackend(new RocksDBStateBackend("hdfs://namenode:9000/flink/checkpoints", true));
```

---

## 🛠️ PRACTICAL HANDS-ON EXERCISES

### Exercise 1: Set Up Flink Cluster

**Step 1: Start the Infrastructure**
```bash
# Navigate to day3 directory
cd day3

# Start Flink cluster with dependencies
docker-compose up -d

# Check services are running
docker-compose ps
```

**You should see:**
- Flink JobManager (Web UI at http://localhost:8081)
- Flink TaskManagers
- Kafka for data sources
- Data generator service

**Step 2: Explore Flink Web UI**
- Open http://localhost:8081 in your browser
- Explore the dashboard: cluster overview, job manager, task managers
- Note: No jobs running yet!

**Step 3: Check Cluster Resources**
```bash
# Check available task slots
curl http://localhost:8081/taskmanagers

# Check cluster configuration  
curl http://localhost:8081/config
```

### Exercise 2: Your First Flink Job - Word Count

**Understanding the Job:**
We'll process text streams and count word occurrences in real-time.

**Step 1: Prepare Data**
```bash
# Create Kafka topics
docker exec -it kafka-flink kafka-topics --create \
  --topic text-input \
  --bootstrap-server localhost:9092 \
  --partitions 4 \
  --replication-factor 1

docker exec -it kafka-flink kafka-topics --create \
  --topic word-counts \
  --bootstrap-server localhost:9092 \
  --partitions 4 \
  --replication-factor 1
```

**Step 2: Submit Flink Job**
```bash
# Submit the word count job (if you have a JAR file)
# For this exercise, we'll use console sources/sinks

# Start Flink SQL client for interactive testing
docker exec -it flink-jobmanager ./bin/sql-client.sh
```

**In SQL Client:**
```sql
-- Create table for input
CREATE TABLE text_input (
    content STRING
) WITH (
    'connector' = 'kafka',
    'topic' = 'text-input',
    'properties.bootstrap.servers' = 'kafka:29092',
    'format' = 'raw'
);

-- Create table for output
CREATE TABLE word_counts (
    word STRING,
    count BIGINT
) WITH (
    'connector' = 'kafka',
    'topic' = 'word-counts',
    'properties.bootstrap.servers' = 'kafka:29092',
    'format' = 'json'
);

-- Run word count query
INSERT INTO word_counts
SELECT word, COUNT(*) as count
FROM (
    SELECT REGEXP_EXTRACT(content, '\\b\\w+\\b', 0) as word
    FROM text_input
    WHERE REGEXP_EXTRACT(content, '\\b\\w+\\b', 0) <> ''
)
GROUP BY word;
```

**Step 3: Send Test Data**
```bash
# In another terminal, send test data
docker exec -it kafka-flink kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic text-input

# Type these sentences:
hello world flink
apache flink streaming
hello streaming world
flink is awesome for real time processing
```

**Step 4: View Results**
```bash
# View word counts
docker exec -it kafka-flink kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic word-counts \
  --from-beginning
```

### Exercise 3: Event Time Processing with Watermarks

**Goal:** Handle out-of-order events correctly using event time.

**Step 1: Start Data Generator**
```bash
# Start the data generator that produces events with timestamps
docker-compose up data-generator
```

**The generator creates events like:**
```json
{
  "user_id": "user_123",
  "event_type": "page_view", 
  "event_time": "2024-01-15T10:30:15.123Z",
  "processing_time": "2024-01-15T10:30:20.456Z",
  "page_url": "/products/laptop"
}
```

**Step 2: Create Event Time Processing Job**

**Java Example (if building custom job):**
```java
public class EventTimeProcessing {
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        
        // Enable event time processing
        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
        
        // Kafka source
        Properties props = new Properties();
        props.setProperty("bootstrap.servers", "kafka:29092");
        
        DataStream<Event> events = env
            .addSource(new FlinkKafkaConsumer<>("user-events", new EventDeserializer(), props))
            .assignTimestampsAndWatermarks(
                WatermarkStrategy.<Event>forBoundedOutOfOrderness(Duration.ofSeconds(10))
                    .withTimestampAssigner((event, timestamp) -> event.getEventTime())
            );
        
        // Count events per user in 1-minute windows
        DataStream<WindowResult> result = events
            .keyBy(Event::getUserId)
            .window(TumblingEventTimeWindows.of(Time.minutes(1)))
            .aggregate(new CountAggregator());
        
        result.print();
        env.execute("Event Time Processing");
    }
}
```

**Step 3: Observe Watermark Behavior**
- Watch the Flink Web UI metrics
- Send events with different timestamps
- Observe how windows are triggered by watermarks

### Exercise 4: Stateful Processing with Checkpoints

**Goal:** Implement stateful processing that survives failures.

**Step 1: Configure Checkpointing**
```java
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

// Enable checkpointing every 30 seconds
env.enableCheckpointing(30000);
env.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
env.getCheckpointConfig().setMinPauseBetweenCheckpoints(5000);
```

**Step 2: Implement Stateful Function**
```java
public class StatefulCounter extends RichFlatMapFunction<Event, CountResult> {
    private ValueState<Long> countState;
    
    @Override
    public void open(Configuration parameters) {
        ValueStateDescriptor<Long> descriptor = 
            new ValueStateDescriptor<>("count", Long.class, 0L);
        countState = getRuntimeContext().getState(descriptor);
    }
    
    @Override
    public void flatMap(Event event, Collector<CountResult> out) throws Exception {
        Long currentCount = countState.value();
        currentCount++;
        countState.update(currentCount);
        
        out.collect(new CountResult(event.getUserId(), currentCount));
    }
}
```

**Step 3: Test Fault Tolerance**
```bash
# Start processing job
# Let it process some data
# Kill a TaskManager: docker kill flink-taskmanager-1
# Observe recovery in Web UI
# Restart TaskManager: docker start flink-taskmanager-1
```

### Exercise 5: Multiple Data Sources and Sinks

**Goal:** Connect Flink to various data sources and sinks.

**Step 1: Set Up External Systems**
```bash
# Make sure all services are running
docker-compose up -d mysql elasticsearch kibana

# Check Elasticsearch is ready
curl http://localhost:9200/_cluster/health
```

**Step 2: Create Multi-Source Job**
```java
public class MultiSourceProcessing {
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        
        // Source 1: Kafka
        DataStream<UserEvent> userEvents = env
            .addSource(new FlinkKafkaConsumer<>("user-events", new EventDeserializer(), kafkaProps));
        
        // Source 2: File (for reference data)
        DataStream<UserProfile> userProfiles = env
            .readTextFile("hdfs://path/to/profiles")
            .map(new ProfileParser());
        
        // Join streams
        DataStream<EnrichedEvent> enriched = userEvents
            .keyBy(UserEvent::getUserId)
            .connect(userProfiles.keyBy(UserProfile::getUserId))
            .flatMap(new EnrichmentFunction());
        
        // Sink 1: Back to Kafka
        enriched.addSink(new FlinkKafkaProducer<>("enriched-events", new EventSerializer(), kafkaProps));
        
        // Sink 2: Elasticsearch
        enriched.addSink(ElasticsearchSink.builder(hosts, new ElasticsearchSinkFunction<>()).build());
        
        // Sink 3: Console (for debugging)
        enriched.print();
        
        env.execute("Multi-Source Processing");
    }
}
```

**Step 3: Monitor Results**
- Check Kafka topics for enriched events
- View data in Elasticsearch/Kibana
- Monitor job performance in Flink Web UI

---

## 📊 UNDERSTANDING FLINK INTERNALS

### Task Execution

**Task Lifecycle:**
```
1. Job Submission → JobManager
2. Job Graph Creation → Logical plan
3. Execution Graph → Physical plan with parallelism
4. Task Deployment → TaskManagers receive tasks
5. Task Execution → Continuous processing
6. Checkpointing → State snapshots
7. Task Completion/Failure → Recovery or finish
```

**Task Slots and Parallelism:**
```
TaskManager with 4 slots:
Slot 1: [Source-1]     Slot 2: [Source-2]     Slot 3: [Map-1]       Slot 4: [Map-2]
        [Map-1]                [Map-2]                [KeyBy-1]            [KeyBy-2]
        [KeyBy-1]              [KeyBy-2]              [Sink-1]             [Sink-2]
        [Sink-1]               [Sink-2]
```

### Memory Management

**TaskManager Memory Structure:**
```
Total Process Memory (1GB)
├── JVM Heap (300MB)
│   ├── Flink Framework (50MB)
│   └── Task Heap (250MB)
├── Off-Heap Memory (600MB)
│   ├── Direct Memory (200MB)
│   ├── Native Memory (RocksDB: 300MB)
│   └── Network Buffers (100MB)
└── JVM Overhead (100MB)
```

**Configuration:**
```yaml
taskmanager.memory.process.size: 1g
taskmanager.memory.flink.size: 900m
taskmanager.memory.framework.heap.size: 50m
taskmanager.memory.task.heap.size: 250m
taskmanager.memory.managed.size: 300m
taskmanager.memory.network.fraction: 0.1
```

### Network and Data Exchange

**Data Shipping Strategies:**
```
Forward: 1-to-1 connection (same key, same partition)
Rebalance: Round-robin distribution  
Rescale: Subset-based distribution
Broadcast: Send to all downstream tasks
Shuffle: Random distribution
```

---

## 🧪 ADVANCED EXERCISES

### Exercise 6: Complex Event Processing (CEP)

**Goal:** Detect patterns in event streams.

```java
import org.apache.flink.cep.CEP;
import org.apache.flink.cep.PatternStream;
import org.apache.flink.cep.pattern.Pattern;

public class FraudDetection {
    public static void main(String[] args) throws Exception {
        // Define pattern: login followed by high-value transaction within 1 minute
        Pattern<LoginEvent, ?> pattern = Pattern.<LoginEvent>begin("login")
            .where(event -> event.getEventType().equals("LOGIN"))
            .next("transaction")
            .where(event -> event.getEventType().equals("TRANSACTION") && event.getAmount() > 10000)
            .within(Time.minutes(1));
        
        // Apply pattern to stream
        PatternStream<LoginEvent> patternStream = CEP.pattern(keyedStream, pattern);
        
        // Process matches
        DataStream<Alert> alerts = patternStream.select(
            (Map<String, List<LoginEvent>> pattern) -> {
                LoginEvent login = pattern.get("login").get(0);
                LoginEvent transaction = pattern.get("transaction").get(0);
                return new Alert(login.getUserId(), "Suspicious activity detected");
            }
        );
        
        alerts.print();
        env.execute("Fraud Detection");
    }
}
```

### Exercise 7: Custom State with TTL

**Goal:** Implement state that automatically expires.

```java
public class SessionProcessor extends KeyedProcessFunction<String, Event, SessionSummary> {
    private ValueState<SessionData> sessionState;
    
    @Override
    public void open(Configuration parameters) {
        // State with TTL
        StateTtlConfig ttlConfig = StateTtlConfig
            .newBuilder(Time.minutes(30))
            .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
            .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
            .cleanupFullSnapshot()
            .build();
            
        ValueStateDescriptor<SessionData> descriptor = 
            new ValueStateDescriptor<>("session", SessionData.class);
        descriptor.enableTimeToLive(ttlConfig);
        
        sessionState = getRuntimeContext().getState(descriptor);
    }
    
    @Override
    public void processElement(Event event, Context ctx, Collector<SessionSummary> out) throws Exception {
        SessionData session = sessionState.value();
        
        if (session == null) {
            // Start new session
            session = new SessionData(event.getUserId(), event.getTimestamp());
        }
        
        // Update session
        session.addEvent(event);
        sessionState.update(session);
        
        // Set cleanup timer
        ctx.timerService().registerEventTimeTimer(event.getTimestamp() + 30 * 60 * 1000);
    }
    
    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<SessionSummary> out) throws Exception {
        // Session expired, emit summary
        SessionData session = sessionState.value();
        if (session != null) {
            out.collect(new SessionSummary(session));
            sessionState.clear();
        }
    }
}
```

### Exercise 8: Custom Metrics and Monitoring

**Goal:** Add custom metrics to your Flink job.

```java
public class MetricReportingMap extends RichMapFunction<Event, ProcessedEvent> {
    private Counter eventsProcessed;
    private Histogram processingLatency;
    private Meter eventsPerSecond;
    
    @Override
    public void open(Configuration parameters) {
        this.eventsProcessed = getRuntimeContext()
            .getMetricGroup()
            .counter("events_processed");
            
        this.processingLatency = getRuntimeContext()
            .getMetricGroup()
            .histogram("processing_latency_ms", new DescriptiveStatisticsHistogram(10000));
            
        this.eventsPerSecond = getRuntimeContext()
            .getMetricGroup()
            .meter("events_per_second", new MeterView(eventsProcessed, 60));
    }
    
    @Override
    public ProcessedEvent map(Event event) throws Exception {
        long startTime = System.currentTimeMillis();
        
        // Process event
        ProcessedEvent result = processEvent(event);
        
        // Update metrics
        eventsProcessed.inc();
        processingLatency.update(System.currentTimeMillis() - startTime);
        
        return result;
    }
}
```

---

## 📈 PERFORMANCE OPTIMIZATION

### Parallelism Tuning

**Finding Optimal Parallelism:**
```java
// Set default parallelism
env.setParallelism(4);

// Set operator-specific parallelism
dataStream
    .map(new MyMapper()).setParallelism(8)  // CPU-intensive
    .keyBy(...)
    .window(...)
    .apply(new WindowFunction()).setParallelism(2); // Less parallelism needed
```

**Guidelines:**
- Start with: `cores_per_machine * number_of_machines`
- CPU-intensive operations: Higher parallelism
- I/O-intensive operations: Moderate parallelism
- Stateful operations: Consider state size distribution

### Memory Optimization

**RocksDB State Backend Tuning:**
```java
RocksDBStateBackend rocksDBStateBackend = new RocksDBStateBackend("hdfs://checkpoints/");

// Increase block cache for better read performance
rocksDBStateBackend.setPredefinedOptions(PredefinedOptions.SPINNING_DISK_OPTIMIZED);

// Custom RocksDB options
rocksDBStateBackend.setRocksDBOptions(new MyRocksDBOptionsFactory());
```

**Network Buffer Tuning:**
```yaml
# Increase network buffers for high-throughput jobs
taskmanager.network.memory.fraction: 0.2
taskmanager.network.memory.min: 256m
taskmanager.network.memory.max: 1g
```

### Checkpointing Optimization

**Tuning Checkpoint Performance:**
```java
// Incremental checkpoints for RocksDB
env.setStateBackend(new RocksDBStateBackend("hdfs://checkpoints/", true));

// Checkpoint configuration
env.getCheckpointConfig().setCheckpointInterval(30000); // 30 seconds
env.getCheckpointConfig().setMinPauseBetweenCheckpoints(5000); // 5 seconds pause
env.getCheckpointConfig().setCheckpointTimeout(600000); // 10 minutes timeout
```

---

## 📝 PRACTICAL INTERVIEW QUESTIONS

### Beginner Level (0-2 years)

**Q1: What is Apache Flink and how does it differ from batch processing?**
**A:** Apache Flink is a distributed stream processing framework that processes unbounded data streams in real-time. Unlike batch processing which processes finite datasets, Flink continuously processes data as it arrives, providing low-latency results and stateful computations.

**Q2: What are the main components of a Flink cluster?**
**A:** 
- **JobManager**: Coordinates execution, manages checkpoints and recovery
- **TaskManager**: Executes tasks, manages memory and data exchange
- **Task Slots**: Units of parallelism within TaskManagers

**Q3: Explain the difference between event time and processing time.**
**A:**
- **Event Time**: When the event actually occurred (embedded in data)
- **Processing Time**: When Flink processes the event
- Event time enables deterministic results despite processing delays

**Q4: What are watermarks in Flink?**
**A:** Watermarks are timestamps that flow with the stream indicating progress in event time. They tell Flink "no events with timestamp ≤ watermark will arrive," enabling window triggering and handling of late events.

### Intermediate Level (2-5 years)

**Q5: How does Flink achieve fault tolerance?**
**A:** Flink uses distributed checkpointing based on the Chandy-Lamport algorithm:
- JobManager initiates checkpoints periodically
- Checkpoint barriers flow through the dataflow
- Operators checkpoint their state when receiving barriers
- On failure, all operators restore from the last completed checkpoint

**Q6: What are the different state backends in Flink and when would you use each?**
**A:**
- **MemoryStateBackend**: Development/testing, small state
- **FsStateBackend**: Production, medium state, stored in distributed filesystem
- **RocksDBStateBackend**: Very large state, incremental checkpoints, stored locally with snapshots in filesystem

**Q7: How would you handle late-arriving events?**
**A:**
- Configure **allowed lateness** for windows
- Use **side outputs** for very late events
- Implement custom **trigger** logic
- Choose appropriate **watermark strategy** based on data characteristics

**Q8: Explain keyed state vs operator state.**
**A:**
- **Keyed State**: Associated with specific key, automatically partitioned
- **Operator State**: Associated with parallel operator instance, manually managed during redistribution

### Advanced Level (5+ years)

**Q9: Design a exactly-once processing pipeline with Flink.**
**A:** 
- Enable exactly-once checkpointing mode
- Use idempotent sinks or transactional sinks (Kafka, database)
- Configure appropriate checkpoint intervals
- Handle failure scenarios with proper restart strategies
- Ensure all sources support resettable positions

**Q10: How would you optimize a Flink job processing 1 million events per second?**
**A:**
- **Parallelism**: Set appropriate parallelism based on CPU cores and data distribution
- **Memory**: Tune TaskManager memory, use RocksDB for large state
- **Network**: Increase network buffers for high throughput
- **Checkpointing**: Use incremental checkpoints, tune checkpoint intervals
- **Serialization**: Use efficient serializers (Avro, Kryo)
- **State**: Minimize state size, use appropriate state TTL

**Q11: Explain how you would implement session windowing for user behavior analysis.**
**A:**
```java
// Session windows with custom gap extractor
dataStream
    .keyBy(Event::getUserId)
    .window(ProcessingTimeSessionWindows.withDynamicGap(
        (event) -> event.getUserType().equals("premium") ? 30 * 60 * 1000 : 15 * 60 * 1000
    ))
    .aggregate(new SessionAggregator());
```

**Q12: How would you handle schema evolution in a Flink streaming job?**
**A:**
- Use schema registry for centralized schema management
- Implement backward-compatible schema changes
- Use Avro or Protobuf for schema evolution support
- Handle deserialization errors gracefully
- Plan migration strategies for breaking changes

---

## ✅ DAY 3 COMPLETION CHECKLIST

Before moving to Day 4, ensure you can:

**Flink Architecture:**
- [ ] Explain JobManager and TaskManager roles
- [ ] Understand task slots and parallelism
- [ ] Describe job execution lifecycle
- [ ] Configure cluster resources

**DataStream API:**
- [ ] Create DataStreams from various sources
- [ ] Apply transformations (map, filter, flatMap)
- [ ] Work with KeyedStreams and aggregations
- [ ] Use different types of joins

**Event Time Processing:**
- [ ] Configure event time processing
- [ ] Implement watermark strategies
- [ ] Handle late events with allowed lateness
- [ ] Use side outputs for very late data

**Fault Tolerance:**
- [ ] Configure checkpointing
- [ ] Choose appropriate state backends
- [ ] Handle job failures and recovery
- [ ] Implement stateful processing

**Practical Skills:**
- [ ] Deploy jobs to Flink cluster
- [ ] Monitor jobs using Web UI
- [ ] Debug performance issues
- [ ] Configure external sources and sinks

---

## 🚀 WHAT'S NEXT?

Tomorrow (Day 4), you'll explore advanced Flink features:
- Advanced state management patterns
- Custom operators and functions
- Savepoints and job evolution
- Performance tuning techniques
- Production deployment strategies

**Preparation:**
- Ensure all Day 3 exercises work correctly
- Practice with different window types
- Experiment with checkpointing configuration
- Review state management concepts

## 📚 Additional Resources

**Official Documentation:**
- [Flink Documentation](https://flink.apache.org/docs/)
- [DataStream API](https://flink.apache.org/docs/dev/datastream_api.html)

**Books:**
- "Stream Processing with Apache Flink" by Fabian Hueske and Vasiliki Kalavri
- "Learning Apache Flink" by Tanmay Deshpande

**Community:**
- [Apache Flink User Mailing List](https://flink.apache.org/community.html#mailing-lists)
- [Flink Forward Conference](https://flink-forward.org/)

---

Outstanding work completing Day 3! You now have a solid foundation in Apache Flink stream processing. Tomorrow we'll dive into advanced patterns and production deployment strategies.