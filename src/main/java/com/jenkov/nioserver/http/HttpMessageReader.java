package com.jenkov.nioserver.http;

import com.jenkov.nioserver.IMessageReader;
import com.jenkov.nioserver.Message;
import com.jenkov.nioserver.MessageBuffer;
import com.jenkov.nioserver.Socket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by jjenkov on 18-10-2015.
 */
public class HttpMessageReader implements IMessageReader {

    private MessageBuffer messageBuffer    = null;

    private List<Message> completeMessages = new ArrayList<Message>();
    private Message       nextMessage      = null;

    public HttpMessageReader() {
    }

    /**
     * 相当于从buffer中划出一块蛋糕,然后给划出的message赋值，赋了个http头
     *
     * @param readMessageBuffer
     */
    @Override
    public void init(MessageBuffer readMessageBuffer) {
        this.messageBuffer        = readMessageBuffer;
        // 相当于从buffer中划出一块蛋糕
        this.nextMessage          = messageBuffer.getMessage();
        // 然后给划出的message赋值，赋了个http头
        this.nextMessage.metaData = new HttpHeaders();
    }

    @Override
    public void read(Socket socket, ByteBuffer byteBuffer) throws IOException {
        // 从Channel中读数据到buffer
        int bytesRead = socket.read(byteBuffer);
        // 翻转一下,buffer准备往外输出
        byteBuffer.flip();

        if(byteBuffer.remaining() == 0){
            byteBuffer.clear();
            return;
        }

        this.nextMessage.writeToMessage(byteBuffer);

        int endIndex = HttpUtil.parseHttpRequest(this.nextMessage.sharedArray, this.nextMessage.offset, this.nextMessage.offset + this.nextMessage.length, (HttpHeaders) this.nextMessage.metaData);
        if(endIndex != -1){
            // 进到这里说明一个message读完了,划出来下一个message
            Message message = this.messageBuffer.getMessage();
            message.metaData = new HttpHeaders();

            message.writePartialMessageToMessage(nextMessage, endIndex);

            completeMessages.add(nextMessage);
            nextMessage = message;
        }
        byteBuffer.clear();
    }


    @Override
    public List<Message> getMessages() {
        return this.completeMessages;
    }

}
