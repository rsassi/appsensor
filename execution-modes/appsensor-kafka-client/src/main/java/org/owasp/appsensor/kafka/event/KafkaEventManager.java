package org.owasp.appsensor.kafka.event;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;

import kafka.consumer.Consumer;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.ConsumerIterator;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;
//import kafka.message.MessageAndMetadata;


import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.owasp.appsensor.core.Attack;
import org.owasp.appsensor.core.ClientApplication;
import org.owasp.appsensor.core.Event;
import org.owasp.appsensor.core.KeyValuePair;
import org.owasp.appsensor.core.RequestHandler;
import org.owasp.appsensor.core.Response;
import org.owasp.appsensor.core.event.EventManager;
import org.owasp.appsensor.core.util.DateUtils;
import org.owasp.appsensor.kafka.KafkaConfig;
import org.owasp.appsensor.kafka.KafkaConfig.KafkaConfigBuilder;
import org.owasp.appsensor.kafka.KafkaConstants;
import org.owasp.appsensor.kafka.util.KafkaSender;
import org.owasp.appsensor.kafka.util.KafkaUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import com.google.gson.Gson;

/**
 * <p>This is the kafka component that handles requests on the client-side.</p>
 * 
 * <p>This class has 2 primary responsibilities</p>
 * <ul>
 *   <li>Forward {@link Event}s and {@link Attack}s from {@link ClientApplication}s to 
 * 	 the Kafka topic for the server-side {@link RequestHandler} to pick them up.</li>
 *   <li>Poll the Kafka topic specific to this {@link ClientApplication} and 
 *   store the messages locally for access.</li>
 * </ul>
 * 
 * <p>The "add event" topic is called "appsensor-add-event-topic".</p>
 * <p>The "add attack" topic is called "appsensor-add-attack-topic".</p>
 * <p>The response topics are client application specific and follow the naming convention 
 *    of "appsensor-" + "client application name" + "-response-topic". If a client application is 
 *    named "myapp", then the queue name would be "appsensor-myapp-response-topic"</p>
 * 
 * <p>Note: This class requires a few settings to run properly. These can be set as either
 *    environment variables ('export my_var="some_value"') or environment 
 *    properties ('-Dmy_var=some_value') set at the JVM</p>
 * <ul>
 *   <li><em>APPSENSOR_CLIENT_APPLICATION_NAME</em> - the name used for this client application, e.g. "my-app"</li>
 *   <li><em>APPSENSOR_KAFKA_CONSUMER_GROUP_ID</em> - A string that uniquely identifies the group of consumer processes 
 *   												  to which this consumer belongs. By setting the same group id multiple processes 
 *   											      indicate that they are all part of the same consumer group, e.g. "my-consumer-group</li>
 *   <li><em>APPSENSOR_KAFKA_CONSUMER_ZOOKEEPER_CONNECT</em> - zookeeper connect string, e.g. "hostname1:port1,hostname2:port2,hostname3:port3"</li>
 *   <li><em>APPSENSOR_KAFKA_PRODUCER_BOOTSTRAP_SERVERS</em> - A list of host/port pairs to use for establishing the initial connection to 
 *   															the Kafka cluster, e.g. "host1:port1,host2:port2"</li>
 *   <li><em>APPSENSOR_KAFKA_PRODUCER_PARTITION</em> - (OPTIONAL, MUST be integer if set) The partition to which the record should be sent, e.g. "2"</li>
 *   <li><em>APPSENSOR_KAFKA_PRODUCER_KEY</em> - (OPTIONAL) The key that will be included in the record, e.g. "my-special-key"</li>
 * </ul>
 * 
 * <p>Note: This class assumes the 'auto.create.topics.enable' setting is set to 'true', which is the default. 
 * If not set to true, the code will fail.</p>
 * 
 * <p>Note: The kafka implementation does NOT perform access control. Due to the asynchronous 
 * nature of the communication, authentication and access control must be performed at one/both of 
 * the network layer or the kafka layer (current version of kafka does not provide security)</p>
 * 
 * @author John Melton (jtmelton@gmail.com) http://www.jtmelton.com/
 */
@Named
public class KafkaEventManager implements EventManager, KafkaConstants {

	private Logger logger = LoggerFactory.getLogger(getClass());
	
	@Inject
	private Environment environment;
	
	private final Gson gson = new Gson();
	
	private boolean initializedProperly = true;
	
	/** maintain a collection of {@link Response}s as an in-memory list */
	private static Collection<Response> responses = new CopyOnWriteArrayList<>();

	private KafkaConfig config;
	
	private KafkaSender kafkaSender; 
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void addEvent(Event event) {
		ensureInitialized();
		
		KeyValuePair keyValuePair = new KeyValuePair(KafkaConstants.EVENT_TYPE, gson.toJson(event));
		String message = gson.toJson(keyValuePair);
		
		try {
			kafkaSender.send(Arrays.asList(APPSENSOR_ADD_EVENT_TOPIC), message);
		} catch (InterruptedException | ExecutionException e) {
			logger.error("Failed to send add event message to output topic.", e);
		}
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public void addAttack(Attack attack) {
		ensureInitialized();
		
		KeyValuePair keyValuePair = new KeyValuePair(KafkaConstants.ATTACK_TYPE, gson.toJson(attack));
		String message = gson.toJson(keyValuePair);
		
		try {
			kafkaSender.send(Arrays.asList(APPSENSOR_ADD_ATTACK_TOPIC), message);
		} catch (InterruptedException | ExecutionException e) {
			logger.error("Failed to send add attack message to output topic.", e);
		}
	}
	
	/**
	 * {@inheritDoc}
	 */
	@Override
	public Collection<Response> getResponses(String earliest) {
		ensureInitialized();
		
		Collection<Response> matchingResponses = new HashSet<>();
		
		for(Response response : responses) {
			DateTime earliestDateTime = DateUtils.fromString(earliest);
			DateTime responseDateTime = DateUtils.fromString(response.getTimestamp());
			
			if(earliestDateTime != null && responseDateTime != null) {
				if(earliestDateTime.isBefore(responseDateTime)) {
					matchingResponses.add(response);
				}
			}
		}
		
		return responses;
	}
	
	private void ensureInitialized() {
		if(! initializedProperly) {
			throw new IllegalStateException(getUninitializedMessage());
		}
	}
	
	@PostConstruct
	public void ensureEnvironmentVariablesSet() {
		initializedProperly = isInitializedProperly();
		
		if (! initializedProperly) {
			logger.error(getUninitializedMessage());
		} else {
			initializeConfig();
			startKafkaListeners();
			initializeSender();
		}
	}
	
	private void startKafkaListeners() {
		logger.info("Starting Kafka listeners for client application response queues");
		new ListenerThread(KafkaUtils.buildResponseTopicName(config.getClientApplicationName())).start();
		logger.info("Completed startup of Kafka listeners for client application response queues");
	}
	
	private void initializeConfig() {
		Integer partition = null;
		
		if (StringUtils.isNotBlank(environment.getProperty(KAFKA_PRODUCER_PARTITION))) {
			partition = Integer.parseInt(environment.getProperty(KAFKA_PRODUCER_PARTITION));
		}
		
		config = 
				new KafkaConfigBuilder()
					.setClientApplicationName(environment.getProperty(APPSENSOR_CLIENT_APPLICATION_NAME))
					.setConsumerGroupId(environment.getProperty(KAFKA_CONSUMER_GROUP_ID))
					.setConsumerZookeeperConnect(environment.getProperty(KAFKA_CONSUMER_ZOOKEEPER_CONNECT))
					.setProducerBootstrapServers(environment.getProperty(KAFKA_PRODUCER_BOOTSTRAP_SERVERS))
					.setProducerPartition(partition)
					.setProducerKey(environment.getProperty(KAFKA_PRODUCER_KEY))
					.build();
	}
	
	private void initializeSender() {
		kafkaSender = new KafkaSender(config);
	}
	
	private boolean isInitializedProperly() {
		boolean initializedProperly = false;
		
		initializedProperly = StringUtils.isNotBlank(environment.getProperty(APPSENSOR_CLIENT_APPLICATION_NAME)) &&
				StringUtils.isNotBlank(environment.getProperty(KAFKA_CONSUMER_GROUP_ID)) &&
				StringUtils.isNotBlank(environment.getProperty(KAFKA_CONSUMER_ZOOKEEPER_CONNECT)) &&
				StringUtils.isNotBlank(environment.getProperty(KAFKA_PRODUCER_BOOTSTRAP_SERVERS));
		
		// fail early
		if (! initializedProperly) {
			return initializedProperly;
		}

		if (StringUtils.isNotBlank(environment.getProperty(KAFKA_PRODUCER_PARTITION))) {
			// optional field, but if set:
			//	- key field must also be set
			//	- this field must be int
			String partition = environment.getProperty(KAFKA_PRODUCER_PARTITION);
			
			try {
				Integer.parseInt(partition);
			} catch(NumberFormatException nfe) {
				// fail if this is not an integer
				initializedProperly = false;
			}
			
			if (! initializedProperly) {
				return initializedProperly;
			}
			
			// also ensure key is set
			initializedProperly = StringUtils.isNotBlank(environment.getProperty(KAFKA_PRODUCER_KEY));
			
			if (! initializedProperly) {
				return initializedProperly;
			}
		}
		
		return initializedProperly;
	}
	
	private String getUninitializedMessage() {
		StringBuilder sb = new StringBuilder();
		
		Collection<String> setVariables = new ArrayList<>();
		Collection<String> missingVariables = new ArrayList<>();
		
		if (StringUtils.isBlank(environment.getProperty(APPSENSOR_CLIENT_APPLICATION_NAME))) {
			missingVariables.add(APPSENSOR_CLIENT_APPLICATION_NAME);
		} else {
			setVariables.add(APPSENSOR_CLIENT_APPLICATION_NAME);
		}
		
		if (StringUtils.isBlank(environment.getProperty(KAFKA_CONSUMER_GROUP_ID))) {
			missingVariables.add(KAFKA_CONSUMER_GROUP_ID);
		} else {
			setVariables.add(KAFKA_CONSUMER_GROUP_ID);
		}
		
		if (StringUtils.isBlank(environment.getProperty(KAFKA_CONSUMER_ZOOKEEPER_CONNECT))) {
			missingVariables.add(KAFKA_CONSUMER_ZOOKEEPER_CONNECT);
		} else {
			setVariables.add(KAFKA_CONSUMER_ZOOKEEPER_CONNECT);
		}
		
		if (StringUtils.isBlank(environment.getProperty(KAFKA_PRODUCER_BOOTSTRAP_SERVERS))) {
			missingVariables.add(KAFKA_PRODUCER_BOOTSTRAP_SERVERS);
		} else {
			setVariables.add(KAFKA_PRODUCER_BOOTSTRAP_SERVERS);
		}

		if (missingVariables.size() > 0) {
			sb.append("The following Environment variables must be set: ").append(missingVariables);
			
			if (setVariables.size() > 0) {
				sb.append(" (already set variables - ").append(setVariables).append(")");
			}
		}
		
		if (StringUtils.isNotBlank(environment.getProperty(KAFKA_PRODUCER_PARTITION))) {
			// optional field, but if set:
			//	- key field must also be set
			//	- this field must be int
			String partition = environment.getProperty(KAFKA_PRODUCER_PARTITION);
			
			try {
				Integer.parseInt(partition);
			} catch(NumberFormatException nfe) {
				// fail if this is not an integer
				sb.append("\r\n");
				sb.append("If you use the " + KAFKA_PRODUCER_PARTITION + ", it must be set to an integer.");
			}
			
			// also ensure key is set
			if (StringUtils.isBlank(environment.getProperty(KAFKA_PRODUCER_KEY))) {
				sb.append("\r\n");
				sb.append("If you use the " + KAFKA_PRODUCER_PARTITION + ", you must also set the " + KAFKA_PRODUCER_KEY + ".");
			}
		}

		return sb.toString();
	}
	
	public class ListenerThread extends Thread {
	    final String topic;
	    
	    ConsumerConnector consumerConnector;
	 
	    public ListenerThread(String topicName){
	    	topic = topicName;
	    	
	    	logger.info("Starting EventManager kafka consumer .. ");
	    	
	    	logger.info("Connecting to zookepper: " + config.getConsumerZookeeperConnect());
	    	logger.info("Connecting with group id: " + config.getConsumerGroupId());
	    	logger.info("Connecting with client id: " + config.getClientApplicationName());
	    	logger.info("Connecting to topic: " + topic);
	    	
	        Properties properties = new Properties();
	        properties.put("zookeeper.connect", config.getConsumerZookeeperConnect());
	        properties.put("group.id", config.getConsumerGroupId());
	        properties.put("client.id", config.getClientApplicationName());
	        properties.put("zookeeper.session.timeout.ms", "400");
	        properties.put("zookeeper.sync.time.ms", "200");
	        properties.put("auto.commit.interval.ms", "1000");
	        
	        ConsumerConfig consumerConfig = new ConsumerConfig(properties);
	        consumerConnector = Consumer.createJavaConsumerConnector(consumerConfig);
	        
	        logger.info("Created event manager kafka consumer");
	    }
	    
	    @Override
	    public void run() {
	        Map<String, Integer> topicCountMap = new HashMap<String, Integer>();
	        topicCountMap.put(topic, new Integer(1));
	        
	        Map<String, List<KafkaStream<byte[], byte[]>>> consumerMap = consumerConnector.createMessageStreams(topicCountMap);
	        List<KafkaStream<byte[], byte[]>> streams = consumerMap.get(topic);
	        KafkaStream<byte[], byte[]> stream = streams.get(0);
	        ConsumerIterator<byte[], byte[]> it = stream.iterator();
	        
            while (it.hasNext()) {
            	String message = new String(it.next().message());
                
                KeyValuePair keyValuePair = gson.fromJson(message, KeyValuePair.class);
                
                String type = keyValuePair.getKey();
                String content = keyValuePair.getValue();
                
                if(RESPONSE_TYPE.equals(type)) {
                	logger.trace("Received response on topic: " + topic);

					Response response = gson.fromJson(content, Response.class);
					
					responses.add(response);
    			} else {
    				logger.trace("Received message for UNKNOWN topic: " + topic);
    			}

            }
            
	    }
	}
	
}
