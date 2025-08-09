# Day 5: Flink-Kafka Integration and Advanced Windowing - Complete Learning Guide

## 📚 Learning Overview
Today you'll master the integration between Apache Flink and Kafka, building sophisticated streaming applications with advanced windowing patterns. You'll learn to handle late data, implement exactly-once processing, and optimize for production workloads.

## 🎯 Learning Objectives
By the end of today, you will be able to:
- Configure Flink-Kafka connectors with best practices
- Implement advanced windowing patterns for complex analytics
- Handle late-arriving data with watermarks and side outputs
- Build exactly-once processing pipelines with transactional semantics
- Optimize performance for high-throughput scenarios
- Monitor and troubleshoot Kafka-Flink integrations

---

## 📖 PART 1: Flink-Kafka Integration Deep Dive

### Modern Kafka Connectors (Flink 1.14+)

#### New KafkaSource API
The new KafkaSource provides a unified, flexible interface for consuming from Kafka.

```java
// Basic KafkaSource setup
KafkaSource<String> source = KafkaSource.<String>builder()
    .setBootstrapServers("kafka-1:29092,kafka-2:29093,kafka-3:29094")
    .setTopics("user-events")
    .setGroupId("flink-consumer-group")
    .setStartingOffsets(OffsetsInitializer.latest())
    .setValueOnlyDeserializer(new SimpleStringSchema())
    .setProperty("partition.discovery.interval.millis", "10000") // Discover new partitions
    .build();

StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
DataStreamSource<String> kafkaStream = env.fromSource(
    source, 
    WatermarkStrategy.noWatermarks(), 
    "Kafka Source"
);
```

#### Advanced Configuration
```java
// Production-ready Kafka source
KafkaSource<Event> eventSource = KafkaSource.<Event>builder()
    .setBootstrapServers("kafka-1:29092,kafka-2:29093,kafka-3:29094")
    .setTopics(Arrays.asList("user-events", "system-events"))
    .setGroupId("event-processor")
    .setStartingOffsets(OffsetsInitializer.commitOffsets()) // Start from committed offsets
    .setDeserializer(KafkaRecordDeserializationSchema.valueOnly(new EventDeserializer()))
    
    // Consumer configuration
    .setProperty("fetch.min.bytes", "1048576") // 1MB minimum fetch
    .setProperty("fetch.max.wait.ms", "500")   // Max wait time
    .setProperty("max.poll.records", "1000")   // Records per poll
    .setProperty("enable.auto.commit", "false") // Flink manages offsets
    .setProperty("isolation.level", "read_committed") // For exactly-once
    
    // Partition discovery
    .setProperty("partition.discovery.interval.millis", "30000") // 30s discovery interval
    .setProperty("flink.partition-discovery.interval-millis", "30000")
    
    // Error handling
    .setProperty("max.poll.interval.ms", "300000") // 5 minutes max processing time
    .build();
```

### Event Time and Watermarks

#### Watermark Strategy Configuration
```java
// Bounded out-of-order watermark strategy
WatermarkStrategy<Event> watermarkStrategy = WatermarkStrategy
    .<Event>forBoundedOutOfOrderness(Duration.ofMinutes(2))
    .withTimestampAssigner((event, timestamp) -> event.getEventTime())
    .withIdleness(Duration.ofMinutes(1)); // Handle idle partitions

DataStreamSource<Event> eventStream = env.fromSource(
    eventSource, 
    watermarkStrategy, 
    "Event Source"
);
```

#### Custom Watermark Generator
```java
public class CustomWatermarkGenerator implements WatermarkGenerator<Event> {
    private final long maxOutOfOrderness = 5000; // 5 seconds
    private long currentMaxTimestamp;
    
    @Override
    public void onEvent(Event event, long eventTimestamp, WatermarkOutput output) {
        currentMaxTimestamp = Math.max(currentMaxTimestamp, eventTimestamp);
    }
    
    @Override
    public void onPeriodicEmit(WatermarkOutput output) {
        // Emit watermark based on business logic
        if (shouldEmitWatermark()) {
            output.emitWatermark(new Watermark(currentMaxTimestamp - maxOutOfOrderness));
        }
    }
    
    private boolean shouldEmitWatermark() {
        // Custom logic: emit watermark based on data volume or time
        return System.currentTimeMillis() % 1000 == 0; // Every second
    }
}

// Use custom watermark generator
WatermarkStrategy<Event> customStrategy = WatermarkStrategy
    .forGenerator(context -> new CustomWatermarkGenerator())
    .withTimestampAssigner((event, timestamp) -> event.getEventTime());
```

### Schema Evolution with Schema Registry

#### Avro Schema Integration
```java
// Schema Registry configuration
Map<String, String> properties = new HashMap<>();
properties.put("schema.registry.url", "http://schema-registry:8081");
properties.put("specific.avro.reader", "true");

// Confluent Schema Registry deserializer
ConfluentRegistryAvroDeserializationSchema<UserEvent> deserializer = 
    ConfluentRegistryAvroDeserializationSchema.forSpecific(
        UserEvent.class, 
        "user-events-value", 
        properties
    );

KafkaSource<UserEvent> avroSource = KafkaSource.<UserEvent>builder()
    .setBootstrapServers("kafka-1:29092")
    .setTopics("user-events")
    .setValueOnlyDeserializer(deserializer)
    .build();
```

#### Handling Schema Evolution
```java
public class EvolvableEventDeserializer implements DeserializationSchema<Event> {
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Override
    public Event deserialize(byte[] message) throws IOException {
        try {
            // Try to deserialize with latest schema (v3)
            return objectMapper.readValue(message, EventV3.class);
        } catch (JsonProcessingException e) {
            try {
                // Fallback to previous schema (v2)
                EventV2 eventV2 = objectMapper.readValue(message, EventV2.class);
                return convertV2ToV3(eventV2);
            } catch (JsonProcessingException e2) {
                // Fallback to original schema (v1)
                EventV1 eventV1 = objectMapper.readValue(message, EventV1.class);
                return convertV1ToV3(eventV1);
            }
        }
    }
    
    @Override
    public boolean isEndOfStream(Event nextElement) {
        return false;
    }
    
    @Override
    public TypeInformation<Event> getProducedType() {
        return TypeInformation.of(Event.class);
    }
}
```

---

## 📖 PART 2: Advanced Windowing Patterns

### Custom Window Assigners

#### Business Logic Windows
```java
public class QuarterlyReportWindow extends WindowAssigner<Event, QuarterWindow> {
    
    @Override
    public Collection<QuarterWindow> assignWindows(
            Event element, 
            long timestamp, 
            WindowAssignerContext context) {
        
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(timestamp);
        
        int year = cal.get(Calendar.YEAR);
        int quarter = (cal.get(Calendar.MONTH) / 3) + 1;
        
        long start = getQuarterStart(year, quarter);
        long end = getQuarterEnd(year, quarter);
        
        return Collections.singletonList(new QuarterWindow(start, end, year, quarter));
    }
    
    @Override
    public Trigger<Event, QuarterWindow> getDefaultTrigger(StreamExecutionEnvironment env) {
        return EventTimeTrigger.create();
    }
    
    @Override
    public TypeSerializer<QuarterWindow> getWindowSerializer(ExecutionConfig executionConfig) {
        return new QuarterWindow.QuarterWindowSerializer();
    }
    
    @Override
    public boolean isEventTime() {
        return true;
    }
}

// Usage
DataStream<QuarterlyReport> quarterlyReports = eventStream
    .keyBy(Event::getRegion)
    .window(new QuarterlyReportWindow())
    .aggregate(new QuarterlyAggregator());
```

### Advanced Window Triggers

#### Count-Based Trigger with Time Limit
```java
public class CountOrTimeoutTrigger<W extends Window> extends Trigger<Event, W> {
    private final int maxCount;
    private final long timeoutMs;
    private final ValueStateDescriptor<Integer> countDescriptor;
    
    public CountOrTimeoutTrigger(int maxCount, long timeoutMs) {
        this.maxCount = maxCount;
        this.timeoutMs = timeoutMs;
        this.countDescriptor = new ValueStateDescriptor<>("count", Integer.class, 0);
    }
    
    @Override
    public TriggerResult onElement(
            Event element, 
            long timestamp, 
            W window, 
            TriggerContext ctx) throws Exception {
        
        ValueState<Integer> countState = ctx.getPartitionedState(countDescriptor);
        Integer count = countState.value();
        count++;
        countState.update(count);
        
        // Set timeout timer on first element
        if (count == 1) {
            long timeoutTime = ctx.getCurrentProcessingTime() + timeoutMs;
            ctx.registerProcessingTimeTimer(timeoutTime);
        }
        
        // Fire if count reaches threshold
        if (count >= maxCount) {
            return TriggerResult.FIRE_AND_PURGE;
        }
        
        return TriggerResult.CONTINUE;
    }
    
    @Override
    public TriggerResult onProcessingTime(long time, W window, TriggerContext ctx) {
        // Fire on timeout
        return TriggerResult.FIRE_AND_PURGE;
    }
    
    @Override
    public void clear(W window, TriggerContext ctx) throws Exception {
        ValueState<Integer> countState = ctx.getPartitionedState(countDescriptor);
        countState.clear();
    }
}

// Usage - trigger window every 100 events or 5 minutes
DataStream<AggregatedResult> result = eventStream
    .keyBy(Event::getUserId)
    .window(GlobalWindows.create())
    .trigger(new CountOrTimeoutTrigger<>(100, Duration.ofMinutes(5).toMillis()))
    .aggregate(new EventAggregator());
```

### Session Windows with Complex Logic

#### Dynamic Session Gaps
```java
public class SmartSessionWindows {
    
    public static SessionWindowTimeGapExtractor<Event> dynamicGap() {
        return new SessionWindowTimeGapExtractor<Event>() {
            @Override
            public long extract(Event element) {
                // Different session timeouts based on user behavior
                switch (element.getEventType()) {
                    case "login":
                        return Duration.ofMinutes(30).toMillis(); // Extend session on login
                    case "purchase":
                        return Duration.ofMinutes(60).toMillis(); // Long session after purchase
                    case "page_view":
                        return Duration.ofMinutes(10).toMillis(); // Standard timeout
                    case "idle":
                        return Duration.ofMinutes(2).toMillis();  // Quick timeout on idle
                    default:
                        return Duration.ofMinutes(15).toMillis(); // Default timeout
                }
            }
        };
    }
}

// Session processing with rich state
public class SessionProcessor extends ProcessWindowFunction<Event, SessionSummary, String, TimeWindow> {
    
    @Override
    public void process(
            String key,
            Context context,
            Iterable<Event> elements,
            Collector<SessionSummary> out) {
        
        SessionSummary session = new SessionSummary();
        session.setUserId(key);
        session.setSessionStart(context.window().getStart());
        session.setSessionEnd(context.window().getEnd());
        
        List<Event> events = new ArrayList<>();
        int pageViews = 0;
        int purchases = 0;
        double totalSpent = 0.0;
        
        for (Event event : elements) {
            events.add(event);
            
            switch (event.getEventType()) {
                case "page_view":
                    pageViews++;
                    break;
                case "purchase":
                    purchases++;
                    totalSpent += event.getAmount();
                    break;
            }
        }
        
        session.setEventCount(events.size());
        session.setPageViews(pageViews);
        session.setPurchases(purchases);
        session.setTotalSpent(totalSpent);
        session.setDuration(context.window().getEnd() - context.window().getStart());
        session.setEvents(events);
        
        out.collect(session);
    }
}

// Complete session analytics pipeline
DataStream<SessionSummary> sessionAnalytics = eventStream
    .keyBy(Event::getUserId)
    .window(EventTimeSessionWindows.withDynamicGap(SmartSessionWindows.dynamicGap()))
    .allowedLateness(Duration.ofMinutes(5)) // Allow late events
    .sideOutputLateData(lateEventsTag) // Capture very late events
    .process(new SessionProcessor());
```

### Window Joins

#### Stream-Stream Windowed Joins
```java
// Join user events with purchase events within 10-minute windows
DataStream<UserEvent> userEvents = // ... user event stream
DataStream<PurchaseEvent> purchaseEvents = // ... purchase event stream

DataStream<EnrichedPurchase> enrichedPurchases = userEvents
    .keyBy(UserEvent::getUserId)
    .intervalJoin(purchaseEvents.keyBy(PurchaseEvent::getUserId))
    .between(Time.minutes(-5), Time.minutes(5)) // Join events within 10-minute window
    .upperBoundExclusive() // Exclude upper bound
    .process(new ProcessJoinFunction<UserEvent, PurchaseEvent, EnrichedPurchase>() {
        @Override
        public void processElement(
                UserEvent left,
                PurchaseEvent right,
                Context ctx,
                Collector<EnrichedPurchase> out) {
            
            EnrichedPurchase enriched = new EnrichedPurchase();
            enriched.setPurchaseId(right.getPurchaseId());
            enriched.setUserId(right.getUserId());
            enriched.setAmount(right.getAmount());
            enriched.setUserBehavior(left.getBehaviorSummary());
            enriched.setTimestamp(right.getTimestamp());
            
            out.collect(enriched);
        }
    });
```

---

## 📖 PART 3: Exactly-Once Processing

### Transactional Kafka Sink

#### Modern KafkaSink with Transactions
```java
// Configure exactly-once Kafka sink
KafkaSink<ProcessedEvent> kafkaSink = KafkaSink.<ProcessedEvent>builder()
    .setBootstrapServers("kafka-1:29092,kafka-2:29093,kafka-3:29094")
    .setRecordSerializer(KafkaRecordSerializationSchema.builder()
        .setTopic("processed-events")
        .setValueSerializationSchema(new ProcessedEventSerializer())
        .setKeySerializationSchema(new SimpleStringSchema())
        .build())
    
    // Exactly-once configuration
    .setDeliveryGuarantee(DeliveryGuarantee.EXACTLY_ONCE)
    .setTransactionalIdPrefix("flink-app-" + UUID.randomUUID().toString())
    .setProperty(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, "300000") // 5 minutes
    
    // Performance optimization
    .setProperty(ProducerConfig.BATCH_SIZE_CONFIG, "65536") // 64KB batches
    .setProperty(ProducerConfig.LINGER_MS_CONFIG, "5")      // 5ms linger
    .setProperty(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy")
    .setProperty(ProducerConfig.BUFFER_MEMORY_CONFIG, "67108864") // 64MB buffer
    
    // Reliability
    .setProperty(ProducerConfig.ACKS_CONFIG, "all")
    .setProperty(ProducerConfig.RETRIES_CONFIG, "10")
    .setProperty(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
    .build();

// Enable checkpointing for exactly-once
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
env.enableCheckpointing(30000); // 30 second checkpoints
env.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
env.getCheckpointConfig().setMinPauseBetweenCheckpoints(5000);
env.getCheckpointConfig().setCheckpointTimeout(300000); // 5 minutes timeout

// Processing pipeline
processedStream.sinkTo(kafkaSink);
```

### End-to-End Exactly-Once Example
```java
public class ExactlyOnceProcessor {
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        
        // Configure exactly-once
        env.enableCheckpointing(30000, CheckpointingMode.EXACTLY_ONCE);
        env.getCheckpointConfig().setMinPauseBetweenCheckpoints(5000);
        
        // Kafka source with read_committed isolation
        KafkaSource<Event> source = KafkaSource.<Event>builder()
            .setBootstrapServers("kafka:9092")
            .setTopics("input-events")
            .setGroupId("exactly-once-processor")
            .setValueOnlyDeserializer(new EventDeserializer())
            .setProperty("isolation.level", "read_committed") // Key for exactly-once
            .build();
        
        // Processing logic with state
        DataStream<ProcessedEvent> processed = env
            .fromSource(source, WatermarkStrategy.noWatermarks(), "Kafka Source")
            .keyBy(Event::getUserId)
            .map(new StatefulProcessor())
            .name("Stateful Processing");
        
        // Exactly-once sink
        KafkaSink<ProcessedEvent> sink = KafkaSink.<ProcessedEvent>builder()
            .setBootstrapServers("kafka:9092")
            .setRecordSerializer(/* serializer config */)
            .setDeliveryGuarantee(DeliveryGuarantee.EXACTLY_ONCE)
            .setTransactionalIdPrefix("processor")
            .build();
        
        processed.sinkTo(sink);
        
        env.execute("Exactly-Once Processing Pipeline");
    }
}

public class StatefulProcessor extends RichMapFunction<Event, ProcessedEvent> {
    private ValueState<Long> countState;
    private ValueState<Double> sumState;
    
    @Override
    public void open(Configuration parameters) {
        ValueStateDescriptor<Long> countDescriptor = 
            new ValueStateDescriptor<>("count", Long.class, 0L);
        countState = getRuntimeContext().getState(countDescriptor);
        
        ValueStateDescriptor<Double> sumDescriptor = 
            new ValueStateDescriptor<>("sum", Double.class, 0.0);
        sumState = getRuntimeContext().getState(sumDescriptor);
    }
    
    @Override
    public ProcessedEvent map(Event event) throws Exception {
        Long count = countState.value();
        Double sum = sumState.value();
        
        count++;
        sum += event.getValue();
        
        countState.update(count);
        sumState.update(sum);
        
        return new ProcessedEvent(
            event.getUserId(),
            event.getTimestamp(),
            count,
            sum / count // running average
        );
    }
}
```

---

## 📖 PART 4: Handling Late Data and Side Outputs

### Late Data Processing Strategies

#### Comprehensive Late Data Handling
```java
public class LateDataProcessor {
    
    // Define output tags for different lateness categories
    private static final OutputTag<Event> LATE_DATA_TAG = 
        new OutputTag<Event>("late-data") {};
    private static final OutputTag<Event> VERY_LATE_DATA_TAG = 
        new OutputTag<Event>("very-late-data") {};
    
    public static DataStream<WindowedResult> processWithLateData(
            DataStream<Event> eventStream) {
        
        WatermarkStrategy<Event> watermarkStrategy = WatermarkStrategy
            .<Event>forBoundedOutOfOrderness(Duration.ofMinutes(2))
            .withTimestampAssigner((event, timestamp) -> event.getEventTime())
            .withIdleness(Duration.ofMinutes(1));
        
        DataStream<Event> watermarkedStream = eventStream.assignTimestampsAndWatermarks(watermarkStrategy);
        
        // Main processing with allowed lateness
        SingleOutputStreamOperator<WindowedResult> mainResult = watermarkedStream
            .keyBy(Event::getCategory)
            .window(TumblingEventTimeWindows.of(Duration.ofMinutes(5)))
            .allowedLateness(Duration.ofMinutes(10)) // Allow 10 minutes late
            .sideOutputLateData(VERY_LATE_DATA_TAG) // Very late events
            .process(new WindowProcessor());
        
        // Process late data separately
        DataStream<Event> lateData = mainResult.getSideOutput(VERY_LATE_DATA_TAG);
        DataStream<LateDataSummary> lateDataSummary = lateData
            .keyBy(Event::getCategory)
            .window(TumblingProcessingTimeWindows.of(Duration.ofHours(1)))
            .aggregate(new LateDataAggregator());
        
        // Send late data alerts
        lateDataSummary
            .filter(summary -> summary.getCount() > 100)
            .addSink(new AlertSink("High late data volume"));
        
        return mainResult;
    }
}

public class WindowProcessor extends ProcessWindowFunction<Event, WindowedResult, String, TimeWindow> {
    private static final Logger LOG = LoggerFactory.getLogger(WindowProcessor.class);
    
    @Override
    public void process(
            String key,
            Context context,
            Iterable<Event> elements,
            Collector<WindowedResult> out) {
        
        long windowStart = context.window().getStart();
        long windowEnd = context.window().getEnd();
        long currentWatermark = context.currentWatermark();
        
        List<Event> events = new ArrayList<>();
        elements.forEach(events::add);
        
        WindowedResult result = new WindowedResult();
        result.setCategory(key);
        result.setWindowStart(windowStart);
        result.setWindowEnd(windowEnd);
        result.setEventCount(events.size());
        result.setWatermarkAtProcessing(currentWatermark);
        result.setProcessingTime(System.currentTimeMillis());
        
        // Calculate statistics
        OptionalDouble avgValue = events.stream()
            .mapToDouble(Event::getValue)
            .average();
        result.setAverageValue(avgValue.orElse(0.0));
        
        // Detect if this is a late update
        boolean isLateUpdate = currentWatermark > windowEnd;
        result.setLateUpdate(isLateUpdate);
        
        if (isLateUpdate) {
            LOG.info("Processing late update for window [{}, {}], watermark: {}", 
                windowStart, windowEnd, currentWatermark);
        }
        
        out.collect(result);
    }
}
```

### Side Output Patterns

#### Multiple Side Outputs for Different Data Types
```java
public class MultiOutputProcessor extends ProcessFunction<Event, ProcessedEvent> {
    
    // Define multiple output tags
    public static final OutputTag<ErrorEvent> ERROR_TAG = 
        new OutputTag<ErrorEvent>("errors") {};
    public static final OutputTag<AuditEvent> AUDIT_TAG = 
        new OutputTag<AuditEvent>("audit") {};
    public static final OutputTag<MetricEvent> METRIC_TAG = 
        new OutputTag<MetricEvent>("metrics") {};
    
    @Override
    public void processElement(
            Event event,
            Context ctx,
            Collector<ProcessedEvent> out) throws Exception {
        
        try {
            // Main processing logic
            ProcessedEvent processed = processEvent(event);
            out.collect(processed);
            
            // Generate audit trail
            AuditEvent audit = new AuditEvent(
                event.getId(),
                event.getUserId(),
                "PROCESSED",
                System.currentTimeMillis()
            );
            ctx.output(AUDIT_TAG, audit);
            
            // Generate metrics
            MetricEvent metric = new MetricEvent(
                "event_processed",
                event.getCategory(),
                1.0,
                System.currentTimeMillis()
            );
            ctx.output(METRIC_TAG, metric);
            
        } catch (Exception e) {
            // Send to error output
            ErrorEvent error = new ErrorEvent(
                event.getId(),
                e.getClass().getSimpleName(),
                e.getMessage(),
                System.currentTimeMillis()
            );
            ctx.output(ERROR_TAG, error);
            
            // Still process the main event with default values
            ProcessedEvent defaultProcessed = createDefaultProcessedEvent(event);
            out.collect(defaultProcessed);
        }
    }
}

// Usage with multiple sinks
SingleOutputStreamOperator<ProcessedEvent> mainStream = eventStream
    .process(new MultiOutputProcessor());

// Route different outputs to appropriate sinks
mainStream.getSideOutput(MultiOutputProcessor.ERROR_TAG)
    .addSink(new ElasticsearchSink<>("error-index"));

mainStream.getSideOutput(MultiOutputProcessor.AUDIT_TAG)
    .addSink(new KafkaSink<>("audit-topic"));

mainStream.getSideOutput(MultiOutputProcessor.METRIC_TAG)
    .addSink(new InfluxDBSink("metrics-db"));
```

---

## 🛠️ PRACTICAL HANDS-ON EXERCISES

### Exercise 1: Real-time User Session Analytics

**Goal:** Build a complete session analytics pipeline with late data handling.

**Step 1: Start Infrastructure**
```bash
# Navigate to day5 directory
cd day5

# Start comprehensive setup
docker-compose up -d kafka-1 kafka-2 kafka-3 schema-registry flink-jobmanager flink-taskmanager redis elasticsearch
```

**Step 2: Create Required Topics**
```bash
# Create input topic
docker exec -it kafka-1 kafka-topics --create \
  --topic user-events \
  --bootstrap-server localhost:9092 \
  --partitions 6 \
  --replication-factor 2 \
  --config retention.ms=86400000

# Create session analytics output
docker exec -it kafka-1 kafka-topics --create \
  --topic session-analytics \
  --bootstrap-server localhost:9092 \
  --partitions 3 \
  --replication-factor 2

# Create late data topic
docker exec -it kafka-1 kafka-topics --create \
  --topic late-user-events \
  --bootstrap-server localhost:9092 \
  --partitions 2 \
  --replication-factor 2
```

**Step 3: Deploy Session Analytics Job**
```bash
# Compile and submit the session analytics job
docker exec -it flink-jobmanager flink run \
  -c com.streaming.SessionAnalyticsJob \
  /opt/flink/jobs/session-analytics.jar \
  --kafka.bootstrap.servers kafka-1:29092,kafka-2:29093,kafka-3:29094 \
  --input.topic user-events \
  --output.topic session-analytics \
  --late.data.topic late-user-events
```

**Step 4: Generate Test Data**
```bash
# Start user behavior generator
docker-compose up user-behavior-generator

# Generate some late events
docker-compose up late-event-generator
```

**Step 5: Monitor Results**
```bash
# Monitor session analytics
docker exec -it kafka-1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic session-analytics \
  --property print.key=true \
  --property key.separator=" => " \
  --from-beginning

# Monitor late data handling
docker exec -it kafka-1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic late-user-events \
  --property print.timestamp=true \
  --from-beginning
```

### Exercise 2: Multi-Stream Correlation with Window Joins

**Goal:** Correlate user actions with purchase outcomes using windowed joins.

**Step 1: Prepare Data Streams**
```bash
# Create additional topics
docker exec -it kafka-1 kafka-topics --create \
  --topic user-actions \
  --bootstrap-server localhost:9092 \
  --partitions 4 \
  --replication-factor 2

docker exec -it kafka-1 kafka-topics --create \
  --topic purchases \
  --bootstrap-server localhost:9092 \
  --partitions 4 \
  --replication-factor 2

docker exec -it kafka-1 kafka-topics --create \
  --topic purchase-correlations \
  --bootstrap-server localhost:9092 \
  --partitions 2 \
  --replication-factor 2
```

**Step 2: Deploy Correlation Job**
```bash
docker exec -it flink-jobmanager flink run \
  -c com.streaming.PurchaseCorrelationJob \
  /opt/flink/jobs/purchase-correlation.jar
```

**Step 3: Test Correlation Logic**
```bash
# Generate correlated events
docker-compose up correlation-generator

# View correlation results
docker exec -it kafka-1 kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic purchase-correlations \
  --property print.key=true
```

### Exercise 3: Exactly-Once End-to-End Pipeline

**Goal:** Implement exactly-once processing with state and transactional sinks.

**Step 1: Configure Kafka for Exactly-Once**
```bash
# Update Kafka configuration for transactions
docker exec -it kafka-1 kafka-configs --alter \
  --bootstrap-server localhost:9092 \
  --entity-type brokers \
  --entity-name 1 \
  --add-config transaction.state.log.replication.factor=2,transaction.state.log.min.isr=2
```

**Step 2: Deploy Exactly-Once Job**
```bash
docker exec -it flink-jobmanager flink run \
  -c com.streaming.ExactlyOnceProcessor \
  /opt/flink/jobs/exactly-once-processor.jar \
  --checkpointing.interval 30000 \
  --kafka.transaction.timeout 300000
```

**Step 3: Test Fault Tolerance**
```bash
# Generate continuous load
docker-compose up load-generator

# Simulate failures
docker restart flink-taskmanager-1

# Verify no data loss or duplication
docker exec -it kafka-1 kafka-run-class kafka.tools.ConsumerOffsetChecker \
  --zookeeper zookeeper:2181 \
  --group exactly-once-processor
```

---

## 📈 PERFORMANCE OPTIMIZATION

### Source and Sink Tuning

#### Kafka Source Optimization
```java
KafkaSource<Event> optimizedSource = KafkaSource.<Event>builder()
    .setBootstrapServers("kafka:9092")
    .setTopics("high-throughput-topic")
    
    // Throughput optimization
    .setProperty("fetch.min.bytes", "1048576")    // 1MB minimum fetch
    .setProperty("fetch.max.wait.ms", "500")      // Max wait time
    .setProperty("max.poll.records", "2000")      // More records per poll
    .setProperty("receive.buffer.bytes", "131072") // 128KB receive buffer
    .setProperty("send.buffer.bytes", "131072")   // 128KB send buffer
    
    // Consumer group settings
    .setProperty("session.timeout.ms", "30000")   // 30s session timeout
    .setProperty("heartbeat.interval.ms", "10000") // 10s heartbeat
    .setProperty("max.poll.interval.ms", "600000") // 10min max poll interval
    
    // Partition assignment
    .setProperty("partition.assignment.strategy", 
        "org.apache.kafka.clients.consumer.RoundRobinAssignor")
    
    .build();
```

#### Kafka Sink Optimization
```java
KafkaSink<ProcessedEvent> optimizedSink = KafkaSink.<ProcessedEvent>builder()
    .setBootstrapServers("kafka:9092")
    
    // Throughput optimization
    .setProperty(ProducerConfig.BATCH_SIZE_CONFIG, "131072")    // 128KB batches
    .setProperty(ProducerConfig.LINGER_MS_CONFIG, "10")         // 10ms linger
    .setProperty(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4") // Fast compression
    .setProperty(ProducerConfig.BUFFER_MEMORY_CONFIG, "134217728") // 128MB buffer
    
    // Reliability settings
    .setProperty(ProducerConfig.ACKS_CONFIG, "1")               // Leader ack only for throughput
    .setProperty(ProducerConfig.RETRIES_CONFIG, "3")            // Limited retries
    .setProperty(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
    
    // Network optimization
    .setProperty(ProducerConfig.SEND_BUFFER_CONFIG, "131072")   // 128KB send buffer
    .setProperty(ProducerConfig.RECEIVE_BUFFER_CONFIG, "131072") // 128KB receive buffer
    
    .build();
```

### Parallelism and Resource Management

#### Dynamic Parallelism Configuration
```java
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

// Set default parallelism based on available cores
int parallelism = Math.max(1, Runtime.getRuntime().availableProcessors());
env.setParallelism(parallelism);

DataStream<Event> eventStream = env.fromSource(kafkaSource, watermarkStrategy, "Events");

// Different parallelism for different operators
DataStream<ProcessedEvent> processed = eventStream
    .keyBy(Event::getUserId)
    .map(new EventProcessor())
    .name("Event Processing")
    .setParallelism(parallelism * 2); // CPU-intensive operation

DataStream<AggregatedResult> aggregated = processed
    .keyBy(ProcessedEvent::getCategory)
    .window(TumblingEventTimeWindows.of(Duration.ofMinutes(1)))
    .aggregate(new EventAggregator())
    .name("Windowed Aggregation")
    .setParallelism(parallelism); // Balanced parallelism

aggregated
    .addSink(kafkaSink)
    .name("Kafka Sink")
    .setParallelism(Math.min(parallelism, 4)); // Limit sink parallelism
```

### Memory and State Optimization

#### RocksDB State Backend Configuration
```java
// Configure RocksDB state backend for large state
RocksDBStateBackend rocksDBStateBackend = new RocksDBStateBackend("hdfs://namenode/checkpoints");

// Enable incremental checkpoints
rocksDBStateBackend.enableIncrementalCheckpointing();

// Optimize for your workload
rocksDBStateBackend.setRocksDBOptions(new RocksDBOptionsFactory() {
    @Override
    public DBOptions createDBOptions(DBOptions currentOptions, Collection<AutoCloseable> handlesToClose) {
        return currentOptions
            .setMaxBackgroundJobs(4)
            .setMaxSubcompactions(2)
            .setMaxOpenFiles(-1);
    }
    
    @Override
    public ColumnFamilyOptions createColumnOptions(ColumnFamilyOptions currentOptions, Collection<AutoCloseable> handlesToClose) {
        return currentOptions
            .setTableFormatConfig(
                new BlockBasedTableConfig()
                    .setBlockSize(64 * 1024)  // 64KB blocks
                    .setBlockCacheSize(256 * 1024 * 1024)) // 256MB cache
            .setWriteBufferSize(128 * 1024 * 1024) // 128MB write buffer
            .setMaxWriteBufferNumber(4)
            .setLevel0FileNumCompactionTrigger(8);
    }
});

env.setStateBackend(rocksDBStateBackend);
```

---

## 📊 MONITORING AND TROUBLESHOOTING

### Key Performance Metrics

#### Flink-Specific Metrics
```java
// Custom metrics in your Flink job
public class MonitoredEventProcessor extends RichMapFunction<Event, ProcessedEvent> {
    private Counter eventsProcessed;
    private Counter eventsFiltered;
    private Histogram processingLatency;
    private Gauge<Double> currentThroughput;
    
    @Override
    public void open(Configuration parameters) throws Exception {
        MetricGroup metricGroup = getRuntimeContext().getMetricGroup();
        
        this.eventsProcessed = metricGroup.counter("events_processed");
        this.eventsFiltered = metricGroup.counter("events_filtered");
        this.processingLatency = metricGroup.histogram("processing_latency_ms", new DescriptiveStatisticsHistogram(1000));
        
        this.currentThroughput = metricGroup.gauge("throughput_per_second", new Gauge<Double>() {
            @Override
            public Double getValue() {
                return calculateCurrentThroughput();
            }
        });
    }
    
    @Override
    public ProcessedEvent map(Event event) throws Exception {
        long startTime = System.currentTimeMillis();
        
        // Filter events
        if (!shouldProcess(event)) {
            eventsFiltered.inc();
            return null;
        }
        
        // Process event
        ProcessedEvent result = doProcessing(event);
        
        // Update metrics
        eventsProcessed.inc();
        processingLatency.update(System.currentTimeMillis() - startTime);
        
        return result;
    }
}
```

#### Kafka Integration Metrics
```bash
# Monitor Kafka consumer lag
docker exec -it kafka-1 kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe \
  --group flink-consumer-group

# Check Kafka topic details
docker exec -it kafka-1 kafka-topics \
  --bootstrap-server localhost:9092 \
  --describe \
  --topic user-events

# Monitor Kafka broker JMX metrics
curl http://localhost:19092/metrics | grep kafka_consumer_lag
```

### Troubleshooting Common Issues

#### Backpressure Analysis
```java
// Identify backpressure sources
public class BackpressureAnalyzer {
    
    public static void analyzeBackpressure(StreamExecutionEnvironment env) {
        // Enable latency tracking
        env.getConfig().setLatencyTrackingInterval(1000); // Track every 1 second
        
        // Add explicit buffering where needed
        DataStream<Event> bufferedStream = eventStream
            .map(new IdentityMap<>())
            .name("Buffer Before Heavy Processing")
            .setBufferTimeout(100); // 100ms buffer timeout
    }
}

// Monitor through Flink Web UI metrics:
// - InputQueueLength: Input buffer usage
// - OutputQueueLength: Output buffer usage  
// - InPoolUsage: Network buffer pool usage
// - BackPressuredTimeMsPerSecond: Time spent backpressured
```

#### Checkpoint Troubleshooting
```java
// Diagnose checkpoint issues
CheckpointConfig checkpointConfig = env.getCheckpointConfig();

// Enable detailed checkpoint statistics
checkpointConfig.enableUnalignedCheckpoints();
checkpointConfig.setAlignmentTimeout(Duration.ofSeconds(10));

// Configure checkpoint storage with compression
checkpointConfig.setCheckpointStorage("hdfs://namenode/checkpoints");
checkpointConfig.enableCheckpointsAfterTasksFinish();

// Monitor checkpoint metrics in Flink Web UI:
// - Checkpoint Duration: Time to complete checkpoint
// - Checkpoint Size: Checkpoint data size
// - Alignment Duration: Time waiting for checkpoint barriers
```

### Performance Profiling

#### JVM and Application Profiling
```bash
# Enable JFR (Java Flight Recorder) profiling
export FLINK_ENV_JAVA_OPTS="-XX:+FlightRecorder -XX:StartFlightRecording=duration=60s,filename=flink-profile.jfr"

# Restart Flink with profiling enabled
docker-compose restart flink-jobmanager flink-taskmanager

# Analyze GC behavior
export FLINK_ENV_JAVA_OPTS="$FLINK_ENV_JAVA_OPTS -XX:+PrintGC -XX:+PrintGCDetails -XX:+PrintGCTimeStamps"

# Monitor heap usage
jstat -gc $(docker exec -it flink-taskmanager-1 jps | grep TaskManagerRunner | cut -d' ' -f1) 2s
```

---

## 📝 PRACTICAL INTERVIEW QUESTIONS

### Intermediate Level (2-5 years)

**Q1: How does Flink handle Kafka consumer group coordination?**
**A:** Flink integrates with Kafka's consumer group protocol through its checkpointing mechanism. When checkpointing is enabled, Flink commits offsets to Kafka during successful checkpoints. The `enable.auto.commit` should be set to false so Flink manages offsets. Consumer group rebalancing happens when new consumers join or leave, and Flink handles this gracefully by restarting from the last checkpoint.

**Q2: Explain different watermark strategies and their use cases.**
**A:** 
- **Bounded Out-of-Order**: For data with known maximum delay (e.g., network latency)
- **Monotonous**: For strictly ordered data
- **Custom Generators**: For complex business logic (e.g., heartbeat-based watermarks)
- **Idle Source Handling**: For sources that may become inactive
- **Periodic vs Punctuated**: Based on time intervals vs specific events

**Q3: How would you handle schema evolution in a Kafka-Flink pipeline?**
**A:** Use Schema Registry with Avro for forward/backward compatibility. Implement versioned deserializers that can handle multiple schema versions. Use union types for optional fields. Test compatibility before deploying new schemas. Consider using Confluent Schema Registry's compatibility checking features.

**Q4: What are the trade-offs between different window types in stream processing?**
**A:**
- **Tumbling**: Memory efficient, no overlap, good for aggregations
- **Sliding**: Higher memory usage, overlapping results, good for trends
- **Session**: Variable size, user-specific, good for user behavior analysis
- **Global**: Most flexible, requires custom triggers, good for complex business logic

### Advanced Level (5+ years)

**Q5: Design a fault-tolerant exactly-once processing pipeline with state migration.**
**A:** 
1. Enable checkpointing with exactly-once mode
2. Use idempotent sources and transactional sinks
3. Implement savepoint-based deployment strategy
4. Handle state schema evolution with state descriptors
5. Use uid() for operators to maintain state mapping across deployments
6. Test failure scenarios and measure recovery time

**Q6: How would you optimize a high-throughput Kafka-Flink pipeline processing 1M events/second?**
**A:**
1. **Parallelism**: Match source parallelism to Kafka partitions
2. **Serialization**: Use efficient formats (Avro, Protobuf)
3. **Network**: Tune buffer sizes and batch configurations
4. **State Backend**: Use RocksDB with incremental checkpoints
5. **Memory**: Optimize heap and off-heap memory allocation
6. **Monitoring**: Track backpressure, lag, and resource utilization

**Q7: Implement a complex event processing system with multiple time windows and late data handling.**
**A:** Use CEP library for pattern detection, implement custom window assigners for business-specific windows, configure appropriate watermark strategies, use side outputs for late data processing, implement alerting for data quality issues, and maintain state TTL for cleanup.

**Q8: How would you implement exactly-once processing across multiple Kafka topics and external systems?**
**A:** Use distributed transactions, implement two-phase commit protocol, ensure idempotent operations, use transactional producers and read_committed consumers, implement proper error handling and recovery, maintain transaction logs for auditing.

---

## 🧪 ADVANCED EXERCISES

### Exercise 4: Complex Event Pattern Detection

**Goal:** Implement fraud detection using complex event processing.

**Pattern:** Detect suspicious login attempts followed by high-value transactions within 10 minutes.

```java
// CEP Pattern Definition
Pattern<LoginEvent, ?> suspiciousPattern = Pattern.<LoginEvent>begin("login")
    .where(event -> event.getEventType().equals("LOGIN"))
    .where(event -> event.isFromNewLocation() || event.hasFailedAttempts())
    .next("transaction")
    .where(event -> event.getEventType().equals("TRANSACTION"))
    .where(event -> event.getAmount() > 10000)
    .within(Time.minutes(10));

// Apply pattern and generate alerts
PatternStream<LoginEvent> patternStream = CEP.pattern(keyedEvents, suspiciousPattern);
DataStream<FraudAlert> alerts = patternStream.select(new FraudAlertSelector());
```

### Exercise 5: Real-time Machine Learning Feature Pipeline

**Goal:** Build a feature engineering pipeline for real-time ML inference.

**Features:**
- User session aggregates (sliding window)
- Purchase history features (state-based)
- Real-time model scoring
- A/B testing framework integration

```java
// Feature engineering with multiple time windows
DataStream<UserFeatures> features = userEvents
    .keyBy(UserEvent::getUserId)
    .window(SlidingEventTimeWindows.of(Time.hours(1), Time.minutes(5)))
    .aggregate(new FeatureAggregator())
    .keyBy(UserFeatures::getUserId)
    .connect(purchaseHistory.keyBy(PurchaseHistory::getUserId))
    .process(new FeatureEnrichmentFunction());
```

### Exercise 6: Multi-Tenant Stream Processing

**Goal:** Implement tenant-aware processing with resource isolation.

**Requirements:**
- Per-tenant parallelism configuration
- Resource quotas and monitoring
- Tenant-specific processing logic
- Cross-tenant data isolation

```java
// Tenant-aware processing
public class TenantAwareProcessor extends KeyedProcessFunction<String, Event, ProcessedEvent> {
    private Map<String, TenantConfig> tenantConfigs;
    private Map<String, RateLimiter> rateLimiters;
    
    @Override
    public void processElement(Event event, Context ctx, Collector<ProcessedEvent> out) {
        String tenantId = event.getTenantId();
        TenantConfig config = tenantConfigs.get(tenantId);
        RateLimiter rateLimiter = rateLimiters.get(tenantId);
        
        if (rateLimiter.tryAcquire()) {
            ProcessedEvent result = processWithTenantConfig(event, config);
            out.collect(result);
        } else {
            // Handle rate limiting
            ctx.output(rateLimitedTag, event);
        }
    }
}
```

---

## ✅ DAY 5 COMPLETION CHECKLIST

Before moving to Day 6, ensure you can:

**Flink-Kafka Integration:**
- [ ] Configure KafkaSource with proper watermark strategies
- [ ] Implement KafkaSink with exactly-once guarantees
- [ ] Handle schema evolution with Schema Registry
- [ ] Manage consumer offsets and checkpoints

**Advanced Windowing:**
- [ ] Implement custom window assigners and triggers
- [ ] Use session windows with dynamic gaps
- [ ] Perform windowed joins between streams
- [ ] Handle late data with side outputs

**Exactly-Once Processing:**
- [ ] Configure transactional Kafka producers and consumers
- [ ] Implement stateful processing with checkpoints
- [ ] Handle failures and recovery scenarios
- [ ] Monitor exactly-once guarantees

**Performance Optimization:**
- [ ] Tune parallelism for sources and sinks
- [ ] Optimize serialization and network settings
- [ ] Monitor backpressure and resource usage
- [ ] Implement proper error handling

**Practical Skills:**
- [ ] Build end-to-end streaming applications
- [ ] Troubleshoot common integration issues
- [ ] Implement monitoring and alerting
- [ ] Handle production deployment scenarios

---

## 🚀 WHAT'S NEXT?

Tomorrow (Day 6), you'll explore AWS streaming services:
- Amazon Kinesis Data Streams architecture and scaling
- Amazon MSK (Managed Streaming for Apache Kafka)
- Kinesis Data Analytics with Apache Flink
- Integration with AWS ecosystem services
- Cost optimization and operational best practices

**Preparation:**
- Ensure all Day 5 exercises work correctly
- Review exactly-once processing concepts
- Understand the relationship between Flink state and Kafka offsets
- Think about how cloud-managed services change operational complexity

## 📚 Additional Resources

**Flink-Kafka Integration:**
- [Flink Kafka Connector Documentation](https://nightlies.apache.org/flink/flink-docs-stable/docs/connectors/datastream/kafka/)
- [Kafka Streams vs Flink](https://flink.apache.org/news/2019/02/25/apache-flink-vs-kafka-streams.html)

**Advanced Windowing:**
- [Flink CEP Documentation](https://nightlies.apache.org/flink/flink-docs-stable/docs/libs/cep/)
- [Stream Processing Patterns](https://www.oreilly.com/library/view/stream-processing-with/9781491974285/)

**Production Patterns:**
- [Flink Best Practices](https://flink.apache.org/news/2020/07/28/flink-production-readiness-checklist.html)
- [Exactly-Once Processing](https://flink.apache.org/features/2018/03/01/end-to-end-exactly-once-apache-flink.html)

---

Excellent work completing Day 5! You now understand advanced Flink-Kafka integration patterns and can build sophisticated streaming applications. Tomorrow we'll explore how cloud providers simplify stream processing operations.

## Training Modules

### Module 1: Kafka Source Configuration (45 minutes)

New KafkaSource API (Flink 1.14+):
```java
KafkaSource<String> source = KafkaSource.<String>builder()
    .setBootstrapServers("localhost:9092")
    .setTopics("input-topic")
    .setGroupId("my-group")
    .setStartingOffsets(OffsetsInitializer.latest())
    .setValueOnlyDeserializer(new SimpleStringSchema())
    .build();

DataStreamSource<String> stream = env.fromSource(source, 
    WatermarkStrategy.noWatermarks(), 
    "Kafka Source");
```

With Watermarks and Event Time:
```java
KafkaSource<Event> source = KafkaSource.<Event>builder()
    .setBootstrapServers("kafka-1:29092,kafka-2:29093")
    .setTopics("events")
    .setGroupId("event-processor")
    .setStartingOffsets(OffsetsInitializer.earliest())
    .setDeserializer(KafkaRecordDeserializationSchema.valueOnly(new EventDeserializer()))
    .build();

WatermarkStrategy<Event> watermarkStrategy = WatermarkStrategy
    .<Event>forBoundedOutOfOrderness(Duration.ofSeconds(20))
    .withTimestampAssigner((event, timestamp) -> event.getTimestamp());

DataStreamSource<Event> eventStream = env.fromSource(source, watermarkStrategy, "Events");
```

### Module 2: Advanced Windowing Patterns (60 minutes)

1. **Session Windows with Dynamic Gap**:
```java
DataStream<SessionEvent> sessionStream = eventStream
    .keyBy(Event::getUserId)
    .window(ProcessingTimeSessionWindows.withDynamicGap(new SessionWindowTimeGapExtractor<Event>() {
        @Override
        public long extract(Event element) {
            return element.getUserType().equals("premium") ? 30000 : 60000; // Different gaps
        }
    }))
    .process(new SessionProcessor());
```

2. **Custom Window Assigner**:
```java
public class CustomWindowAssigner extends WindowAssigner<Event, CustomWindow> {
    @Override
    public Collection<CustomWindow> assignWindows(Event element, long timestamp, WindowAssignerContext context) {
        // Custom windowing logic based on event properties
        long windowStart = calculateWindowStart(element);
        long windowEnd = calculateWindowEnd(element);
        return Collections.singleton(new CustomWindow(windowStart, windowEnd));
    }
}
```

3. **Global Windows with Custom Trigger**:
```java
DataStream<Aggregate> result = eventStream
    .keyBy(Event::getCategory)
    .window(GlobalWindows.create())
    .trigger(new CustomTrigger())
    .aggregate(new CustomAggregateFunction());

public class CustomTrigger extends Trigger<Event, GlobalWindow> {
    @Override
    public TriggerResult onElement(Event element, long timestamp, GlobalWindow window, TriggerContext ctx) {
        if (shouldTrigger(element)) {
            return TriggerResult.FIRE;
        }
        // Register timer for window timeout
        ctx.registerProcessingTimeTimer(System.currentTimeMillis() + 60000);
        return TriggerResult.CONTINUE;
    }
}
```

### Module 3: Exactly-Once Processing (45 minutes)

Transactional Kafka Sink:
```java
KafkaSink<String> sink = KafkaSink.<String>builder()
    .setBootstrapServers("kafka-1:29092,kafka-2:29093")
    .setRecordSerializer(KafkaRecordSerializationSchema.builder()
        .setTopic("output-topic")
        .setValueSerializationSchema(new SimpleStringSchema())
        .build()
    )
    .setDeliveryGuarantee(DeliveryGuarantee.EXACTLY_ONCE)
    .setTransactionalIdPrefix("my-app")
    .setProperty(ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, "300000")
    .build();

processedStream.sinkTo(sink);
```

Required Kafka Configuration:
```properties
# Enable transactions
transaction.state.log.replication.factor=2
transaction.state.log.min.isr=2

# Consumer configuration for exactly-once
isolation.level=read_committed
```

### Module 4: Schema Evolution with Schema Registry (30 minutes)

Avro Schema Integration:
```java
// Schema Registry configuration
Map<String, String> properties = new HashMap<>();
properties.put("schema.registry.url", "http://schema-registry:8081");

ConfluentRegistryAvroDeserializationSchema<User> deserializer = 
    ConfluentRegistryAvroDeserializationSchema.forSpecific(User.class, "user-topic-value", properties);

KafkaSource<User> source = KafkaSource.<User>builder()
    .setBootstrapServers("kafka-1:29092")
    .setTopics("users")
    .setValueOnlyDeserializer(deserializer)
    .build();
```

## Hands-on Exercises

### Exercise 1: Real-time Analytics Pipeline
```bash
# Start the infrastructure
docker-compose up -d

# Deploy real-time analytics job
docker exec -it flink-jobmanager-integration flink run /opt/flink/jobs/RealtimeAnalyticsJob.jar

# Generate events
docker-compose up event-generator

# Monitor results in Kafka UI and InfluxDB
```

### Exercise 2: Session Analytics
```bash
# Deploy session analytics job
docker exec -it flink-jobmanager-integration flink run /opt/flink/jobs/SessionAnalyticsJob.jar

# Generate user behavior events
# Monitor session metrics in dashboard
```

### Exercise 3: Late Data Handling
```bash
# Deploy late data processing job
docker exec -it flink-jobmanager-integration flink run /opt/flink/jobs/LateDataJob.jar

# Generate out-of-order events
# Observe watermark behavior and late event handling
```

## Best Practices

### Kafka Integration Best Practices
1. **Configure appropriate consumer properties** for your use case
2. **Use consumer groups** for scalable processing
3. **Enable checkpointing** for offset management
4. **Handle deserialization errors** gracefully
5. **Monitor consumer lag** and processing rates

### Windowing Best Practices
1. **Choose appropriate window types** based on business logic
2. **Configure allowed lateness** based on data characteristics
3. **Use side outputs** for extremely late events
4. **Optimize window size** for memory usage
5. **Consider window alignment** for consistent results

### Performance Best Practices
1. **Right-size parallelism** for sources and sinks
2. **Use appropriate serialization** formats
3. **Configure network buffers** properly
4. **Monitor backpressure** indicators
5. **Optimize checkpoint intervals**

## Real-World Examples

### E-commerce Real-time Recommendations
```
Architecture:
1. User behavior events from web/mobile → Kafka
2. Real-time feature extraction with sliding windows
3. Model scoring with async I/O to ML service
4. Recommendation updates to Redis/Cassandra
5. A/B testing framework integration
6. Real-time performance metrics to InfluxDB
```

### Financial Fraud Detection
```
Pipeline:
1. Transaction events → Kafka (partitioned by account)
2. Real-time risk scoring with user session state
3. Rule engine with broadcast state for dynamic rules
4. CEP for complex fraud patterns
5. Alert generation with different severity levels
6. Model feedback loop for continuous learning
```

### IoT Anomaly Detection
```
Processing Flow:
1. Sensor readings → Kafka (high throughput)
2. Device state management with TTL
3. Time-series aggregations in tumbling windows
4. Statistical anomaly detection with sliding windows
5. Machine learning model inference
6. Multi-level alerting system
7. Time-series storage for historical analysis
```

## Advanced Patterns

### 1. Multi-Stream Processing with CoProcessFunction
```java
DataStream<Event1> stream1 = env.fromSource(source1, watermarkStrategy1, "Stream1");
DataStream<Event2> stream2 = env.fromSource(source2, watermarkStrategy2, "Stream2");

DataStream<CombinedResult> result = stream1
    .keyBy(Event1::getKey)
    .connect(stream2.keyBy(Event2::getKey))
    .process(new CoProcessFunction<Event1, Event2, CombinedResult>() {
        private ValueState<Event1> event1State;
        private ValueState<Event2> event2State;
        
        @Override
        public void processElement1(Event1 event, Context ctx, Collector<CombinedResult> out) {
            event1State.update(event);
            if (event2State.value() != null) {
                out.collect(combine(event, event2State.value()));
            }
        }
        
        @Override
        public void processElement2(Event2 event, Context ctx, Collector<CombinedResult> out) {
            event2State.update(event);
            if (event1State.value() != null) {
                out.collect(combine(event1State.value(), event));
            }
        }
    });
```

### 2. Dynamic Partitioning with Custom Partitioner
```java
KafkaSink<Event> sink = KafkaSink.<Event>builder()
    .setBootstrapServers("kafka-1:29092")
    .setRecordSerializer(KafkaRecordSerializationSchema.builder()
        .setTopic("output-topic")
        .setPartitioner(new CustomPartitioner<Event>() {
            @Override
            public int partition(Event record, byte[] key, byte[] value, String targetTopic, int[] partitions) {
                // Custom partitioning logic
                return record.getEventType().hashCode() % partitions.length;
            }
        })
        .build()
    )
    .build();
```

### 3. Schema Evolution Handling
```java
public class EvolvableEventDeserializer implements DeserializationSchema<Event> {
    @Override
    public Event deserialize(byte[] message) throws IOException {
        try {
            return deserializeV2(message);
        } catch (Exception e) {
            // Fallback to older schema version
            return deserializeV1(message);
        }
    }
}
```

## Interview Questions

### Intermediate Level
1. **How does Flink handle Kafka offset management?**
   - Integration with checkpoints, consumer group coordination, failure recovery

2. **Explain different watermark strategies and when to use each.**
   - Periodic, punctuated, bounded out-of-order, idle source handling

3. **How would you handle schema evolution in a Kafka-Flink pipeline?**
   - Schema Registry, backward compatibility, deserialization error handling

4. **What are the trade-offs between different window types?**
   - Memory usage, latency, completeness, use case alignment

### Advanced Level
1. **Design a fault-tolerant exactly-once processing pipeline.**
   - Transactional sources/sinks, checkpointing, idempotency, error handling

2. **How would you optimize a high-throughput Kafka-Flink pipeline?**
   - Parallelism tuning, serialization, network buffers, backpressure handling

3. **Implement a complex event processing system with multiple time windows.**
   - CEP patterns, state management, performance optimization

4. **Handle late-arriving data in a real-time analytics system.**
   - Watermark strategies, allowed lateness, side outputs, business logic

## Performance Tuning

### Source/Sink Optimization
```java
// Optimize Kafka source
KafkaSource<Event> source = KafkaSource.<Event>builder()
    .setBootstrapServers("kafka:9092")
    .setProperty("fetch.min.bytes", "1048576")  // 1MB
    .setProperty("fetch.max.wait.ms", "500")
    .setProperty("max.poll.records", "1000")
    .setProperty("receive.buffer.bytes", "65536")
    .build();

// Optimize Kafka sink
KafkaSink<Result> sink = KafkaSink.<Result>builder()
    .setProperty("batch.size", "32768")
    .setProperty("linger.ms", "5")
    .setProperty("compression.type", "snappy")
    .setProperty("buffer.memory", "67108864")  // 64MB
    .build();
```

### Memory and State Optimization
```properties
# Flink configuration
taskmanager.memory.managed.fraction: 0.4
taskmanager.memory.process.size: 4gb
state.backend.rocksdb.block.blocksize: 64KB
state.backend.rocksdb.writebuffer.size: 64MB
state.backend.rocksdb.writebuffer.count: 4
```

## Monitoring Dashboard

### Key Metrics to Track
1. **Throughput**: Records/second processed
2. **Latency**: Processing delay and watermark lag  
3. **Kafka Lag**: Consumer group lag
4. **Checkpoint Metrics**: Duration, size, failures
5. **Resource Utilization**: CPU, memory, network

### Grafana Dashboard Queries
```sql
-- Processing throughput
rate(flink_taskmanager_job_task_numRecordsInPerSecond[5m])

-- Kafka consumer lag
kafka_consumer_lag_sum by (topic, partition)

-- Checkpoint duration
flink_jobmanager_job_lastCheckpointDuration

-- Watermark lag
flink_taskmanager_job_task_currentEventTimeLag
```

## Next Steps
Tomorrow we'll explore AWS streaming services:
- Amazon Kinesis Data Streams
- Amazon MSK (Managed Streaming for Kafka)
- Kinesis Data Analytics for Apache Flink
- Integration patterns with AWS services