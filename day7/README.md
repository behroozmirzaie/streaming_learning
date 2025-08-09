# Day 7: Production Patterns and Monitoring

## Learning Objectives
- Implement comprehensive monitoring and observability
- Design deployment strategies for streaming applications
- Master disaster recovery and backup procedures
- Optimize performance for production workloads
- Implement cost management and resource optimization
- Build operational runbooks and incident response

## Key Concepts

### 1. Observability Stack
- **Metrics**: Quantitative measurements of system behavior
- **Logs**: Detailed event records for debugging
- **Traces**: Request flow through distributed systems
- **Dashboards**: Visual representation of system health
- **Alerting**: Automated notifications for anomalies

### 2. Production Deployment Patterns
- **Blue-Green Deployment**: Zero-downtime deployments
- **Canary Releases**: Gradual rollout to subset of traffic
- **Rolling Updates**: Sequential instance replacement
- **Circuit Breakers**: Failure isolation mechanisms
- **Feature Flags**: Runtime behavior control

### 3. Disaster Recovery
- **RTO (Recovery Time Objective)**: Maximum acceptable downtime
- **RPO (Recovery Point Objective)**: Maximum acceptable data loss
- **Backup Strategies**: Data and configuration preservation
- **Multi-Region Setup**: Geographic redundancy
- **Chaos Engineering**: Proactive failure testing

### 4. Performance Optimization
- **Capacity Planning**: Resource requirement forecasting
- **Load Testing**: System behavior under stress
- **Bottleneck Identification**: Performance constraint analysis
- **Scaling Strategies**: Horizontal and vertical scaling
- **Resource Optimization**: Cost-effective resource allocation

## Training Modules

### Module 1: Comprehensive Monitoring Setup (60 minutes)

Prometheus Configuration:
```yaml
# prometheus.yml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

rule_files:
  - "alert-rules.yml"

alerting:
  alertmanagers:
    - static_configs:
        - targets:
          - alertmanager:9093

scrape_configs:
  - job_name: 'kafka'
    static_configs:
      - targets: ['kafka-exporter:9308']
  
  - job_name: 'flink'
    static_configs:
      - targets: ['flink-jobmanager:9249', 'flink-taskmanager:9249']
  
  - job_name: 'jmx-kafka'
    static_configs:
      - targets: ['kafka-1:19092', 'kafka-2:19093', 'kafka-3:19094']
    scrape_interval: 10s
    metrics_path: /metrics
```

Alert Rules:
```yaml
# alert-rules.yml
groups:
  - name: kafka.rules
    rules:
      - alert: KafkaDown
        expr: up{job="kafka"} == 0
        for: 30s
        labels:
          severity: critical
        annotations:
          summary: "Kafka broker is down"
          description: "Kafka broker {{ $labels.instance }} has been down for more than 30 seconds"

      - alert: KafkaConsumerLag
        expr: kafka_consumer_lag_sum > 1000
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "High consumer lag detected"
          description: "Consumer group {{ $labels.group }} has lag of {{ $value }} messages"

  - name: flink.rules
    rules:
      - alert: FlinkJobDown
        expr: flink_jobmanager_job_uptime == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Flink job is down"
          description: "Flink job {{ $labels.job_name }} is not running"

      - alert: FlinkHighCheckpointDuration
        expr: flink_jobmanager_job_lastCheckpointDuration > 300000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Flink checkpoint duration is high"
          description: "Checkpoint duration is {{ $value }}ms for job {{ $labels.job_name }}"

      - alert: FlinkBackpressure
        expr: flink_taskmanager_job_task_backPressuredTimeMsPerSecond > 0
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "Flink backpressure detected"
          description: "Backpressure detected in task {{ $labels.task_name }}"
```

### Module 2: Application Performance Monitoring (45 minutes)

Custom Metrics Collection:
```java
// Flink job with custom metrics
public class MonitoredStreamingJob {
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        
        DataStream<Event> events = env.addSource(new EventSource())
            .map(new EnrichmentFunction())
            .name("event-enrichment");
            
        // Add custom metrics
        events.map(new MapFunction<Event, ProcessedEvent>() {
            private Counter eventsProcessed;
            private Histogram processingTime;
            
            @Override
            public void open(Configuration parameters) {
                this.eventsProcessed = getRuntimeContext()
                    .getMetricGroup()
                    .counter("events_processed_total");
                    
                this.processingTime = getRuntimeContext()
                    .getMetricGroup()
                    .histogram("processing_time_ms", new DescriptiveStatisticsHistogram(1000));
            }
            
            @Override
            public ProcessedEvent map(Event event) {
                long startTime = System.currentTimeMillis();
                
                ProcessedEvent result = processEvent(event);
                
                eventsProcessed.inc();
                processingTime.update(System.currentTimeMillis() - startTime);
                
                return result;
            }
        });
    }
}
```

Distributed Tracing:
```java
// OpenTracing integration
@Component
public class TracedEventProcessor {
    
    @Autowired
    private Tracer tracer;
    
    public ProcessedEvent processEvent(Event event) {
        Span span = tracer.nextSpan()
            .name("event-processing")
            .tag("event.type", event.getType())
            .tag("event.user_id", event.getUserId())
            .start();
        
        try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
            // Event processing logic
            ProcessedEvent result = performProcessing(event);
            
            span.tag("processing.duration_ms", getDuration());
            span.tag("processing.status", "success");
            
            return result;
        } catch (Exception e) {
            span.tag("error", true);
            span.tag("error.message", e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }
}
```

### Module 3: Deployment Strategies (60 minutes)

Blue-Green Deployment:
```yaml
# Blue-Green deployment for Flink jobs
apiVersion: v1
kind: ConfigMap
metadata:
  name: flink-deployment-config
data:
  deploy.sh: |
    #!/bin/bash
    set -e
    
    SAVEPOINT_PATH=""
    NEW_JAR_PATH=$1
    CURRENT_COLOR=$(kubectl get deployment flink-app -o jsonpath='{.metadata.labels.color}')
    
    if [ "$CURRENT_COLOR" = "blue" ]; then
        NEW_COLOR="green"
    else
        NEW_COLOR="blue"
    fi
    
    echo "Current deployment: $CURRENT_COLOR, deploying to: $NEW_COLOR"
    
    # Take savepoint from current deployment
    if [ "$CURRENT_COLOR" != "" ]; then
        SAVEPOINT_PATH=$(./take-savepoint.sh $CURRENT_COLOR)
        echo "Savepoint created: $SAVEPOINT_PATH"
    fi
    
    # Deploy new version
    ./deploy-version.sh $NEW_COLOR $NEW_JAR_PATH $SAVEPOINT_PATH
    
    # Health check
    if ./health-check.sh $NEW_COLOR; then
        echo "New deployment healthy, switching traffic"
        ./switch-traffic.sh $NEW_COLOR
        ./cleanup-old-deployment.sh $CURRENT_COLOR
    else
        echo "Health check failed, rolling back"
        ./cleanup-failed-deployment.sh $NEW_COLOR
        exit 1
    fi
```

Canary Deployment:
```python
# Canary deployment controller
class CanaryDeployment:
    def __init__(self, app_name, new_version):
        self.app_name = app_name
        self.new_version = new_version
        self.canary_percentage = 5
        self.metrics_client = MetricsClient()
        
    def deploy_canary(self):
        """Deploy canary version with small traffic percentage"""
        self.create_canary_deployment()
        self.route_traffic_to_canary(self.canary_percentage)
        
        # Monitor for 10 minutes
        if self.monitor_canary_health(duration_minutes=10):
            self.promote_canary()
        else:
            self.rollback_canary()
    
    def monitor_canary_health(self, duration_minutes):
        """Monitor canary metrics and compare with production"""
        start_time = time.time()
        end_time = start_time + (duration_minutes * 60)
        
        while time.time() < end_time:
            canary_metrics = self.get_canary_metrics()
            production_metrics = self.get_production_metrics()
            
            # Check error rates
            if canary_metrics.error_rate > production_metrics.error_rate * 1.5:
                self.logger.error("Canary error rate too high")
                return False
                
            # Check latency
            if canary_metrics.p99_latency > production_metrics.p99_latency * 1.2:
                self.logger.error("Canary latency too high")
                return False
                
            time.sleep(30)  # Check every 30 seconds
        
        return True
    
    def promote_canary(self):
        """Gradually promote canary to full production"""
        percentages = [10, 25, 50, 75, 100]
        
        for percentage in percentages:
            self.route_traffic_to_canary(percentage)
            time.sleep(300)  # Wait 5 minutes between steps
            
            if not self.check_health():
                self.rollback_canary()
                return False
        
        self.cleanup_old_version()
        return True
```

### Module 4: Disaster Recovery (45 minutes)

Multi-Region Setup:
```yaml
# Disaster recovery configuration
disaster_recovery:
  primary_region: us-east-1
  secondary_region: us-west-2
  
  kafka_replication:
    tool: MirrorMaker2
    topics:
      - user-events
      - processed-events
    replication_factor: 3
    
  flink_recovery:
    savepoint_storage: s3://flink-savepoints-backup
    state_backend: rocksdb
    checkpoint_interval: 30s
    
  data_backup:
    frequency: hourly
    retention: 30d
    destinations:
      - s3://backup-primary
      - s3://backup-secondary
```

Backup and Recovery Procedures:
```bash
#!/bin/bash
# backup-streaming-infrastructure.sh

set -e

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="/backups/$TIMESTAMP"

echo "Starting backup at $TIMESTAMP"

# Backup Kafka topics
echo "Backing up Kafka topics..."
for topic in $(kafka-topics --bootstrap-server localhost:9092 --list); do
    kafka-console-consumer \
        --bootstrap-server localhost:9092 \
        --topic $topic \
        --from-beginning \
        --timeout-ms 10000 > "$BACKUP_DIR/kafka_$topic.json" || true
done

# Backup Flink savepoints
echo "Creating Flink savepoints..."
for job_id in $(flink list -r | grep RUNNING | awk '{print $4}'); do
    savepoint_path=$(flink savepoint $job_id s3://savepoints-backup/$TIMESTAMP/)
    echo "Savepoint created: $savepoint_path"
done

# Backup configurations
echo "Backing up configurations..."
kubectl get configmaps -o yaml > "$BACKUP_DIR/configmaps.yaml"
kubectl get secrets -o yaml > "$BACKUP_DIR/secrets.yaml"

# Backup monitoring data
echo "Backing up Prometheus data..."
curl -X POST http://prometheus:9090/api/v1/admin/tsdb/snapshot
cp -r /prometheus/snapshots/latest/* "$BACKUP_DIR/prometheus/"

echo "Backup completed: $BACKUP_DIR"
```

## Hands-on Exercises

### Exercise 1: Complete Monitoring Setup
```bash
# Start production monitoring stack
docker-compose up -d prometheus grafana alertmanager

# Deploy applications with monitoring
docker-compose up -d kafka-1 kafka-2 kafka-3 flink-jobmanager flink-taskmanager

# Access dashboards
# Prometheus: http://localhost:9090
# Grafana: http://localhost:3000 (admin/admin)
# Alertmanager: http://localhost:9093
```

### Exercise 2: Load Testing and Performance Tuning
```bash
# Start load generator
docker-compose up load-generator

# Monitor performance in Grafana
# Identify bottlenecks using metrics
# Tune configuration based on observations
```

### Exercise 3: Disaster Recovery Simulation
```bash
# Simulate broker failure
docker stop kafka-2-prod

# Observe failover behavior
# Test data consistency
# Practice recovery procedures
```

## Best Practices

### Monitoring Best Practices
1. **Implement the Four Golden Signals**: Latency, traffic, errors, saturation
2. **Set meaningful SLIs/SLOs**: Based on user experience
3. **Use multi-level alerting**: Different severities for different impacts
4. **Monitor business metrics**: Not just technical metrics
5. **Implement runbook automation**: Reduce mean time to recovery

### Deployment Best Practices
1. **Automate everything**: Reduce human errors
2. **Test in production-like environments**: Catch issues early
3. **Use gradual rollouts**: Minimize blast radius
4. **Implement proper rollback**: Quick recovery mechanisms
5. **Monitor deployments**: Automated health checks

### Disaster Recovery Best Practices
1. **Regular DR drills**: Practice makes perfect
2. **Document procedures**: Clear, up-to-date runbooks
3. **Automate recovery**: Reduce recovery time
4. **Test backups**: Ensure they work when needed
5. **Cross-region redundancy**: Geographic distribution

## Real-World Production Patterns

### 1. Event-Driven Microservices
```yaml
Architecture:
  Services:
    - UserService: Manages user data and authentication
    - OrderService: Handles order processing
    - InventoryService: Manages product inventory
    - NotificationService: Sends user notifications
    
  Communication:
    - Event Streaming: Kafka for service communication
    - API Gateway: Kong or AWS API Gateway
    - Service Discovery: Consul or AWS Cloud Map
    
  Monitoring:
    - Distributed Tracing: Jaeger or AWS X-Ray
    - Metrics: Prometheus + Grafana
    - Logging: ELK Stack or AWS CloudWatch
    - APM: New Relic or DataDog
```

### 2. Real-time ML Pipeline
```yaml
Pipeline:
  Data Ingestion:
    - Kafka: Real-time event streams
    - Kinesis: AWS-native streaming
    - Pub/Sub: Google Cloud messaging
    
  Feature Engineering:
    - Flink: Real-time feature computation
    - Spark Streaming: Micro-batch processing
    - Custom operators: Domain-specific logic
    
  Model Serving:
    - KServe: Kubernetes-native serving
    - AWS SageMaker: Managed inference
    - TensorFlow Serving: High-performance serving
    
  Feedback Loop:
    - A/B Testing: Feature flag systems
    - Model Performance: Continuous monitoring
    - Data Drift Detection: Statistical monitoring
```

### 3. IoT Data Platform
```yaml
Architecture:
  Edge Layer:
    - Edge Computing: Process data locally
    - Protocol Gateways: MQTT, CoAP, LoRaWAN
    - Data Compression: Reduce transmission costs
    
  Streaming Layer:
    - Kafka: High-throughput ingestion
    - Flink: Real-time analytics
    - Time-series DB: InfluxDB, TimescaleDB
    
  Storage Layer:
    - Hot Storage: Recent data for real-time queries
    - Warm Storage: Compressed data for analytics
    - Cold Storage: Archive for compliance
    
  Analytics Layer:
    - Stream Processing: Real-time insights
    - Batch Processing: Historical analysis
    - Machine Learning: Predictive analytics
```

## Interview Questions

### Senior Engineer Level
1. **How would you design a monitoring strategy for a multi-region streaming platform?**
   - Cross-region metrics aggregation, alert routing, dashboard federation

2. **Describe your approach to zero-downtime deployments for stateful streaming applications.**
   - Savepoints, state migration, canary deployments, rollback strategies

3. **How would you handle backpressure in a high-throughput streaming system?**
   - Flow control, buffering strategies, circuit breakers, load shedding

4. **Design a disaster recovery plan for a financial trading system.**
   - RTO/RPO requirements, data consistency, failover procedures, testing

### Principal/Staff Level
1. **Design a cost-optimized streaming architecture for a startup scaling to enterprise.**
   - Technology selection, scaling strategies, operational efficiency

2. **How would you implement exactly-once processing across multiple data centers?**
   - Consensus protocols, distributed transactions, conflict resolution

3. **Design an observability strategy for a streaming platform serving 1000+ teams.**
   - Multi-tenancy, self-service monitoring, cost allocation, governance

4. **Implement a chaos engineering program for streaming infrastructure.**
   - Failure injection, blast radius control, learning from failures

## Production Checklist

### Pre-Production Checklist
```yaml
Infrastructure:
  ✓ High availability setup (multi-AZ/region)
  ✓ Security configuration (encryption, authentication)
  ✓ Network configuration (VPC, security groups)
  ✓ Monitoring and alerting setup
  ✓ Backup and disaster recovery procedures
  ✓ Load testing completed
  ✓ Capacity planning validated
  ✓ Documentation updated

Application:
  ✓ Error handling and retry logic
  ✓ Graceful shutdown procedures
  ✓ Health check endpoints
  ✓ Configuration externalized
  ✓ Logging and metrics instrumentation
  ✓ Security scanning completed
  ✓ Performance testing passed
  ✓ Integration tests passing

Operational:
  ✓ Runbooks documented
  ✓ On-call procedures defined
  ✓ Incident response plan
  ✓ Change management process
  ✓ Deployment procedures automated
  ✓ Rollback procedures tested
  ✓ Team training completed
  ✓ Support documentation available
```

### Production Readiness Review
```yaml
Review Areas:
  Reliability:
    - Fault tolerance mechanisms
    - Recovery procedures
    - Data consistency guarantees
    
  Scalability:
    - Horizontal scaling capabilities
    - Performance under load
    - Resource utilization efficiency
    
  Security:
    - Authentication and authorization
    - Data encryption
    - Network security
    - Vulnerability assessment
    
  Observability:
    - Metrics coverage
    - Log aggregation
    - Distributed tracing
    - Alerting effectiveness
    
  Operational:
    - Automation level
    - Documentation quality
    - Team readiness
    - Support processes
```

## Cost Optimization Strategies

### Resource Optimization
```python
# Automated resource scaling based on metrics
class AutoScaler:
    def __init__(self, metrics_client, scaling_client):
        self.metrics = metrics_client
        self.scaling = scaling_client
        
    def optimize_flink_cluster(self):
        """Scale Flink cluster based on workload"""
        current_utilization = self.metrics.get_cpu_utilization('flink-taskmanager')
        current_parallelism = self.scaling.get_current_parallelism()
        
        if current_utilization > 80:
            # Scale up
            new_parallelism = int(current_parallelism * 1.5)
            self.scaling.update_parallelism(new_parallelism)
        elif current_utilization < 30:
            # Scale down
            new_parallelism = max(1, int(current_parallelism * 0.7))
            self.scaling.update_parallelism(new_parallelism)
            
    def optimize_kafka_retention(self):
        """Adjust retention based on storage costs"""
        for topic in self.metrics.get_kafka_topics():
            storage_cost = self.calculate_storage_cost(topic)
            access_pattern = self.analyze_access_pattern(topic)
            
            if storage_cost > threshold and access_pattern == 'infrequent':
                # Reduce retention or move to cheaper storage
                self.scaling.update_retention(topic, reduced_retention)
```

### Cost Monitoring
```sql
-- Cost tracking queries for streaming infrastructure
SELECT 
    service_name,
    resource_type,
    DATE_TRUNC('day', timestamp) as date,
    SUM(cost_usd) as daily_cost,
    AVG(utilization_percent) as avg_utilization
FROM cost_metrics 
WHERE timestamp >= NOW() - INTERVAL '30 days'
GROUP BY service_name, resource_type, DATE_TRUNC('day', timestamp)
ORDER BY date DESC;

-- Identify cost optimization opportunities
SELECT 
    resource_id,
    resource_type,
    cost_per_hour,
    avg_utilization,
    CASE 
        WHEN avg_utilization < 20 THEN 'Underutilized'
        WHEN avg_utilization > 80 THEN 'Overutilized' 
        ELSE 'Optimized'
    END as optimization_opportunity
FROM resource_utilization 
WHERE timestamp >= NOW() - INTERVAL '7 days';
```

<function_calls>
<invoke name="TodoWrite">
<parameter name="todos">[{"id": "1", "content": "Create overall project structure with 7 day folders", "status": "completed"}, {"id": "2", "content": "Day 1: Kafka fundamentals setup and training", "status": "completed"}, {"id": "3", "content": "Day 2: Advanced Kafka patterns and operations", "status": "completed"}, {"id": "4", "content": "Day 3: Apache Flink basics and stream processing", "status": "completed"}, {"id": "5", "content": "Day 4: Flink advanced features and state management", "status": "completed"}, {"id": "6", "content": "Day 5: Flink-Kafka integration and windowing", "status": "completed"}, {"id": "7", "content": "Day 6: AWS streaming services (Kinesis, MSK, KDA)", "status": "completed"}, {"id": "8", "content": "Day 7: Production patterns and monitoring", "status": "completed"}]