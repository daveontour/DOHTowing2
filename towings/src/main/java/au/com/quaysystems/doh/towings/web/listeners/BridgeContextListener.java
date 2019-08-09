package au.com.quaysystems.doh.towings.web.listeners;

import javax.servlet.ServletContextEvent;
import javax.servlet.annotation.WebListener;

import org.basex.core.Context;
import org.basex.query.QueryProcessor;
import org.basex.query.iter.Iter;
import org.basex.query.value.item.Item;
import org.slf4j.LoggerFactory;

import com.ibm.mq.MQException;
import com.ibm.mq.constants.MQConstants;

import au.com.quaysystems.doh.towings.web.mq.MReceiver;
import au.com.quaysystems.doh.towings.web.mq.MSender;
import ch.qos.logback.classic.Logger;

/*
 * 
 * 1. Listen for incoming request on the bridging queue, adds aircraft registration if available
 * and puts it on the output queue
 * 
 * The handling of construction of XML documents is inelegant. Regex is used to extract values rather 
 * than a parser. Elements are added by String substitution. This was deliberate to try keep it as light-weight
 * as possible. 
 * 
 * Uses method in the base class to extract flight descriptor for towing message and get registration for AMS
 */
@WebListener
public class BridgeContextListener extends TowContextListenerBase {

	private String notifTemplate = "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\">\r\n" + 
			"  <soap:Header></soap:Header>\r\n" + 
			"  <soap:Body>\r\n" + 
			"	%s\r\n" + 
			"  </soap:Body>\r\n" + 
			" </soap:Envelope>";

	// Used by BaseX to extract the portion of the message we are interested in
	String queryBody = 
			"declare variable $var1 as xs:string external;\n"+
					"for $x in fn:parse-xml($var1)//Notification\r\n" + 
					"return $x";

	@Override
	public void contextInitialized(ServletContextEvent servletContextEvent) {
		log = (Logger)LoggerFactory.getLogger(BridgeContextListener.class);
		super.contextInitialized(servletContextEvent);

		// Start the listener for incoming notification
		this.startListener();
	}

	public void startListener() {

		log.info("---> Starting Notification Listener");
		t = new NotifcationBridgeListener();
		t.setName("Notif. Process");
		t.start();
		log.info("<--- Started Notification Listener");

	}

	public class NotifcationBridgeListener extends Thread {

		public void run() {

			do {				
				MReceiver recv = connectToMQ(msmqbridge);
				if (recv == null) {
					log.error(String.format("Exceeded IBM MQ connect retry limit {%s}. Exiting", retriesIBMMQ));
					continue;
				}

				log.info(String.format("Conected to queue %s", msmqbridge));

				boolean continueOK = true;

				do {
					try {
						String message = null;
						try {
							message = recv.mGet(msgRecvTimeout, true);
							try {
								recv.disconnect();
							} catch (Exception e) {
								log.error("Recieve Disconnect Error - probably not fatal");
							}
						} catch (MQException ex) {
							if ( ex.completionCode == 2 && ex.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) {
								log.debug("No Notification Messages");
								continue;
							}
						} 

						if (!message.contains("TowingCreatedNotification") &&
								!message.contains("TowingUpdatedNotification") &&
								!message.contains("TowingDeletedNotification")) {

							// Exit the loop if it's not a message we are interested in
							log.debug("Unhandled message received - ignoring");
							continueOK = false;
							continue;
						}

						log.debug("Message Received");
						message = message.substring(message.indexOf("<")).replace("xsi:type", "type");
						String notification = "<Error>true</Error>";
						Context context = new Context();
						try  {
							QueryProcessor proc = new QueryProcessor(queryBody, context);
							proc.bind("var1", message);
							Iter iter = proc.iter();
							for (Item item; (item = iter.next()) != null;) {
								notification  = item.serialize().toString();
							}
							proc.close();
						} catch (Exception ex) {
							notification = "<Error>true</Error>";
						}

						notification = String.format(notifTemplate, notification);
						notification = notification.replace("xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"", "");

						// Get the registration of the flight by extracting flight details and calling a web service to get the flight 
						String rego = "<Registration>"+getRegistration(notification)+"</Registration>";
						notification = notification.replaceAll("</FlightIdentifier>", rego+"\n</FlightIdentifier>");						
						log.debug("Message Processed");

						try {
							MSender send = new MSender(ibmoutqueue, host, qm, channel,  port,  user,  pass);
							send.mqPut(notification);
							log.debug("Message Sent");
						} catch (Exception e) {
							log.error("Message Send Error");
							log.error(e.getMessage());
						}
					} catch (Exception e) {
						log.error("Unhandled Exception "+e.getMessage());
						//						recv.disconnect();
						continueOK = false;
					}
				} while (continueOK);
			} while (true);
		}
	}
}