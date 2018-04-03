package com.jenkov.nioserver;

/**
 * A shared buffer which can contain many messages inside. A message gets a section of the buffer to use. If the
 * message outgrows the section in size, the message requests a larger section and the message is copied to that
 * larger section. The smaller section is then freed again.
 *
 * 通过复制实现的自适应buffer，非阻塞型IO的只能读取和写入部分数据的问题的解决办法的核心
 *
 * Created by jjenkov on 18-10-2015.
 */
public class MessageBuffer {

    public static int KB = 1024;
    public static int MB = 1024 * KB;

    private static final int CAPACITY_SMALL  =   4  * KB; // 小型区域一格存4KB
    private static final int CAPACITY_MEDIUM = 128  * KB; // 中型区域一格存128KB
    private static final int CAPACITY_LARGE  = 1024 * KB; // 大型区域一格存1024KB

    // 各个区域的总容量
    //package scope (default) - so they can be accessed from unit tests.
    byte[]  smallMessageBuffer  = new byte[1024 *   4 * KB];   //1024 x   4KB messages =  4MB.
    byte[]  mediumMessageBuffer = new byte[128  * 128 * KB];   // 128 x 128KB messages = 16MB.
    byte[]  largeMessageBuffer  = new byte[16   *   1 * MB];   //  16 *   1MB messages = 16MB.

    // 搞了小、中、大三种规格的内存（这些里面都存的是消息在smallMessageBuffer的偏移量）
    QueueIntFlip smallMessageBufferFreeBlocks  = new QueueIntFlip(1024); // 1024 free sections
    QueueIntFlip mediumMessageBufferFreeBlocks = new QueueIntFlip(128);  // 128  free sections
    QueueIntFlip largeMessageBufferFreeBlocks  = new QueueIntFlip(16);   // 16   free sections

    //todo make all message buffer capacities and block sizes configurable
    //todo calculate free block queue sizes based on capacity and block size of buffers.

    public MessageBuffer() {
        //add all free sections to all free section queues.
        for(int i=0; i<smallMessageBuffer.length; i+= CAPACITY_SMALL){
            // 0-4K 1-8K ... n-4n K（这些都是偏移量）
            this.smallMessageBufferFreeBlocks.put(i);
        }
        for(int i=0; i<mediumMessageBuffer.length; i+= CAPACITY_MEDIUM){
            // 0-128K 1-256K ... n-128n K
            this.mediumMessageBufferFreeBlocks.put(i);
        }
        for(int i=0; i<largeMessageBuffer.length; i+= CAPACITY_LARGE){
            // 0-1024K 2-2048K ... n-1024n K
            this.largeMessageBufferFreeBlocks.put(i);
        }
    }

    public Message getMessage() {
        // 从 0-4K 1-8K ... n-4n K 中取出数据（取出偏移量）
        int nextFreeSmallBlock = this.smallMessageBufferFreeBlocks.take();

        // 这里是取不到了
        if(nextFreeSmallBlock == -1) return null;

        Message message = new Message(this);       //todo get from Message pool - caps memory usage.

        // 这个消息使用的字节数组
        message.sharedArray = this.smallMessageBuffer;
        // 这个字节数组一格的大小
        message.capacity    = CAPACITY_SMALL;
        // 这个消息在sharedArray中的偏移量
        message.offset      = nextFreeSmallBlock;
        // 这个消息的长度
        message.length      = 0;

        return message;
    }

    /**
     * 消息的一格内容扩展,小变中,中变大
     *
     * @param message
     * @return
     */
    public boolean expandMessage(Message message){
        if(message.capacity == CAPACITY_SMALL){
            return moveMessage(message, this.smallMessageBufferFreeBlocks, this.mediumMessageBufferFreeBlocks, this.mediumMessageBuffer, CAPACITY_MEDIUM);
        } else if(message.capacity == CAPACITY_MEDIUM){
            return moveMessage(message, this.mediumMessageBufferFreeBlocks, this.largeMessageBufferFreeBlocks, this.largeMessageBuffer, CAPACITY_LARGE);
        } else {
            return false;
        }
    }

    private boolean moveMessage(Message message, QueueIntFlip srcBlockQueue, QueueIntFlip destBlockQueue, byte[] dest, int newCapacity) {
        // 从destBlockQueue取出下个能用的位置
        int nextFreeBlock = destBlockQueue.take();
        if(nextFreeBlock == -1) return false;

        // 把message.sharedArray挪到dest
        System.arraycopy(message.sharedArray, message.offset, dest, nextFreeBlock, message.length);

        srcBlockQueue.put(message.offset); //free smaller block after copy

        // 然后换成dest
        message.sharedArray = dest;
        message.offset      = nextFreeBlock;
        message.capacity    = newCapacity;
        return true;
    }





}
