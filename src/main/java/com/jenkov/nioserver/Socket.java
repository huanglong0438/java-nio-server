package com.jenkov.nioserver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Created by jjenkov on 16-10-2015.
 */
public class Socket {

    public long socketId;

    /**
     * 每个Socket都有一个Channel（本质就是Socket），一个Reader，一个Writer
     */
    public SocketChannel  socketChannel = null;
    public IMessageReader messageReader = null;
    public MessageWriter  messageWriter = null;

    public boolean endOfStreamReached = false;

    public Socket() {
    }

    public Socket(SocketChannel socketChannel) {
        this.socketChannel = socketChannel;
    }

    public int read(ByteBuffer byteBuffer) throws IOException {
        int bytesRead = this.socketChannel.read(byteBuffer);
        int totalBytesRead = bytesRead;

        while(bytesRead > 0){
            // 一直读，全撸出来
            bytesRead = this.socketChannel.read(byteBuffer);
            totalBytesRead += bytesRead;
        }
        if(bytesRead == -1){
            // 读到末尾了
            this.endOfStreamReached = true;
        }

        return totalBytesRead;
    }

    public int write(ByteBuffer byteBuffer) throws IOException{
        int bytesWritten      = this.socketChannel.write(byteBuffer);
        int totalBytesWritten = bytesWritten;

        // 一直写，直到byteBuffer被撸空
        while(bytesWritten > 0 && byteBuffer.hasRemaining()){
            bytesWritten = this.socketChannel.write(byteBuffer);
            totalBytesWritten += bytesWritten;
        }

        return totalBytesWritten;
    }


}
