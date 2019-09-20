package au.com.quaysystems.doh.towings.web.listeners;

import java.io.IOException;
import java.util.UUID;

import javax.servlet.ServletContextEvent;
import javax.servlet.annotation.WebListener;

import org.basex.core.Context;
import org.basex.data.Result;
import org.basex.query.QueryProcessor;
import org.basex.query.iter.Iter;
import org.basex.query.value.item.Item;
import org.jdom2.JDOMException;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.LoggerFactory;

import com.ibm.mq.MQException;
import com.ibm.mq.constants.MQConstants;

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

	String notTemplate = "<soap:Envelope xmlns:soap=\"http://www.w3.org/2001/12/soap-envelope\" xmlns:aip=\"http://www.sita.aero/aip/XMLSchema\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" encodingStyle=\"http://www.w3.org/2001/12/soap-encoding\" xmlns:ams-messages=\"http://www.sita.aero/ams6-xml-api-messages\" >\r\n" + 
			"	<soap:Header>\r\n" + 
			"		<soap:MessageMetadata>\r\n" + 
			"			<aip:Source>SITA</aip:Source>\r\n" + 
			"			<aip:Timestamp>%s</aip:Timestamp> \r\n" + 
			"			<aip:MessageType>PublishGenericDataInput</aip:MessageType>  \r\n" + 
			"			<aip:ExtensionFields>\r\n" + 
			"				<aip:ExtensionField Name=\"EventType\">\r\n" + 
			"					<aip:Value Type=\"String\">\r\n" + 
			"						<aip:String>%s</aip:String>  \r\n" + 
			"					</aip:Value>\r\n" + 
			"				</aip:ExtensionField>\r\n" + 
			"			</aip:ExtensionFields>\r\n" + 
			"			<aip:UUID>%s</aip:UUID> \r\n" + 
			"		</soap:MessageMetadata>\r\n" + 
			"		<soap:OperationData>\r\n" + 
			"			<aip:OperationName/>\r\n" + 
			"			<aip:CorrelationID/>\r\n" + 
			"		</soap:OperationData>\r\n" + 
			"	</soap:Header>\r\n" + 
			"	<soap:Body>\r\n" + 
			"		<ams-messages:AMSX_Message>\r\n" + 
			"           %s \r\n" +
			"		</ams-messages:AMSX_Message>\r\n" + 
			"	</soap:Body>\r\n" + 
			"</soap:Envelope>";

	// Used by BaseX to extract the portion of the message we are interested in
	public String queryBody = 
			"declare variable $var1 as xs:string external;\n"+
					"for $x in fn:parse-xml($var1)//Notification\r\n" + 
					"return $x";

	private String notificationDummy = "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\r\n" + 
			" <soap:Header></soap:Header>\r\n" + 
			" <soap:Body>\r\n" + 
			"  <Notification  type=\"TowingUpdatedNotification\">\r\n" + 
			"   <Airport>DOH</Airport>\r\n" + 
			"   %s \r\n"+
			"  </Notification>\r\n" + 
			" </soap:Body>\r\n" + 
			"</soap:Envelope>";

	public boolean stopThread = false;

	public int notificationType = 0;    // Create = 1, Update = 2,  Delete = 3

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

			do {
				notificationType = 0;

				String message = null;
				try {
					message = getRequestMessage(msmqbridge);
				} catch (MQException ex) {
					if ( ex.completionCode == 2 && ex.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) {
						continue;
					} else {
						log.error("Unhandled Error Getting Message");
						log.error(ex.getMessage());
					}
					continue;
				} catch (Exception ex) {
					log.error("Unhandled Error Getting Message");
					log.error(ex.getMessage());
					continue;
				}

				if (message == null) {
					continue;
				}

				log.debug("Notification Message Received");


				/*
				 * Messages from the bridge queue that we are interested in are TOW EVENTS and Flight 
				 * Updates. Flight Updates are important to see if the registration of a flight
				 * has changed. 
				 */
				if (message.contains("<FlightUpdatedNotification>")) {
					handleFlightUpdate(message);
					continue;
				}

				/*
				 * So now we're only interested in the Tow Events
				 */
				if (!message.contains("TowingCreatedNotification") &&
						!message.contains("TowingUpdatedNotification") &&
						!message.contains("TowingDeletedNotification")) {

					// Exit the loop if it's not a message we are interested in
					log.debug("Unhandled message received - ignoring");
					continue;
				}

				// Determine what type of tow event that it is
				String eventType = null;
				if (message.contains("TowingCreatedNotification")) {
					eventType = "TOW_MOVEMENT_CREATION";
				}
				if (message.contains("TowingUpdatedNotification")) {
					eventType = "TOW_MOVEMENT_UPDATE";
				}
				if (message.contains("TowingDeletedNotification")) {
					eventType = "TOW_MOVEMENT_DELETION";
				}

				// Clean up the message a bit for easier handling
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

				// Prepare the header information
				DateTime dt = new DateTime();
				DateTimeFormatter fmt = ISODateTimeFormat.dateTime();
				String timestamp = fmt.print(dt);
				String uuid = UUID.randomUUID().toString();

				// Put all the pieces of the message together into the correct structure
				String notificationMessage = String.format(notTemplate,timestamp, eventType,uuid, notification);
				notificationMessage = notificationMessage.replace("xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"", "");

				//Put in the correct message type
				if (eventType == "TOW_MOVEMENT_CREATION") {
					notificationMessage = notificationMessage.replace("<Notification", "<TowingCreatedNotification");
					notificationMessage = notificationMessage.replace("</Notification", "</TowingCreatedNotification");
					notificationMessage = notificationMessage.replace("type=\"TowingCreatedNotification\"", "");						
				}
				if (eventType == "TOW_MOVEMENT_UPDATE") {
					notificationMessage = notificationMessage.replace("<Notification", "<TowingUpdatedNotification");
					notificationMessage = notificationMessage.replace("</Notification", "</TowingUpdatedNotification");
					notificationMessage = notificationMessage.replace("type=\"TowingUpdatedNotification\"", "");						

				}
				if (eventType == "TOW_MOVEMENT_DELETION") {
					notificationMessage = notificationMessage.replace("<Notification", "<TowingDeletedNotification");
					notificationMessage = notificationMessage.replace("</Notification", "</TowingDeletedNotification");
					notificationMessage = notificationMessage.replace("type=\"TowingDeletedNotification\"", "");										
				}


				// Get the registration of the flight by extracting flight details and calling a web service to get the flight 

				String rego = "nil";
				try {
					rego = getRegistration(notification);
				} catch (JDOMException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				} catch (IOException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				}

				// Create an element representing the registration
				String regoElement = "<Registration>"+rego+"</Registration>";

				// Substitute it into the end of the message
				notificationMessage = notificationMessage.replaceAll("</FlightIdentifier>", regoElement+"\n</FlightIdentifier>");						

				log.trace("Message Processed");						
				log.debug(notificationMessage);

				try {
					
					boolean sent = sendMessage(notificationMessage, ibmoutqueue);
					if (!sent) {
						log.error("Message Send Error");	
					}

				} catch (Exception e) {
					log.error("Message Send Error");
					log.error(e.getMessage());
				}

			} while (!stopThread);
		}
	}

	private String stripNS(String xml) {
		return xml.replaceAll("xmlns(.*?)=(\".*?\")", "");
	}

	/*
	 * Handle flight update messages. 
	 * There may have been an update to the registration of the aircraft operating the flight
	 * in which case we need to send out an updates TOW_UPDATE message with the new registration
	 */
	public void handleFlightUpdate(String message) {

		log.trace("Handling Updated Notification");

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
			log.trace("Flight Update Does NOT include rego update");
			return;
		} else {
			log.trace("Flight Update DOES include rego update, processing");
		}



		//It does, so construct the flight descriptor
		// The flight descriptor is needed to find the towing events
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
			// Get all the towing events for this flight descriptor
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
						boolean sent = sendMessage(msg, msmqbridge);
						if (!sent) {
							log.error("Flight Update Dummy Message Send Error");
						}
					} catch (Exception e) {
						log.error("Flight Update dummy message Send Error");
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

	// The flight descriptor used by the AMS Webservice looks something like
	//    "6E1713@2019-08-01T09:00A";
	// so we need to construct this from the information in the message

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