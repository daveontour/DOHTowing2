package au.com.quaysystems.doh.towings.web.listeners;

import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
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
import org.joda.time.Period;
import org.joda.time.PeriodType;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.LoggerFactory;

import com.ibm.mq.MQException;
import com.ibm.mq.constants.MQConstants;

import au.com.quaysystems.doh.towings.web.mq.MReceiver;
import au.com.quaysystems.doh.towings.web.mq.MSender;
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

//	private String template = "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\r\n" + 
//			" <soap:Header correlationID=\"%s\"></soap:Header>\r\n" + 
//			" <soap:Body>\r\n" + 
//			"  <ArrayOfTowing xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">\r\n" + 
//			"   %s\r\n"+
//			"  </ArrayOfTowing>\r\n" + 
//			" </soap:Body>\r\n" + 
//			"</soap:Envelope>";
//	
//	private String syncTemplate = "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\r\n" + 
//			" <soap:Header correlationID=\"SITASYNC\"><Sync>true</Sync></soap:Header>\r\n" + 
//			" <soap:Body>\r\n" + 
//			"  <ArrayOfTowing xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">\r\n" + 
//			"   %s\r\n"+
//			"  </ArrayOfTowing>\r\n" + 
//			" </soap:Body>\r\n" + 
//			"</soap:Envelope>";
	
	private String template2 = "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\"  xmlns:aip=\"http://www.sita.aero/aip/XMLSchema\"  xmlns:ams-mob=\"http://www.sita.aero/ams6-xml-mobilize\"  encodingStyle=\"http://www.w3.org/2001/12/soap-encoding\">\r\n" + 
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
	
	private String syncTemplate2 = "<soap:Envelope xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"   xmlns:aip=\"http://www.sita.aero/aip/XMLSchema\">\r\n" + 
			" <soap:Header>%s</soap:Header>\r\n" + 
			" <soap:Body>\r\n" + 
			"  <ArrayOfTowing xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">\r\n" + 
			"   %s\r\n"+
			"  </ArrayOfTowing>\r\n" + 
			" </soap:Body>\r\n" + 
			"</soap:Envelope>";
	String macsRubbish = "<soap:MessageMetadata>\r\n" + 
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
	
	String macsRubbishSync = "<soap:MessageMetadata>\r\n" + 
			"      <aip:Source>SITA</aip:Source>\r\n" + 
			"      <aip:Timestamp>%s</aip:Timestamp>\r\n" + 
			"      <aip:MessageType>PublishFlightDataInput</aip:MessageType>\r\n" + 
			"      <aip:ExtensionFields>\r\n" + 
			"        <aip:ExtensionField Name=\"EventType\" >\r\n" + 
			"          <aip:Value Type=\"String\" >\r\n" + 
			"            <aip:String>TOW_MOVEMENT_UPDATE</aip:String>\r\n" + 
			"          </aip:Value>\r\n" + 
			"        </aip:ExtensionField>\r\n" + 
			"      </aip:ExtensionFields>\r\n" + 
			"      <aip:UUID>%s</aip:UUID>\r\n" + 
			"    </soap:MessageMetadata>\r\n" + 
			"    <soap:OperationData>\r\n" + 
			"      <aip:OperationName/>\r\n" + 
			"      <aip:CorrelationID></aip:CorrelationID>\r\n" + 
			"    </soap:OperationData>";
	


	String queryBody = 
			"declare variable $var1 as xs:string external;\n"+
					"for $x in fn:parse-xml($var1)//Towing\r\n" + 
					"return $x";
	private final DateTimeFormatter dtf = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss");
	public boolean stopThread = false;

	private Timer periodicTimer;


	@Override
	public void contextInitialized(ServletContextEvent servletContextEvent) {

		log = (Logger)LoggerFactory.getLogger(RequestListener.class);
		super.contextInitialized(servletContextEvent);


		// Schedule the periodic sync
		if (enablePush) {
			this.periodicRefresh();
		}

		// Initialize the output queue by sending the current set of tows
		if (syncOnStartUp) {
			log.info("===> Start Of Initial Population");
			try {
				this.getAndSendTows();
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			log.info("<=== End Of Initial Population");
		}

		// Start the listener for incoming requests
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

	public void periodicRefresh() {

		log.info("===> Scheduling period resync");
		log.info("Time between resync events = "+ refreshPeriod + "(ms)");

		TimerTask dailyTask = new TimerTask() {
			public void run() {
				log.info("DAILY REFRESH TASK - START");
				try {
					MSender send = new MSender(ibmoutqueue, host, qm, channel,  port,  user,  pass);

					//Clear the queue since this is a refresh
					if (deleteBeforeSync) {
						send.clearQueue();
					}

					getAndSendTows();
					log.info("PERIODIC REFRESH TASK - COMPLETE");
				} catch (Exception e) {
					e.printStackTrace();
					log.error("PERIODIC REFRESH TASK - UNSUCCESSFUL");
				}
			}
		};

		// Set the time that the daily sync runs
		DateTime sched = new DateTime()
				.withHourOfDay(Integer.parseInt(refreshTimeStr.split(":")[0]))
				.withMinuteOfHour(Integer.parseInt(refreshTimeStr.split(":")[1]))
				.withMinuteOfHour(0)
				.withSecondOfMinute(0)
				.withMillisOfSecond(0);

		// Change time to the scheduled time based on the refresh period
		while (sched.isBeforeNow()) {
			sched = sched.plusMillis(refreshPeriod);
		}	

		DateTime now = new DateTime();

		Period p = new Period(now, sched, PeriodType.millis());
		long delay = Math.abs(p.getValue(0));

		periodicTimer = new Timer("Periodic Refresh");
		periodicTimer.schedule(
				dailyTask,
				delay,
				refreshPeriod
				);

		log.info("<=== First Periodic Sync Scheduled at: "+sched.toDate().toString()+" with refresh of "+refreshPeriod+"(ms)");
	}

	public boolean getAndSendTows() throws Exception{

		DateTime dt = new DateTime();
		DateTime fromTime = new DateTime(dt.plusMinutes(fromMin));
		DateTime toTime = new DateTime(dt.plusMinutes(toMin));

		String from = dtf.print(fromTime);
		String to = dtf.print(toTime);

		String response = getTows(from,to);
		String towingsXML = this.getTowingsXML(response);
		
		// New header for MACS
		DateTime dt2 = new DateTime();
		DateTimeFormatter fmt = ISODateTimeFormat.dateTime();
		String timestamp = fmt.print(dt2);
		String uuid = UUID.randomUUID().toString();
		String header = String.format(macsRubbishSync, timestamp, uuid );
		String msg2 = String.format(syncTemplate2, header, towingsXML);
		msg2 = msg2.replace("xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"", "");	
		
		
		//String msg = String.format(syncTemplate, this.getTowingsXML(response));

		try {
			MSender send = new MSender(ibmoutqueue, host, qm, channel,  port,  user,  pass);
			send.mqPut(msg2);
			log.info("Sync Sent");
			
			try {
				send.disconnect();
			} catch (Exception e) {
				log.error("Send Disconnect Error - probably not fatal");
			}
			
			return true;
		} catch (Exception e) {
			log.error("Sync Send Error");
			log.error(e.getMessage());
			return false;
		}
	}

	public String getTowingsXML(String input) {
		String towings = "";
		Context context = new Context();
		try  {
			QueryProcessor proc = new QueryProcessor(queryBody, context);
			proc.bind("var1", input);
			Iter iter = proc.iter();
			for (Item item; (item = iter.next()) != null;) {
				String tow = item.serialize().toString();
				String rego = "<Registration>"+getRegistration(tow)+"</Registration>";
				tow = tow.replaceAll("</FlightIdentifier>", rego+"\n</FlightIdentifier>");						
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
				MReceiver recv = connectToMQ(ibminqueue);
				if (recv == null) {
					log.error(String.format("Exceeded IBM MQ connect retry limit {%s}. Exiting", retriesIBMMQ));
					return;
				}

				log.info(String.format("Conected to queue %s", ibminqueue));


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
								log.debug("No Request Messages");
								if (stopThread) {
									log.info("Stopping Request Listener Thread");
									return;
								}
								continue;
							}
						}
						log.debug("Request Message Received");
						try {
							message = message.substring(message.indexOf("<"));
						} catch (Exception e1) {
							log.info("Badly formatted request received");
							log.debug(message);
						}
						

						DateTime dt = new DateTime();
						DateTime fromTime = new DateTime(dt.plusMinutes(fromMin));
						DateTime toTime = new DateTime(dt.plusMinutes(toMin));

						String from = dtf.print(fromTime);
						String to = dtf.print(toTime);
						String correlationID = "-";
						
						// Extract the from and to times from the incoming message
						
						Document xmlDoc = getDocumentFromString(message);
						Element root = xmlDoc.getRootElement();
						
						ArrayList<Namespace> ns = new ArrayList<>();
						ns.add(Namespace.getNamespace("soap", "http://www.w3.org/2001/12/soap-envelope"));
						ns.add(Namespace.getNamespace("aip", "http://www.sita.aero/aip/XMLSchema"));					
						XPathFactory xpfac = XPathFactory.instance();
						
						XPathExpression<Element> xp = xpfac.compile("//soap:Body//aip:RangeFrom", Filters.element(),null,ns);
						
						try {
							from = xp.evaluateFirst(root).getValue();
						} catch (Exception e3) {
							from = dtf.print(fromTime);;
						}
						
						try {
							xp = xpfac.compile("//soap:Body//aip:RangeTo", Filters.element(),null,ns);
							to = xp.evaluateFirst(root).getValue();
						} catch (Exception e2) {
							to = dtf.print(toTime);
						}
						
						try {
							xp = xpfac.compile("//soap:Header//aip:CorrelationID", Filters.element(),null,ns);
							correlationID = xp.evaluateFirst(root).getValue();
						} catch (Exception e1) {
							correlationID = "-";
						}

						
						log.debug(correlationID+"  "+from+" "+to);

						String response = getTows(from,to);
						String towingsXML = getTowingsXML(response);
						
						// New header for MACS
						DateTime dt2 = new DateTime();
						DateTimeFormatter fmt = ISODateTimeFormat.dateTime();
						String timestamp = fmt.print(dt2);
						String uuid = UUID.randomUUID().toString();
						String header = String.format(macsRubbish, timestamp, uuid, correlationID );
						String msg2 = String.format(template2, header, towingsXML);
						msg2 = msg2.replace("xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"", "");
						
						
						//String msg = String.format(template, correlationID, towingsXML);

						try {
							MSender send = new MSender(ibmoutqueue, host, qm, channel,  port,  user,  pass);
							send.mqPut(msg2);
							log.debug("Request Response Sent");
							log.trace(msg2);
							continueOK = false;

							try {
								send.disconnect();
							} catch (Exception e) {
								log.error("Send Disconnect Error - probably not fatal");
								continueOK = false;
							}

						} catch (Exception e) {
							log.error("Request Response Send Error");
							log.error(e.getMessage());
							continueOK = false;
						}
					} catch (Exception e) {
						log.error("Unhandled Exception "+e.getMessage());
						e.printStackTrace();
						//						recv.disconnect();
						continueOK = false;
					}
				} while (continueOK && !stopThread);
			} while (!stopThread);
		}
	}
}