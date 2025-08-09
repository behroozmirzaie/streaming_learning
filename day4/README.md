# Day 4: Advanced Flink Features and State Management - Complete Learning Guide

## 📚 Learning Overview
Today you'll master advanced Apache Flink patterns including sophisticated state management, custom operators, savepoints for job evolution, async I/O, and production deployment strategies. You'll build enterprise-grade streaming applications.

## 🎯 Learning Objectives
By the end of today, you will be able to:
- Implement advanced state management patterns (TTL, broadcast state, queryable state)
- Build custom operators and process functions
- Use savepoints for zero-downtime job updates
- Implement async I/O for external enrichment
- Design fault-tolerant production streaming architectures
- Optimize Flink jobs for high performance and scalability

---

## 📖 PART 1: Advanced State Management

### State Types Deep Dive

#### 1. **Keyed State Types**

**ValueState - Single Value per Key**
```java
public class UserProfileProcessor extends KeyedProcessFunction<String, UserEvent, EnrichedEvent> {
    private ValueState<UserProfile> profileState;
    
    @Override
    public void open(Configuration parameters) {
        ValueStateDescriptor<UserProfile> descriptor = 
            new ValueStateDescriptor<>("user-profile", UserProfile.class);
        profileState = getRuntimeContext().getState(descriptor);
    }
    
    @Override
    public void processElement(UserEvent event, Context ctx, Collector<EnrichedEvent> out) throws Exception {
        UserProfile profile = profileState.value();
        if (profile == null) {
            profile = new UserProfile(event.getUserId());
        }
        
        // Update profile based on event
        profile.updateFromEvent(event);
        profileState.update(profile);
        
        out.collect(new EnrichedEvent(event, profile));
    }
}
```

**ListState - List of Values per Key**
```java
public class RecentEventsTracker extends KeyedProcessFunction<String, Event, Alert> {
    private ListState<Event> recentEventsState;
    
    @Override
    public void open(Configuration parameters) {
        ListStateDescriptor<Event> descriptor = 
            new ListStateDescriptor<>("recent-events", Event.class);
        recentEventsState = getRuntimeContext().getListState(descriptor);
    }
    
    @Override
    public void processElement(Event event, Context ctx, Collector<Alert> out) throws Exception {
        // Add current event
        recentEventsState.add(event);
        
        // Get all recent events
        List<Event> events = new ArrayList<>();
        for (Event e : recentEventsState.get()) {
            events.add(e);
        }
        
        // Keep only last 10 events
        if (events.size() > 10) {
            recentEventsState.clear();
            events = events.subList(events.size() - 10, events.size());
            for (Event e : events) {
                recentEventsState.add(e);
            }
        }
        
        // Check for patterns in recent events
        if (detectSuspiciousPattern(events)) {
            out.collect(new Alert(event.getUserId(), "Suspicious pattern detected"));
        }
    }
}
```

**MapState - Key-Value Map per Key**
```java
public class SessionManager extends KeyedProcessFunction<String, UserAction, SessionSummary> {
    private MapState<String, Long> sessionCountsState; // sessionId -> count
    private ValueState<Long> lastActivityState;
    
    @Override
    public void open(Configuration parameters) {
        MapStateDescriptor<String, Long> mapDescriptor = 
            new MapStateDescriptor<>("session-counts", String.class, Long.class);
        sessionCountsState = getRuntimeContext().getMapState(mapDescriptor);
        
        ValueStateDescriptor<Long> valueDescriptor = 
            new ValueStateDescriptor<>("last-activity", Long.class, 0L);
        lastActivityState = getRuntimeContext().getState(valueDescriptor);
    }
    
    @Override
    public void processElement(UserAction action, Context ctx, Collector<SessionSummary> out) throws Exception {
        String sessionId = action.getSessionId();
        
        // Update session count
        Long currentCount = sessionCountsState.get(sessionId);
        if (currentCount == null) {
            currentCount = 0L;
        }
        sessionCountsState.put(sessionId, currentCount + 1);
        
        // Update last activity
        lastActivityState.update(action.getTimestamp());
        
        // Register cleanup timer (30 minutes of inactivity)
        ctx.timerService().registerEventTimeTimer(action.getTimestamp() + 30 * 60 * 1000);
    }
    
    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<SessionSummary> out) throws Exception {
        // Session timeout - emit summary and cleanup
        Map<String, Long> sessions = new HashMap<>();
        for (Map.Entry<String, Long> entry : sessionCountsState.entries()) {
            sessions.put(entry.getKey(), entry.getValue());
        }
        
        out.collect(new SessionSummary(ctx.getCurrentKey(), sessions, timestamp));
        
        // Clear state
        sessionCountsState.clear();
        lastActivityState.clear();
    }
}
```

#### 2. **State TTL (Time-To-Live)**

**Automatic State Cleanup**
```java
public class UserSessionProcessor extends KeyedProcessFunction<String, Event, SessionEvent> {
    private ValueState<SessionData> sessionState;
    
    @Override
    public void open(Configuration parameters) {
        // Configure TTL - expire after 30 minutes of inactivity
        StateTtlConfig ttlConfig = StateTtlConfig
            .newBuilder(Time.minutes(30))
            .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
            .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
            .cleanupInBackground() // Background cleanup
            .build();
        
        ValueStateDescriptor<SessionData> descriptor = 
            new ValueStateDescriptor<>("session", SessionData.class);
        descriptor.enableTimeToLive(ttlConfig);
        
        sessionState = getRuntimeContext().getState(descriptor);
    }
    
    @Override
    public void processElement(Event event, Context ctx, Collector<SessionEvent> out) throws Exception {
        SessionData session = sessionState.value(); // May return null if expired
        
        if (session == null) {
            // Start new session
            session = new SessionData(event.getUserId(), event.getTimestamp());
            out.collect(new SessionEvent("SESSION_START", event.getUserId()));
        }
        
        // Update session
        session.addEvent(event);
        sessionState.update(session);
        
        out.collect(new SessionEvent("EVENT_PROCESSED", event.getUserId()));
    }
}
```

**TTL Cleanup Strategies**
```java
// Different cleanup strategies
StateTtlConfig ttlConfig = StateTtlConfig
    .newBuilder(Time.hours(1))
    
    // When to update TTL
    .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite) // Default
    // .setUpdateType(StateTtlConfig.UpdateType.OnReadAndWrite) // Also on reads
    
    // Visibility of expired state
    .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired) // Default
    // .setStateVisibility(StateTtlConfig.StateVisibility.ReturnExpiredIfNotCleanedUp)
    
    // Cleanup strategies
    .cleanupFullSnapshot() // Clean during full checkpoint
    .cleanupIncrementally(1000, false) // Clean incrementally
    .cleanupInBackground() // Background thread cleanup
    
    .build();
```

#### 3. **Broadcast State Pattern**

**Dynamic Configuration Updates**
```java
// Main data stream
DataStream<Transaction> transactions = env.addSource(new TransactionSource());

// Configuration stream (rules, thresholds, etc.)
DataStream<Rule> rules = env.addSource(new RuleSource());

// Create broadcast state descriptor
MapStateDescriptor<String, Rule> ruleStateDescriptor = new MapStateDescriptor<>(
    "RulesBroadcastState",
    BasicTypeInfo.STRING_TYPE_INFO,
    TypeInformation.of(Rule.class)
);

// Broadcast the rules stream
BroadcastStream<Rule> ruleBroadcastStream = rules.broadcast(ruleStateDescriptor);

// Connect and process
DataStream<Alert> alerts = transactions
    .connect(ruleBroadcastStream)
    .process(new FraudDetectionFunction());

public class FraudDetectionFunction extends BroadcastProcessFunction<Transaction, Rule, Alert> {
    private MapStateDescriptor<String, Rule> ruleStateDescriptor;
    
    @Override
    public void open(Configuration parameters) {
        ruleStateDescriptor = new MapStateDescriptor<>(
            "RulesBroadcastState",
            BasicTypeInfo.STRING_TYPE_INFO,
            TypeInformation.of(Rule.class)
        );
    }
    
    @Override
    public void processElement(Transaction transaction, ReadOnlyContext ctx, Collector<Alert> out) throws Exception {
        // Access broadcast state (read-only)
        ReadOnlyBroadcastState<String, Rule> broadcastState = ctx.getBroadcastState(ruleStateDescriptor);
        
        // Apply all rules to transaction
        for (Map.Entry<String, Rule> entry : broadcastState.immutableEntries()) {
            Rule rule = entry.getValue();
            if (rule.matches(transaction)) {
                out.collect(new Alert(transaction.getUserId(), rule.getDescription()));
            }
        }
    }
    
    @Override
    public void processBroadcastElement(Rule rule, Context ctx, Collector<Alert> out) throws Exception {
        // Update broadcast state
        BroadcastState<String, Rule> broadcastState = ctx.getBroadcastState(ruleStateDescriptor);
        
        if (rule.isActive()) {
            broadcastState.put(rule.getId(), rule);
        } else {
            broadcastState.remove(rule.getId());
        }
    }
}
```

### Queryable State

**External Access to Flink State**
```java
// Enable queryable state in your job
ValueStateDescriptor<Long> descriptor = new ValueStateDescriptor<>("count", Long.class, 0L);

// Make state queryable
descriptor.setQueryable("user-counts");

DataStream<Event> events = env.addSource(new EventSource());
events
    .keyBy(Event::getUserId)
    .process(new KeyedProcessFunction<String, Event, Event>() {
        private ValueState<Long> countState;
        
        @Override
        public void open(Configuration parameters) {
            countState = getRuntimeContext().getState(descriptor);
        }
        
        @Override
        public void processElement(Event event, Context ctx, Collector<Event> out) throws Exception {
            Long count = countState.value();
            countState.update(count + 1);
            out.collect(event);
        }
    });

// Query from external client
QueryableStateClient client = new QueryableStateClient(tmHostname, proxyPort);

CompletableFuture<ValueState<Long>> future = client.getKvState(
    jobId,
    "user-counts",     // Queryable name
    "user123",         // Key
    BasicTypeInfo.STRING_TYPE_INFO,
    descriptor
);

future.thenAccept(resultState -> {
    try {
        Long count = resultState.value();
        System.out.println("User count: " + count);
    } catch (Exception e) {
        e.printStackTrace();
    }
});
```

---

## 📖 PART 2: Custom Operators and Process Functions

### ProcessFunction Deep Dive

#### 1. **KeyedProcessFunction with Timers**

**Session Window Implementation**
```java
public class CustomSessionWindow extends KeyedProcessFunction<String, Event, SessionResult> {
    private ValueState<SessionData> sessionState;
    private ValueState<Long> timerState;
    
    private static final long SESSION_TIMEOUT = 30 * 60 * 1000; // 30 minutes
    
    @Override
    public void open(Configuration parameters) {
        ValueStateDescriptor<SessionData> sessionDescriptor = 
            new ValueStateDescriptor<>("session", SessionData.class);
        sessionState = getRuntimeContext().getState(sessionDescriptor);
        
        ValueStateDescriptor<Long> timerDescriptor = 
            new ValueStateDescriptor<>("timer", Long.class);
        timerState = getRuntimeContext().getState(timerDescriptor);
    }
    
    @Override
    public void processElement(Event event, Context ctx, Collector<SessionResult> out) throws Exception {
        SessionData session = sessionState.value();
        
        if (session == null) {
            // Start new session
            session = new SessionData(event.getUserId(), event.getTimestamp());
        }
        
        // Update session
        session.addEvent(event);
        session.setLastEventTime(event.getTimestamp());
        sessionState.update(session);
        
        // Cancel previous timer
        Long currentTimer = timerState.value();
        if (currentTimer != null) {
            ctx.timerService().deleteEventTimeTimer(currentTimer);
        }
        
        // Set new timer
        long newTimer = event.getTimestamp() + SESSION_TIMEOUT;
        ctx.timerService().registerEventTimeTimer(newTimer);
        timerState.update(newTimer);
    }
    
    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<SessionResult> out) throws Exception {
        // Session expired
        SessionData session = sessionState.value();
        
        if (session != null) {
            // Emit session result
            SessionResult result = new SessionResult(
                session.getUserId(),
                session.getStartTime(),
                timestamp,
                session.getEventCount(),
                session.calculateMetrics()
            );
            out.collect(result);
            
            // Clean up state
            sessionState.clear();
            timerState.clear();
        }
    }
}
```

#### 2. **CoProcessFunction for Dual Stream Processing**

**Stream Joining with Different Rates**
```java
public class OrderPaymentJoin extends CoProcessFunction<Order, Payment, EnrichedOrder> {
    // State to store orders waiting for payment
    private ValueState<Order> orderState;
    // State to store payments waiting for order
    private ValueState<Payment> paymentState;
    
    @Override
    public void open(Configuration parameters) {
        ValueStateDescriptor<Order> orderDescriptor = 
            new ValueStateDescriptor<>("pending-order", Order.class);
        orderState = getRuntimeContext().getState(orderDescriptor);
        
        ValueStateDescriptor<Payment> paymentDescriptor = 
            new ValueStateDescriptor<>("pending-payment", Payment.class);
        paymentState = getRuntimeContext().getState(paymentDescriptor);
    }
    
    @Override
    public void processElement1(Order order, Context ctx, Collector<EnrichedOrder> out) throws Exception {
        // Process order stream
        Payment payment = paymentState.value();
        
        if (payment != null) {
            // Payment already arrived, join immediately
            out.collect(new EnrichedOrder(order, payment));
            paymentState.clear();
        } else {
            // Wait for payment, store order
            orderState.update(order);
            
            // Set timeout timer (5 minutes)
            ctx.timerService().registerProcessingTimeTimer(
                ctx.timerService().currentProcessingTime() + 5 * 60 * 1000
            );
        }
    }
    
    @Override
    public void processElement2(Payment payment, Context ctx, Collector<EnrichedOrder> out) throws Exception {
        // Process payment stream
        Order order = orderState.value();
        
        if (order != null) {
            // Order already arrived, join immediately
            out.collect(new EnrichedOrder(order, payment));
            orderState.clear();
        } else {
            // Wait for order, store payment
            paymentState.update(payment);
            
            // Set timeout timer (5 minutes)
            ctx.timerService().registerProcessingTimeTimer(
                ctx.timerService().currentProcessingTime() + 5 * 60 * 1000
            );
        }
    }
    
    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<EnrichedOrder> out) throws Exception {
        // Timeout - emit partial results or alerts
        Order order = orderState.value();
        Payment payment = paymentState.value();
        
        if (order != null) {
            // Order without payment - potential issue
            out.collect(new EnrichedOrder(order, null, "PAYMENT_TIMEOUT"));
            orderState.clear();
        }
        
        if (payment != null) {
            // Payment without order - investigate
            out.collect(new EnrichedOrder(null, payment, "ORDER_NOT_FOUND"));
            paymentState.clear();
        }
    }
}
```

### Async I/O for External Enrichment

#### Database Lookups Without Blocking
```java
public class AsyncDatabaseEnrichment extends RichAsyncFunction<Event, EnrichedEvent> {
    private DatabaseClient databaseClient;
    private ExecutorService executorService;
    
    @Override
    public void open(Configuration parameters) throws Exception {
        // Initialize async database client
        databaseClient = new AsyncDatabaseClient(
            "jdbc:postgresql://localhost:5432/mydb",
            "user", "password"
        );
        
        // Thread pool for async operations
        executorService = Executors.newFixedThreadPool(10);
    }
    
    @Override
    public void asyncInvoke(Event input, ResultFuture<EnrichedEvent> resultFuture) throws Exception {
        CompletableFuture<UserProfile> future = CompletableFuture.supplyAsync(() -> {
            try {
                return databaseClient.getUserProfile(input.getUserId());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executorService);
        
        future.whenComplete((profile, throwable) -> {
            if (throwable == null) {
                EnrichedEvent enrichedEvent = new EnrichedEvent(input, profile);
                resultFuture.complete(Collections.singleton(enrichedEvent));
            } else {
                resultFuture.completeExceptionally(throwable);
            }
        });
    }
    
    @Override
    public void timeout(Event input, ResultFuture<EnrichedEvent> resultFuture) throws Exception {
        // Handle timeout - maybe return partial data
        EnrichedEvent partialEvent = new EnrichedEvent(input, UserProfile.getDefault());
        resultFuture.complete(Collections.singleton(partialEvent));
    }
    
    @Override
    public void close() throws Exception {
        if (databaseClient != null) {
            databaseClient.close();
        }
        if (executorService != null) {
            executorService.shutdown();
        }
    }
}

// Usage in DataStream
DataStream<Event> events = env.addSource(new EventSource());

DataStream<EnrichedEvent> enrichedEvents = AsyncDataStream.unorderedWait(
    events,
    new AsyncDatabaseEnrichment(),
    5000, // 5 second timeout
    TimeUnit.MILLISECONDS,
    100   // Max async requests in flight
);
```

#### Redis Cache Lookups
```java
public class AsyncRedisEnrichment extends RichAsyncFunction<Event, EnrichedEvent> {
    private RedisAsyncClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisAsyncCommands<String, String> commands;
    
    @Override
    public void open(Configuration parameters) throws Exception {
        RedisURI redisUri = RedisURI.Builder
            .redis("localhost", 6379)
            .withDatabase(0)
            .withTimeout(Duration.ofSeconds(2))
            .build();
            
        redisClient = RedisClient.create(redisUri);
        connection = redisClient.connect();
        commands = connection.async();
    }
    
    @Override
    public void asyncInvoke(Event input, ResultFuture<EnrichedEvent> resultFuture) throws Exception {
        String cacheKey = "user:" + input.getUserId();
        
        RedisFuture<String> future = commands.get(cacheKey);
        
        future.whenComplete((cachedData, throwable) -> {
            if (throwable == null && cachedData != null) {
                try {
                    UserProfile profile = UserProfile.fromJson(cachedData);
                    EnrichedEvent enrichedEvent = new EnrichedEvent(input, profile);
                    resultFuture.complete(Collections.singleton(enrichedEvent));
                } catch (Exception e) {
                    resultFuture.completeExceptionally(e);
                }
            } else {
                // Cache miss - return event without enrichment
                EnrichedEvent enrichedEvent = new EnrichedEvent(input, null);
                resultFuture.complete(Collections.singleton(enrichedEvent));
            }
        });
    }
}
```

---

## 📖 PART 3: Savepoints and Job Evolution

### Understanding Savepoints

**Savepoints vs Checkpoints:**
```
Checkpoints:
- Automatic, periodic
- For fault recovery
- Managed by Flink
- May be deleted automatically

Savepoints:
- Manual, on-demand
- For job evolution
- User-managed
- Kept until explicitly deleted
```

### Taking and Restoring Savepoints

#### Command Line Operations
```bash
# Take a savepoint
bin/flink savepoint <jobId> [targetDirectory]
# Example:
bin/flink savepoint 5e20cb6b0f357591171dfcca2ebc23bf /tmp/savepoints/

# Cancel job with savepoint
bin/flink cancel -s [targetDirectory] <jobId>

# Start job from savepoint
bin/flink run -s <savepointPath> <jobJar> [arguments]
# Example:
bin/flink run -s /tmp/savepoints/savepoint-5e20cb-123456789 my-job.jar

# Allow non-restored state (for job evolution)
bin/flink run -s <savepointPath> -n <jobJar>
```

#### Programmatic Savepoint Creation
```java
// In your Flink job, you can trigger savepoints programmatically
public class SavepointTrigger {
    public static void main(String[] args) throws Exception {
        String jobManagerHost = "localhost";
        int jobManagerPort = 8081;
        
        ClusterClient<?> client = StandaloneClusterClient.builder()
            .setRpcService(RpcService.create())
            .setClusterEntrypoint(new StandaloneSessionClusterEntrypoint.Configuration(
                jobManagerHost, jobManagerPort))
            .build();
        
        JobID jobId = JobID.fromHexString("your-job-id-here");
        
        CompletableFuture<String> savepoint = client.triggerSavepoint(
            jobId, 
            "/path/to/savepoints/"
        );
        
        String savepointPath = savepoint.get();
        System.out.println("Savepoint created at: " + savepointPath);
    }
}
```

### Job Evolution Patterns

#### 1. **Adding New Operators**
```java
// Version 1 of your job
DataStream<Event> events = env.addSource(new EventSource());
DataStream<ProcessedEvent> processed = events
    .map(new EventProcessor())
    .uid("event-processor"); // IMPORTANT: Set UIDs for evolution

processed.addSink(new EventSink()).uid("event-sink");

// Version 2 - Adding new transformation
DataStream<Event> events = env.addSource(new EventSource());
DataStream<ProcessedEvent> processed = events
    .map(new EventProcessor())
    .uid("event-processor"); // Same UID as before

// NEW: Add enrichment step
DataStream<EnrichedEvent> enriched = processed
    .map(new EventEnricher())
    .uid("event-enricher"); // New UID for new operator

enriched.addSink(new EventSink()).uid("event-sink");
```

#### 2. **Modifying Existing Operators**
```java
// Evolution-friendly state management
public class EvolvableEventProcessor extends KeyedProcessFunction<String, Event, ProcessedEvent> {
    
    // Version 1 state
    private ValueState<UserStats> userStatsState;
    
    // Version 2 state (added later)
    private ValueState<UserPreferences> userPreferencesState;
    
    @Override
    public void open(Configuration parameters) {
        // Original state
        ValueStateDescriptor<UserStats> statsDescriptor = 
            new ValueStateDescriptor<>("user-stats", UserStats.class);
        userStatsState = getRuntimeContext().getState(statsDescriptor);
        
        // New state (Version 2) - with default handling
        ValueStateDescriptor<UserPreferences> prefsDescriptor = 
            new ValueStateDescriptor<>("user-preferences", UserPreferences.class);
        userPreferencesState = getRuntimeContext().getState(prefsDescriptor);
    }
    
    @Override
    public void processElement(Event event, Context ctx, Collector<ProcessedEvent> out) throws Exception {
        // Update existing state
        UserStats stats = userStatsState.value();
        if (stats == null) {
            stats = new UserStats();
        }
        stats.updateFromEvent(event);
        userStatsState.update(stats);
        
        // Handle new state gracefully
        UserPreferences prefs = userPreferencesState.value();
        if (prefs == null) {
            // First time after job evolution - initialize with defaults
            prefs = UserPreferences.getDefaults();
            userPreferencesState.update(prefs);
        }
        
        out.collect(new ProcessedEvent(event, stats, prefs));
    }
}
```

#### 3. **Schema Evolution**
```java
// Version 1: Simple event
public class EventV1 {
    public String userId;
    public String eventType;
    public long timestamp;
    
    // Serialization methods
}

// Version 2: Extended event with backward compatibility
public class EventV2 {
    public String userId;
    public String eventType;
    public long timestamp;
    
    // New fields with defaults
    public String sessionId = "unknown";
    public Map<String, String> metadata = new HashMap<>();
    
    // Migration constructor
    public EventV2(EventV1 v1Event) {
        this.userId = v1Event.userId;
        this.eventType = v1Event.eventType;
        this.timestamp = v1Event.timestamp;
        // New fields use defaults
    }
}
```

### Advanced Savepoint Strategies

#### Blue-Green Deployment with Savepoints
```bash
#!/bin/bash
# Blue-Green deployment script

CURRENT_JOB_ID=$(get_current_job_id)
NEW_JAR_PATH=$1
SAVEPOINT_DIR="/savepoints"

echo "Starting blue-green deployment..."

# Step 1: Take savepoint of current job
echo "Taking savepoint of current job: $CURRENT_JOB_ID"
SAVEPOINT_PATH=$(bin/flink savepoint $CURRENT_JOB_ID $SAVEPOINT_DIR)
echo "Savepoint created: $SAVEPOINT_PATH"

# Step 2: Cancel current job
echo "Cancelling current job..."
bin/flink cancel $CURRENT_JOB_ID

# Step 3: Deploy new version
echo "Deploying new version from savepoint..."
NEW_JOB_OUTPUT=$(bin/flink run -s $SAVEPOINT_PATH -d $NEW_JAR_PATH)
NEW_JOB_ID=$(echo $NEW_JOB_OUTPUT | extract_job_id)

# Step 4: Health check
echo "Performing health check..."
if health_check $NEW_JOB_ID; then
    echo "Deployment successful! New job ID: $NEW_JOB_ID"
    # Clean up old savepoints if desired
    cleanup_old_savepoints
else
    echo "Health check failed! Rolling back..."
    bin/flink cancel $NEW_JOB_ID
    # Restart old version
    bin/flink run -s $SAVEPOINT_PATH -d $OLD_JAR_PATH
    exit 1
fi
```

---

## 🛠️ PRACTICAL HANDS-ON EXERCISES

### Exercise 1: Advanced State Management

**Goal:** Implement a sophisticated session analytics system using different state types.

**Step 1: Set Up Environment**
```bash
cd day4
docker-compose up -d
```

**Step 2: Implement Multi-State Session Processor**
```java
public class AdvancedSessionProcessor extends KeyedProcessFunction<String, UserEvent, SessionInsight> {
    
    // Different state types for different purposes
    private ValueState<SessionMetrics> metricsState;           // Basic session metrics
    private ListState<UserEvent> recentEventsState;           // Last 10 events
    private MapState<String, Integer> pageViewsState;         // Page -> count
    private ValueState<Long> sessionStartState;               // Session start time
    
    @Override
    public void open(Configuration parameters) {
        // Session metrics with TTL
        StateTtlConfig ttlConfig = StateTtlConfig
            .newBuilder(Time.hours(2))
            .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
            .cleanupFullSnapshot()
            .build();
            
        ValueStateDescriptor<SessionMetrics> metricsDescriptor = 
            new ValueStateDescriptor<>("session-metrics", SessionMetrics.class);
        metricsDescriptor.enableTimeToLive(ttlConfig);
        metricsState = getRuntimeContext().getState(metricsDescriptor);
        
        // Recent events list
        ListStateDescriptor<UserEvent> eventsDescriptor = 
            new ListStateDescriptor<>("recent-events", UserEvent.class);
        recentEventsState = getRuntimeContext().getListState(eventsDescriptor);
        
        // Page views map
        MapStateDescriptor<String, Integer> pageViewsDescriptor = 
            new MapStateDescriptor<>("page-views", String.class, Integer.class);
        pageViewsState = getRuntimeContext().getMapState(pageViewsDescriptor);
        
        // Session start time
        ValueStateDescriptor<Long> startDescriptor = 
            new ValueStateDescriptor<>("session-start", Long.class);
        sessionStartState = getRuntimeContext().getState(startDescriptor);
    }
    
    @Override
    public void processElement(UserEvent event, Context ctx, Collector<SessionInsight> out) throws Exception {
        // Update metrics
        SessionMetrics metrics = metricsState.value();
        if (metrics == null) {
            metrics = new SessionMetrics();
            sessionStartState.update(event.getTimestamp());
        }
        metrics.addEvent(event);
        metricsState.update(metrics);
        
        // Update recent events (keep last 10)
        recentEventsState.add(event);
        List<UserEvent> events = StreamSupport.stream(recentEventsState.get().spliterator(), false)
            .collect(Collectors.toList());
        if (events.size() > 10) {
            recentEventsState.clear();
            events.stream().skip(events.size() - 10).forEach(e -> {
                try { recentEventsState.add(e); } catch (Exception ex) { /* handle */ }
            });
        }
        
        // Update page views
        if (event.getEventType().equals("PAGE_VIEW")) {
            String page = event.getPage();
            Integer count = pageViewsState.get(page);
            pageViewsState.put(page, (count == null ? 0 : count) + 1);
        }
        
        // Generate insights
        SessionInsight insight = generateInsights(event, metrics, events);
        out.collect(insight);
        
        // Set session timeout timer
        ctx.timerService().registerEventTimeTimer(event.getTimestamp() + Time.minutes(30).toMilliseconds());
    }
    
    private SessionInsight generateInsights(UserEvent event, SessionMetrics metrics, List<UserEvent> recentEvents) {
        // Analyze patterns in recent events
        boolean isEngaged = metrics.getEventCount() > 10;
        boolean isConverting = recentEvents.stream().anyMatch(e -> e.getEventType().equals("PURCHASE"));
        
        return new SessionInsight(
            event.getUserId(),
            metrics.getEventCount(),
            metrics.getSessionDuration(),
            isEngaged,
            isConverting
        );
    }
}
```

### Exercise 2: Async I/O for Real-time Enrichment

**Goal:** Build a real-time user enrichment system using async database lookups.

**Step 1: Set Up External Systems**
```bash
# Start PostgreSQL and Redis
docker-compose up -d postgres redis

# Populate test data
docker exec -it postgres-advanced psql -U flink -d flink_state -c "
CREATE TABLE user_profiles (
    user_id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(100),
    tier VARCHAR(20),
    created_at TIMESTAMP
);

INSERT INTO user_profiles VALUES 
('user1', 'John Doe', 'gold', NOW()),
('user2', 'Jane Smith', 'silver', NOW()),
('user3', 'Bob Johnson', 'bronze', NOW());
"
```

**Step 2: Implement Async Enrichment**
```java
public class RealTimeEnrichmentJob {
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(10000);
        
        // Raw events
        DataStream<UserEvent> events = env.addSource(new UserEventSource());
        
        // Async enrichment with database lookup
        DataStream<EnrichedUserEvent> enrichedEvents = AsyncDataStream.unorderedWait(
            events,
            new AsyncDatabaseEnrichmentFunction(),
            5000, // 5 second timeout
            TimeUnit.MILLISECONDS,
            50    // Max 50 concurrent requests
        );
        
        // Further processing
        DataStream<UserInsight> insights = enrichedEvents
            .keyBy(EnrichedUserEvent::getUserId)
            .window(TumblingEventTimeWindows.of(Time.minutes(5)))
            .aggregate(new UserInsightAggregator());
        
        insights.addSink(new UserInsightSink());
        
        env.execute("Real-time User Enrichment");
    }
}

public class AsyncDatabaseEnrichmentFunction extends RichAsyncFunction<UserEvent, EnrichedUserEvent> {
    private AsyncDatabaseClient dbClient;
    private AsyncRedisClient redisClient;
    
    @Override
    public void open(Configuration parameters) throws Exception {
        // Initialize async database client
        dbClient = new AsyncDatabaseClient("postgresql://localhost:5432/flink_state");
        
        // Initialize Redis client for caching
        redisClient = new AsyncRedisClient("redis://localhost:6379");
    }
    
    @Override
    public void asyncInvoke(UserEvent event, ResultFuture<EnrichedUserEvent> resultFuture) throws Exception {
        String userId = event.getUserId();
        String cacheKey = "user:" + userId;
        
        // Try cache first
        redisClient.get(cacheKey)
            .thenCompose(cachedProfile -> {
                if (cachedProfile != null) {
                    // Cache hit
                    return CompletableFuture.completedFuture(UserProfile.fromJson(cachedProfile));
                } else {
                    // Cache miss - query database
                    return dbClient.getUserProfile(userId)
                        .thenCompose(profile -> {
                            // Cache the result
                            return redisClient.setex(cacheKey, 3600, profile.toJson())
                                .thenApply(ignored -> profile);
                        });
                }
            })
            .whenComplete((profile, throwable) -> {
                if (throwable == null) {
                    EnrichedUserEvent enriched = new EnrichedUserEvent(event, profile);
                    resultFuture.complete(Collections.singleton(enriched));
                } else {
                    // Fallback to default profile
                    EnrichedUserEvent enriched = new EnrichedUserEvent(event, UserProfile.getDefault());
                    resultFuture.complete(Collections.singleton(enriched));
                }
            });
    }
    
    @Override
    public void timeout(UserEvent input, ResultFuture<EnrichedUserEvent> resultFuture) throws Exception {
        // Handle timeout gracefully
        EnrichedUserEvent enriched = new EnrichedUserEvent(input, UserProfile.getDefault());
        resultFuture.complete(Collections.singleton(enriched));
    }
}
```

### Exercise 3: Broadcast State for Dynamic Rules

**Goal:** Implement a dynamic fraud detection system using broadcast state.

**Step 1: Set Up Rule Stream**
```bash
# Create topics for events and rules
docker exec -it kafka-advanced kafka-topics --create --topic user-transactions --bootstrap-server localhost:9092 --partitions 4 --replication-factor 1

docker exec -it kafka-advanced kafka-topics --create --topic fraud-rules --bootstrap-server localhost:9092 --partitions 1 --replication-factor 1
```

**Step 2: Implement Dynamic Fraud Detection**
```java
public class DynamicFraudDetectionJob {
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        
        // Transaction stream
        DataStream<Transaction> transactions = env
            .addSource(new KafkaSource<>("user-transactions"))
            .assignTimestampsAndWatermarks(WatermarkStrategy.forBoundedOutOfOrderness(Duration.ofSeconds(10)));
        
        // Rules stream (broadcast)
        DataStream<FraudRule> rules = env.addSource(new KafkaSource<>("fraud-rules"));
        
        // Broadcast state descriptor
        MapStateDescriptor<String, FraudRule> ruleStateDescriptor = new MapStateDescriptor<>(
            "FraudRules",
            BasicTypeInfo.STRING_TYPE_INFO,
            TypeInformation.of(FraudRule.class)
        );
        
        BroadcastStream<FraudRule> ruleBroadcastStream = rules.broadcast(ruleStateDescriptor);
        
        // Connect and process
        DataStream<FraudAlert> alerts = transactions
            .connect(ruleBroadcastStream)
            .process(new DynamicFraudDetector(ruleStateDescriptor));
        
        alerts.addSink(new AlertSink());
        
        env.execute("Dynamic Fraud Detection");
    }
}

public class DynamicFraudDetector extends BroadcastProcessFunction<Transaction, FraudRule, FraudAlert> {
    private final MapStateDescriptor<String, FraudRule> ruleStateDescriptor;
    private ValueState<TransactionHistory> historyState;
    
    public DynamicFraudDetector(MapStateDescriptor<String, FraudRule> ruleStateDescriptor) {
        this.ruleStateDescriptor = ruleStateDescriptor;
    }
    
    @Override
    public void open(Configuration parameters) {
        ValueStateDescriptor<TransactionHistory> historyDescriptor = 
            new ValueStateDescriptor<>("transaction-history", TransactionHistory.class);
        historyState = getRuntimeContext().getState(historyDescriptor);
    }
    
    @Override
    public void processElement(Transaction transaction, ReadOnlyContext ctx, Collector<FraudAlert> out) throws Exception {
        // Update transaction history
        TransactionHistory history = historyState.value();
        if (history == null) {
            history = new TransactionHistory();
        }
        history.addTransaction(transaction);
        historyState.update(history);
        
        // Apply all active rules
        ReadOnlyBroadcastState<String, FraudRule> broadcastState = ctx.getBroadcastState(ruleStateDescriptor);
        
        for (Map.Entry<String, FraudRule> entry : broadcastState.immutableEntries()) {
            FraudRule rule = entry.getValue();
            
            if (rule.evaluate(transaction, history)) {
                FraudAlert alert = new FraudAlert(
                    transaction.getUserId(),
                    rule.getRuleId(),
                    rule.getRiskLevel(),
                    "Rule triggered: " + rule.getDescription(),
                    transaction.getTimestamp()
                );
                out.collect(alert);
            }
        }
    }
    
    @Override
    public void processBroadcastElement(FraudRule rule, Context ctx, Collector<FraudAlert> out) throws Exception {
        // Update broadcast state with new/updated rule
        BroadcastState<String, FraudRule> broadcastState = ctx.getBroadcastState(ruleStateDescriptor);
        
        if (rule.isActive()) {
            broadcastState.put(rule.getRuleId(), rule);
            System.out.println("Added/Updated rule: " + rule.getRuleId());
        } else {
            broadcastState.remove(rule.getRuleId());
            System.out.println("Removed rule: " + rule.getRuleId());
        }
    }
}

// Example fraud rules
public class FraudRule {
    private String ruleId;
    private String description;
    private boolean active;
    private RiskLevel riskLevel;
    private Predicate<Transaction> transactionPredicate;
    private Predicate<TransactionHistory> historyPredicate;
    
    public boolean evaluate(Transaction transaction, TransactionHistory history) {
        return active && 
               (transactionPredicate == null || transactionPredicate.test(transaction)) &&
               (historyPredicate == null || historyPredicate.test(history));
    }
    
    // Static rule factory methods
    public static FraudRule highValueRule() {
        return new FraudRule(
            "HIGH_VALUE",
            "Transaction amount > $10,000",
            true,
            RiskLevel.HIGH,
            tx -> tx.getAmount() > 10000,
            null
        );
    }
    
    public static FraudRule velocityRule() {
        return new FraudRule(
            "VELOCITY",
            "More than 5 transactions in 10 minutes",
            true,
            RiskLevel.MEDIUM,
            null,
            history -> history.getTransactionCount(Duration.ofMinutes(10)) > 5
        );
    }
}
```

### Exercise 4: Savepoint Evolution Testing

**Goal:** Practice job evolution using savepoints.

**Step 1: Deploy Initial Version**
```bash
# Deploy version 1 of a job
docker exec -it flink-jobmanager-advanced flink run -d /opt/flink/jobs/EventProcessorV1.jar

# Let it run and process some data
# Check job ID
JOB_ID=$(docker exec -it flink-jobmanager-advanced flink list -r | grep EventProcessor | awk '{print $4}')
echo "Job ID: $JOB_ID"
```

**Step 2: Take Savepoint and Evolve**
```bash
# Take savepoint
SAVEPOINT_PATH=$(docker exec -it flink-jobmanager-advanced flink savepoint $JOB_ID /tmp/flink-savepoints/)
echo "Savepoint: $SAVEPOINT_PATH"

# Cancel current job
docker exec -it flink-jobmanager-advanced flink cancel $JOB_ID

# Deploy evolved version from savepoint
docker exec -it flink-jobmanager-advanced flink run -s $SAVEPOINT_PATH -d /opt/flink/jobs/EventProcessorV2.jar
```

**Step 3: Monitor Evolution**
```bash
# Check new job is running
docker exec -it flink-jobmanager-advanced flink list -r

# Monitor metrics to ensure continuity
curl http://localhost:8081/jobs/overview
```

---

## 📊 ADVANCED MONITORING AND OBSERVABILITY

### Custom Metrics Implementation

**Comprehensive Metrics Collection**
```java
public class MetricsCollectingProcessor extends KeyedProcessFunction<String, Event, ProcessedEvent> {
    
    // Different metric types
    private Counter eventsProcessed;
    private Gauge<Double> currentQueueSize;
    private Histogram processingLatency;
    private Meter eventsPerSecond;
    
    // Custom metrics
    private Counter errorCount;
    private Gauge<Integer> activeUsers;
    private Histogram eventSize;
    
    @Override
    public void open(Configuration parameters) {
        MetricGroup metricGroup = getRuntimeContext().getMetricGroup()
            .addGroup("custom")
            .addGroup("processor");
        
        // Standard metrics
        eventsProcessed = metricGroup.counter("events_processed");
        eventsPerSecond = metricGroup.meter("events_per_second", new MeterView(eventsProcessed, 60));
        
        processingLatency = metricGroup.histogram("processing_latency_ms", 
            new DescriptiveStatisticsHistogram(1000));
        
        currentQueueSize = metricGroup.gauge("queue_size", () -> getCurrentQueueSize());
        
        // Error tracking
        errorCount = metricGroup.counter("processing_errors");
        
        // Business metrics
        activeUsers = metricGroup.gauge("active_users", () -> getActiveUserCount());
        eventSize = metricGroup.histogram("event_size_bytes", 
            new DescriptiveStatisticsHistogram(1000));
    }
    
    @Override
    public void processElement(Event event, Context ctx, Collector<ProcessedEvent> out) throws Exception {
        long startTime = System.currentTimeMillis();
        
        try {
            // Track event size
            eventSize.update(event.getSerializedSize());
            
            // Process event
            ProcessedEvent result = processEvent(event);
            
            // Update success metrics
            eventsProcessed.inc();
            processingLatency.update(System.currentTimeMillis() - startTime);
            
            out.collect(result);
            
        } catch (Exception e) {
            errorCount.inc();
            throw e;
        }
    }
    
    private double getCurrentQueueSize() {
        // Implementation to get current queue size
        return 0.0; // Placeholder
    }
    
    private int getActiveUserCount() {
        // Implementation to count active users
        return 0; // Placeholder
    }
}
```

### Health Checks and Alerts

**Health Check Implementation**
```java
public class HealthCheckOperator extends AbstractStreamOperator<HealthStatus> 
    implements OneInputStreamOperator<Event, HealthStatus>, CheckpointedFunction {
    
    private transient ListState<HealthMetric> healthMetricsState;
    private transient List<HealthMetric> healthMetrics;
    
    private Counter healthCheckCount;
    private Gauge<Double> systemHealth;
    
    @Override
    public void open() throws Exception {
        super.open();
        
        healthCheckCount = getRuntimeContext().getMetricGroup().counter("health_checks");
        systemHealth = getRuntimeContext().getMetricGroup().gauge("system_health", 
            () -> calculateSystemHealth());
    }
    
    @Override
    public void processElement(StreamRecord<Event> element) throws Exception {
        Event event = element.getValue();
        
        // Perform various health checks
        HealthStatus status = performHealthChecks(event);
        
        healthMetrics.add(new HealthMetric(
            System.currentTimeMillis(),
            status.getOverallScore(),
            status.getComponentScores()
        ));
        
        // Keep only recent metrics (last 100)
        if (healthMetrics.size() > 100) {
            healthMetrics.remove(0);
        }
        
        healthCheckCount.inc();
        output.collect(new StreamRecord<>(status));
    }
    
    private HealthStatus performHealthChecks(Event event) {
        Map<String, Double> componentScores = new HashMap<>();
        
        // Latency check
        long currentTime = System.currentTimeMillis();
        long eventTime = event.getTimestamp();
        double latencyScore = calculateLatencyScore(currentTime - eventTime);
        componentScores.put("latency", latencyScore);
        
        // Throughput check
        double throughputScore = calculateThroughputScore();
        componentScores.put("throughput", throughputScore);
        
        // Error rate check
        double errorScore = calculateErrorRateScore();
        componentScores.put("error_rate", errorScore);
        
        // Memory usage check
        double memoryScore = calculateMemoryScore();
        componentScores.put("memory", memoryScore);
        
        double overallScore = componentScores.values().stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);
        
        return new HealthStatus(overallScore, componentScores);
    }
    
    private double calculateSystemHealth() {
        if (healthMetrics.isEmpty()) {
            return 1.0;
        }
        
        return healthMetrics.stream()
            .mapToDouble(HealthMetric::getOverallScore)
            .average()
            .orElse(0.0);
    }
}
```

---

## 📝 ADVANCED INTERVIEW QUESTIONS

### Expert Level (7+ years)

**Q1: Design a multi-tenant streaming platform using Flink that can handle 10,000+ jobs.**
**A:** Architecture considerations:
- **Resource Isolation**: Use resource quotas and separate TaskManager pools per tenant
- **State Isolation**: Tenant-specific state backends with proper security
- **Job Management**: Custom JobManager with tenant-aware scheduling
- **Monitoring**: Per-tenant metrics and alerting
- **Security**: Authentication, authorization, and network isolation
- **Cost Management**: Resource usage tracking and billing per tenant

**Q2: How would you implement exactly-once processing for a financial trading system?**
**A:** Implementation approach:
```java
// Transactional sink with exactly-once guarantees
TwoPhaseCommitSinkFunction<TradeEvent> tradeSink = new TwoPhaseCommitSinkFunction<TradeEvent>() {
    @Override
    protected void invoke(TxnContext transaction, TradeEvent trade, Context context) throws Exception {
        // Write to transaction log first
        transaction.writeToTransactionLog(trade);
    }
    
    @Override
    protected TxnContext beginTransaction() throws Exception {
        return new TradeTransaction();
    }
    
    @Override
    protected void preCommit(TxnContext transaction) throws Exception {
        // Prepare phase - validate all trades
        transaction.prepare();
    }
    
    @Override
    protected void commit(TxnContext transaction) {
        // Commit phase - finalize trades
        transaction.commit();
    }
    
    @Override
    protected void abort(TxnContext transaction) {
        // Rollback if needed
        transaction.rollback();
    }
};
```

**Q3: Implement a custom state backend optimized for time-series data.**
**A:** Design considerations:
- **Time-based Partitioning**: Partition state by time windows
- **Compression**: Use time-series specific compression algorithms
- **Retention**: Automatic cleanup of old data
- **Indexing**: Efficient time-range queries
```java
public class TimeSeriesStateBackend extends AbstractStateBackend {
    private final String basePath;
    private final CompressionType compression;
    
    @Override
    public <K> AbstractKeyedStateBackend<K> createKeyedStateBackend(
            Environment env,
            JobID jobID,
            String operatorIdentifier,
            TypeSerializer<K> keySerializer,
            int numberOfKeyGroups,
            KeyGroupRange keyGroupRange,
            TaskKvStateRegistry kvStateRegistry,
            TtlTimeProvider ttlTimeProvider,
            MetricGroup metricGroup,
            Collection<KeyedStateHandle> stateHandles,
            CloseableRegistry cancelStreamRegistry) throws IOException {
        
        return new TimeSeriesKeyedStateBackend<>(
            env.getJobID(),
            operatorIdentifier,
            keySerializer,
            numberOfKeyGroups,
            keyGroupRange,
            basePath,
            compression
        );
    }
}
```

**Q4: How would you handle schema evolution in a large-scale streaming system with hundreds of data sources?**
**A:** Schema management strategy:
- **Schema Registry**: Centralized schema management with versioning
- **Backward Compatibility**: Enforce compatibility rules
- **Migration Framework**: Automated schema migration pipelines
- **Multi-Version Support**: Support multiple schema versions simultaneously
- **Monitoring**: Track schema usage and compatibility violations

**Q5: Design a disaster recovery solution for a mission-critical streaming application.**
**A:** DR Architecture:
- **Multi-Region Setup**: Active-passive or active-active deployment
- **State Replication**: Cross-region state backup and synchronization
- **Data Replication**: Kafka MirrorMaker or similar for data replication
- **Automated Failover**: Health monitoring and automatic failover
- **Recovery Testing**: Regular DR drills and validation

### System Design Questions

**Q6: Design a real-time recommendation system serving 1 million requests per second.**
**A:** Architecture components:
```
Data Sources → Kafka → Flink (Feature Engineering) → Model Serving → Cache → API
                  ↓
              State Store (User/Item Features)
                  ↓
              Batch ML Pipeline (Model Training)
```
Key considerations:
- **Feature Store**: Real-time and batch feature computation
- **Model Serving**: A/B testing framework
- **Caching**: Multi-level caching strategy
- **Personalization**: User context and session management
- **Scalability**: Horizontal scaling and load balancing

**Q7: How would you implement a real-time data lineage tracking system?**
**A:** Implementation approach:
- **Metadata Collection**: Instrument operators to collect lineage info
- **Graph Storage**: Store lineage as a directed acyclic graph
- **Real-time Updates**: Stream lineage changes to consumers
- **Query Interface**: Support for lineage queries and visualization
- **Impact Analysis**: Track downstream effects of changes

---

## ✅ DAY 4 COMPLETION CHECKLIST

Before moving to Day 5, ensure you can:

**Advanced State Management:**
- [ ] Implement different state types (Value, List, Map)
- [ ] Configure and use state TTL
- [ ] Use broadcast state for dynamic configurations
- [ ] Implement queryable state for external access

**Custom Operators:**
- [ ] Build custom ProcessFunctions with timers
- [ ] Implement CoProcessFunction for dual streams
- [ ] Use async I/O for external enrichment
- [ ] Create custom windowing logic

**Savepoints and Evolution:**
- [ ] Take and restore savepoints
- [ ] Handle job evolution scenarios
- [ ] Implement backward-compatible schema changes
- [ ] Use UIDs for operator identification

**Production Patterns:**
- [ ] Implement comprehensive monitoring
- [ ] Add custom metrics and health checks
- [ ] Design fault-tolerant architectures
- [ ] Optimize for high-performance scenarios

**Practical Skills:**
- [ ] Deploy complex multi-component jobs
- [ ] Monitor and troubleshoot advanced scenarios
- [ ] Implement zero-downtime deployments
- [ ] Handle production-scale state management

---

## 🚀 WHAT'S NEXT?

Tomorrow (Day 5), you'll master Flink-Kafka integration:
- Advanced Flink-Kafka connector patterns
- Complex windowing strategies
- End-to-end exactly-once processing
- Performance optimization for high-throughput
- Stream-batch unified processing

**Preparation:**
- Review today's advanced patterns
- Practice with savepoint operations
- Experiment with different state backends
- Think about production deployment strategies

## 📚 Additional Resources

**Advanced Flink:**
- [Flink State Management](https://flink.apache.org/docs/dev/stream/state/)
- [Flink Async I/O](https://flink.apache.org/docs/dev/stream/operators/asyncio.html)

**Production Deployment:**
- [Flink Operations Guide](https://flink.apache.org/docs/ops/)
- [Kubernetes Operator](https://flink.apache.org/docs/deployment/resource-providers/native_kubernetes.html)

---

Excellent progress on Day 4! You now understand advanced Flink patterns and can build enterprise-grade streaming applications. Tomorrow we'll integrate everything with Kafka for powerful end-to-end streaming solutions.