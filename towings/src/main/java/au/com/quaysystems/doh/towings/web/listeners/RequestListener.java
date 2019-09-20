package au.com.quaysystems.doh.towings.web.listeners;

import java.util.ArrayList;
import java.util.Timer;
import java.util.UUID;

import javax.servlet.ServletContextEvent;
import javax.servlet.annotation.WebListener;

import org.basex.core.Context;
import org.basex.query.QueryProcessor;
import org.basex.query.iter.Iter;
import org.basex.query.value.item.Item;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.xpath.XPathExpression;
import org.jdom2.xpath.XPathFactory;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.LoggerFactory;

import com.ibm.mq.MQException;
import com.ibm.mq.constants.MQConstants;

import ch.qos.logback.classic.Logger;

/*
 * Two Purposes:
 * 
 * 1. Listen for incoming request on the input queue for Tows within a specificed period
 * 2. Schedule the periodic resync which by defualt is daily
 * 
 * Also does the sync on startup.
 * 
 * Towings are retrieved using the AMS RESTAPI Server.
 * The customer required registration of the aircraft in the response, so flight descriptor
 * information in the towing is extracted and used with the AMS Web Services to get the flight for each  of 
 * the tow records. 
 * 
 * Uses method in the base class to extract flight descriptor for towing message and get registration for AMS
 * 
 * Incoming messages may have a correlationID, which is returned in the response
 * Incoming messages are not parsed or checked for validity
 * 
 * Sync pushes have an element in the header to indicate a push. 
 * 
 * The handling and construction of XML documents is inelegant. Regex is used to extract values rather 
 * than a parser. Elements are added by String substitution. This was deliberate to try keep it as light-weight
 * as possible. 
 * 
 * One exception to the above is the use of BaseX FLWOR queries to get the individual tow events for <ArrayOfTowing>
 * 
 */

@WebListener
public class RequestListener extends TowContextListenerBase {


	private String messageTemplate = "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\"  xmlns:aip=\"http://www.sita.aero/aip/XMLSchema\"  xmlns:ams-mob=\"http://www.sita.aero/ams6-xml-mobilize\"  encodingStyle=\"http://www.w3.org/2001/12/soap-encoding\">\r\n" + 
			"  <soap:Header>"+
			"	%s\r\n" + 
			"</soap:Header>\r\n" + 
			"  <soap:Body>\r\n" + 
			" <ams-mob:AMS_Mobilize_Message>"+
			"  <ArrayOfTowing xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">\r\n" + 
			"   %s\r\n"+
			"  </ArrayOfTowing>\r\n" + 
			" </ams-mob:AMS_Mobilize_Message>"+
			"  </soap:Body>\r\n" + 
			" </soap:Envelope>";

	String headerTemplate = "<soap:MessageMetadata>\r\n" + 
			"      <aip:Source>SITA</aip:Source>\r\n" + 
			"      <aip:Timestamp>%s</aip:Timestamp>\r\n" + 
			"      <aip:MessageType>PublishFlightDataInput</aip:MessageType>\r\n" + 
			"      <aip:ExtensionFields>\r\n" + 
			"        <aip:ExtensionField Name=\"EventType\" >\r\n" + 
			"          <aip:Value Type=\"String\" >\r\n" + 
			"            <aip:String>TOW_REQUEST_RESPONSE</aip:String>\r\n" + 
			"          </aip:Value>\r\n" + 
			"        </aip:ExtensionField>\r\n" + 
			"      </aip:ExtensionFields>\r\n" + 
			"      <aip:UUID>%s</aip:UUID>\r\n" + 
			"    </soap:MessageMetadata>\r\n" + 
			"    <soap:OperationData>\r\n" + 
			"      <aip:OperationName/>\r\n" + 
			"      <aip:CorrelationID>%s</aip:CorrelationID>\r\n" + 
			"    </soap:OperationData>";


	private final DateTimeFormatter dtf = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss");
	public boolean stopThread = false;

	private Timer periodicTimer;


	@Override
	public void contextInitialized(ServletContextEvent servletContextEvent) {

		log = (Logger)LoggerFactory.getLogger(RequestListener.class);
		super.contextInitialized(servletContextEvent);
		this.startListener();

	}

	@Override
	public void contextDestroyed(ServletContextEvent servletContextEvent) {
		this.stopThread = true;
		if (periodicTimer != null) {
			periodicTimer.cancel();
		}
	}

	public void startListener() {
		log.info("===> Start Request Listner Loop");
		t = new RequestListenerLoop();
		t.setName("Request Process");
		t.start();
		log.info("<=== Request Listner Loop Started");
	}

	public String getTowingsXML(String input) {

		// The query to be used by the FLWOR query
		String queryBody = 
				"declare variable $var1 as xs:string external;\n"+
						"for $x in fn:parse-xml($var1)//Towing\r\n" + 
						"return $x";
		String towings = "";
		Context context = new Context();
		try  {
			QueryProcessor proc = new QueryProcessor(queryBody, context);
			proc.bind("var1", input);
			Iter iter = proc.iter();
			for (Item item; (item = iter.next()) != null;) {
				String tow = item.serialize().toString();

				// The Tow XML does not include the registration, so go get the registration
				// if available and insert it.
				String rego = "<Registration>"+getRegistration(tow)+"</Registration>";

				// Hack it at the the end of the XML 
				tow = tow.replaceAll("</FlightIdentifier>", rego+"\n</FlightIdentifier>");

				// Add it to the existing 
				towings = towings.concat(tow).concat("\r\n");				
			}
			proc.close();
		} catch (Exception ex) {
			return "";
		}
		return towings;
	}

	public class RequestListenerLoop extends Thread {

		public void run() {

			do {
				String message = null;
				try {
					message = getRequestMessage(ibminqueue);
				} catch (MQException ex) {
					if ( ex.completionCode == 2 && ex.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) {
						continue;
					} else {
						log.error("Unhandled  MQ Error Getting Message");
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

				log.debug("Request Message Received");
				try {
					// Just the XML. Avoid any rubbish at the start of the string
					message = message.substring(message.indexOf("<"));
				} catch (Exception e1) {
					log.info("Badly formatted request received");
					log.debug(message);
					continue;
				}


				// Set up the default range and correlationID
				DateTime dt = new DateTime();
				DateTime fromTime = new DateTime(dt.plusMinutes(fromMin));
				DateTime toTime = new DateTime(dt.plusMinutes(toMin));

				String from = dtf.print(fromTime);
				String to = dtf.print(toTime);
				String correlationID = "-";

				// Extract the from and to times from the incoming message					
				Document xmlDoc = null;
				try {
					xmlDoc = getDocumentFromString(message);
				} catch (Exception e4) {
					log.info("Badly formatted request received");
					log.debug(message);
					e4.printStackTrace();
					continue;
				} 
				if (xmlDoc == null) {
					log.info("Badly formatted request received");
					log.debug(message);
					continue;
				}

				Element root = xmlDoc.getRootElement();

				ArrayList<Namespace> ns = new ArrayList<>();
				ns.add(Namespace.getNamespace("soap", "http://www.w3.org/2001/12/soap-envelope"));
				ns.add(Namespace.getNamespace("aip", "http://www.sita.aero/aip/XMLSchema"));					
				XPathFactory xpfac = XPathFactory.instance();

				//From time
				try {
					XPathExpression<Element> xpathExpression = xpfac.compile("//soap:Body//aip:RangeFrom", Filters.element(),null,ns);
					from = xpathExpression.evaluateFirst(root).getValue();
				} catch (Exception e3) {
					//Use the default
					from = dtf.print(fromTime);;
				}

				//To time
				try {
					XPathExpression<Element> xpathExpression = xpfac.compile("//soap:Body//aip:RangeTo", Filters.element(),null,ns);
					to = xpathExpression.evaluateFirst(root).getValue();
				} catch (Exception e2) {
					//Use the default
					to = dtf.print(toTime);
				}

				//Correlation ID
				try {
					XPathExpression<Element> xpathExpression = xpfac.compile("//soap:Header//aip:CorrelationID", Filters.element(),null,ns);
					correlationID = xpathExpression.evaluateFirst(root).getValue();
				} catch (Exception e1) {
					correlationID = "-";
				}


				log.debug(correlationID+"  "+from+" "+to);

				// We have the required range, so now go to AMS and get the towing events in the specified range
				String towingEvents = null;
				try {
					towingEvents = getTows(from,to);
				} catch (Exception e1) {
					log.error("Unable to retrieve towing events");
					e1.printStackTrace();
					towingEvents = null;
				} 

				// Add the aircraft registration to the tow events that were returned
				String towingsXML = getTowingsXML(towingEvents);

				// Prepare the header of the response message
				DateTime dt2 = new DateTime();
				DateTimeFormatter fmt = ISODateTimeFormat.dateTime();
				String timestamp = fmt.print(dt2);
				String uuid = UUID.randomUUID().toString();
				String header = String.format(headerTemplate, timestamp, uuid, correlationID );

				// Add the header and body together
				String responseMessage = String.format(messageTemplate, header, towingsXML);
				responseMessage = responseMessage.replace("xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"", "");

				try {

					// Send the message to the output queue

					boolean sent = sendMessage(responseMessage, ibmoutqueue);
					if (!sent) {
						log.error("Request Response Send Error");	
					} else {
						log.debug("Request Response Sent");
					}

				} catch (Exception e) {
					log.error("Request Response Send Error");
					log.error(e.getMessage());
				}
			} while (!stopThread);
		}
	}
}