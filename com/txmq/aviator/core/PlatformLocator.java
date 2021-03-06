package com.txmq.aviator.core;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.core.UriBuilder;

import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.ssl.SSLContextConfigurator;
import org.glassfish.grizzly.ssl.SSLEngineConfigurator;
import org.glassfish.grizzly.websockets.WebSocketAddOn;
import org.glassfish.grizzly.websockets.WebSocketEngine;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.media.multipart.MultiPartFeature;
import org.glassfish.jersey.server.ResourceConfig;

import com.swirlds.platform.Platform;
import com.swirlds.platform.SwirldState;
import com.swirlds.platform.Transaction;
import com.txmq.aviator.config.AviatorConfig;
import com.txmq.aviator.config.model.BlockLoggerConfig;
import com.txmq.aviator.config.model.MessagingConfig;
import com.txmq.aviator.messaging.AviatorCoreTransactionTypes;
import com.txmq.aviator.messaging.AviatorTransactionType;
import com.txmq.aviator.messaging.AviatorMessage;
import com.txmq.aviator.messaging.rest.CORSFilter;
import com.txmq.aviator.messaging.socket.TransactionServer;
import com.txmq.aviator.messaging.websocket.grizzly.AviatorWebSocketApplication;
import com.txmq.aviator.persistence.BlockLogger;
import com.txmq.aviator.persistence.IBlockLogger;
import com.txmq.aviator.pipeline.routers.AviatorPipelineRouter;

/**
 * A static locator class for Exo platform constructs.  This class allows applications
 * to get access to Swirlds platform, Swirlds state, and the transaction router from
 * anywhere in the application.
 * 
 * I'm not in love with using static methods essentially as global variables.  I'd 
 * love to hear ideas on a better way to approach this.
 */
public class PlatformLocator {
	/**
	 * Reference to the Swirlds platform
	 */
	private static Platform platform;

	/**
	 * Pipeline routers 
	 */
	private static Map<String, AviatorPipelineRouter> pipelineRouters = new HashMap<String, AviatorPipelineRouter>();
	//private static ExoPipelineRouter pipelineRouter = new ExoPipelineRouter();
	
	/**
	 * Reference to the block logging manager
	 */
	private static BlockLogger blockLogger = new BlockLogger();

	/**
	 * Tracks running instances of Grizzly so they can be shut down later. 
	 */
	private static List<HttpServer> httpServers = new ArrayList<HttpServer>();
	
	/**
	 * When run in test mode, Exo will maintain a single instance of the application's 
	 * state so that JUnit tests can run against code that requires data 
	 * in the state, without a dependency on the platform being up and running.
	 * 
	 * Block logging will be disabled when running in test mode.
	 */
	private static AviatorState testState = null;
	
	/**
	 * Places Exo in test mode, using the passed-in instance of a state.  This is useful
	 * for automated testing where you may want to configure an application state manually
	 * and run a series of tests against that known state.
	 */
	public static void enableTestMode(AviatorState state) {
		PlatformLocator.testState = state;
	}
	
	/**
	 * Places Exo in test mode.  Exo will create an instance 
	 * of the supplied type to service as a mock state. 
	 * @throws IllegalAccessException 
	 * @throws InstantiationException 
	 */
	public static void enableTestMode(Class<? extends AviatorState> stateClass) throws InstantiationException, IllegalAccessException {
		PlatformLocator.testState = stateClass.newInstance();
	}
	
	/**
	 * Tests if the application is running in test mode
	 */
	public static boolean isTestMode() {
		return (PlatformLocator.testState != null);
	}

	/**
	 * Indicates that the node should shut down 
	 */
	private static boolean shouldShutdown = false;
	
	public static boolean shouldShutdown() {
		return shouldShutdown;
	}
	
	public static void shutdown() {
		shouldShutdown = true;
		
		//TODO:  Shut down socket listeners
		
		//This will be slow, but in production there should only be one 
		//httpServer in the list so forrealz it'll make no difference
		try {
			for (HttpServer httpServer : httpServers) {
				GrizzlyFuture<HttpServer> future = httpServer.shutdown(30, TimeUnit.SECONDS);
				future.get();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		//Sleep 30 seconds to make sure we allow time for transactions to finish processing.
		try {
			Thread.sleep(30000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		getBlockLogger().flushLoggers();
	}
	
	/**
	 * Initializes the platform from an exo-config.json file located 
	 * in the same directory as the application runs in.
	 * @throws ClassNotFoundException 
	 */
	@SuppressWarnings("unchecked")
	public static synchronized void initFromConfig(Platform platform) throws ReflectiveOperationException {
		PlatformLocator.platform = platform;
		init(platform, (List<String>) AviatorConfig.get("transactionProcessors"));
		
		//Set up socket messaging, if it's in the config..
		MessagingConfig messagingConfig = null; 
		if (AviatorConfig.has("socketMessaging")) {
			try {
				messagingConfig = parseMessagingConfig((MessagingConfig) AviatorConfig.get("socketMessaging"));
				if (messagingConfig.secured == true) {
					initSecuredSocketMessaging(	messagingConfig.port, 
												messagingConfig.handlers, 
												messagingConfig.clientKeystore.path, 
												messagingConfig.clientKeystore.password, 
												messagingConfig.serverKeystore.path, 
												messagingConfig.serverKeystore.password);
				} else {
					initSocketMessaging(messagingConfig.port, messagingConfig.handlers);
				}
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("Error configuring socket messaging:  " + e.getMessage());
			}
		}
		
		//Set up REST, if it's in the config..
		if (AviatorConfig.has("rest")) {
			try {
				messagingConfig = parseMessagingConfig((MessagingConfig) AviatorConfig.get("rest"));
				initREST(messagingConfig);
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("Error configuring REST:  " + e.getMessage());
			}
		}
		
		//TODO:  Backport multiple logger feature
		//configure block logging, if indicated in the config file
		if (AviatorConfig.has("blockLoggers") && PlatformLocator.testState == null) {
			for (BlockLoggerConfig loggerConfig : (List<BlockLoggerConfig>) AviatorConfig.get("blockLoggers")) {
				try {
					Class<? extends IBlockLogger> loggerClass = 
						(Class<? extends IBlockLogger>) Class.forName(loggerConfig.loggerClass);
					IBlockLogger logger = loggerClass.newInstance();
					logger.configure(loggerConfig.parameters);
					blockLogger.addLogger(logger, platform.getAddress().getSelfName());
				} catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
					throw new IllegalArgumentException("Error configuring block logger:  " + e.getMessage());
				}	
			}
		}				
	}
	
	private static MessagingConfig parseMessagingConfig(MessagingConfig config) {
		MessagingConfig result = new MessagingConfig();
		
		//If a port has been defined in the config, use it over the derived port.
		if (config.port > 0) {
			result.port = config.port;				
		} else {
			//Test if there's a derived port value.  If not, we have an invalid messaging config
			if (config.derivedPort != null) {
				//Calculate the port for socket connections based on the hashgraph's port
				//If we're in test mode, mock this up to be a typical value, e.g. 5220X
				if (testState == null) {
					result.port = platform.getAddress().getPortExternalIpv4() + config.derivedPort;
				} else {
					result.port = 50204 + config.derivedPort;
				}
			} else {
				throw new IllegalArgumentException(
					"One of \"port\" or \"derivedPort\" must be defined."
				);
			}
		}
		
		if (config.handlers != null && config.handlers.length > 0) {
			result.handlers = config.handlers;
		} else {
			throw new IllegalArgumentException(
				"No handlers were defined in configuration"
			);
		}
		
		result.secured = config.secured;
		result.clientKeystore = config.clientKeystore;
		result.clientTruststore = config.clientTruststore;
		result.serverKeystore = config.serverKeystore;
		result.serverTruststore = config.serverTruststore;
		return result;
	}
	
	/**
	 * Initializes the pipeline router for this node
	 * This is more complicated than it needs to be to 
	 * account for multiple nodes running in the same JVM.
	 */
	private static synchronized void initPipelineRouter(List<String> packages) {
		AviatorPipelineRouter pipelineRouter = new AviatorPipelineRouter();
		pipelineRouter.init(packages);
		String nodeName = null;
		try {
			nodeName = ((AviatorState) platform.getState()).getMyName();
		} finally {
			platform.releaseState();
		}
		pipelineRouters.put(nodeName, pipelineRouter);
	}
	
	/**
	 * Accessor for the Exo pipeline router.  
	 * @see com.txmq.aviator.pipeline.routers.AviatorPipelineRouter
	 */	
	public static synchronized AviatorPipelineRouter getPipelineRouter() {
		String nodeName = null;
		try {
			nodeName = ((AviatorState) platform.getState()).getMyName();
		} finally {
			platform.releaseState();
		}
		return pipelineRouters.get(nodeName);
	}
	
	/**
	 * Accessor for the Exo pipeline router.  
	 * @see com.txmq.aviator.pipeline.routers.AviatorPipelineRouter
	 */	
	public static synchronized AviatorPipelineRouter getPipelineRouter(String nodeName) {
		return pipelineRouters.get(nodeName);
	}
	
	/**
	 * Initializes the platform from an exo-condig.json 
	 * file located using the path parameter.
	 * @param path
	 * @throws ClassNotFoundException 
	 */
	public static synchronized void initFromConfig(Platform platform, String path) throws ReflectiveOperationException {
		AviatorConfig.loadConfiguration(path);
		initFromConfig(platform);
	}
	
	/**
	 * Initialization method for the platform.  This should be called by your main's 
	 * init() or run() methods
	 * @throws IllegalAccessException 
	 * @throws ReflectiveOperationException 
	 */
	public static synchronized void init(Platform platform) throws ReflectiveOperationException {
		PlatformLocator.platform = platform;
		AviatorTransactionType.initialize();			
	}
	
	public static synchronized void init(	Platform platform, 
											List<String> transactionProcessorPackages)
		throws ReflectiveOperationException
	{
		init(platform);
		initPipelineRouter(transactionProcessorPackages);
	}
	
	
	public static synchronized void init(	Platform platform, 
							List<String> transactionProcessorPackages, 
							IBlockLogger logger) 
		throws ReflectiveOperationException
	{
		init(platform, transactionProcessorPackages);
		blockLogger.addLogger(logger,  platform.getAddress().getSelfName());
	}	
	
	/**
	 * Initializes Grizzly-based REST interfaces defined in the included package list, listening on the included port.
	 * Enabling REST will automatically expose the endpoints service and generate an ANNOUNCE_NODE message.
	 * @param port
	 * @param packages
	 */
	public static void initREST(int port, String[] packages) {
		MessagingConfig internalConfig = new MessagingConfig();
		internalConfig.port = port;
		internalConfig.handlers = packages;
		
		initREST(internalConfig);		
	}
	
	/**
	 * Initializes Grizzly-based REST interfaces as defined in the messaging config.  This method will
	 * allow for REST endpoints using HTTPS by defining keystore information in exo-config.json.
	 * Enabling REST will automatically expose the endpoints service and generate an ANNOUNCE_NODE message.
	 * @param restConfig
	 */
	public static void initREST(MessagingConfig restConfig) {
		try {
			platform.getState();
		} finally {
			platform.releaseState();
		}
		
		URI baseUri = UriBuilder.fromUri("http://0.0.0.0").port(restConfig.port).build();
		ResourceConfig config = new ResourceConfig()
				.packages("com.txmq.aviator.messaging.rest")
				.register(new CORSFilter())
				.register(JacksonFeature.class)
				.register(MultiPartFeature.class);
		
		for (String pkg : restConfig.handlers) {
			config.packages(pkg);
		}
		
		System.out.println("Attempting to start Grizzly on " + baseUri);
		HttpServer grizzly = null;
		if (restConfig.secured == true) {
			SSLContextConfigurator sslContext = new SSLContextConfigurator();
			sslContext.setKeyStoreFile(restConfig.serverKeystore.path);
			sslContext.setKeyStorePass(restConfig.serverKeystore.password);
			sslContext.setTrustStoreFile(restConfig.serverTruststore.path);
			sslContext.setTrustStorePass(restConfig.serverTruststore.password);
			
			grizzly = GrizzlyHttpServerFactory.createHttpServer(
				baseUri, 
				config, 
				true, 
				new SSLEngineConfigurator(sslContext).setClientMode(false).setNeedClientAuth(false)
			);
		} else {
			grizzly = GrizzlyHttpServerFactory.createHttpServer(baseUri, config);
		}
		
		System.out.println("Starting Grizzly");
		try {
			grizzly.start();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		HttpServer wsServer = HttpServer.createSimpleServer(".", restConfig.port + 1000);
		final WebSocketAddOn addon = new WebSocketAddOn();
		for (NetworkListener listener : wsServer.getListeners()) {
			listener.registerAddOn(addon);
		}
		WebSocketEngine.getEngine().register("", "/wstest", new AviatorWebSocketApplication());
		
		try {
			wsServer.start();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		//Track Grizzly instances so they can be shut down later
		if (grizzly != null) {
			httpServers.add(grizzly);
		}
		
		//Publish available Exo API endpoints to the HG.
		try {
			String externalUrl = baseUri.toString();
			if (platform != null) {
				externalUrl = "http://";
				byte[] rawAddress = platform.getAddress().getAddressExternalIpv4();
				for (int ptr = 0;  ptr < rawAddress.length;  ptr++) {
					int i = rawAddress[ptr] & 0xFF;
					externalUrl += Integer.toString(i);
					if (ptr < rawAddress.length - 1) {
						externalUrl += ".";
					}
				}
				externalUrl += ":" + restConfig.port;
				
				System.out.println("Reporting available REST API at " + externalUrl);
			} else {
				
			}
			//Port ExoCoreTransactionTypes to public static Strings
			createTransaction(new AviatorMessage<Serializable>(AviatorCoreTransactionTypes.NAMESPACE, AviatorCoreTransactionTypes.ANNOUNCE_NODE, externalUrl));
					
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
	}
	
	/**
	 * Sets up a socket-based API for communicating with this Swirld on the supplied 
	 * port.  Scans the supplied list of packages for methods annotated with 
	 * @ExoTransaction and automatically maps incoming messages to matching handlers.  
	 * Any transactions which have not been mapped are passed through the platform to
	 * be processed by the model.  
	 * 
	 * This method creates unsecured sockets with no authentication or in-transit encryption.  
	 * Beware of dragons.
	 * 
	 * @param port
	 * @param packages
	 */
	public static void initSocketMessaging(int port, String[] packages) {
		new TransactionServer(platform, port, packages).start();
	}
	
	/**
	 * Sets up a TLS-encrypted socket-based API for communicating with this Swirld on 
	 * the supplied port.  X.509 certs are used to authenticate connecting clients, 
	 * and vice-versa.  Scans the supplied list of packages for methods annotated with 
	 * @ExoTransaction and automatically maps incoming messages to matching handlers.  
	 * Any transactions which have not been mapped are passed through the platform to
	 * be processed by the model.
	 * 
	 * @param port
	 * @param packages
	 */
	public static void initSecuredSocketMessaging(	int port, 
													String[] packages, 
													String clientKeystorePath,
													String clientKeystorePassword,
													String serverKeystorePath,
													String serverKeystorePassword) {
		new TransactionServer(	platform, 
								port, 
								packages, 
								clientKeystorePath, 
								clientKeystorePassword, 
								serverKeystorePath, 
								serverKeystorePassword).start();
	}
	
	/**
	 * Submits a transaction to the platform.  Applications should use this method
	 * over ExoPlatformLocator.getPlatform().createTransaction() to enable unit testing 
	 * via test mode.
	 * 
	 * This signature matches the createTransaction signature of the Swirlds Platform.
	 */
	public static void createTransaction(AviatorMessage<? extends Serializable> transaction) throws IOException {
		try {
			//Check if we're running in test mode.
			long transactionID = new Random().nextLong();
			Instant timeCreated = Instant.now();
			AviatorState preConsensusState = null;
			
			if (testState != null) {
				//Test mode..  Get a pre-consensus copy of the state
				try {
					preConsensusState = (AviatorState) testState.getClass().getConstructors()[0].newInstance();
				} catch (Exception e) {
					// TODO Better error handling..
					e.printStackTrace();
				}
				
				preConsensusState.copyFrom((SwirldState) testState);
				
				//We have a temporary state constructed.  Run the rest of the pipeline.
			} else {
				preConsensusState = getState();
			}
			
			String nodeName = preConsensusState.getMyName();
			
			//Process message received handlers
			getPipelineRouter(nodeName).routeMessageReceived(transaction, preConsensusState);
			
			//If the transaction was not interrupted, submit it to the platform
			if (transaction.isInterrupted() == false) {
				Transaction serializedTransaction = new Transaction(transaction.serialize());
				if (testState != null) {
					preConsensusState.handleTransaction(transactionID, false, timeCreated, timeCreated, serializedTransaction, null);
					testState.handleTransaction(transactionID, true, timeCreated, timeCreated, serializedTransaction, null);
				} else {
					platform.createTransaction(serializedTransaction);
					getPipelineRouter(preConsensusState.getMyName()).notifySubmitted(transaction, nodeName);
				}
			}
		} finally {
			platform.releaseState();
		}
	}
	
	/**
	 * Accessor for a reference to the Swirlds platform.  Developers must call 
	 * ExoPlatformLocator.init() to intialize the locator before calling getPlatform()
	 */
	public static Platform getPlatform() throws IllegalStateException {
		if (platform == null) {
			throw new IllegalStateException(
				"PlatformLocator has not been initialized.  " + 
				"Please initialize PlatformLocator in your SwirldMain implementation."
			);
		}
		
		return platform;
	}
	
	/**
	 * Accessor for Swirlds state.  Developers must call ExoPlatformLocator.init()
	 * to initialize the locator before calling getState(), unless running in test mode.
	 * 
	 * Developers should prefer this method to ExoPlatformLocator.getPlatform().getState()
	 * because this method supports returning a state in test mode without initializing
	 * the platform.
	 * 
	 * Note that as of the 18.10.23 SDK you must call platform.releaseState() manually 
	 * after using PlatformLocator.getState() to access the application state.
	 */
	public static AviatorState getState() throws IllegalStateException {
		if (PlatformLocator.testState == null) {
			if (platform == null) {
				throw new IllegalStateException(
					"PlatformLocator has not been initialized.  " + 
					"Please initialize PlatformLocator in your SwirldMain implementation."
				);
			}
			
			return (AviatorState) platform.getState();
		} else {
			return testState;
		}
	}
	
	/**
	 * Accessor for the block logging manager
	 */
	public static BlockLogger getBlockLogger() {
		return blockLogger;
	}
}
