# Log level (options: DEBUG, INFO, WARN, ERROR, OFF )
log.level=TRACE

#
# AMS Connection Parameters
#

# Airport Code for Web Services and RestAPI call
airport=DOH

# Access security token defined in AMS
# POC 
token=3974f18c-fcf2-4666-bc82-a44da8d990cb

# The URL of the RestAPI (%s are placeholder where the from and to time will be substituted in by the application (refer RestAPI docuementation)
#POC
towrequest.url=http://hiamcraams01/api/v1/DOH/Towings/%s/%s

# URL of the AMS WebService Endpoint
#POC
ws.url=http://hiamcraams01/SITAAMSIntegrationService/v2/SITAAMSIntegrationService

#
# IBM MQ Connection Parameters
#
# Parameters for connection to MQ
# Important! If the parameter is not used, put in the value "NONE" !Important
# Default production config for DOHA JAF

mq.qmgr=JAFQMGR
mq.channel=GLASSFISH.SVRCONN
mq.host=172.23.73.8
mq.port=1424
mq.user=gfservice
mq.pass=Gfsvr@w0rk


# Queue that receives messages from the bridge from RestAPI MS MQ Message queue. 
mq.msmqbridgequeue=MSMQ.BRIDGE

# Queue to write the messages out to for MACS to receive (JAF 
mq.ibmoutqueue=JA.MACS.RDDS.TOW2.MQ.OUT

# Input Queue from MACS where requests are received.
mq.ibminqueue=JA.MACS.RDDS.TOW2.MQ.IN

# Number of times to attempt to connect to the MS MQ Notification queue ( 0 for no limit )
ibmmq.retries=0

# Time to wait for a message when listening to the input queues (ms)
msg.recv.timeout=10000

#
# Functionality Control Parameters
#

#Timeout for HTTP Request to AMS in milliseconds
httpRequestTimeout=10000

# Send a sync of all the tows when the interface starts up
syncOnStartUp=true

# The size of the the window around "now" to get tows in minutes for periodic or startup sync
fromMin=-1440
toMin=1440

# Switch to control whether the daily/periodic refresh is actually enabled. 
enablePush=false

# Time in 24HR format that the first periodic sync will be done
daily.refresh.time=00:00

# Time in milliseconds between successive execution of the periodic sync job. By default it is 24 hours, but it could be set to any value
refresh.period=60000

# Boolean value on whether to do delete existing messages on the output queue before sending the daily sync
deleteBeforeSync=false
