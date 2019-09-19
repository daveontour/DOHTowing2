package au.com.quaysystems.doh.towings.web.mq;

import com.ibm.mq.MQException;
import com.ibm.mq.MQQueue;
import com.ibm.mq.MQQueueManager;

public class MBase {

	protected MQQueueManager qMgr;
	public MQQueue queue;
	protected boolean disconected = true;

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
}