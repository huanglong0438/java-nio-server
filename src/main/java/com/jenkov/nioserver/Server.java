package com.jenkov.nioserver;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Created by jjenkov on 24-10-2015.
 */
public class Server {

    private SocketAccepter  socketAccepter  = null;
    private SocketProcessor socketProcessor = null;

    private int tcpPort = 0;
    private IMessageReaderFactory messageReaderFactory = null;
    private IMessageProcessor     messageProcessor = null;

    public Server(int tcpPort, IMessageReaderFactory messageReaderFactory, IMessageProcessor messageProcessor) {
        this.tcpPort = tcpPort;
        this.messageReaderFactory = messageReaderFactory;
        this.messageProcessor = messageProcessor;
    }

    public void start() throws IOException {

        // socket的队列
        Queue socketQueue = new ArrayBlockingQueue(1024); //move 1024 to ServerConfig

        // 接受新连接的线程
        this.socketAccepter  = new SocketAccepter(tcpPort, socketQueue);

        // 读和写的buffer
        MessageBuffer readBuffer  = new MessageBuffer();
        MessageBuffer writeBuffer = new MessageBuffer();

        // 处理请求的线程
        this.socketProcessor = new SocketProcessor(socketQueue, readBuffer, writeBuffer,  this.messageReaderFactory, this.messageProcessor);

        Thread accepterThread  = new Thread(this.socketAccepter);
        Thread processorThread = new Thread(this.socketProcessor);

        accepterThread.start();
        processorThread.start();
    }


}
