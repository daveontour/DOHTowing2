package au.com.quaysystems.doh.towings.web.listeners;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Properties;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.xpath.XPathExpression;
import org.jdom2.xpath.XPathFactory;

import au.com.quaysystems.doh.towings.web.mq.MReceiver;
import au.com.quaysystems.doh.towings.web.services.AMSServices;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;


public class TowContextListenerBase implements ServletContextListener {


	protected Logger log;
	protected AMSServices ams;

	protected Thread t;

	protected String logLevel;

	protected int fromMin;
	protected int toMin;
	protected String token;
	protected String ibminqueue;

	protected String towRequestURL;

	protected String refreshTimeStr;
	protected String msmqbridge;
	protected String ibmoutqueue;

	protected int msgRecvTimeout;
	protected int retriesIBMMQ;

	protected String host;
	protected String qm;
	protected String channel;
	protected int port;
	protected String user;
	protected String pass;

	protected String airport;
	protected String wsurl;

	protected boolean deleteBeforeSync;
	protected int refreshPeriod;

	protected boolean enablePush;
	protected boolean syncOnStartUp;

	protected Properties props;
	private int httpRequestTimeout;

	@Override
	public void contextInitialized(ServletContextEvent servletContextEvent) {

		// Load all the properties used by the sub classes
		try {
			props = getProperties();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		logLevel = props.getProperty("log.level", "INFO");
		refreshTimeStr = props.getProperty("daily.refresh.time","5:00");
		toMin = Integer.parseInt(props.getProperty("toMin", "1400"));
		fromMin = Integer.parseInt(props.getProperty("fromMin", "-1400"));
		token = props.getProperty("token");
		ibminqueue = props.getProperty("mq.ibminqueue");
		ibmoutqueue = props.getProperty("mq.ibmoutqueue");

		host = props.getProperty("mq.host");
		qm = props.getProperty("mq.qmgr");
		channel = props.getProperty("mq.channel");
		port = Integer.parseInt(props.getProperty("mq.port"));
		user = props.getProperty("mq.user");
		pass = props.getProperty("mq.pass");

		msgRecvTimeout = Integer.parseInt(props.getProperty("msg.recv.timeout", "5000"));
		retriesIBMMQ = Integer.parseInt(props.getProperty("ibmmq.retries", "0"));

		msmqbridge = props.getProperty("mq.msmqbridgequeue");
		ibmoutqueue = props.getProperty("mq.ibmoutqueue");

		towRequestURL = props.getProperty("towrequest.url", "http://localhost:80/api/v1/DOH/Towings/%s/%s");
		httpRequestTimeout = Integer.parseInt(props.getProperty("httpRequestTimeout", "10000"));

		airport = props.getProperty("airport");
		wsurl = props.getProperty("ws.url");

		deleteBeforeSync = Boolean.parseBoolean(props.getProperty("deleteBeforeSync", "false"));
		enablePush = Boolean.parseBoolean(props.getProperty("enablePush", "false"));
		syncOnStartUp = Boolean.parseBoolean(props.getProperty("syncOnStartUp", "true"));

		refreshPeriod = Integer.parseInt(props.getProperty("refresh.period", "86400000"));


		this.ams = new AMSServices(token, wsurl);

		// Set the configured logging level
		this.setLogLevel();


		return;
	}

	public Properties getProperties() throws IOException {

		
		InputStream inputStream = null;
		Properties props = new Properties();

		try {
			String propFileName = "application.properties";
 
			inputStream = getClass().getClassLoader().getResourceAsStream(propFileName);
 
			if (inputStream != null) {
				props.load(inputStream);
			} else {
				try {
					File initialFile = new File("C:/Users/dave_/Desktop/application.properties");
					inputStream = new FileInputStream(initialFile);
					props.load(inputStream);
				} catch (Exception e) {
					throw new FileNotFoundException("property file '" + propFileName + "' not found in the classpath");
				}
			}
 
 		} catch (Exception e) {
			System.out.println("Exception: " + e);
		} finally {
			inputStream.close();
		}
		return props;

	}


	public void setLogLevel() {

		switch(logLevel) {
		case "TRACE" :
			log.setLevel(Level.TRACE);
			break;
		case "ERROR" :
			log.setLevel(Level.ERROR);
			break;
		case "WARN" :
			log.setLevel(Level.WARN);
			break;
		case "INFO" :
			log.setLevel(Level.INFO);
			break;
		case "DEBUG" :
			log.setLevel(Level.DEBUG);
			break;
		case "OFF" :
			log.setLevel(Level.OFF);
			break;
		}		
	}

	public MReceiver connectToMQ(String queue) {
		//Try connection to the IBMMQ Queue until number of retries exceeded

		boolean connectionOK = false;
		int tries = 0;
		MReceiver recv = null;
		do {
			try {
				tries = tries + 1;
				recv = new MReceiver(queue, host, qm, channel,  port,  user,  pass);
				connectionOK = true;
				tries = 0;
			} catch (Exception ex) {
				log.error(String.format("Error connection to source queue: Error Message %s ",ex.getMessage()));
				connectionOK = false;
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		} while (!connectionOK  && (tries < retriesIBMMQ || retriesIBMMQ == 0));

		if (connectionOK) {
			return recv;
		} else {
			return null;
		}
	}

	public String getRegistration(String notif) throws JDOMException, IOException {
		
		Document xmlDoc = getDocumentFromString(notif);
		Element root = xmlDoc.getRootElement();
		
		ArrayList<Namespace> ns = new ArrayList<>();
		ns.add(Namespace.getNamespace("s", "http://schemas.xmlsoap.org/soap/envelope/"));
		ns.add(Namespace.getNamespace("amsws", "http://www.sita.aero/ams6-xml-api-webservice"));					
		ns.add(Namespace.getNamespace("amsdt", "http://www.sita.aero/ams6-xml-api-datatypes"));					
		XPathFactory xpfac = XPathFactory.instance();
		
		XPathExpression<Element> xp = xpfac.compile("//FlightDescriptor", Filters.element(),null,ns);

		String reg = "nil";
		String flightID = null;
		
		try {
			flightID = xp.evaluateFirst(root).getValue();
		} catch (Exception e1) {
			flightID = null;
		}


		if (flightID == null) {
			return reg;
		}

		try {
			// Use the AMS Web Services to get the flight using the flight descriptor
			String flt = ams.getFlight(flightID);
				
			if (flt == null) {
				return reg;
			} 

			xmlDoc = getDocumentFromString(flt);
			root = xmlDoc.getRootElement();
			XPathExpression<Element> xp2 = xpfac.compile("//s:Body//amsdt:Registration", Filters.element(),null,ns);

			try {
				reg = xp2.evaluateFirst(root).getValue();
			} catch (Exception e) {
				reg = "nil";
			}

			return reg;

		} catch (Exception e) {
			e.printStackTrace();

			// Any errors in looking for the registration returns default
			return reg;
		}

	}

	public String getTows(String from, String to) throws ClientProtocolException, IOException {

		String URI = String.format(towRequestURL, from, to);
		log.trace("Get Tow URL Created: "+ URI);
		
		RequestConfig requestConfig = RequestConfig.custom()
			    .setConnectionRequestTimeout(httpRequestTimeout)
			    .setConnectTimeout(httpRequestTimeout)
			    .setSocketTimeout(httpRequestTimeout)
			    .build();

		HttpClient client = HttpClientBuilder.create().build();
		HttpUriRequest request = RequestBuilder.get()
				.setUri(URI)
				.setHeader("Authorization", token)
				.setConfig(requestConfig)
				.build();
		

		HttpResponse response = client.execute(request);
		int statusCode = response.getStatusLine().getStatusCode();

		if (statusCode == HttpStatus.SC_OK) {
			log.debug("Get Tow Information from AMS Succeeded");
			String res = EntityUtils.toString(response.getEntity());
			log.debug(res);
			return res;
		} else {
			log.error(String.format("Get Tow information from AMS failed. HTTP Status Code: %s", statusCode));
			return "<Status>Failed</Status>";
		}				    
	}

	public String getTow(String fltDescriptor) throws ClientProtocolException, IOException {

		String url = towRequestURL.substring(0, towRequestURL.indexOf("Tow"))+fltDescriptor+"/Towings";
		log.trace("Get Towing URL Created: "+ url);
		
		RequestConfig requestConfig = RequestConfig.custom()
			    .setConnectionRequestTimeout(httpRequestTimeout)
			    .setConnectTimeout(httpRequestTimeout)
			    .setSocketTimeout(httpRequestTimeout)
			    .build();


		HttpClient client = HttpClientBuilder.create().build();
		HttpUriRequest request = RequestBuilder.get()
				.setUri(url)
				.setHeader("Authorization", token)
				.setConfig(requestConfig)
				.build();

		HttpResponse response = client.execute(request);
		int statusCode = response.getStatusLine().getStatusCode();

		if (statusCode == HttpStatus.SC_OK) {
			return EntityUtils.toString(response.getEntity());
		} else {
			log.error("GET FAILURE");
			return "<Status>Failed</Failed>";
		}				    
	}
	
	public Document getDocumentFromString(String string) throws JDOMException, IOException {
	    if (string == null) {
	        throw new IllegalArgumentException("string may not be null");
	    }

	    byte[] byteArray = null;
	    try {
	        byteArray = string.getBytes("UTF-8");
	    } catch (UnsupportedEncodingException e) {
	    }
	    ByteArrayInputStream baos = new ByteArrayInputStream(byteArray);

	    // Reader reader = new StringReader(hOCRText);
	    SAXBuilder builder = new SAXBuilder();
	    Document document = builder.build(baos);

	    return document;
	}
	


	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		// TODO Auto-generated method stub

	}
}