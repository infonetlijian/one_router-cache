/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package input;

import java.util.Random;

import core.DTNHost;
import core.Message;
import core.World;

/**
 * External event for creating a message.
 */
public class MessageCreateEvent extends MessageEvent {
	private int size;
	private int responseSize;
	/**------------------------------   �� MessageCreateEvent ��ӵĲ���       --------------------------------*/
	
	private String fileID; 			// ������ļ���ID��
	public static final String SelectLabel = "SelectLabel";
	
	/**------------------------------   �� MessageCreateEvent ��ӵĲ���       --------------------------------*/
	/**
	 * Creates a message creation event with a optional response request
	 * @param from The creator of the message
	 * @param to Where the message is destined to
	 * @param id ID of the message
	 * @param size Size of the message
	 * @param responseSize Size of the requested response message or 0 if
	 * no response is requested
	 * @param time Time, when the message is created
	 */
	public MessageCreateEvent(int from, int to, String id, int size,
			int responseSize, double time) {
		super(from,to, id, time);
		this.size = size;
		this.responseSize = responseSize;
	}

	@Override
	public String toString() {
		return super.toString() + " [" + fromAddr + "->" + toAddr + "] " +
		"size:" + size + " CREATE";
	}
/**------------------------------   �� MessageCreateEvent ��ӵĺ�������       --------------------------------*/
	
	/** �й����ļ�����޸ĵĲ���       */
	public String getFileID() {
		Random random = new Random();
		int id =random.nextInt(20);
		return "filename" +id;		//return filename;
	}
	/*
	 public void processEvent(World world) {
		DTNHost to = world.getNodeByAddress(this.toAddr);
		DTNHost from = world.getNodeByAddress(this.fromAddr);			
		
		Message m = new Message(from, to, this.id, this.size);//�����µ���Ϣ
		m.setResponseSize(this.responseSize);
		from.createNewMessage(m);//���²�������Ϣ�ŵ���Ӧ�ڵ��router���汣��
	}
	 */
	/**
	 * Creates the message this event represents. 
	 */
	@Override
	public void processEvent(World world) {
		
        this.fileID=getFileID();
        DTNHost from = world.getNodeByAddress(this.fromAddr);
		this.toAddr=from.getFiles().get(this.fileID);							// �޸�
		DTNHost to= world.getNodeByAddress(this.toAddr);
		this.responseSize= to.getFileBuffer().get(this.fileID).getSize();		// responseSize�趨�����ļ��Ĵ�С��
		
		Message m = new Message(from, to, this.id, this.size);
		m.setResponseSize(this.responseSize);
		m.setFilename(this.fileID);
		
		m.updateProperty(SelectLabel, 0);													//��ʶΪ���ư�
		
		// ���Ŀ�Ľڵ��Դ�ڵ㲻ͬ���Ŵ�����Ϣ����Ϊȡ���ļ�������ģ�     ͬʱ����ڵ㻺�����ļ������ٷ�������
		if(this.toAddr!=this.fromAddr && !from.getFileBuffer().containsKey(this.fileID)) {	
			from.createNewMessage(m); 														// ����Ϣ�Ž�������ȥ
			from.putIntoJudgeForRetransfer(m);												// ��Ҫ����Ϣ���뵽�ж���Ϣ�Ƿ��ش���buffer��
		}
	}
	
	/**------------------------------   �� MessageCreateEvent ��ӵĺ�������       --------------------------------*/
}
