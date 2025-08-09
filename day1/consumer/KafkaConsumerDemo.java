import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import java.time.Duration;
import java.util.*;

public class KafkaConsumerDemo {
    private static final String TOPIC_NAME = System.getenv().getOrDefault("TOPIC_NAME", "user-events");
    private static final String BOOTSTRAP_SERVERS = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
    private static final String GROUP_ID = System.getenv().getOrDefault("GROUP_ID", "demo-consumer-group");

    public static void main(String[] args) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        
        // Best practice configurations
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 3000);

        Consumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(TOPIC_NAME));

        Map<String, Integer> eventCounts = new HashMap<>();
        int totalMessages = 0;

        try {
            while (true) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                
                for (ConsumerRecord<String, String> record : records) {
                    totalMessages++;
                    String key = record.key();
                    
                    eventCounts.put(key, eventCounts.getOrDefault(key, 0) + 1);
                    
                    System.out.printf("Consumed message: key=%s, value=%s, topic=%s, partition=%d, offset=%d%n",
                        record.key(), record.value(), record.topic(), record.partition(), record.offset());
                    
                    // Simulate message processing
                    Thread.sleep(10);
                }
                
                if (!records.isEmpty()) {
                    // Manual commit for better control
                    consumer.commitSync();
                    System.out.printf("Committed offsets. Total messages processed: %d%n", totalMessages);
                    System.out.println("Event counts by user: " + eventCounts);
                }
            }
        } catch (Exception e) {
            System.err.println("Error in consumer: " + e.getMessage());
        } finally {
            consumer.close();
            System.out.println("Consumer closed");
        }
    }
}