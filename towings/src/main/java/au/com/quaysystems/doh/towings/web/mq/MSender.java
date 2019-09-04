/*
 * Utility Class for accessing and sending messages to 
 * a specified queue
 * 
 */

package au.com.quaysystems.doh.towings.web.mq;

import com.ibm.mq.MQEnvironment;
import com.ibm.mq.MQException;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQPutMessageOptions;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;

public class MSender extends MBase{

	public String qName;
	private int openOptions = MQConstants.MQOO_OUTPUT | MQConstants.MQOO_INQUIRE | MQConstants.MQOO_INPUT_AS_Q_DEF;

	public MSender(String q, String host, String qm, String channel, int port, String user, String pass) {
		
		this.qName = q;
		
		if (!host.contains("NONE")) {
			MQEnvironment.hostname = host;
		}
		if (!channel.contains("NONE")) {
			MQEnvironment.channel = channel;
		}
		if (port != 0) {
			MQEnvironment.port = port;
		}
		if (!user.contains("NONE")) {
			MQEnvironment.userID = user;
		}
		if (!pass.contains("NONE")) {
			MQEnvironment.password = pass;
		}

		try {
			this.qMgr = new MQQueueManager(qm);
			this.queue = qMgr.accessQueue(q, openOptions, null, null, null);
		} catch (MQException e) {		
			e.printStackTrace();
		}
	}


	public boolean mqPut(String msg, boolean persistent, boolean noFail) {
		try {

			// Define a MQ message buffer
			MQMessage mBuf = new MQMessage();

			//Set the message persistence on (defined on queue as default, so not really necessary)
			if (persistent) {
				mBuf.persistence =  MQConstants.MQPER_PERSISTENT;
			} else {
				mBuf.persistence =  MQConstants.MQPER_NOT_PERSISTENT;
			}
			mBuf.clearMessage();                // reset the buffer
			mBuf.correlationId = MQConstants.MQCI_NONE; // set correlationId
			mBuf.messageId = MQConstants.MQMI_NONE;     // set messageId


			// create message options
			MQPutMessageOptions pmo = new MQPutMessageOptions();

			mBuf.writeString(msg);
			queue.put(mBuf, pmo);      // put the message out on the queue

		} catch (MQException ex) {
			if (!noFail) {
				System.out.println(	"MQ sending exception occurred : Completion code "+ ex.completionCode + " Reason code "	+ ex.reasonCode);
			}
			return false;

		} catch (Exception ex) {
			if (!noFail) {
				System.out.println(	"MQ sending exception occurred "	+ ex.getStackTrace());
				ex.printStackTrace();
			}
			return false;
		}

		return true;
	}

	public boolean mqPut(String msg) {
		return mqPut(msg, true, false);
	}
}


