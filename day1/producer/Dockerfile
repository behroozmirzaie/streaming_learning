FROM openjdk:11-jre-slim

WORKDIR /app

COPY . .

RUN apt-get update && apt-get install -y curl && \
    curl -O https://archive.apache.org/dist/kafka/2.8.0/kafka_2.13-2.8.0.tgz && \
    tar -xzf kafka_2.13-2.8.0.tgz && \
    mv kafka_2.13-2.8.0 kafka && \
    rm kafka_2.13-2.8.0.tgz

CMD ["java", "-cp", "kafka/libs/*:.", "KafkaProducerDemo"]