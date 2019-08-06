package au.com.quaysystems.doh.towings.web.mq;

import com.ibm.mq.MQException;
import com.ibm.mq.MQMessage;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;
import com.ibm.mq.constants.MQConstants;

public class MBase {

	protected static MQQueueManager qMgr;
	public MQQueue queue;
//	protected FatUtil config = FatUtil.getFatUtil();
	protected boolean disconected = true;

	public int getQueueDepth() {
		try {
			int depth = queue.getCurrentDepth();
			return depth;
		} catch (MQException e) {
			e.printStackTrace();
		}

		return 0;
	}

	public boolean disconnect() {
		return disconnect(false);

	}
	public boolean disconnect(boolean noFail) {
		try {
			qMgr.disconnect();
			qMgr.close();
			this.disconected = true;
		} catch (MQException e) {
			if (!noFail) {
				e.printStackTrace();
			}
			return false;
		}
		return true;
	}

	public void disconnectFailover() throws MQException {
		try {
			qMgr.disconnect();
			qMgr.close();
			this.disconected = true;
		} catch (MQException e) {

			throw e;
		}
	}
	public int getDepth() {
		try {
			return queue.getCurrentDepth();
		} catch (MQException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return -1;
		}
	}

	public boolean clearQueue()  {

		boolean loopAgain = true;

		try {

			while (loopAgain){
				MQMessage message = new MQMessage();
				try	{
					queue.get(message);
				} catch (MQException e)	{
					if (e.completionCode == 1 && e.reasonCode == MQConstants.MQRC_TRUNCATED_MSG_ACCEPTED){
						// Just what we expected!!
					} else {
						loopAgain = false;
						if (e.completionCode == 2 && e.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE)	{
							// Good, we are now done - no error!!
						} else {
							e.printStackTrace();
							return false;
						}
					}
				}
			}
			return true;

		} catch (Exception e1)	{
			e1.printStackTrace();
			return false;
		}
	}
}