package com.jenkov.nioserver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.*;

/**
 * Created by jjenkov on 16-10-2015.
 */
public class SocketProcessor implements Runnable {

    private Queue<Socket>  inboundSocketQueue   = null;

    private MessageBuffer  readMessageBuffer    = null; //todo   Not used now - but perhaps will be later - to check for space in the buffer before reading from sockets
    private MessageBuffer  writeMessageBuffer   = null; //todo   Not used now - but perhaps will be later - to check for space in the buffer before reading from sockets (space for more to write?)

    private IMessageReaderFactory messageReaderFactory = null;

    private Queue<Message> outboundMessageQueue = new LinkedList<>(); //todo use a better / faster queue.

    private Map<Long, Socket> socketMap         = new HashMap<>();

    private ByteBuffer readByteBuffer  = ByteBuffer.allocate(1024 * 1024); // 1MB
    private ByteBuffer writeByteBuffer = ByteBuffer.allocate(1024 * 1024);
    private Selector   readSelector    = null;
    private Selector   writeSelector   = null;

    private IMessageProcessor messageProcessor = null;
    private WriteProxy        writeProxy       = null;

    private long              nextSocketId = 16 * 1024; //start incoming socket ids from 16K - reserve bottom ids for pre-defined sockets (servers).

    private Set<Socket> emptyToNonEmptySockets = new HashSet<>();
    private Set<Socket> nonEmptyToEmptySockets = new HashSet<>();


    public SocketProcessor(Queue<Socket> inboundSocketQueue, MessageBuffer readMessageBuffer, MessageBuffer writeMessageBuffer, IMessageReaderFactory messageReaderFactory, IMessageProcessor messageProcessor) throws IOException {
        // 所有accept到的Socket都放到了这个队列里
        this.inboundSocketQueue = inboundSocketQueue;

        // 小中大三种规格内存
        this.readMessageBuffer    = readMessageBuffer;
        this.writeMessageBuffer   = writeMessageBuffer;
        this.writeProxy           = new WriteProxy(writeMessageBuffer, this.outboundMessageQueue);

        this.messageReaderFactory = messageReaderFactory;

        this.messageProcessor     = messageProcessor;

        // 读和写分别开了个Selector
        this.readSelector         = Selector.open();
        this.writeSelector        = Selector.open();
    }

    public void run() {
        while(true){
            try{
                executeCycle();
            } catch(IOException e){
                e.printStackTrace();
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }


    /**
     * 循环处理：
     * 1. 从Socket队列中取出Socket，并对其进行各种设置（Reader、Writer），并且在Selector上注册读事件
     * 2. 从Selector中获取可读的Socket，然后对这些Socket执行读操作（通过Socket中设置的自定义的reader）
     *
     * @throws IOException
     */
    public void executeCycle() throws IOException {
        // 从inboundSocketQueue捞出来一个Socket,设置reader和writer
        takeNewSockets();
        readFromSockets();
        // undo
        writeToSockets();
    }


    public void takeNewSockets() throws IOException {
        // 从队列里面捞出来一个Socket（封装了一个id号、Channel、reader、writer）
        Socket newSocket = this.inboundSocketQueue.poll();

        while(newSocket != null){
            newSocket.socketId = this.nextSocketId++;
            newSocket.socketChannel.configureBlocking(false);

            // 给这个新的Socket设置好reader和writer
            newSocket.messageReader = this.messageReaderFactory.createMessageReader();
            // reader的初始化，主要干的就是赋值内部的readMessageBuffer，然后从buffer中划出来一块，给赋值HTTP头部
            newSocket.messageReader.init(this.readMessageBuffer);

            // new了一个writer,但是啥都没干
            newSocket.messageWriter = new MessageWriter();

            // 注册户口
            this.socketMap.put(newSocket.socketId, newSocket);

            // 注册到readSelector读事件
            SelectionKey key = newSocket.socketChannel.register(this.readSelector, SelectionKey.OP_READ);
            key.attach(newSocket);

            // 再处理下一个
            newSocket = this.inboundSocketQueue.poll();
        }
    }


    public void readFromSockets() throws IOException {
        // 从readSelector中获得可读的
        int readReady = this.readSelector.selectNow();

        if(readReady > 0){
            Set<SelectionKey> selectedKeys = this.readSelector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

            while(keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();

                // 从Socket读,读出完整的message放到写队列
                readFromSocket(key);

                keyIterator.remove();
            }
            selectedKeys.clear();
        }
    }

    private void readFromSocket(SelectionKey key) throws IOException {
        Socket socket = (Socket) key.attachment();
        // 读操作，这个messageReader是自定义的，有小中大自动扩容功能,有部分消息暂存功能
        socket.messageReader.read(socket, this.readByteBuffer);

        // messageReader是自定义的，从messageReader中获取看是否有读完整的消息
        List<Message> fullMessages = socket.messageReader.getMessages();
        if(fullMessages.size() > 0){
            // 如果有完整的消息，就可以进入messageProcessor进行处理了
            for(Message message : fullMessages){
                message.socketId = socket.socketId;
                // 处理下,放到写队列
                this.messageProcessor.process(message, this.writeProxy);  //the message processor will eventually push outgoing messages into an IMessageWriter for this socket.
            }
            fullMessages.clear();
        }

        if(socket.endOfStreamReached){
            System.out.println("Socket closed: " + socket.socketId);
            this.socketMap.remove(socket.socketId);
            key.attach(null);
            key.cancel();
            key.channel().close();
        }
    }


    public void writeToSockets() throws IOException {

        // Take all new messages from outboundMessageQueue
        takeNewOutboundMessages();

        // Cancel all sockets which have no more data to write.
        cancelEmptySockets();

        // Register all sockets that *have* data and which are not yet registered.
        registerNonEmptySockets();

        // Select from the Selector.
        int writeReady = this.writeSelector.selectNow();

        if(writeReady > 0){
            Set<SelectionKey>      selectionKeys = this.writeSelector.selectedKeys();
            Iterator<SelectionKey> keyIterator   = selectionKeys.iterator();

            while(keyIterator.hasNext()){
                SelectionKey key = keyIterator.next();

                Socket socket = (Socket) key.attachment();

                socket.messageWriter.write(socket, this.writeByteBuffer);

                if(socket.messageWriter.isEmpty()){
                    this.nonEmptyToEmptySockets.add(socket);
                }

                keyIterator.remove();
            }

            selectionKeys.clear();

        }
    }

    private void registerNonEmptySockets() throws ClosedChannelException {
        for(Socket socket : emptyToNonEmptySockets){
            socket.socketChannel.register(this.writeSelector, SelectionKey.OP_WRITE, socket);
        }
        emptyToNonEmptySockets.clear();
    }

    private void cancelEmptySockets() {
        for(Socket socket : nonEmptyToEmptySockets){
            SelectionKey key = socket.socketChannel.keyFor(this.writeSelector);

            key.cancel();
        }
        nonEmptyToEmptySockets.clear();
    }

    private void takeNewOutboundMessages() {
        Message outMessage = this.outboundMessageQueue.poll();
        while(outMessage != null){
            Socket socket = this.socketMap.get(outMessage.socketId);

            if(socket != null){
                MessageWriter messageWriter = socket.messageWriter;
                if(messageWriter.isEmpty()){
                    messageWriter.enqueue(outMessage);
                    nonEmptyToEmptySockets.remove(socket);
                    emptyToNonEmptySockets.add(socket);    //not necessary if removed from nonEmptyToEmptySockets in prev. statement.
                } else{
                   messageWriter.enqueue(outMessage);
                }
            }

            outMessage = this.outboundMessageQueue.poll();
        }
    }

}
