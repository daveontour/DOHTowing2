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

	public String replyToQueue = null;
	public String qName;
	private int openOptions = MQConstants.MQOO_OUTPUT | MQConstants.MQOO_INQUIRE | MQConstants.MQOO_INPUT_AS_Q_DEF;
//	private int messSentCount = 0;
//	private int badSend = 0;
//	private int goodSend = 0;
//	private boolean lastSendGood = false;


	public void setReplyToQueue(String q) {
		this.replyToQueue = q;
	}
	public MSender(String q, String host, String qm, String channel, int port, String user, String pass) {
		
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
		if (!host.contains("NONE")) {
			MQEnvironment.hostname = host;
		}

		qName = q;
		try {
			qMgr = new MQQueueManager(qm);
			queue = qMgr.accessQueue(q, openOptions, null, null, null);
			this.replyToQueue = null;
		} catch (MQException e) {		
			e.printStackTrace();
		}
//
//		resetCount();
	}

//	public MSender(String q, boolean noFail) throws MQException {
//		qName = q;
//		try {
//			qMgr = new MQQueueManager(config.qmgr);
//			queue = qMgr.accessQueue(q, openOptions, null, null, null);
//			this.replyToQueue = null;
//		} catch (MQException ex) {
//			throw ex;
//		}
//
//		resetCount();
//	}
//
//	public MSender(String q, int oOpts ){
//		qName = q;
//		try {
//			qMgr = new MQQueueManager(config.qmgr);
//			queue = qMgr.accessQueue(q, oOpts, null, null, null);
//			this.replyToQueue = null;
//		} catch (MQException e) {
//			e.printStackTrace();
//		}
//
//		resetCount();
//	}	
//
//	public MSender(String q, int oOpts, String replyToQueue ) {
//		qName = q;
//		try {
//			qMgr = new MQQueueManager(config.qmgr);
//			queue = qMgr.accessQueue(q, oOpts, null, null, null);
//			this.replyToQueue = replyToQueue;
//		} catch (MQException e) {
//			e.printStackTrace();
//		}
//		resetCount();
//	}

//	public void resetCount() {
//		messSentCount = 0;
//		goodSend = 0;
//		badSend = 0;
//	}
//
//	public int getMessageSentCount() {
//		return this.messSentCount;
//	}
//
//	public int getGoodSentCount() {
//		return this.goodSend;
//	}	
//
//	public int getBadSentCount() {
//		return this.badSend;
//	}
//
//	public boolean isLastSendGood() {
//		return this.lastSendGood;
//	}

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
//			mBuf.replyToQueueManagerName = config.qmgr;
			mBuf.replyToQueueName= this.replyToQueue;

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

//		if (msg.contains("Intentionally_Bad")) {
//			badSend++;
//			lastSendGood  = false;
//		} else {
//			goodSend++;
//			lastSendGood = true;
//		}
//		messSentCount++;
		return true;
	}



	public boolean mqPut(String msg) {
		return mqPut(msg, true, false);
	}
}


