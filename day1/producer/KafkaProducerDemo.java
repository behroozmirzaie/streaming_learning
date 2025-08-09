import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import java.util.*;
import java.util.concurrent.ExecutionException;

public class KafkaProducerDemo {
    private static final String TOPIC_NAME = System.getenv().getOrDefault("TOPIC_NAME", "user-events");
    private static final String BOOTSTRAP_SERVERS = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        
        // Best practice configurations
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 1);
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        Producer<String, String> producer = new KafkaProducer<>(props);

        String[] eventTypes = {"login", "logout", "purchase", "view_product", "add_to_cart"};
        String[] userIds = {"user1", "user2", "user3", "user4", "user5"};

        try {
            for (int i = 0; i < 1000; i++) {
                String userId = userIds[i % userIds.length];
                String eventType = eventTypes[new Random().nextInt(eventTypes.length)];
                String value = String.format("{\"userId\":\"%s\",\"eventType\":\"%s\",\"timestamp\":%d,\"sessionId\":\"session_%d\"}", 
                    userId, eventType, System.currentTimeMillis(), i);

                ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC_NAME, userId, value);
                
                // Asynchronous send with callback
                producer.send(record, new Callback() {
                    @Override
                    public void onCompletion(RecordMetadata metadata, Exception exception) {
                        if (exception == null) {
                            System.out.printf("Sent message: topic=%s, partition=%d, offset=%d, key=%s%n",
                                metadata.topic(), metadata.partition(), metadata.offset(), record.key());
                        } else {
                            System.err.println("Error sending message: " + exception.getMessage());
                        }
                    }
                });

                Thread.sleep(100); // Simulate realistic timing
            }
        } finally {
            producer.flush();
            producer.close();
            System.out.println("Producer finished sending messages");
        }
    }
}