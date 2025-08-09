import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Produced;

import java.util.Arrays;
import java.util.Properties;

public class WordCountStreamsApp {
    private static final String INPUT_TOPIC = "text-input";
    private static final String OUTPUT_TOPIC = "word-counts";
    private static final String BOOTSTRAP_SERVERS = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
    private static final String APPLICATION_ID = System.getenv().getOrDefault("APPLICATION_ID", "streams-wordcount");

    public static void main(String[] args) {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, APPLICATION_ID);
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        
        // Advanced configurations
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.EXACTLY_ONCE);
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000);
        props.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 0); // Disable caching for demo

        StreamsBuilder builder = new StreamsBuilder();

        // Create the processing topology
        KStream<String, String> textLines = builder.stream(INPUT_TOPIC);
        
        KTable<String, Long> wordCounts = textLines
            // Split each text line into words
            .flatMapValues(textLine -> Arrays.asList(textLine.toLowerCase().split("\\W+")))
            // Filter out empty strings
            .filter((key, word) -> !word.isEmpty())
            // Group by word (key becomes the word)
            .groupBy((key, word) -> word)
            // Count occurrences of each word
            .count();

        // Convert KTable to KStream and write to output topic
        wordCounts.toStream().to(OUTPUT_TOPIC, Produced.with(Serdes.String(), Serdes.Long()));

        // Print the topology for debugging
        System.out.println("Topology: " + builder.build().describe());

        KafkaStreams streams = new KafkaStreams(builder.build(), props);
        
        // Add shutdown hook for graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
        
        try {
            streams.start();
            System.out.println("WordCount Streams application started");
            System.out.println("Processing topology: " + builder.build().describe());
            
            // Keep the application running
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            System.err.println("Application interrupted: " + e.getMessage());
        } finally {
            streams.close();
            System.out.println("WordCount Streams application stopped");
        }
    }
}