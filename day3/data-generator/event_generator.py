#!/usr/bin/env python3
import json
import time
import random
from datetime import datetime, timedelta
from kafka import KafkaProducer
from faker import Faker
import os

fake = Faker()

def create_producer():
    return KafkaProducer(
        bootstrap_servers=os.getenv('KAFKA_BOOTSTRAP_SERVERS', 'localhost:9092'),
        value_serializer=lambda v: json.dumps(v).encode('utf-8'),
        key_serializer=lambda k: k.encode('utf-8') if k else None
    )

def generate_user_event():
    """Generate realistic user behavior events"""
    base_time = datetime.now()
    
    # Sometimes generate out-of-order events
    if random.random() < 0.1:
        event_time = base_time - timedelta(seconds=random.randint(1, 300))
    else:
        event_time = base_time - timedelta(seconds=random.randint(0, 5))
    
    event_types = ['page_view', 'click', 'purchase', 'login', 'logout', 'add_to_cart']
    
    return {
        'user_id': f'user_{random.randint(1, 1000)}',
        'session_id': f'session_{random.randint(1, 5000)}',
        'event_type': random.choice(event_types),
        'event_time': event_time.isoformat(),
        'processing_time': base_time.isoformat(),
        'page_url': fake.url(),
        'user_agent': fake.user_agent(),
        'ip_address': fake.ipv4(),
        'product_id': f'product_{random.randint(1, 500)}' if random.random() > 0.3 else None,
        'value': round(random.uniform(10.0, 500.0), 2) if random.random() > 0.7 else None
    }

def generate_sensor_reading():
    """Generate IoT sensor data"""
    base_time = datetime.now()
    
    # Simulate sensor delay with occasional out-of-order readings
    if random.random() < 0.15:
        event_time = base_time - timedelta(seconds=random.randint(1, 60))
    else:
        event_time = base_time
    
    sensor_types = ['temperature', 'humidity', 'pressure', 'vibration']
    device_id = f'device_{random.randint(1, 100)}'
    sensor_type = random.choice(sensor_types)
    
    # Generate realistic sensor values based on type
    if sensor_type == 'temperature':
        value = round(random.uniform(18.0, 35.0), 2)
    elif sensor_type == 'humidity':
        value = round(random.uniform(30.0, 80.0), 2)
    elif sensor_type == 'pressure':
        value = round(random.uniform(980.0, 1050.0), 2)
    else:  # vibration
        value = round(random.uniform(0.0, 10.0), 2)
    
    return {
        'device_id': device_id,
        'sensor_type': sensor_type,
        'value': value,
        'unit': {'temperature': 'C', 'humidity': '%', 'pressure': 'hPa', 'vibration': 'g'}[sensor_type],
        'event_time': event_time.isoformat(),
        'processing_time': base_time.isoformat(),
        'location': {
            'building': f'building_{random.randint(1, 10)}',
            'floor': random.randint(1, 20),
            'room': f'room_{random.randint(1, 100)}'
        }
    }

def main():
    producer = create_producer()
    
    print("Starting event generation...")
    print(f"Kafka servers: {os.getenv('KAFKA_BOOTSTRAP_SERVERS', 'localhost:9092')}")
    
    try:
        while True:
            # Generate user events
            for _ in range(random.randint(1, 5)):
                event = generate_user_event()
                producer.send('user-events', key=event['user_id'], value=event)
            
            # Generate sensor readings
            for _ in range(random.randint(10, 30)):
                reading = generate_sensor_reading()
                producer.send('sensor-readings', key=reading['device_id'], value=reading)
            
            # Generate some text for word count demo
            if random.random() < 0.3:
                words = fake.sentence(nb_words=random.randint(5, 15))
                producer.send('text-input', value={'text': words, 'timestamp': datetime.now().isoformat()})
            
            producer.flush()
            time.sleep(random.uniform(0.1, 1.0))  # Variable delay
            
    except KeyboardInterrupt:
        print("Stopping event generation...")
    finally:
        producer.close()

if __name__ == "__main__":
    main()