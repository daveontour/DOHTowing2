package au.com.quaysystems.doh.towings.web.services;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.PropertyException;
import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.client.core.WebServiceMessageCallback;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.SoapMessage;

import au.com.quaysystems.doh.towings.web.ws.AirportIdentifierType;
import au.com.quaysystems.doh.towings.web.ws.ArrayOfLookupCode;
import au.com.quaysystems.doh.towings.web.ws.CodeContext;
import au.com.quaysystems.doh.towings.web.ws.FlightId;
import au.com.quaysystems.doh.towings.web.ws.FlightKind;
import au.com.quaysystems.doh.towings.web.ws.GetFlight;
import au.com.quaysystems.doh.towings.web.ws.LookupCode;
import au.com.quaysystems.doh.towings.web.ws.ObjectFactory;

public class AMSServices {

	private final Logger log = LoggerFactory.getLogger(AMSServices.class);
	private Jaxb2Marshaller marshaller;
	private WebServiceTemplate ws;
	private JAXBContext context;
	private Marshaller m;
	private ObjectFactory fact;
	private JAXBElement<String> token;
	private JAXBElement<String> airport;
	private AirportIdentifierType apType = AirportIdentifierType.IATA_CODE;

	public AMSServices(
			String toke,
			String ap,
			String wsURL)  {
		
		fact = new ObjectFactory();
		marshaller = new Jaxb2Marshaller();
		marshaller.setContextPath("au.com.quaysystems.doh.towings.web.ws");
		ws = new WebServiceTemplate();
		ws.setDefaultUri(wsURL);
		ws.setMarshaller(marshaller);
		ws.setUnmarshaller(new StringUnmarshaller());
		
		this.token = fact.createGetFlightsSessionToken(toke);
		this.airport = fact.createGetFlightsAirport(ap);
		
		try {
			context = JAXBContext.newInstance("au.com.quaysystems.doh.towings.web.ws");
			m = context.createMarshaller();
			m.setProperty(Marshaller.JAXB_ENCODING, "iso-8859-15");
			m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
		} catch (PropertyException e) {
			log.error(e.getMessage());
			e.printStackTrace();
		} catch (JAXBException e) {
			log.error(e.getMessage());
			e.printStackTrace();
		}
	}
	private String callWebService(final String service, Object payload) throws JAXBException {
		
		Object response =  ws.marshalSendAndReceive(payload, new WebServiceMessageCallback() {
			public void doWithMessage(WebServiceMessage message) {
				((SoapMessage)message).setSoapAction(service);
			}
		});
		
		return (String)response;
	}
	
	private String stripNS(String xml) {
    	return xml.replaceAll("xmlns(.*?)=(\".*?\")", "");
	}

	public String getFlight( String id) throws DatatypeConfigurationException, JAXBException {

	//	String id = "6E1713@2019-08-01T09:00A";
		
		// id is in the form of the descriptor on the Towing messages
		
		FlightId fltID = new FlightId();
		
		String num = id.substring(2, id.indexOf("@"));
		fltID.setFlightNumberField(num);

		String airline  = id.substring(0,2);
		LookupCode alLookupCode = new LookupCode();
		alLookupCode.setValueField(airline);
		alLookupCode.setCodeContextField(CodeContext.IATA);
		ArrayOfLookupCode arrlc = new ArrayOfLookupCode();
		arrlc.getLookupCode().add(alLookupCode);
		fltID.setAirlineDesignatorField(arrlc);

		String sched = id.substring(id.indexOf("@")+1, id.length()-2);
		DateTime dt = new DateTime(sched);
		XMLGregorianCalendar d = DatatypeFactory.newInstance().newXMLGregorianCalendar(dt.toGregorianCalendar());
		fltID.setScheduledDateField(d);
		
		String kind = id.substring(id.length()-1);
		if (kind.equals("A")) {
			fltID.setFlightKindField(FlightKind.ARRIVAL);
		} else {
			fltID.setFlightKindField(FlightKind.DEPARTURE);
		}
		
		LookupCode apLookupCode = new LookupCode();
		
		// Specific to Doha
		apLookupCode.setValueField("OTHH");
		apLookupCode.setCodeContextField(CodeContext.ICAO);
		ArrayOfLookupCode aplc = new ArrayOfLookupCode();
		aplc.getLookupCode().add(apLookupCode);
		fltID.setAirportCodeField(aplc);
		
		GetFlight request = new GetFlight();
		request.setSessionToken(this.token);
		request.setFlightId(this.fact.createGetFlightFlightId(fltID));
		
		return stripNS(callWebService("http://www.sita.aero/ams6-xml-api-webservice/IAMSIntegrationService/GetFlight", request));
	}
}
