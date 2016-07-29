/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import input.ExternalEvent;
import input.MessageCreateEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Random;

import routing.util.RoutingInfo;
import util.Tuple;
import core.Application;
import core.Connection;
import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.Settings;
import core.SettingsError;
import core.SimClock;
import core.SimError;
import core.file;

/**
 * Superclass for message routers.
 */
public abstract class MessageRouter {
	/** Message buffer size -setting id ({@value}). Integer value in bytes.*/
	public static final String B_SIZE_S = "bufferSize";
	/**
	 * Message TTL -setting id ({@value}). Value is in minutes and must be
	 * an integer. 
	 */ 
	public static final String MSG_TTL_S = "msgTtl";
	/**
	 * Message/fragment sending queue type -setting id ({@value}). 
	 * This setting affects the order the messages and fragments are sent if the
	 * routing protocol doesn't define any particular order (e.g, if more than 
	 * one message can be sent directly to the final recipient). 
	 * Valid values are<BR>
	 * <UL>
	 * <LI/> 1 : random (message order is randomized every time; default option)
	 * <LI/> 2 : FIFO (most recently received messages are sent last)
	 * </UL>
	 */ 
	public static final String SEND_QUEUE_MODE_S = "sendQueue";
	
	/** Setting value for random queue mode */
	public static final int Q_MODE_RANDOM = 1;
	/** Setting value for FIFO queue mode */
	public static final int Q_MODE_FIFO = 2;
	
	/* Return values when asking to start a transmission:
	 * RCV_OK (0) means that the host accepts the message and transfer started, 
	 * values < 0 mean that the  receiving host will not accept this 
	 * particular message (right now), 
	 * values > 0 mean the host will not right now accept any message. 
	 * Values in the range [-100, 100] are reserved for general return values
	 * (and specified here), values beyond that are free for use in 
	 * implementation specific cases */
	/** Receive return value for OK */
	public static final int RCV_OK = 0;
	/** Receive return value for busy receiver */
	public static final int TRY_LATER_BUSY = 1;
	/** Receive return value for an old (already received) message */
	public static final int DENIED_OLD = -1;
	/** Receive return value for not enough space in the buffer for the msg */
	public static final int DENIED_NO_SPACE = -2;
	/** Receive return value for messages whose TTL has expired */
	public static final int DENIED_TTL = -3;
	/** Receive return value for a node low on some resource(s) */
	public static final int DENIED_LOW_RESOURCES = -4;
	/** Receive return value for a node low on some resource(s) */
	public static final int DENIED_POLICY = -5;
	/** Receive return value for unspecified reason */
	public static final int DENIED_UNSPECIFIED = -99;
	
	private List<MessageListener> mListeners;
	/** The messages being transferred with msgID_hostName keys */
	private HashMap<String, Message> incomingMessages;
	/** The messages this router is carrying */
	private HashMap<String, Message> messages; 
	/** The messages this router has received as the final recipient */
	private HashMap<String, Message> deliveredMessages;
	/** The messages that Applications on this router have blacklisted */
	private HashMap<String, Object> blacklistedMessages;
	/** Host where this router belongs to */
	private DTNHost host;
	/** size of the buffer */
	private int bufferSize;
	/** TTL for all messages */
	protected int msgTtl;
	/** Queue mode for sending messages */
	private int sendQueueMode;
	
	/** applications attached to the host */
	private HashMap<String, Collection<Application>> applications = null;
	/**------------------------------   ��MessageRouter��ӵı���       --------------------------------*/
	/** �ļ������С*/
	private int filebuffersize;
	/** bitMap���ڶ�chunkID����ӳ��    */
	private ArrayList<Integer> bitMap = new ArrayList<Integer>();
	/**����һ����ʱ�Ķ��У����ڶ��м̽ڵ�õ�chunk������Ϣ�洢 */
	protected Queue<Message> tempQueue = new LinkedList<Message>();
	/** ��Ҫ�����ά��������ʽ���������ݽ��д洢 */
	protected HashMap<String,HashMap<String,Message>> MessageHashMap = new HashMap<String,HashMap<String,Message>>();
	/** �µ��ļ�����,��дmessages*/
	private HashMap<String, Message> myMessages;
	/** �����ж��ļ��Ƿ�õ�ȷ�ϣ��Ӷ������Ƿ���Ҫ�ش�  */
	private HashMap<String, ArrayList<Object>> judgeForRetransfer 
						= new HashMap<String, ArrayList<Object>>();	
	/** �����ж��ش�ʱ�䣬�����趨Ϊ100s */
	protected double time_out = 20;
	/** �����ж��ش���������ʼΪ0���趨����ش�3��*/
	protected int reTransTimes = 3;
	/**������ack����time_waitʱ�� */
	protected double time_wait = 40;
	/** ����Ӧ����ĵȴ�ʱ�� time_free */
	protected double time_free = 3.5*time_out;
	/** ��Ӧ��Ϣǰ׺ */
	public static final String RESPONSE_PREFIX = "R_";
	/** �����жϰ������� */
	public static final String SelectLabel = "SelectLabel";
	/** �½�һ���ļ�buffer */
	public static final String F_SIZE_S = "filebuffersize";
	
	/** ------------------------------   ��MessageRouter��ӵı���       --------------------------------*/
	
	/**
	 * Constructor. Creates a new message router based on the settings in
	 * the given Settings object. Size of the message buffer is read from
	 * {@link #B_SIZE_S} setting. Default value is Integer.MAX_VALUE.
	 * @param s The settings object
	 */
	public MessageRouter(Settings s) {
		this.bufferSize = Integer.MAX_VALUE; // defaults to rather large buffer	
		this.msgTtl = Message.INFINITE_TTL;
		this.applications = new HashMap<String, Collection<Application>>();
		
		if (s.contains(B_SIZE_S)) {
			this.bufferSize = s.getInt(B_SIZE_S);
		}
		if (s.contains(MSG_TTL_S)) {
			this.msgTtl = s.getInt(MSG_TTL_S);
		}
		if (s.contains(SEND_QUEUE_MODE_S)) {
			this.sendQueueMode = s.getInt(SEND_QUEUE_MODE_S);
			if (sendQueueMode < 1 || sendQueueMode > 2) {
				throw new SettingsError("Invalid value for " + 
						s.getFullPropertyName(SEND_QUEUE_MODE_S));
			}
		}
		else {
			sendQueueMode = Q_MODE_RANDOM;
		}
		
	}
	
	/**
	 * Initializes the router; i.e. sets the host this router is in and
	 * message listeners that need to be informed about message related
	 * events etc.
	 * @param host The host this router is in
	 * @param mListeners The message listeners
	 */
	public void init(DTNHost host, List<MessageListener> mListeners) {
		this.incomingMessages = new HashMap<String, Message>();
		this.messages = new HashMap<String, Message>();
		this.deliveredMessages = new HashMap<String, Message>();
		this.blacklistedMessages = new HashMap<String, Object>();
		this.mListeners = mListeners;
		this.host = host;
	}
	
	/**
	 * Copy-constructor.
	 * @param r Router to copy the settings from.
	 */
	protected MessageRouter(MessageRouter r) {
		this.bufferSize = r.bufferSize;
		this.msgTtl = r.msgTtl;
		this.sendQueueMode = r.sendQueueMode;

		this.applications = new HashMap<String, Collection<Application>>();
		for (Collection<Application> apps : r.applications.values()) {
			for (Application app : apps) {
				addApplication(app.replicate());
			}
		}
	}
	
	/**
	 * Updates router.
	 * This method should be called (at least once) on every simulation
	 * interval to update the status of transfer(s). 
	 */
	public void update(){
		for (Collection<Application> apps : this.applications.values()) {
			for (Application app : apps) {
				app.update(this.host);
			}
		}
	}
	
	/**
	 * Informs the router about change in connections state.
	 * @param con The connection that changed
	 */
	public abstract void changedConnection(Connection con);	
	
	/**
	 * Returns a message by ID.
	 * @param id ID of the message
	 * @return The message
	 */
	protected Message getMessage(String id) {
		return this.messages.get(id);
	}
	
	/**
	 * Checks if this router has a message with certain id buffered.
	 * @param id Identifier of the message
	 * @return True if the router has message with this id, false if not
	 */
	public boolean hasMessage(String id) {
		return this.messages.containsKey(id);
	}
	
	/**
	 * Returns true if a full message with same ID as the given message has been
	 * received by this host as the <strong>final</strong> recipient 
	 * (at least once).
	 * @param m message we're interested of
	 * @return true if a message with the same ID has been received by 
	 * this host as the final recipient.
	 */
	protected boolean isDeliveredMessage(Message m) {
		return (this.deliveredMessages.containsKey(m.getId()));
	}
	
	/** 
	 * Returns <code>true</code> if the message has been blacklisted. Messages
	 * get blacklisted when an application running on the node wants to drop it.
	 * This ensures the peer doesn't try to constantly send the same message to
	 * this node, just to get dropped by an application every time.
	 * 
	 * @param id	id of the message
	 * @return <code>true</code> if blacklisted, <code>false</code> otherwise.
	 */
	protected boolean isBlacklistedMessage(String id) {
		return this.blacklistedMessages.containsKey(id);
	}
	
	/**
	 * Returns a reference to the messages of this router in collection.
	 * <b>Note:</b> If there's a chance that some message(s) from the collection
	 * could be deleted (or added) while iterating through the collection, a
	 * copy of the collection should be made to avoid concurrent modification
	 * exceptions. 
	 * @return a reference to the messages of this router in collection
	 */
	public Collection<Message> getMessageCollection() {
		return this.messages.values();
	}
	
	/**
	 * Returns the number of messages this router has
	 * @return How many messages this router has
	 */
	public int getNrofMessages() {
		return this.messages.size();
	}
	
	/**
	 * Returns the size of the message buffer.
	 * @return The size or Integer.MAX_VALUE if the size isn't defined.
	 */
	public int getBufferSize() {
		return this.bufferSize;
	}
	
	/**
	 * Returns the amount of free space in the buffer. May return a negative
	 * value if there are more messages in the buffer than should fit there
	 * (because of creating new messages).
	 * @return The amount of free space (Integer.MAX_VALUE if the buffer
	 * size isn't defined)
	 */
	public int getFreeBufferSize() {
		int occupancy = 0;
		
		if (this.getBufferSize() == Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		
		for (Message m : getMessageCollection()) {
			occupancy += m.getSize();
		}
		
		return this.getBufferSize() - occupancy;
	}
	
	/**
	 * Returns the host this router is in
	 * @return The host object
	 */
	protected DTNHost getHost() {
		return this.host;
	}
	
	/**
	 * Start sending a message to another host.
	 * @param id Id of the message to send
	 * @param to The host to send the message to
	 */
	public void sendMessage(String id, DTNHost to) {
		Message m = getMessage(id);
		Message m2;
		if (m == null) throw new SimError("no message for id " +
				id + " to send at " + this.host);

		m2 = m.replicate();	// send a replicate of the message
		to.receiveMessage(m2, this.host);
	}
	
	/**
	 * Requests for deliverable message from this router to be sent trough a
	 * connection.
	 * @param con The connection to send the messages trough
	 * @return True if this router started a transfer, false if not
	 */
	public boolean requestDeliverableMessages(Connection con) {
		return false; // default behavior is to not start -- subclasses override
	}
	
	/**
	 * Try to start receiving a message from another host.
	 * @param m Message to put in the receiving buffer
	 * @param from Who the message is from
	 * @return Value zero if the node accepted the message (RCV_OK), value less
	 * than zero if node rejected the message (e.g. DENIED_OLD), value bigger
	 * than zero if the other node should try later (e.g. TRY_LATER_BUSY).
	 */
	public int receiveMessage(Message m, DTNHost from) {
		Message newMessage = m.replicate();
				
		this.putToIncomingBuffer(newMessage, from);		
		newMessage.addNodeOnPath(this.host);
		
		for (MessageListener ml : this.mListeners) {
			ml.messageTransferStarted(newMessage, from, getHost());
		}
		
		return RCV_OK; // superclass always accepts messages
	}
	
	
	/**
	 * Puts a message to incoming messages buffer. Two messages with the
	 * same ID are distinguished by the from host.
	 * @param m The message to put
	 * @param from Who the message was from (previous hop).
	 */
	protected void putToIncomingBuffer(Message m, DTNHost from) {
		this.incomingMessages.put(m.getId() + "_" + from.toString(), m);
	}
	
	/**
	 * Removes and returns a message with a certain ID from the incoming 
	 * messages buffer or null if such message wasn't found. 
	 * @param id ID of the message
	 * @param from The host that sent this message (previous hop)
	 * @return The found message or null if such message wasn't found
	 */
	protected Message removeFromIncomingBuffer(String id, DTNHost from) {
		return this.incomingMessages.remove(id + "_" + from.toString());
	}
	
	/**
	 * Returns true if a message with the given ID is one of the
	 * currently incoming messages, false if not
	 * @param id ID of the message
	 * @return True if such message is incoming right now
	 */
	protected boolean isIncomingMessage(String id) {
		return this.incomingMessages.containsKey(id);
	}
	
	/**
	 * Adds a message to the message buffer and informs message listeners
	 * about new message (if requested).
	 * @param m The message to add
	 * @param newMessage If true, message listeners are informed about a new
	 * message, if false, nothing is informed.
	 */
	protected void addToMessages(Message m, boolean newMessage) {
		this.messages.put(m.getId(), m);
		
		if (newMessage) {
			for (MessageListener ml : this.mListeners) {
				ml.newMessage(m);
			}
		}
	}
	

	
	/**
	 * Removes and returns a message from the message buffer.
	 * @param id Identifier of the message to remove
	 * @return The removed message or null if message for the ID wasn't found
	 */
	protected Message removeFromMessages(String id) {
		Message m = this.messages.remove(id);
		return m;
	}
	
	/**
	 * This method should be called (on the receiving host) when a message 
	 * transfer was aborted.
	 * @param id Id of the message that was being transferred
	 * @param from Host the message was from (previous hop)
	 * @param bytesRemaining Nrof bytes that were left before the transfer
	 * would have been ready; or -1 if the number of bytes is not known
	 */
	public void messageAborted(String id, DTNHost from, int bytesRemaining) {
		Message incoming = removeFromIncomingBuffer(id, from);
		if (incoming == null) {
			throw new SimError("No incoming message for id " + id + 
					" to abort in " + this.host);
		}		
		
		for (MessageListener ml : this.mListeners) {
			ml.messageTransferAborted(incoming, from, this.host);
		}
	}
	
	/**
	 * Creates a new message to the router.
	 * @param m The message to create
	 * @return True if the creation succeeded, false if not (e.g.
	 * the message was too big for the buffer)
	 */
	public boolean createNewMessage(Message m) {
		m.setTtl(this.msgTtl);
		addToMessages(m, true);		
		return true;
	}
	
	/**
	 * Deletes a message from the buffer and informs message listeners
	 * about the event
	 * @param id Identifier of the message to delete
	 * @param drop If the message is dropped (e.g. because of full buffer) this 
	 * should be set to true. False value indicates e.g. remove of message
	 * because it was delivered to final destination.  
	 */
	public void deleteMessage(String id, boolean drop) {
		Message removed = removeFromMessages(id); 
		if (removed == null) throw new SimError("no message for id " +
				id + " to remove at " + this.host);

		for (MessageListener ml : this.mListeners) {
			ml.messageDeleted(removed, this.host, drop);
		}
	}
	
	/**
	 * Sorts/shuffles the given list according to the current sending queue 
	 * mode. The list can contain either Message or Tuple<Message, Connection> 
	 * objects. Other objects cause error. 
	 * @param list The list to sort or shuffle
	 * @return The sorted/shuffled list
	 */
	@SuppressWarnings(value = "unchecked") /* ugly way to make this generic */
	protected List sortByQueueMode(List list) {
		switch (sendQueueMode) {
		case Q_MODE_RANDOM:
			Collections.shuffle(list, new Random(SimClock.getIntTime()));
			break;
		case Q_MODE_FIFO://���þ�̬�㷨
			Collections.sort(list, //�ڶ�����������һ��int�͵�ֵ�����൱��һ����־������sort������ʲô˳������list��������
					new Comparator() {//�൱��ʵ����һ���̳�Comparator�˽ӿڵķ���
				/** Compares two tuples by their messages' receiving time */
				public int compare(Object o1, Object o2) {
					double diff;
					Message m1, m2;
					
					if (o1 instanceof Tuple) {//�ж��Ƿ�ΪTuple���һ��ʵ��
						m1 = ((Tuple<Message, Connection>)o1).getKey();
						m2 = ((Tuple<Message, Connection>)o2).getKey();
					}
					else if (o1 instanceof Message) {
						m1 = (Message)o1;
						m2 = (Message)o2;
					}
					else {
						throw new SimError("Invalid type of objects in " + 
								"the list");
					}
					
					diff = m1.getReceiveTime() - m2.getReceiveTime();//��receiveTimeΪ�ж�׼��
					if (diff == 0) {
						return 0;
					}
					return (diff < 0 ? -1 : 1);
				}
			});
			break;
		/* add more queue modes here */
		default:
			throw new SimError("Unknown queue mode " + sendQueueMode);
		}
		
		return list;
	}

	/**
	 * Gives the order of the two given messages as defined by the current
	 * queue mode 
	 * @param m1 The first message
	 * @param m2 The second message
	 * @return -1 if the first message should come first, 1 if the second 
	 *          message should come first, or 0 if the ordering isn't defined
	 */
	protected int compareByQueueMode(Message m1, Message m2) {
		switch (sendQueueMode) {
		case Q_MODE_RANDOM:
			/* return randomly (enough) but consistently -1, 0 or 1 */
			return (m1.hashCode()/2 + m2.hashCode()/2) % 3 - 1; 
		case Q_MODE_FIFO:
			double diff = m1.getReceiveTime() - m2.getReceiveTime();
			if (diff == 0) {
				return 0;
			}
			return (diff < 0 ? -1 : 1);
		/* add more queue modes here */
		default:
			throw new SimError("Unknown queue mode " + sendQueueMode);
		}
	}
	
	/**
	 * Returns routing information about this router.
	 * @return The routing information.
	 */
	public RoutingInfo getRoutingInfo() {
		RoutingInfo ri = new RoutingInfo(this);
		RoutingInfo incoming = new RoutingInfo(this.incomingMessages.size() + 
				" incoming message(s)");
		RoutingInfo delivered = new RoutingInfo(this.deliveredMessages.size() +
				" delivered message(s)");
		
		RoutingInfo cons = new RoutingInfo(host.getConnections().size() + 
			" connection(s)");
				
		ri.addMoreInfo(incoming);
		ri.addMoreInfo(delivered);
		ri.addMoreInfo(cons);
		
		for (Message m : this.incomingMessages.values()) {
			incoming.addMoreInfo(new RoutingInfo(m));
		}
		
		for (Message m : this.deliveredMessages.values()) {
			delivered.addMoreInfo(new RoutingInfo(m + " path:" + m.getHops()));
		}
		
		for (Connection c : host.getConnections()) {
			cons.addMoreInfo(new RoutingInfo(c));
		}

		return ri;
	}
	
	/** 
	 * Adds an application to the attached applications list.
	 * 
	 * @param app	The application to attach to this router.
	 */
	public void addApplication(Application app) {
		if (!this.applications.containsKey(app.getAppID())) {
			this.applications.put(app.getAppID(),
					new LinkedList<Application>());
		}
		this.applications.get(app.getAppID()).add(app);
	}
	
	/** 
	 * Returns all the applications that want to receive messages for the given
	 * application ID.
	 * 
	 * @param ID	The application ID or <code>null</code> for all apps.
	 * @return		A list of all applications that want to receive the message.
	 */
	public Collection<Application> getApplications(String ID) {
		LinkedList<Application>	apps = new LinkedList<Application>();
		// Applications that match
		Collection<Application> tmp = this.applications.get(ID);
		if (tmp != null) {
			apps.addAll(tmp);
		}
		// Applications that want to look at all messages
		if (ID != null) {
			tmp = this.applications.get(null);
			if (tmp != null) {
				apps.addAll(tmp);
			}
		}
		return apps;
	}

	/**
	 * Creates a replicate of this router. The replicate has the same
	 * settings as this router but empty buffers and routing tables.
	 * @return The replicate
	 */
	public abstract MessageRouter replicate();
	
	/**
	 * Returns a String presentation of this router
	 * @return A String presentation of this router
	 */
	public String toString() {
		return getClass().getSimpleName() + " of " + 
			this.getHost().toString() + " with " + getNrofMessages() 
			+ " messages";
	}
	/**  ------------------------- ��ʦ�ֶԴ�����޸� ------------------------ */
	
	/** ��ȡ��д����Ϣ����������*/
	protected Message getmyMessage(String id) {
		return this.myMessages.get(id);
	}
	/** ��ȡ��Ϣ����*/
	public Collection<Message> getmyMessageCollection() {
		return this.myMessages.values();
	}
	/** �ж��Ƿ���������Ϣ*/
	public boolean hasmyMessages(String id) {
		return this.myMessages.containsKey(id);
	}
	/** ��ȡ��Ϣ����Ĵ�С */
	public int getNrofmyMessages() {
		return this.myMessages.size();
	}
	/** ��ӵ���Ӧ���ļ���������  addToFileBuffer() */
	protected void addTomyMessages(Message m, boolean newMessage) {
		this.myMessages.put(m.getFilename(), m);								// �ŵ���Ϣ������messages��
		//����û�м����¼�������
	}
	/**������Ϣ���´����Ļ������Ƴ���*/
	protected Message removeFrommyMessages(String id) {
		Message m = this.myMessages.remove(id);
		return m;
	}
	/**  ------------------------- ��ʦ�ֶԴ�����޸� ------------------------ */
	
	
	/** -------------------------- �ҶԴ�����޸�  --------------------------- */
	
	/** ��ӵ���Ӧ���ļ���������  addToFileBuffer() */       
	protected void addToFileBuffer(Message m, boolean newMessage) {
		if ( m.getResponseSize() ==0){											//��message��ȡ��file��
			file ee = m.getFile();				
			this.getHost().getFileBuffer().put(m.getFilename(), ee);			// �ŵ���Ϣ������FileBuffer��
		}
	}	
	/** ���chunk����Ӧ��chunkBuffer�У�  	*/
	protected void addToChunkBuffer(Message m, boolean newMessage){
		if(m.getProperty(SelectLabel)== (Object)1){
			
			if(this.getHost().getChunkBuffer().containsKey(m.getFilename())){
				this.getHost().getChunkBuffer().get(m.getFilename()).put(m.getChunkID(),m.getFile());
			}	else{
				HashMap<String,file> NewHashMap = new HashMap<String,file>();
				NewHashMap.put(m.getChunkID(),m.getFile());
				this.getHost().getChunkBuffer().put(m.getFilename(), NewHashMap);
			}
		}
	}
	/** ��bitMap������Ԫ���������   */
	public void setZeroForBitMap(){
		this.bitMap.clear();
		for(int i=0;i<10;i++)
			bitMap.add(0);
	}
    /** �õ�����ļ��Ļ����Сfilebuffersize */
	public int getFileBufferSize(){
		return this.filebuffersize;
	}
	/** �õ���ǰ·�ɵ��ش�buffer��*/
	public HashMap<String,ArrayList<Object>> getJudgeForRetransfer(){
		return this.judgeForRetransfer;
	}
	
	/** ���մ�������Ϣ���뵽�ж��Ƿ���Ҫ�ش�buffer�� */
	public void putJudgeForRetransfer(Message m){		
		switch((int) m.getProperty(SelectLabel)){
		case 0:{
			ArrayList<Object> arraylist = new ArrayList<Object>();
			arraylist.add(0, m);
			arraylist.add(1, this.time_out);
			arraylist.add(2, this.reTransTimes);
			this.judgeForRetransfer.put(m.getId(), arraylist);
			return;
		}
		case 1:{
			ArrayList<Object> arraylist = new ArrayList<Object>();
			arraylist.add(0, m);
			arraylist.add(1, this.time_free);
			arraylist.add(2, -1);
			this.judgeForRetransfer.put("Chunk"+m.getInitMsgId(), arraylist);
			return;
		}
		case 2:{
			ArrayList<Object> arraylist = new ArrayList<Object>();
			arraylist.add(0, m);
			arraylist.add(1, this.time_out);
			arraylist.add(2, this.reTransTimes);
			this.judgeForRetransfer.put(m.getId(), arraylist);
			return;
		}
		case 3:{
			ArrayList<Object> arraylist = new ArrayList<Object>();
			arraylist.add(0, m);
			arraylist.add(1, this.time_wait);
			arraylist.add(2, -1);
			this.judgeForRetransfer.put(m.getId(), arraylist);
			return;
		}
		case 4:{
			ArrayList<Object> arraylist = new ArrayList<Object>();
			arraylist.add(0, m);
			arraylist.add(1, this.time_wait);
			arraylist.add(2, -1);
			this.judgeForRetransfer.put(m.getId(), arraylist);
			return;
		}
		}
	}
	
	/** ���´�ȷ����Ϣbuffer�е���Ϣ */
	public void updateReTransfer(){
		
		/**	������Ҫ�Դ�ȷ����Ϣ�����е���Ϣ��ȷ��ʱ����£����ж��Ƿ��ڣ������ش���*/
		
		for( ArrayList<Object> reTrans : this.judgeForRetransfer.values()){
			reTrans.set(1, (double)reTrans.get(1)-0.1);
			Message n = (Message)reTrans.get(0);
			String s = n.getId();
			if((double)reTrans.get(1)<=0){	//�ж�����ʱ���Ƿ��ڣ�
				Message m = (Message)reTrans.get(0);
				switch((int) m.getProperty(SelectLabel)){
				case 0:{
					if(this.getHost().getFileBuffer().containsKey(m.getFilename())==false){										// ����������У��Ͳ����ط�������Ϣ��û�вŷ�
						if((int)reTrans.get(2)>0){	//�ж��ش������Ƿ�����
							Message reqMessage = new Message(m.getFrom(),m.getTo(),
									m.getId(), m.getResponseSize());
							
							reqMessage.setInitMsgId(m.getInitMsgId());
							reqMessage.updateProperty(SelectLabel, 0);															//	��ʶΪ���ư�
							reqMessage.setFilename(m.getFilename());
							reqMessage.setZeroForBitMap();
							reqMessage.setTime(SimClock.getTime()+0.01, SimClock.getTime()+0.01);								//	������Ϣ����ʱ��
							
							this.judgeForRetransfer.get(m.getInitMsgId()).set(1, this.time_out);
							int i = (int) this.judgeForRetransfer.get(m.getInitMsgId()).get(2);
			                this.judgeForRetransfer.get(m.getInitMsgId()).set(2, i-1); 											//	�ش���������һ��
			                this.createNewMessage(reqMessage);
						}
						else{
							this.judgeForRetransfer.remove(m.getInitMsgId());
						}
					}
					else{
						this.judgeForRetransfer.remove(m.getInitMsgId());
					}
					return;
				}
				/** ����Ӧ�����time_free���ڣ������ж��ڴ����Ƿ��ж�Ӧ��Ӧ����� �еĻ�ɾ�ˣ�Ȼ��ɾ����ȷ����Ϣ�����еĴ���Ϣ��*/
				case 1:{ 		
					if(this.MessageHashMap.containsKey(n.getFilename())){
						this.MessageHashMap.remove(n.getFilename());
					}
					if(this.getHost().getChunkBuffer().containsKey(n.getFilename())){
						this.getHost().getChunkBuffer().remove(n.getFilename());
					}
					this.judgeForRetransfer.remove("Chunk"+n.getInitMsgId());
				}
				
				case 2:{																										// ����������У��Ͳ����ط�������Ϣ��û�вŷ�
					if(this.getHost().getFileBuffer().containsKey(m.getFilename())==false){
						if((int)reTrans.get(2)>0){	//�ж��ش������Ƿ����꣬�ش����ư�
							Message ctrMessage = new Message(m.getFrom(),m.getTo(),
									RESPONSE_PREFIX + m.getId(), m.getResponseSize());
							
							ctrMessage.setInitMsgId(m.getInitMsgId());
							ctrMessage.updateProperty(SelectLabel, 2);															//	��ʶΪ���ư�
							ctrMessage.setFilename(m.getFilename());
							ctrMessage.setZeroForBitMap();
							ctrMessage.setTime(SimClock.getTime()+0.01, SimClock.getTime()+0.01);								//	������Ϣ����ʱ��
							
							this.judgeForRetransfer.get(RESPONSE_PREFIX +"ctr_"+m.getInitMsgId()).set(1, this.time_out);		//	ˢ���ش�ʱ��
			                int j = (int) this.judgeForRetransfer.get(RESPONSE_PREFIX +"ctr_"+m.getInitMsgId()).get(2);
			                this.judgeForRetransfer.get(RESPONSE_PREFIX +"ctr_"+m.getInitMsgId()).set(2, j-1); 					//	�ش���������һ��
			                this.createNewMessage(ctrMessage);
						}
						else{
							this.judgeForRetransfer.remove(RESPONSE_PREFIX +"ctr_"+m.getInitMsgId());
						}	
					}
					else{
						this.judgeForRetransfer.remove(RESPONSE_PREFIX +"ctr_"+m.getInitMsgId());
					}			
					return;
				}
				case 3:{		// �Կ��ư���ȷ����Ϣ
					this.judgeForRetransfer.remove(s);
					return;
				}
				case 4:{		// ���������ȷ����Ϣ��
					this.judgeForRetransfer.remove(s);
					return;
				}
				}
			}
		}
		
	}
	
	/** ------------------------ �ҶԴ�����޸�  ----------------------------  */
	
	
	// ͬʱ��messageCreate()���������޸�
	// update()�����������޸�
	// MessageTransferred()�����������޸�
	
	/**
	 * This method should be called (on the receiving host) after a message
	 * was successfully transferred. The transferred message is put to the
	 * message buffer unless this host is the final recipient of the message.
	 * @param id Id of the transferred message
	 * @param from Host the message was from (previous hop)
	 * @return The message that this host received
	 */
	public Message messageTransferred(String id, DTNHost from) {
		Message incoming = removeFromIncomingBuffer(id, from);			//����Ϣ��incomingMessagesɾ��
		boolean isFinalRecipient;										//�ж���Ϣ���ݵ�Ŀ�Ľڵ�
		boolean isFirstDelivery; 										// is this first delivered instance of the msg //��Ϣ���ݵ��ýڵ�	
		if (incoming == null) {
			throw new SimError("No message with ID " + id + " in the incoming "+
					"buffer of " + this.host);
		}
		incoming.setReceiveTime(SimClock.getTime());					//������Ϣ����ʱ��		
		
		
		//System.out.println(this.getHost()+"  "+"��ǰ�ڵ�·�ɵĴ�ȷ�ϻ���(==============================)��"+this.judgeForRetransfer);
		
		System.out.println("IB�ɹ������ļ���"+"  "+this.getHost()+"   "+incoming.getProperty(SelectLabel)+ "  "
				+incoming.getFilename()+" "+incoming.getChunkID()+"  "
					+incoming.getId()+" "+incoming.getFrom()+"  "+incoming.getTo()+"  "+"��ʼ��Ϣ���ƣ�"+"  "+incoming.getInitMsgId());
		
		
		// Pass the message to the application (if any) and get outgoing message
		/*** ����Ϣ����Ӧ�ò㴦��(����еĻ�)�� ��ЩӦ�ûᶪ������Ϣ ***/
		Message outgoing = incoming;
		for (Application app : getApplications(incoming.getAppID())) {
			// Note that the order of applications is significant		
			// since the next one gets the output of the previous.
			outgoing = app.handle(outgoing, this.host);
			if (outgoing == null) break; 								// Some app wanted to drop the message
		}
		
		Message aMessage = (outgoing==null)?(incoming):(outgoing);
		// If the application re-targets the message (changes 'to')
		// then the message is not considered as 'delivered' to this host.
		isFinalRecipient = aMessage.getTo() == this.host;
		isFirstDelivery = isFinalRecipient && !isDeliveredMessage(aMessage);  	//�ж��Ƿ�ΪĿ�Ľڵ���Ϊ��һ�ε���
		
		/*** ����Ϣ����ʵ�����������Ӧ�Ļ����� ***/
		if (!isFinalRecipient && outgoing!=null) {								//����Ŀ�Ľڵ㣬Ӧ�ò�Ҳ���붪�������Ϣ
			addToMessages(aMessage, false);       								//incomingMessages --> messages
			
			/**  ����ļ�������ת�����ֻ���
			 *   ˼·��  ������յ���Ϣ����Ϣ�����ж���
			 *             �����������Ϣ��鿴���档��
			 *             �����Ӧ����Ϣ��鿴�������Ƿ��и��ļ������û����洢  */
			

			/** ��Ӧ��������Ϣ 
			 *  ��黺�����Ƿ���ڣ�����������һ���ظ���ɾ�����󣬷���Ӧ�ò����������뵽message�С�*/
			
/*			System.out.println("�м̽ڵ�"+"  "+this.getHost()+"   "+aMessage.getProperty(SelectLabel)+ "  "
								+aMessage.getFilename()+" "+aMessage.getChunkID()+"  "
									+aMessage.getId()+" "+aMessage.getFrom()+"  "+aMessage.getTo());*/
			
			if(aMessage.getProperty(SelectLabel)== (Object) 0 ){	
				// �����������������ackȷ�ϰ���ʧ��ɵ��ط�����Ҫtime_wait����
				if(this.judgeForRetransfer.containsKey(RESPONSE_PREFIX +"ackr_" + aMessage.getInitMsgId())){
					// Ҳ��������ackȷ�ϰ���ʧ��ɵ��ط����ư�������ֱ�ӻظ�ȷ�ϰ�����
					Message m = (Message)this.judgeForRetransfer
								.get(RESPONSE_PREFIX +"ackr_" + aMessage.getInitMsgId()).get(0);

					Message ackMessage = new Message(m.getFrom(),m.getTo(),
										RESPONSE_PREFIX + m.getId(), m.getResponseSize());
					
					ackMessage.setInitMsgId(m.getInitMsgId());
					ackMessage.updateProperty(SelectLabel, 4);															//	��ʶΪ���ư�
					ackMessage.setFilename(m.getFilename());
					ackMessage.setZeroForBitMap();
					ackMessage.setTime(SimClock.getTime()+0.01, SimClock.getTime()+0.01);								//	������Ϣ����ʱ��
					
					this.judgeForRetransfer.get(RESPONSE_PREFIX +"ackr_"+m.getInitMsgId()).set(1, this.time_wait);		//	ˢ���ش�ʱ��
	                this.createNewMessage(ackMessage);

				}
				else{
					Message ackMessage =new Message(this.getHost(),aMessage.getHops().get(aMessage.getHopCount()-1),
							RESPONSE_PREFIX +"ackr_"+ aMessage.getInitMsgId(), aMessage.getResponseSize());
					ackMessage.setInitMsgId(aMessage.getInitMsgId());
					ackMessage.updateProperty(SelectLabel, 4);												//	��ʶΪ�������ȷ�ϰ�
					ackMessage.setFilename(aMessage.getFilename());
					ackMessage.setTime(SimClock.getTime()+0, SimClock.getTime()+0);				              
					this.putJudgeForRetransfer(ackMessage);
					this.createNewMessage(ackMessage); 
					
					this.messages.get(aMessage.getId()).setTime(SimClock.getTime()+0.01, SimClock.getTime()+0.01); //  ��ԭ��������Ϣ�Ĵ���ʱ�������ʱ����������趨
					
					if (this.getHost().getFileBufferForFile(aMessage)!=null) {
		            	/**
		            	 * ��Ҫ������Ӷ��ļ���Ƭ�Ĵ���Ȼ���ٽ���Ӧ��������Ϣ���������������
		            	 * ��������ҵ�����Ч�ķ��������д�������
		            	 */
						this.getHost().getFileBufferForFile(aMessage).setTimeRequest(SimClock.getTime());  		//Ϊ�������ļ���������ʱ��					
						file f = this.getHost().getFileBufferForFile(aMessage);
						for(int i=0;i<10;i++){
							file chunk = f.copyFrom(f);
							for(int j=i*10;j<i*10+10;j++){
								chunk.getData().add(j-i*10,f.getData().get(j));
							}
							
							//����chunkID����message�����á�
							Message res = new Message(this.getHost(),aMessage.getHops().get(aMessage.getHopCount()-1),
									RESPONSE_PREFIX + aMessage.getId()+i, aMessage.getResponseSize(), 
									chunk);	
							res.setInitMsgId(aMessage.getInitMsgId());
							res.setResponseSize(0);						//��һ��Ӧ��û��
							res.setFilename(aMessage.getFilename());
							res.setChunkID(aMessage.getFilename()+"ChunkID"+i);
							res.updateProperty(SelectLabel, 1);												//˵������һ��Ӧ���
							
							res.setTime(SimClock.getTime()+0.01*(i+2), SimClock.getTime()+0.01*(i+2));
							
							//System.out.println(res.getCreationTime()+"  "+res.getReceiveTime());
			                
							this.createNewMessage(res);
			                //this.getMessage(RESPONSE_PREFIX + aMessage.getId()).setRequest(aMessage);		// ����Ӧ����Ϣ
			                
						}         	
						
						//Ӧ����Ϣ����֮��Ӧ�÷���һ�����ư�
						Message ctrMessage =new Message(this.getHost(),aMessage.getHops().get(aMessage.getHopCount()-1),
								RESPONSE_PREFIX +"ctr_"+  aMessage.getInitMsgId(), aMessage.getResponseSize());
						ctrMessage.setInitMsgId(aMessage.getInitMsgId());
						ctrMessage.updateProperty(SelectLabel, 2);												//��ʶΪ���ư�
						ctrMessage.setFilename(aMessage.getFilename());
						ctrMessage.setZeroForBitMap();
						ctrMessage.setTime(SimClock.getTime()+0.12, SimClock.getTime()+0.12);
		                this.createNewMessage(ctrMessage);  	             
	            		this.putJudgeForRetransfer(ctrMessage);
	            		
		                this.removeFromMessages(aMessage.getId());		                						// ���濪ʼɾ��ԭ��������
						//System.out.println("+++++++++++++++����һ�����ư�++++++++++++++++++++");
		            }
				}
			}	
			
			else if (aMessage.getProperty(SelectLabel)== (Object) 1) {								//����һ��Ӧ����Ϣ

				/** ΪӦ������ϼ�ʱ�� Time_free*/
				if(this.judgeForRetransfer.containsKey("Chunk"+aMessage.getInitMsgId())){				// �����ж�Ӧ����ļ�ʱ���ڴ�ȷ����Ϣ���Ƿ���ڣ� ���ڵĻ����£��������ڣ�������һ��
					this.judgeForRetransfer.get("Chunk"+aMessage.getInitMsgId()).set(1, this.time_free);
				}
				else{
					this.putJudgeForRetransfer(aMessage);
				}

				
				if (this.getHost().getFileBufferForFile(aMessage)==null){
					
					/** ����ӵ�������֮ǰ����Ҫ�ȶԻ������жϣ��Ƿ����� ��δ����ֱ�Ӽ��뻺�棻
					 *  ���������ȶԻ��������ݽ���ɾ�����ټ��뻺��	 * */
					
					addToChunkBuffer(aMessage,false);
					
					//���ö��ж���Ϣ���д洢��ͳһ��һ����ά��HashMap���д洢��
					if(MessageHashMap.containsKey(aMessage.getFilename())){
						this.MessageHashMap.get(aMessage.getFilename()).put(aMessage.getChunkID(), aMessage);
					}	else{
						HashMap<String,Message> NewHashMap = new HashMap<String,Message>();
						NewHashMap.put(aMessage.getChunkID(), aMessage);
						this.MessageHashMap.put(aMessage.getFilename(), NewHashMap);
					}
					this.removeFromMessages(aMessage.getId());		                				// 	���濪ʼɾ��ԭ����Ӧ���
				}

			} 
			
			else if(aMessage.getProperty(SelectLabel)== (Object) 2){								//	�ж��յ���Ϊ���ư�
				if(this.getHost().getFileBuffer().containsKey(aMessage.getFilename())==false){
					// Ҳ��������ackȷ�ϰ���ʧ��ɵ��ط����ư�������ֱ�ӻظ�ȷ�ϰ�����.�������ڴ�ʱ��chunkBuffer���Ѿ�û����chunk�ļ���
					boolean a = false;
					for(int i=0;i<10;i++){
						a = this.getHost().getChunkBuffer().get(aMessage.getFilename()).containsKey(aMessage.getFilename()+"ChunkID"+i);
						if (a == true)
							break;
					}
					if(	a==false ){						//֤��һ���ļ���û�У�������ack����ʧ��ɵġ�
						Message m = (Message)this.judgeForRetransfer
									.get(RESPONSE_PREFIX +RESPONSE_PREFIX +"ctr_" + aMessage.getInitMsgId()).get(0);

						Message ackMessage = new Message(m.getFrom(),m.getTo(),
											RESPONSE_PREFIX + m.getId(), m.getResponseSize());
						
						ackMessage.setInitMsgId(m.getInitMsgId());
						ackMessage.updateProperty(SelectLabel, 3);															//	��ʶΪ���ư�
						ackMessage.setFilename(m.getFilename());
						ackMessage.setZeroForBitMap();
						ackMessage.setTime(SimClock.getTime()+0.01, SimClock.getTime()+0.01);									//	������Ϣ����ʱ��
						
						this.judgeForRetransfer.get(RESPONSE_PREFIX + RESPONSE_PREFIX +"ctr_"+m.getInitMsgId()).set(1, this.time_wait);		//	ˢ���ش�ʱ��
		                this.createNewMessage(ackMessage);

					}
					
					else{
						if (this.getHost().getFileBufferForFile(aMessage)==null) {

							if(MessageHashMap.containsKey(aMessage.getFilename())){
								this.MessageHashMap.get(aMessage.getFilename()).put(aMessage.getId(), aMessage);
							}	else{
								HashMap<String,Message> NewHashMap = new HashMap<String,Message>();
								NewHashMap.put(aMessage.getChunkID(), aMessage);
								this.MessageHashMap.put(aMessage.getId(), NewHashMap);
							}
			            	this.removeFromMessages(aMessage.getId());		                					// 	���濪ʼɾ��ԭ���Ŀ��ư�
						
			            	// ����chunkBuffer��chunk�Ƿ�����
			            	boolean b = true;						//b=1 Ĭ��Ϊ����
			            	this.setZeroForBitMap();
			            	for(int i=0;i<10;i++){
								if(this.getHost().getChunkBuffer().get(aMessage.getFilename()).containsKey(aMessage.getFilename()+"ChunkID"+i))
			            			this.bitMap.set(i, 1);
			            		else 
			            			b = false;
			            	}
			            	// �ж��Ƿ����룿����
			            	if(b){//  �ж�����������
							
			            		//	�������֮ǰ����Ҫ���ж��ڴ��Ƿ����������Ļ���Ҫɾ���ڴ�
			            		if (this.getHost().getFreeFileBufferSize() < 0) {
			            			this.getHost().makeRoomForNewFile(0);       									//��Ҫʱ��ɾ����Щ������յ��Ҳ����ڴ������Ϣ
			            			System.out.print("+++++++++++++++++++++ɾ���ɹ�++++++++++++++++++++"+"\n");
			            		}				
								file NewFile = this.getHost().getChunkBuffer().get(aMessage.getFilename()).get(aMessage.getFilename()+"ChunkID"+0);
			            		for(int i=1;i<10;i++){
			            			file temp = this.getHost().getChunkBuffer().get(aMessage.getFilename()).get(aMessage.getFilename()+"ChunkID"+i);
			            			ArrayList<Integer> c = temp.getData();
			            			NewFile.getData().addAll(i*10, c);
			            		}
								//chunkBufferȡ��֮����Ҫ�������������
								this.getHost().getChunkBuffer().remove(aMessage.getFilename());
			            		
			            		//	��FileBuffer�з��ļ�
			            		NewFile.setInitFile(NewFile);
								NewFile.setTimeRequest(SimClock.getTime());
								
								System.out.println(this.getHost()+"  "+this.getHost().getFileBuffer());
								
			            		this.getHost().getFileBuffer().put(aMessage.getFilename(), NewFile);

								System.out.println(this.getHost()+"  "+this.getHost().getFileBuffer());
								System.out.println("++++++++++�м̽ڵ��з��뻺��ɹ�++++++++");
								
			            		
								/**	�յ����ư�֮����Ҫ�������£�һ���ظ���һ����һ������Ŀ�Ľڵ㷢
			            		 	1���ж����������£���MessageHashMap  ����Ϣ˳��ȡ��������һ���� 	*/
			            		HashMap<String,Message> NewHashMap = MessageHashMap.get(aMessage.getFilename());
			            		
				            	for(int i=0;i<10;i++){
				            		Message m = NewHashMap.get(aMessage.getFilename()+"ChunkID"+i);
				            		
				            		/** ��Ҫ�Ǹı�Դ��ַ  */
				            		DTNHost thisHost = this.getHost();						//	Դ��ַ 
				            		DTNHost thisto = m.getTo();								//	��ǰ��Ϣ��Ŀ�Ľڵ�
				            		Message newMessage = new Message(thisHost,thisto,m.getId(),m.getSize());
				            		newMessage.copyFrom(m);									//  copy��ǰ��Ϣ������
				        			newMessage.setFilename(m.getFilename());	        			
				        			newMessage.setBitMap(m.getBitMap());
				        			newMessage.setInitMsgId(m.getInitMsgId());
					 		        newMessage.setFile(m.getFile());
					 		        newMessage.setTime(SimClock.getTime()+0.01*(i+1), SimClock.getTime()+0.01*(i+1));
					 		        
				            		this.createNewMessage(newMessage);
				            	}
				            	Message m = NewHashMap.remove(aMessage.getId());		//  ����ǿ��ư�
				            	//	�������������ȷ�ϵĻ������Խ��ó�������Ϣ�����޸ģ�������copyFrom�ķ�����
			            		
				            	DTNHost thisHost = this.getHost();						//	Դ��ַ 
			            		DTNHost thisto = m.getTo();								//	��ǰ��Ϣ��Ŀ�Ľڵ�
			            		Message newMessage = new Message(thisHost,thisto,m.getId(),m.getSize());
			            		newMessage.copyFrom(m);									//  copy��ǰ��Ϣ������
			        			newMessage.setFilename(m.getFilename());	        			
			        			newMessage.setBitMap(m.getBitMap());
			        			newMessage.setInitMsgId(m.getInitMsgId());
			        			newMessage.setTime(SimClock.getTime()+0.11, SimClock.getTime()+0.11);
			            		
				 		        this.createNewMessage(newMessage);
			            		this.putJudgeForRetransfer(newMessage);					//  �ɵ�ǰ�ڵ㷢���Ŀ��ư������뵱ǰ�ڵ�Ĵ�ȷ�ϻ�����
			            		
			            		this.MessageHashMap.remove(aMessage.getFilename());
			            		
			            	}
			            	// 	2���ظ�ȷ�ϰ�			
			            	Message ackMessage =new Message(this.getHost(),aMessage.getHops().get(aMessage.getHopCount()-1),
			            			RESPONSE_PREFIX + aMessage.getId(), aMessage.getResponseSize());
							ackMessage.setInitMsgId(aMessage.getInitMsgId());
			            	ackMessage.updateProperty(SelectLabel,3);													//˵������һ��ȷ�ϰ�
							ackMessage.getBitMap().clear();             												//�����bitmap
			            	ackMessage.getBitMap().addAll(this.bitMap);													//�ظ�bitMap
			            	
			            	System.out.println("�м���bitmap���в��ԣ�"+"  "+ ackMessage.getBitMap());
			            	
			            	ackMessage.setFilename(aMessage.getFilename());
							this.putJudgeForRetransfer(ackMessage);
			            	this.createNewMessage(ackMessage);
			            }
					}
				}

			}

			else if(aMessage.getProperty(SelectLabel)== (Object) 4){									//	֤�����Ƕ��������ȷ�ϰ�			
				this.judgeForRetransfer.remove(aMessage.getInitMsgId());								//  ɾ�������������ش���������Ϣ
			} 
			

		}	else if (isFirstDelivery) {																	// ����Ŀ�Ľڵ����ǵ�һ�ε���
			this.deliveredMessages.put(id, aMessage);	
/*			System.out.println("����Ŀ�Ľڵ�"+"  "+this.getHost()+"   "+aMessage.getProperty(SelectLabel)+ "  "
								+aMessage.getFilename()+" "+aMessage.getChunkID()+"  "
					+aMessage.getId()+" "+aMessage.getFrom()+"  "+aMessage.getTo());*/
			
			/** ����������Ϣ�����������Ƿ����ļ��������ļ�����Ӧ����Ϣ��     û�и������������ڳ�����	*/ 
			
			if(aMessage.getProperty(SelectLabel)== (Object) 0){					
				
				// �����������������ackȷ�ϰ���ʧ��ɵ��ط�����Ҫtime_wait����
				if(this.judgeForRetransfer.containsKey(RESPONSE_PREFIX +"ackr_" + aMessage.getInitMsgId())){
					// Ҳ��������ackȷ�ϰ���ʧ��ɵ��ط����ư�������ֱ�ӻظ�ȷ�ϰ�����
					Message m = (Message)this.judgeForRetransfer
								.get(RESPONSE_PREFIX +"ackr_" + aMessage.getInitMsgId()).get(0);

					Message ackMessage = new Message(m.getFrom(),m.getTo(),
										RESPONSE_PREFIX + m.getId(), m.getResponseSize());
					
					ackMessage.setInitMsgId(m.getInitMsgId());
					ackMessage.updateProperty(SelectLabel, 4);															//	��ʶΪ���ư�
					ackMessage.setFilename(m.getFilename());
					ackMessage.setZeroForBitMap();
					ackMessage.setTime(SimClock.getTime()+0.01, SimClock.getTime()+0.01);									//	������Ϣ����ʱ��
					
					this.judgeForRetransfer.get(RESPONSE_PREFIX +"ackr_"+m.getInitMsgId()).set(1, this.time_wait);		//	ˢ���ش�ʱ��
	                this.createNewMessage(ackMessage);

				}
				else{
					
					//�յ�������Ϣ����Ҫ��������Ϣ����ȷ��
					Message ackMessage =new Message(this.getHost(),aMessage.getHops().get(aMessage.getHopCount()-1),
							RESPONSE_PREFIX +"ackr_"+ aMessage.getId(), aMessage.getResponseSize());
					ackMessage.setInitMsgId(aMessage.getInitMsgId());
					ackMessage.updateProperty(SelectLabel, 4);												//	��ʶΪ�������ȷ�ϰ�
					ackMessage.setFilename(aMessage.getFilename());
					ackMessage.setTime(SimClock.getTime()+0, SimClock.getTime()+0);				
					//System.out.println(ackMessage.getCreationTime()+"  "+ackMessage.getReceiveTime());              
					this.putJudgeForRetransfer(ackMessage);
					this.createNewMessage(ackMessage);  
					
					
					if (this.getHost().getFileBufferForFile(aMessage)!=null) {
						//System.out.println(this.getHost()+"  "+aMessage.getFrom()+"  "+this.getHost().getFileBufferForFile(aMessage));	
						
		            	/**
		            	 * ��Ҫ������Ӷ��ļ���Ƭ�Ĵ���Ȼ���ٽ���Ӧ��������Ϣ���������������
		            	 * ��������ҵ�����Ч�ķ��������д�������
		            	 */
						this.getHost().getFileBufferForFile(aMessage).setTimeRequest(SimClock.getTime());  	//�������ļ���������ʱ��					
						file f = this.getHost().getFileBufferForFile(aMessage);
						
						//System.out.println("��ʼ���ļ��е����ݴ�С"+"  "+f.getData().size());
						
						for(int i=0;i<10;i++){
							file chunk = f.copyFrom(f);
							for(int j=i*10;j<i*10+10;j++){
								chunk.getData().add(j-i*10,f.getData().get(j));
							}
							
							Message res = new Message(this.getHost(), aMessage.getFrom(),
									RESPONSE_PREFIX + aMessage.getId()+i, aMessage.getResponseSize(), 
									chunk);	
							res.setInitMsgId(aMessage.getInitMsgId());
							res.setResponseSize(0);						//��һ��Ӧ��û��
							res.setFilename(aMessage.getFilename());
							res.setChunkID(aMessage.getFilename()+"ChunkID"+i);									//����chunkID����message�����á�
							res.updateProperty(SelectLabel,1);													//˵������һ��Ӧ���
							
							res.setTime(SimClock.getTime()+0.01*(i+1), SimClock.getTime()+0.01*(i+1));
							
							this.createNewMessage(res);

						}

						
						//Ӧ����Ϣ����֮��Ӧ�÷���һ�����ư�
						Message ctrMessage =new Message(this.getHost(),aMessage.getFrom(),
								RESPONSE_PREFIX + "ctr_"+ aMessage.getId(), aMessage.getResponseSize());
						ctrMessage.setInitMsgId(aMessage.getInitMsgId());
						ctrMessage.updateProperty(SelectLabel, 2);												//��ʶΪ���ư�
						ctrMessage.setFilename(aMessage.getFilename());
						ctrMessage.setZeroForBitMap();
						ctrMessage.setTime(SimClock.getTime()+0.11, SimClock.getTime()+0.11);	              
						this.createNewMessage(ctrMessage);  
						
	            		this.putJudgeForRetransfer(ctrMessage);
		            }
					else {
						System.out.print("��ΪĿ�Ľڵ�ʱ�����ִ���Ŀ�Ľڵ���û�ж�Ӧ���ļ���"+"\n");
					}
				}
			}	
			
			else if (aMessage.getProperty(SelectLabel)== (Object) 1){										// ����һ��Ӧ����Ϣ��Я������chunk�ļ�  				
				
				/** ΪӦ������ϼ�ʱ�� Time_free,�����ж�Ӧ����ļ�ʱ���ڴ�ȷ����Ϣ���Ƿ���ڣ� ���ڵĻ����£��������ڣ�������һ��   */
				if(this.judgeForRetransfer.containsKey("Chunk"+aMessage.getInitMsgId())){				
					this.judgeForRetransfer.get("Chunk"+aMessage.getInitMsgId()).set(1, this.time_free);
				}
				else{
					this.putJudgeForRetransfer(aMessage);
				}
				
				
				/** ��chunkBuffer�з����ļ� */
				if (this.getHost().getFileBufferForFile(aMessage)==null){
					addToChunkBuffer(aMessage,false);
				}
				/** ����Ϣ�����ڶ����ط� ���м̽ڵ㵱��Ŀ�Ľڵ���д���*/
				if (this.MessageHashMap.containsKey(aMessage.getFilename())){
					this.MessageHashMap.get(aMessage.getFilename()).put(aMessage.getChunkID(), aMessage);
				}
					
				
			} 
			
			else if(aMessage.getProperty(SelectLabel)== (Object) 2){										// �ж��յ���Ϊ���ư����ظ�ȷ��
				if(this.getHost().getFileBuffer().containsKey(aMessage.getFilename())==false){				// ��Ŀ�Ľڵ��в������ļ��������
					// Ҳ��������ackȷ�ϰ���ʧ��ɵ��ط����ư�������ֱ�ӻظ�ȷ�ϰ�����.�������ڴ�ʱ��chunkBuffer���Ѿ�û����chunk�ļ���
					boolean a = false;
					for(int i=0;i<10;i++){
						a = this.getHost().getChunkBuffer().get(aMessage.getFilename()).containsKey(aMessage.getFilename()+"ChunkID"+i);
						if (a == true)
							break;
					}
					
					//System.out.println("%%%%%%%%%%%%%%%%%%%�����Ƿ�������ack����ʧ����ط����ư���"+"  "+ a);
					if(	a==false ){						//֤��һ���ļ���û�У�������ack����ʧ��ɵġ�
						Message m = (Message)this.judgeForRetransfer
									.get(RESPONSE_PREFIX +RESPONSE_PREFIX +"ctr_" + aMessage.getInitMsgId()).get(0);

						Message ackMessage = new Message(m.getFrom(),m.getTo(),
											RESPONSE_PREFIX + m.getId(), m.getResponseSize());
						
						ackMessage.setInitMsgId(m.getInitMsgId());
						ackMessage.updateProperty(SelectLabel, 3);															//	��ʶΪ���ư�
						ackMessage.setFilename(m.getFilename());
						ackMessage.setZeroForBitMap();
						ackMessage.setTime(SimClock.getTime()+0.01, SimClock.getTime()+0.01);									//	������Ϣ����ʱ��
						
						this.judgeForRetransfer.get(RESPONSE_PREFIX + RESPONSE_PREFIX +"ctr_"+m.getInitMsgId()).set(1, this.time_wait);		//	ˢ���ش�ʱ��
		                this.createNewMessage(ackMessage);

					}
					
					else{

						// ����chunkBuffer��chunk�Ƿ����룬�ظ�ȷ�ϰ�
						boolean b = true;						//b=1 Ĭ��Ϊ����
						this.setZeroForBitMap();
						for(int i=0;i<10;i++){
							if(this.getHost().getChunkBuffer().get(aMessage.getFilename()).containsKey(aMessage.getFilename()+"ChunkID"+i))
								this.bitMap.set(i, 1);
							else 
								b = false;  
						} 
						
						// �ж��Ƿ����룿����
						if(b){//  �ж�����������
							
							//	�������֮ǰ����Ҫ���ж��ڴ��Ƿ����������Ļ���Ҫɾ���ڴ�
							if (this.getHost().getFreeFileBufferSize() < 0) {
								this.getHost().makeRoomForNewFile(0);       										//��Ҫʱ��ɾ����Щ������յ��Ҳ����ڴ������Ϣ
								System.out.print("+++++++++++++++++++++ɾ���ɹ�++++++++++++++++++++"+"\n");
							}
							
							file NewFile = this.getHost().getChunkBuffer().get(aMessage.getFilename()).get(aMessage.getFilename()+"ChunkID"+0);
							
							for(int i=1;i<10;i++){
								file temp = this.getHost().getChunkBuffer().get(aMessage.getFilename()).get(aMessage.getFilename()+"ChunkID"+i);
								
								ArrayList<Integer> c = temp.getData();
								NewFile.getData().addAll(i*10, c);
							}
							//chunkBufferȡ��֮����Ҫ�������������
							this.getHost().getChunkBuffer().remove(aMessage.getFilename());
							
							//	��FileBuffer�з��ļ�
							NewFile.setInitFile(NewFile);
							NewFile.setTimeRequest(SimClock.getTime());
							
							System.out.println(this.getHost()+"  "+this.getHost().getFileBuffer());
							
							this.getHost().getFileBuffer().put(aMessage.getFilename(), NewFile);
							
							System.out.println(this.getHost()+"  "+this.getHost().getFileBuffer());
							System.out.println("++++++++++Ŀ�Ľڵ��з��뻺��ɹ�++++++++");
							
							/** �������Ŀ�Ľڵ��ش��ļ�����ư�ʱ���bug*/
							if(MessageHashMap.containsKey(aMessage.getFilename())){
								this.MessageHashMap.get(aMessage.getFilename()).put(aMessage.getId(), aMessage);
								
								/** ֤������������Ŀ�Ľڵ㣬������Ҫ��MessageHashMap����Ϣ�����ش�*/
								HashMap<String,Message> NewHashMap = MessageHashMap.get(aMessage.getFilename());
								// ������ڵ�һ��ʱ������
								DTNHost temp = null;
								for(int i=0;i<10;i++){
									Message m = NewHashMap.get(aMessage.getFilename()+"ChunkID"+i);
									if(this.getHost()!= m.getTo())
										temp = m.getTo();
								}
								
								for(int i=0;i<10;i++){
									Message m = NewHashMap.get(aMessage.getFilename()+"ChunkID"+i);
			            		

									/** ��Ҫ�Ǹı�Դ��ַ  */
									DTNHost thisHost = this.getHost();						//	Դ��ַ 
									DTNHost thisto = m.getTo();								//	��ǰ��Ϣ��Ŀ�Ľڵ�
									
									if(thisHost == m.getTo()){
										thisto = temp;
									}
									
									Message newMessage = new Message(thisHost,thisto,m.getId(),m.getSize());
									newMessage.copyFrom(m);									//  copy��ǰ��Ϣ������
									newMessage.setFilename(m.getFilename());	        			
									newMessage.setBitMap(m.getBitMap());
									newMessage.setInitMsgId(m.getInitMsgId());
									newMessage.setFile(m.getFile());
									newMessage.setTime(SimClock.getTime()+0.01*(i+1), SimClock.getTime()+0.01*(i+1));
				 		        
									this.createNewMessage(newMessage);
								}
								Message m = NewHashMap.remove(aMessage.getId());		//  ����ǿ��ư�
								//	�������������ȷ�ϵĻ������Խ��ó�������Ϣ�����޸ģ�������copyFrom�ķ�����
			        		
								DTNHost thisHost = this.getHost();						//	Դ��ַ 
								DTNHost thisto = m.getTo();								//	��ǰ��Ϣ��Ŀ�Ľڵ�
								
								if(thisHost == m.getTo()){
									thisto = temp;
								}
								String ID = RESPONSE_PREFIX + "ctr_"+m.getInitMsgId();
								Message newMessage = new Message(thisHost,thisto,ID,m.getSize());
								newMessage.copyFrom(m);									//  copy��ǰ��Ϣ������
								newMessage.setFilename(m.getFilename());	        			
								newMessage.setBitMap(m.getBitMap());
								newMessage.setInitMsgId(m.getInitMsgId());
								newMessage.setTime(SimClock.getTime()+0.11, SimClock.getTime()+0.11);
			        		
								this.createNewMessage(newMessage);
								this.putJudgeForRetransfer(newMessage);					//  �ɵ�ǰ�ڵ㷢���Ŀ��ư������뵱ǰ�ڵ�Ĵ�ȷ�ϻ�����
			        		
								this.MessageHashMap.remove(aMessage.getFilename());
							}
			
						}
						// �ظ�ȷ�ϰ�
						
						Message ackMessage =new Message(this.getHost(),aMessage.getFrom(),
								RESPONSE_PREFIX + aMessage.getId(), aMessage.getResponseSize());
						ackMessage.setInitMsgId(aMessage.getInitMsgId());
						ackMessage.updateProperty(SelectLabel,3);													//˵������һ��ȷ�ϰ�
						ackMessage.getBitMap().clear();             												//�����bitmap
						ackMessage.getBitMap().addAll(this.bitMap);										//�ظ�bitMap
						ackMessage.setFilename(aMessage.getFilename());				
						this.putJudgeForRetransfer(ackMessage);
						System.out.println("Ŀ�Ľڵ�bitmapȷ�ϣ�"+"  "+ackMessage.getBitMap());
		                
						this.createNewMessage(ackMessage);  
					}
				}

			}
			
			
			else if(aMessage.getProperty(SelectLabel)== (Object) 3){										//�ж��յ���Ϊȷ�ϰ�
				
				//����bitMap�Ƿ����룿δ�����ط���
				boolean b = true;										//�����ж��Ƿ���Ҫ�ٻظ�һ�����ư���Ĭ��Ϊ����Ҫ��
				file f = this.getHost().getFileBufferForFile(aMessage);				
				
				//System.out.print("�����ж�bitmap:"+"  ");			
				for(int i=0; i<10; i++){
					file chunk = f.copyFrom(f);
					if(aMessage.getBitMap().get(i)!=1){
						//System.out.print(aMessage.getBitMap().get(i)+"   ");

						//�ط�chunkIDΪi�İ�
						for(int j=i*10;j<i*10+10;j++){
							chunk.getData().add(j-i*10,f.getData().get(j));
						}
						Message res = new Message(this.getHost(), aMessage.getFrom(),
								RESPONSE_PREFIX + aMessage.getId()+i, aMessage.getResponseSize(), 
								chunk);	

						res.setInitMsgId(aMessage.getInitMsgId());
						res.setResponseSize(0);						//��һ��Ӧ��û��
						res.setFilename(aMessage.getFilename());
						res.setChunkID(aMessage.getFilename()+"ChunkID"+i);									//����chunkID����message�����á�
						res.updateProperty(SelectLabel,1);													//˵������һ��Ӧ���
        				//res.getBitMap().addAll(aMessage.getBitMap());
    					
        				this.createNewMessage(res);
						b = false;
					
/*						System.out.println("���·����ļ���"+"  "+this.getHost()+"   "+res.getProperty(SelectLabel)+ "  "
											+res.getFilename()+" "+res.getChunkID()+"  "
											+res.getId()+" "+res.getFrom()+"  "+res.getTo());*/
					}	
				}	
				
				if(b==false){				//��b=false��֤���а���ʧ����ʱ��Ҫ�ٷ���һ�����ư�
					
					Message ctrMessage =new Message(this.getHost(),aMessage.getFrom(),
							RESPONSE_PREFIX + aMessage.getId(), aMessage.getResponseSize());
					
					ctrMessage.setInitMsgId(aMessage.getInitMsgId());
					ctrMessage.updateProperty(SelectLabel, 2);															//	��ʶΪ���ư�
					ctrMessage.setFilename(aMessage.getFilename());
					ctrMessage.setZeroForBitMap();
					ctrMessage.setTime(SimClock.getTime()+0.01, SimClock.getTime()+0.01);									//	������Ϣ����ʱ��
	            
					
					this.judgeForRetransfer.get(RESPONSE_PREFIX +"ctr_"+aMessage.getInitMsgId()).set(1, this.time_out);	//	ˢ���ش�ʱ��
	                int m = (int) this.judgeForRetransfer.get(RESPONSE_PREFIX +"ctr_"+aMessage.getInitMsgId()).get(2);
	                this.judgeForRetransfer.get(RESPONSE_PREFIX +"ctr_"+aMessage.getInitMsgId()).set(2, m-1); 			//	�ش���������һ��
	                this.createNewMessage(ctrMessage);
	            }
				
				else{
					this.judgeForRetransfer.remove(RESPONSE_PREFIX +"ctr_"+aMessage.getInitMsgId());
				}
			}
			
			else if(aMessage.getProperty(SelectLabel)== (Object) 4){									//	֤�����Ƕ��������ȷ�ϰ�			
				this.judgeForRetransfer.remove(aMessage.getInitMsgId());								//  ɾ�������������ش���������Ϣ
			} 
			
		} else if (outgoing == null) {			
			// Blacklist messages that an app wants to drop.
			// Otherwise the peer will just try to send it back again.
			this.blacklistedMessages.put(id, null);												//��test ע�͵���
		}		
		for (MessageListener ml : this.mListeners) {
			ml.messageTransferred(aMessage, from, this.host,
					isFirstDelivery);
		}		
		return aMessage;
	}
}
