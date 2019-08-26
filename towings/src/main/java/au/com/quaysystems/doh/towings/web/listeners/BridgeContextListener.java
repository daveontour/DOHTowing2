package au.com.quaysystems.doh.towings.web.listeners;

import java.io.IOException;

import javax.servlet.ServletContextEvent;
import javax.servlet.annotation.WebListener;

import org.basex.core.Context;
import org.basex.data.Result;
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

	String notificationDummy = "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\r\n" + 
			" <soap:Header></soap:Header>\r\n" + 
			" <soap:Body>\r\n" + 
			"  <Notification  type=\"TowingUpdatedNotification\">\r\n" + 
			"   <Airport>DOH</Airport>\r\n" + 
			"   %s \r\n"+
			"  </Notification>\r\n" + 
			" </soap:Body>\r\n" + 
			"</soap:Envelope>";
	
	public boolean stopThread = false;

	@Override
	public void contextInitialized(ServletContextEvent servletContextEvent) {
		log = (Logger)LoggerFactory.getLogger(BridgeContextListener.class);
		super.contextInitialized(servletContextEvent);

		// Start the listener for incoming notification
		this.startListener();
	}
	
	@Override
	public void contextDestroyed(ServletContextEvent servletContextEvent) {
		this.stopThread = true;
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
			
			if (stopThread) {
				log.info("Stopping Bridge Listener Thread");
				return;
			}

			do {	
				if (stopThread) {
					log.info("Stopping Bridge Listener Thread");
					return;
				}
				MReceiver recv = connectToMQ(msmqbridge);
				if (recv == null) {
					log.error(String.format("Exceeded IBM MQ connect retry limit {%s}. Exiting", retriesIBMMQ));
					continue;
				}

				log.info(String.format("Conected to queue %s", msmqbridge));

				boolean continueOK = true;

				do {
					if (stopThread) {
						log.info("Stopping Bridge Listener Thread");
						return;
					}
					
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
								if (stopThread) {
									log.info("Stopping Bridge Listener Thread");
									return;
								}
								continue;
							}
						} 

						if (message.contains("<FlightUpdatedNotification>")) {
							handleFlightUpdate(message);
							continueOK = false;
							continue;
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

	private String stripNS(String xml) {
		return xml.replaceAll("xmlns(.*?)=(\".*?\")", "");
	}

	public void handleFlightUpdate(String message) {
		
		log.info("Handling Updated Notification");
		
		String queryBody = 
				"declare variable $var1 as xs:string external;\n"+
						"for $x in fn:parse-xml($var1)//AircraftChange/NewValue/Aircraft/AircraftId/Registration/text()\r\n" + 
						"return $x";
		message = stripNS(message);
		Context context = new Context();
		String rego = null;
		try  {
			QueryProcessor proc = new QueryProcessor(queryBody, context);
			proc.bind("var1", message);
			Result result = proc.execute();
			rego = result.serialize().toString();
			proc.close();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		
		
		// Does the notification have a new aircraft registration
		if (rego == null || rego.length() < 2) {
			log.info("Flight Update Does NOT include rego update");
			return;
		} else {
			log.info("Flight Update DOES include rego update, processing");
		}


		
		//It does, so construct the flight descriptor
		String fltDescriptor = getFlightDescriptor(message);
		if (fltDescriptor == null) {
			return;
		}
		
		if (fltDescriptor.length() < 10) {
			log.error("========== Flight Descriptor Parsing Problem ==========");
			log.error("===== Flt Descriptor ===:  "+ fltDescriptor);
			log.error("========== Source Message Below =======================");
			log.error(message);
			log.error("========== End of Source Message ======================");
			
			// Should return here, but let it go ahead so the problem turns up in the 
			// event log so it can be identified. 
			//return;
		} else {
			log.info(fltDescriptor);
		}

		try {
			// Get all the towing events for this fligt descriptor
			String tows = this.getTow(fltDescriptor);
		

			// Get all the towing events for this fligt descriptor
			String queryTowing = 
					"declare variable $var1 as xs:string external;\n"+
							"for $x in fn:parse-xml($var1)//Towing\r\n" + 
							"return $x";

			try  {
				QueryProcessor proc = new QueryProcessor(queryTowing, context);
				proc.bind("var1", tows);
				Iter iter = proc.iter();
				for (Item item; (item = iter.next()) != null;) {

					// For each towing event, make it look like a TowingNotification message
					// and put it on the Bridge Queue, so the message is picked up and processed
					// as a normal notification message.
					String tow = item.serialize().toString();
					String msg = String.format(notificationDummy, tow);
		
					try {
						MSender send = new MSender(msmqbridge, host, qm, channel,  port,  user,  pass);
						send.mqPut(msg);
						send.disconnect();
						log.info("Constructed TowNotification Message Sent");
					} catch (Exception e) {
						log.error("Sync Send Error");
						log.error(e.getMessage());
					}

				}
				proc.close();
			} catch (Exception ex) {
				ex.printStackTrace();
			}	
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public String getFlightDescriptor(String message) {

		String desc = null;
		String kind = null;
		String airline = null;
		String fltNum = null;
		String sched = null;

		message = stripNS(message);
		Context context = new Context();

		String queryBody = 
				"declare variable $var1 as xs:string external;\n"+
						"for $x in fn:parse-xml($var1)//FlightUpdatedNotification/Flight/FlightId/FlightKind/text()\r\n" + 
						"return $x";

		try  {
			QueryProcessor proc = new QueryProcessor(queryBody, context);
			proc.bind("var1", message);
			Result result = proc.execute();
			kind = result.serialize().toString();
			proc.close();
		} catch (Exception ex) {
			return desc;
		}

		queryBody = 
				"declare variable $var1 as xs:string external;\n"+
						"for $x in fn:parse-xml($var1)//FlightUpdatedNotification/Flight/FlightId/FlightNumber/text()\r\n" + 
						"return $x";

		try  {
			QueryProcessor proc = new QueryProcessor(queryBody, context);
			proc.bind("var1", message);
			Result result = proc.execute();
			fltNum = result.serialize().toString();
			proc.close();
		} catch (Exception ex) {
			return desc;
		}

		queryBody = 
				"declare variable $var1 as xs:string external;\n"+
						"for $x in fn:parse-xml($var1)//FlightUpdatedNotification/Flight/FlightState/ScheduledTime/text()\r\n" + 
						"return $x";

		try  {
			QueryProcessor proc = new QueryProcessor(queryBody, context);
			proc.bind("var1", message);
			Result result = proc.execute();
			sched = result.serialize().toString();
			proc.close();
		} catch (Exception ex) {
			return desc;
		}

		queryBody = 
				"declare variable $var1 as xs:string external;\n"+
						"for $x in fn:parse-xml($var1)//FlightUpdatedNotification/Flight/FlightId/AirlineDesignator[@codeContext=\"IATA\"]/text()\r\n" + 
						"return $x";

		try  {
			QueryProcessor proc = new QueryProcessor(queryBody, context);
			proc.bind("var1", message);
			Result result = proc.execute();
			airline = result.serialize().toString();
			proc.close();
		} catch (Exception ex) {
			return desc;
		}

		//		String id = "6E1713@2019-08-01T09:00A";
		if (kind.contains("Arrival")) {
			desc = airline+fltNum+"@"+sched+"A";
		} else {
			desc = airline+fltNum+"@"+sched+"D";	
		}

		return desc;

	}


}