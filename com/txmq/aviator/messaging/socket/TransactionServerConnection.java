package com.txmq.aviator.messaging.socket;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.Socket;

import com.swirlds.platform.Platform;
import com.txmq.aviator.core.PlatformLocator;
import com.txmq.aviator.core.AviatorState;
import com.txmq.aviator.messaging.AviatorCoreTransactionTypes;
import com.txmq.aviator.messaging.AviatorMessage;

/**
 * TransactionServerConnection represents the server-side of an established connection.
 * It runs on its own thread and accepts ExoMessages from the socket.
 */
public class TransactionServerConnection extends Thread {

	private Socket socket;
	private Platform platform;
	private ExoMessageRouter messageRouter;
	
	public TransactionServerConnection(Socket socket, Platform platform, ExoMessageRouter messageRouter) {
		this.socket = socket;
		this.platform = platform;
		this.messageRouter = messageRouter;
	}
	
	/**
	 * Accepts transactions in ExoMessage instances from the socket and process them.
	 */
	@SuppressWarnings("unchecked")
	public void run() {
		try {
			//Set up streams for reading from and writing to the socket.
			ObjectOutputStream writer = new ObjectOutputStream(this.socket.getOutputStream());
			ObjectInputStream reader = new ObjectInputStream(socket.getInputStream());
			AviatorMessage<?> message;
			AviatorMessage<Serializable> response = new AviatorMessage<Serializable>();
			try {
				//Read the message object and try to cast it to ExoMessage
				Object tmp = reader.readObject();
				message = (AviatorMessage<?>) tmp; 
				AviatorState state = (AviatorState) this.platform.getState();
				
				try {
					response = (AviatorMessage<Serializable>) this.messageRouter.routeMessage(message, state);
				} catch (IllegalArgumentException e) {
					/*
					 * This exception is thrown by transactionRouter when it can't figure 
					 * out where to route a message.  In the case of socket transactions, 
					 * those transaction types it can't route are messages that we can 
					 * simply pass through to the platform for processing by the Hashgraph,
					 * unless it's an ACKNOWLEDGE transaction.
					 */
					if (message.transactionType.getValue() == AviatorCoreTransactionTypes.ACKNOWLEDGE) {
						//We shouldn't receive this from the client.  If we do, just send it back
						response.transactionType.setValue(AviatorCoreTransactionTypes.ACKNOWLEDGE);
					} else {	
						PlatformLocator.createTransaction(message);
						response.transactionType.setValue(AviatorCoreTransactionTypes.ACKNOWLEDGE);
					}
				} catch (ReflectiveOperationException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				//write the response to the socket
				writer.writeObject(response);					
				writer.flush();
			} catch (ClassNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			System.out.println("Closing Socket");
			this.socket.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
