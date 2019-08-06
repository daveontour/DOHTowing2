package au.com.quaysystems.doh.towings.web.listeners;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

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
		
		airport = props.getProperty("airport");
		wsurl = props.getProperty("ws.url");
		
		deleteBeforeSync = Boolean.parseBoolean(props.getProperty("deleteBeforeSync", "false"));
		enablePush = Boolean.parseBoolean(props.getProperty("enablePush", "false"));
		syncOnStartUp = Boolean.parseBoolean(props.getProperty("syncOnStartUp", "true"));
		
		refreshPeriod = Integer.parseInt(props.getProperty("refresh.period", "86400000"));
		
		
		this.ams = new AMSServices(token, airport, wsurl);
		
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
				throw new FileNotFoundException("property file '" + propFileName + "' not found in the classpath");
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

	public String getRegistration(String notif) {
		
		// Pattern for getting the flight descriptor from the input message
	    Pattern pDescriptor = Pattern.compile("<FlightDescriptor>(.*)</FlightDescriptor>");
	    
	    // Pattern for getting the registration from the return flight record
	    Pattern pReg = Pattern.compile("<Registration>([a-zA-Z0-9]*)</Registration>");
	    
	    // If any errors occur or registration not available
	    String reg = "nil";


	    //Extract the flight descriptor
	    Matcher m = pDescriptor.matcher(notif);
	    String flightID = null;
	    if (m.find()) {
	    	flightID = m.group(1);
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
			
			// Extract the registration from the flight record returned from AMS
		    Matcher mReg = pReg.matcher(flt);
		    if (mReg.find()) {
		    	reg = mReg.group(1);
		    	
		    	// If we get here, the registration has been found
		    	return reg;
		    }
		    
		    // If we get here, the registration was not found, so just returning the default
		    return reg;
			
		} catch (Exception e) {
			e.printStackTrace();
			
			// Any errors in looking for the registration returns default
			return reg;
		}

	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
		// TODO Auto-generated method stub
		
	}
}