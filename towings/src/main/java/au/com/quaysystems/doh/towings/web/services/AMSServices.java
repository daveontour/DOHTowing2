package au.com.quaysystems.doh.towings.web.services;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AMSServices {

	private final Logger log = LoggerFactory.getLogger(AMSServices.class);
	private String token;
	private String wsurl;

	
	// Hard coded for DOHA
	private String getFlightTemplate = "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:ams6=\"http://www.sita.aero/ams6-xml-api-webservice\" xmlns:wor=\"http://schemas.datacontract.org/2004/07/WorkBridge.Modules.AMS.AMSIntegrationAPI.Mod.Intf.DataTypes\">\r\n" + 
			"   <soapenv:Header/>\r\n" + 
			"   <soapenv:Body>\r\n" + 
			"      <ams6:GetFlight>\r\n" + 
			"         <!--Optional:-->\r\n" + 
			"         <ams6:sessionToken>%s</ams6:sessionToken>\r\n" + 
			"         <!--Optional:-->\r\n" + 
			"         <ams6:flightId>\r\n" + 
			"            <wor:_hasAirportCodes>false</wor:_hasAirportCodes>\r\n" + 
			"            <wor:_hasFlightDesignator>true</wor:_hasFlightDesignator>\r\n" + 
			"            <wor:_hasScheduledTime>false</wor:_hasScheduledTime>\r\n" + 
			"            <wor:airlineDesignatorField>\r\n" + 
			"               <!--Zero or more repetitions:-->\r\n" + 
			"               <wor:LookupCode>\r\n" + 
			"                  <wor:codeContextField>IATA</wor:codeContextField>\r\n" + 
			"                  <wor:valueField>%s</wor:valueField>\r\n" + 
			"               </wor:LookupCode>\r\n" + 
			"            </wor:airlineDesignatorField>\r\n" + 
			"            <wor:airportCodeField>\r\n" + 
			"               <!--Zero or more repetitions:-->\r\n" + 
			"               <wor:LookupCode>\r\n" + 
			"                  <wor:codeContextField>ICAO</wor:codeContextField>\r\n" + 
			"                  <wor:valueField>OTHH</wor:valueField>\r\n" + 
			"               </wor:LookupCode>\r\n" + 
			"            </wor:airportCodeField>\r\n" + 
			"            <wor:flightKindField>%s</wor:flightKindField>\r\n" + 
			"            <wor:flightNumberField>%s</wor:flightNumberField>\r\n" + 
			"            <wor:scheduledDateField>%s</wor:scheduledDateField>\r\n" + 
			"         </ams6:flightId>\r\n" + 
			"      </ams6:GetFlight>\r\n" + 
			"   </soapenv:Body>\r\n" + 
			"</soapenv:Envelope>";

	public AMSServices(
			String token,
			String wsURL)  {
		
		this.token = token;
		this.wsurl = wsURL;
		
		
	}
	
	private String stripNS(String xml) {
    	return xml.replaceAll("xmlns(.*?)=(\".*?\")", "");
	}

	
	public String getFlight( String id) {

	//	String id = "6E1713@2019-08-01T09:00A";
		
		// id is in the form of the descriptor on the Towing messages
		
		String fltNum = id.substring(2, id.indexOf("@"));
		String airline  = id.substring(0,2);
		String sched = id.substring(id.indexOf("@")+1, id.indexOf("@")+11);
		String kind = "Arrival";

		if (id.substring(id.length()-1).equals("A")) {
			kind = "Arrival";
		} else {
			kind = "Departure";
		}

		

		String payload = String.format(getFlightTemplate, token,airline, kind, fltNum, sched);
		
		StringEntity entity = null;
		entity = new StringEntity(payload, ContentType.APPLICATION_XML);

	
		HttpClient client = HttpClientBuilder.create().build();
		HttpUriRequest request = RequestBuilder.post()
				.setUri(this.wsurl)
				.setHeader("SOAPAction", "http://www.sita.aero/ams6-xml-api-webservice/IAMSIntegrationService/GetFlight")
				.setHeader("Content-Type","text/xml;charset=UTF-8")
				.setEntity(entity)
				.build();

		try {

			HttpResponse response = client.execute(request);
			int statusCode = response.getStatusLine().getStatusCode();

			if (statusCode == HttpStatus.SC_OK) {
				return stripNS(EntityUtils.toString(response.getEntity()));
			} else {
				log.error("GET FAILURE");
				return "<Status>Failed</Failed>";
			}
		} catch (Exception e) {
			log.error("AMS WebService ERROR");
			log.error(e.getMessage());
			e.printStackTrace();
			return "<Status>Failed</Failed>";
		} 		
	}
}
